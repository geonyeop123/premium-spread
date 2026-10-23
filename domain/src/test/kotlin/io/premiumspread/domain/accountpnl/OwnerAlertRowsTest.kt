package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertRoute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OwnerAlertRowsTest {

    private val mail = AccountPnlMail("account-pnl-profit-1", "s", "b")
    private val message = DiscordMessage("T", "D", 0x2ECC71)
    private var renderCount = 0
    private val render: () -> DiscordMessage = { renderCount++; message }

    @Test
    fun `3-인자 생성은 EMAIL 이고 Discord 필드가 없다`() {
        assertThat(mail.channel).isEqualTo(OwnerAlertChannel.EMAIL)
        assertThat(mail.discordRoute).isNull()
        assertThat(mail.discordColor).isNull()
    }

    @Test
    fun `행 채널과 Discord 필드가 어긋나면 거부한다`() {
        val route = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, null, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, route, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.EMAIL, route, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.EMAIL, null, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `메일과 Discord 가 모두 켜지면 메일 행이 먼저인 2행이다`() {
        val route = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
        val rows = OwnerAlertRows.expand(mail, OwnerAlertRoute(true, route), render)

        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isSameAs(mail)
        assertThat(rows[1]).isEqualTo(
            AccountPnlMail("account-pnl-profit-1", "T", "D", OwnerAlertChannel.DISCORD, route, 0x2ECC71),
        )
        assertThat(renderCount).isEqualTo(1)
    }

    @Test
    fun `Discord 만 켜지면 Discord 1행이다`() {
        val route = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND)
        val rows = OwnerAlertRows.expand(mail, OwnerAlertRoute(false, route), render)

        assertThat(rows).hasSize(1)
        assertThat(rows[0].channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(rows[0].discordRoute).isEqualTo(route)
    }

    @Test
    fun `메일만 켜지면 메일 행 하나이고 렌더러를 부르지 않는다`() {
        assertThat(OwnerAlertRows.expand(mail, OwnerAlertRoute(true, null), render)).containsExactly(mail)
        assertThat(renderCount).isZero()
    }

    @Test
    fun `채널이 모두 꺼지면 빈 목록이고 렌더러를 부르지 않는다`() {
        assertThat(OwnerAlertRows.expand(mail, OwnerAlertRoute(false, null), render)).isEmpty()
        assertThat(renderCount).isZero()
    }

    @Test
    fun `DISCORD 행을 mail 로 주면 거부한다`() {
        val discordRow = AccountPnlMail(
            "k", "s", "b", OwnerAlertChannel.DISCORD, DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT), 1,
        )
        assertThatThrownBy { OwnerAlertRows.expand(discordRow, OwnerAlertRoute(true, null), render) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
