package io.premiumspread.application.notification.owneralert

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore.Status
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.time.Instant

/** owner 알림 독립 전달 Job (design §7.2, dod AC9). */
@ExtendWith(OutputCaptureExtension::class)
class OwnerAlertDeliveryJobTest {
    private val clock = MutableClock(Instant.parse("2026-10-28T00:00:00Z"))
    private val fake = FakeOwnerAlertStore()
    private val mail = FakeMailSender()
    private val discord = FakeDiscordSender()
    private val executor = mockk<JobExecutor>()
    private val configSlot = slot<JobConfig>()
    private var staleRecovered = 0
    private var staleFailure: Exception? = null
    private val staleArgs = mutableListOf<Pair<Instant, Duration>>()

    /** FakeOwnerAlertStore 는 recoverStale 을 기록하지 않으므로 위임 래퍼로 인자·순서를 잰다 (fake 무수정). */
    private val store: AccountPnlAlertStore = object : AccountPnlAlertStore by fake {
        override fun recoverStale(now: Instant, staleAfter: Duration): Int {
            fake.calls += "recoverStale"
            staleArgs += now to staleAfter
            staleFailure?.let { throw it }
            return staleRecovered
        }
    }

    init {
        every { executor.execute(capture(configSlot), any()) } answers { secondArg<() -> JobResult>().invoke() }
    }

    private fun job() = OwnerAlertDeliveryJob(store, mail, discord, executor, clock)

    @Test
    fun `OWNER_ALERT_DELIVERY 설정으로 executor 를 통해 실행한다`() {
        job().run()

        assertThat(configSlot.captured.jobId).isEqualTo(JobId.OWNER_ALERT_DELIVERY)
    }

    @Test
    fun `매 회차 recoverStale 을 now 와 5분으로 claim 보다 먼저 부른다`() {
        fake.add(emailRow("k1"))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(staleArgs).containsExactly(clock.instant() to Duration.ofMinutes(5))
        assertThat(fake.calls.first()).isEqualTo("recoverStale")
        assertThat(fake.calls.indexOf("recoverStale")).isLessThan(fake.calls.indexOf("claim"))
    }

    @Test
    fun `복구한 claim 이 있으면 경고 로그 후 계속 전달한다`(output: CapturedOutput) {
        staleRecovered = 2
        fake.add(emailRow("k1"))

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(output.all).contains("Recovered 2 stale owner alert claims")
        assertThat(mail.sent).hasSize(1)
    }

    @Test
    fun `recoverStale 예외면 claim 없이 Failure 이고 오류를 로그한다`(output: CapturedOutput) {
        staleFailure = IllegalStateException("store down: recoverStale")
        fake.add(emailRow("k1"))

        val result = job().run()

        assertThat(result).isInstanceOf(JobResult.Failure::class.java)
        assertThat(fake.calls).containsExactly("recoverStale")
        assertThat(mail.attempts).isEmpty()
        assertThat(output.all).contains("Owner alert store failed during recoverStale")
    }

    @Test
    fun `PENDING 12행이면 한 회차에 claim 10회 발송 10건 다음 회차에 2건`() {
        (1..12).forEach { fake.add(emailRow("k$it")) }
        val job = job()

        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(fake.calls.count { it == "claim" }).isEqualTo(10)
        assertThat(mail.sent).hasSize(10)

        assertThat(job.run()).isEqualTo(JobResult.Success)
        assertThat(mail.sent).hasSize(12)
        assertThat(fake.rows.map { it.status }.toSet()).containsExactly(Status.SENT)
    }

    @Test
    fun `claim owner 는 접두사로 시작하고 64자 이하이며 인스턴스 안에서 같다`() {
        fake.add(emailRow("k1"))
        fake.add(emailRow("k2"))

        job().run()

        assertThat(fake.owners).isNotEmpty()
        assertThat(fake.owners.toSet()).hasSize(1)
        val owner = fake.owners.first()
        assertThat(owner).startsWith(OwnerAlertDeliveryJob.OWNER_PREFIX)
        assertThat(owner.length).isLessThanOrEqualTo(64)
    }

    @Test
    fun `재시도 가능한 Discord 실패는 회차 끝에 markAttemptFailed 하고 Success 다`() {
        fake.add(discordRow("k1"))
        discord.failure = { OwnerAlertSendException(false, "Discord webhook unavailable: PNL") }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(fake.calls.filter { it.startsWith("mark") }).containsExactly("markAttemptFailed:1")
        assertThat(fake.row(1).status).isEqualTo(Status.PENDING)
        assertThat(fake.row(1).attempts).isEqualTo(1)
    }

    @Test
    fun `영구 Discord 실패는 즉시 markFailed 하고 Success 다`() {
        fake.add(discordRow("k1"))
        discord.failure = { OwnerAlertSendException(true, "Discord webhook rejected: PNL") }

        assertThat(job().run()).isEqualTo(JobResult.Success)

        assertThat(fake.calls.filter { it.startsWith("mark") }).containsExactly("markFailed:1")
        assertThat(fake.row(1).status).isEqualTo(Status.FAILED)
    }

    @Test
    fun `claim 예외는 Failure 다`() {
        fake.failOn = "claim"

        assertThat(job().run()).isInstanceOf(JobResult.Failure::class.java)
    }

    private fun emailRow(key: String) = AccountPnlMail(key, "subject $key", "body $key")

    private fun discordRow(key: String) =
        AccountPnlMail(key, "title $key", "desc $key", OwnerAlertChannel.DISCORD, PNL_HERE, 0x2ECC71)

    private companion object {
        val PNL_HERE = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
    }
}
