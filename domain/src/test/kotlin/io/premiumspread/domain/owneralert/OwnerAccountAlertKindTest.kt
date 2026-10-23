package io.premiumspread.domain.owneralert

import io.premiumspread.domain.owneralert.DiscordPush.HERE
import io.premiumspread.domain.owneralert.DiscordPush.SILENT
import io.premiumspread.domain.owneralert.DiscordPush.SOUND
import io.premiumspread.domain.owneralert.DiscordTarget.PNL
import io.premiumspread.domain.owneralert.OwnerAlertKind.NET_PERCENT_SWING
import io.premiumspread.domain.owneralert.OwnerAlertKind.POSITION_CHANGED
import io.premiumspread.domain.owneralert.OwnerAlertKind.UNRECOVERED_REMINDER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OwnerAccountAlertKindTest {

    private val accountKinds = listOf(POSITION_CHANGED, NET_PERCENT_SWING, UNRECOVERED_REMINDER)

    @Test
    fun `계정 알림 세 종류의 기본 경로는 설계 표와 같다`() {
        assertThat(POSITION_CHANGED.defaultRoute).isEqualTo(OwnerAlertRoute(true, DiscordRoute(PNL, HERE)))
        assertThat(NET_PERCENT_SWING.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(PNL, SOUND)))
        assertThat(UNRECOVERED_REMINDER.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(PNL, SILENT)))
    }

    @Test
    fun `계정 알림 key 는 kebab 이고 fromKey 로 되돌아온다`() {
        assertThat(accountKinds.map { it.key })
            .containsExactly("position-changed", "net-percent-swing", "unrecovered-reminder")
        accountKinds.forEach { assertThat(OwnerAlertKind.fromKey(it.key)).isEqualTo(it) }
        assertThat(OwnerAlertKind.fromKey(" Net-Percent-Swing ")).isEqualTo(NET_PERCENT_SWING)
    }

    @Test
    fun `메일과 Discord 를 모두 쓰면 기본 경로`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
        accountKinds.forEach { assertThat(routing.resolve(it)).isEqualTo(it.defaultRoute) }
    }

    @Test
    fun `Discord 가 꺼져 있으면 세 종류 모두 메일만`() {
        accountKinds.forEach {
            assertThat(OwnerAlertRouting.EMAIL_ONLY.resolve(it)).isEqualTo(OwnerAlertRoute(true, null))
        }
    }
}
