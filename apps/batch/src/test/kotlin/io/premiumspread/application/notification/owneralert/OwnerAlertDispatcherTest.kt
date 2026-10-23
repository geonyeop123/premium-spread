package io.premiumspread.application.notification.owneralert

import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore.Status
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Instant

/** owner 알림 전달 1회분의 행 단위 전이 (design §5.2 전이표, §8 JobResult 분기, dod AC8-a). */
class OwnerAlertDispatcherTest {
    private val clock = MutableClock(Instant.parse("2026-10-20T03:00:00Z"))
    private val store = FakeOwnerAlertStore()
    private val mail = FakeMailSender()
    private val discord = FakeDiscordSender()

    private fun dispatcher(
        discordSender: OwnerAlertDiscordSender? = discord,
        mailSender: AccountPnlMailSender = mail,
    ) = OwnerAlertDispatcher(store, mailSender, discordSender, clock, OWNER)

    // ---- 성공 ----

    @Test
    fun `메일 행은 메일 sender 로 보내고 SENT 로 표시한다`() {
        store.add(emailRow("k1"))

        assertThat(dispatcher().dispatch()).isEqualTo(JobResult.Success)

        assertThat(mail.sent).containsExactly(emailRow("k1"))
        assertThat(discord.attempts).isEmpty()
        assertThat(store.row(1).status).isEqualTo(Status.SENT)
        assertThat(store.owners).containsOnly(OWNER)
    }

    @Test
    fun `Discord 행은 행의 route 와 제목·설명·색 그대로 Discord sender 로 보내고 SENT 로 표시한다`() {
        store.add(discordRow("k1"))

        assertThat(dispatcher().dispatch()).isEqualTo(JobResult.Success)

        assertThat(discord.sent).containsExactly(PNL_HERE to DiscordMessage("title k1", "desc k1", COLOR))
        assertThat(mail.attempts).isEmpty()
        assertThat(store.row(1).status).isEqualTo(Status.SENT)
    }

    // ---- 재시도 불가 ----

    @Test
    fun `Discord sender 가 없으면 Discord 행을 같은 회차에 즉시 FAILED 로 표시한다`() {
        store.add(discordRow("k1"))
        store.add(emailRow("k2"))

        assertThat(dispatcher(discordSender = null).dispatch()).isEqualTo(JobResult.Success)

        assertThat(store.calls.filter { it.startsWith("markFailed") }).containsExactly("markFailed:1")
        assertThat(store.calls).doesNotContain("markAttemptFailed:1")
        assertThat(discord.attempts).isEmpty()
        assertThat(mail.attempts.map { it.eventKey }).containsExactly("k2")
        assertThat(store.row(1).status).isEqualTo(Status.FAILED)
        assertThat(store.row(1).attempts).isEqualTo(1)
        assertThat(store.row(2).status).isEqualTo(Status.SENT)
    }

    @Test
    fun `재시도 불가 실패는 즉시 markFailed 하고 회차 끝에 되돌리지 않는다`() {
        store.add(discordRow("k1"))
        store.add(emailRow("k2"))
        discord.failure = { OwnerAlertSendException(true, "Discord webhook rejected: PNL") }

        assertThat(dispatcher().dispatch()).isEqualTo(JobResult.Success)

        assertThat(store.calls).containsExactly("claim", "markFailed:1", "claim", "markSent:2", "claim")
        assertThat(store.row(1).status).isEqualTo(Status.FAILED)
        assertThat(store.row(1).attempts).isEqualTo(1)
    }

    @Test
    fun `메일 채널이 없으면 UNAVAILABLE_MAIL_SENDER 로 남은 메일 행을 FAILED 로 표시한다`() {
        store.add(emailRow("k1"))

        val result = dispatcher(mailSender = OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER).dispatch()

        assertThat(result).isEqualTo(JobResult.Success)
        assertThat(store.calls).containsExactly("claim", "markFailed:1", "claim")
        assertThat(store.row(1).status).isEqualTo(Status.FAILED)
        assertThat(store.row(1).attempts).isEqualTo(1)
    }

    // ---- 재시도 가능 ----

    @Test
    fun `재시도 가능 실패와 그 밖 예외는 회차 끝에 markAttemptFailed 로 되돌린다`() {
        listOf(
            OwnerAlertSendException(false, "Discord webhook PNL returned 503"),
            IllegalStateException("connection reset"),
        ).forEach { failure ->
            store.rows.clear()
            store.calls.clear()
            store.add(discordRow("k1"))
            store.add(emailRow("k2"))
            discord.failure = { failure }

            assertThat(dispatcher().dispatch()).describedAs(failure.javaClass.simpleName).isEqualTo(JobResult.Success)

            assertThat(store.calls).describedAs(failure.javaClass.simpleName)
                .containsExactly("claim", "claim", "markSent:2", "claim", "markAttemptFailed:1")
            assertThat(store.row(1).status).isEqualTo(Status.PENDING)
            assertThat(store.row(1).attempts).isEqualTo(1)
        }
    }

    // ---- 저장소 ----

    @Test
    fun `markFailed 예외는 Failure 이고 claim 루프를 멈춘다`() {
        store.add(discordRow("k1"))
        store.add(emailRow("k2"))
        discord.failure = { OwnerAlertSendException(true, "Discord webhook rejected: PNL") }
        store.failOn = "markFailed"

        val result = dispatcher().dispatch()

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        assertThat((result as JobResult.Failure).exception).hasMessage("store down: markFailed")
        assertThat(store.row(2).status).isEqualTo(Status.PENDING)
    }

    @Test
    fun `markFailed 가 fencing 되면 경고만 남기고 다음 행을 계속 보낸다`() {
        store.add(discordRow("k1"))
        store.add(emailRow("k2"))
        discord.failure = { OwnerAlertSendException(true, "Discord webhook rejected: PNL") }
        store.fencedOperations += "markFailed"

        assertThat(dispatcher().dispatch()).isEqualTo(JobResult.Success)

        assertThat(store.calls).containsExactly("claim", "markFailed:1", "claim", "markSent:2", "claim")
        assertThat(store.row(2).status).isEqualTo(Status.SENT)
    }

    // ---- 회차 ----

    @Test
    fun `회차당 최대 10건까지만 claim 한다`() {
        (1..12).forEach { store.add(emailRow("k$it")) }

        assertThat(dispatcher().dispatch()).isEqualTo(JobResult.Success)

        assertThat(store.calls.count { it == "claim" }).isEqualTo(OwnerAlertDispatcher.MAX_DELIVERIES_PER_RUN)
        assertThat(mail.sent.map { it.eventKey }).containsExactlyElementsOf((1..10).map { "k$it" })
        assertThat(store.rows.filter { it.status == Status.PENDING }.map { it.mail.eventKey }).containsExactly("k11", "k12")
    }

    @Test
    fun `발송 중 취소되면 그 행은 시도 실패로 되돌리고 남은 행은 claim 하지 않는다`() {
        store.add(discordRow("k1"))
        store.add(discordRow("k2"))
        discord.onSend = { Thread.currentThread().interrupt() }
        discord.failure = { OwnerAlertSendException(false, "Discord webhook PNL interrupted") }

        try {
            val result = dispatcher().dispatch()

            assertThat(result).isEqualTo(JobResult.Success)
            assertThat(store.calls.count { it == "claim" }).isEqualTo(1)
            assertThat(store.calls.last()).isEqualTo("markAttemptFailed:1")
            assertThat(store.row(1).status).isEqualTo(Status.PENDING)
            assertThat(store.row(1).attempts).isEqualTo(1)
            assertThat(store.row(2).status).isEqualTo(Status.PENDING)
            assertThat(store.row(2).attempts).isEqualTo(0)
        } finally {
            Thread.interrupted()
        }
    }

    // ---- 로그 ----

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `발송 실패 로그는 채널·재시도 불가 여부·우리 사유만 남기고 주소·URL·본문은 남기지 않는다`(output: CapturedOutput) {
        store.add(emailRow("k1"))
        store.add(discordRow("k2"))
        store.add(discordRow("k3"))
        mail.failure = { IllegalStateException("smtp to owner@example.test", IllegalArgumentException("rejected")) }
        discord.failure = { message ->
            if (message.title == "title k2") {
                OwnerAlertSendException(true, "Discord webhook rejected: PNL")
            } else {
                IllegalStateException("POST https://discord.example.test/api/webhooks/1/TOKENq failed")
            }
        }

        dispatcher().dispatch()

        assertThat(output.all).contains(
            "Owner alert send failed: id=1, channel=EMAIL, attempt=1, permanent=false, error=IllegalStateException, " +
                "cause=IllegalArgumentException, reason=-",
            "Owner alert send failed: id=2, channel=DISCORD, attempt=1, permanent=true, error=OwnerAlertSendException, " +
                "cause=none, reason=Discord webhook rejected: PNL",
            "Owner alert send failed: id=3, channel=DISCORD, attempt=1, permanent=false, error=IllegalStateException, " +
                "cause=none, reason=-",
        ).doesNotContain(
            "smtp to owner@example.test",
            "owner@example.test",
            "TOKENq",
            "discord.example.test",
            "desc k2",
            "body k1",
        )
    }

    private fun emailRow(key: String) = AccountPnlMail(key, "subject $key", "body $key")

    private fun discordRow(key: String) =
        AccountPnlMail(key, "title $key", "desc $key", OwnerAlertChannel.DISCORD, PNL_HERE, COLOR)

    private companion object {
        const val OWNER = "account-pnl-monitor:test"
        const val COLOR = 0x2ECC71
        val PNL_HERE = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
    }
}
