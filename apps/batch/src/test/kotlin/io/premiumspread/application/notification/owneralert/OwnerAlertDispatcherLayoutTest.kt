package io.premiumspread.application.notification.owneralert

import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.FakeOwnerAlertStore.Status
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** DISCORD 행의 `discordLayout` 이 `DiscordMessage.layout` 으로 그대로 넘어간다 (dod AC14). 전이표는 `OwnerAlertDispatcherTest` 가 지킨다. */
class OwnerAlertDispatcherLayoutTest {
    private val clock = MutableClock(Instant.parse("2026-10-20T03:00:00Z"))
    private val store = FakeOwnerAlertStore()
    private val mail = FakeMailSender()
    private val discord = FakeDiscordSender()
    private val dispatcher = OwnerAlertDispatcher(store, mail, discord, clock, OWNER)

    @Test
    fun `layout 이 있는 Discord 행은 그 layout 을 메시지에 실어 보낸다`() {
        val layout = DiscordLayout(
            author = "실계정 · BTC",
            fields = listOf(DiscordField("현김프", "1.78%", true)),
            footer = "환율 1,400.00원/USD",
            timestamp = Instant.parse("2026-10-20T00:00:00Z"),
        )
        store.add(discordRow("k1", layout))

        assertThat(dispatcher.dispatch()).isEqualTo(JobResult.Success)

        assertThat(discord.sent).containsExactly(ROUTE to DiscordMessage("title k1", "desc k1", COLOR, layout))
        assertThat(discord.sent.single().second.layout).isSameAs(layout)
        assertThat(store.row(1).status).isEqualTo(Status.SENT)
    }

    @Test
    fun `layout 이 없는 Discord 행은 layout null 로 보낸다`() {
        store.add(discordRow("k1", null))

        assertThat(dispatcher.dispatch()).isEqualTo(JobResult.Success)

        assertThat(discord.sent.single().second.layout).isNull()
    }

    @Test
    fun `메일 행은 Discord sender 를 건드리지 않는다`() {
        store.add(AccountPnlMail("k1", "subject k1", "body k1"))

        assertThat(dispatcher.dispatch()).isEqualTo(JobResult.Success)

        assertThat(discord.attempts).isEmpty()
        assertThat(mail.sent.map { it.eventKey }).containsExactly("k1")
    }

    private fun discordRow(key: String, layout: DiscordLayout?) =
        AccountPnlMail(key, "title $key", "desc $key", OwnerAlertChannel.DISCORD, ROUTE, COLOR, layout)

    private companion object {
        const val OWNER = "account-pnl-monitor:test"
        const val COLOR = 0x2ECC71
        val ROUTE = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
    }
}
