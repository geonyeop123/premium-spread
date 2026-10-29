package io.premiumspread.domain.owneralert

import io.premiumspread.domain.owneralert.DiscordPush.SILENT
import io.premiumspread.domain.owneralert.DiscordPush.SOUND
import io.premiumspread.domain.owneralert.DiscordTarget.MARKET
import io.premiumspread.domain.owneralert.OwnerAlertKind.DAILY_SUMMARY
import io.premiumspread.domain.owneralert.OwnerAlertKind.PREMIUM_NEW_EXTREME
import io.premiumspread.domain.owneralert.OwnerAlertKind.PREMIUM_SWING
import io.premiumspread.domain.owneralert.OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OwnerMarketAlertKindTest {

    private val marketKinds = listOf(PREMIUM_SWING, PREMIUM_THRESHOLD_CROSSED, PREMIUM_NEW_EXTREME, DAILY_SUMMARY)

    @Test
    fun `시장 알림 네 종류의 기본 경로는 설계 표와 같다`() {
        assertThat(PREMIUM_SWING.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(MARKET, SOUND)))
        assertThat(PREMIUM_THRESHOLD_CROSSED.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(MARKET, SOUND)))
        assertThat(PREMIUM_NEW_EXTREME.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(MARKET, SILENT)))
        assertThat(DAILY_SUMMARY.defaultRoute).isEqualTo(OwnerAlertRoute(true, DiscordRoute(MARKET, SILENT)))
    }

    @Test
    fun `시장 알림 key 는 kebab 이고 fromKey 로 되돌아온다`() {
        assertThat(marketKinds.map { it.key })
            .containsExactly("premium-swing", "premium-threshold-crossed", "premium-new-extreme", "daily-summary")
        marketKinds.forEach { assertThat(OwnerAlertKind.fromKey(it.key)).isEqualTo(it) }
        assertThat(OwnerAlertKind.fromKey(" Daily-Summary ")).isEqualTo(DAILY_SUMMARY)
    }

    @Test
    fun `메일과 Discord 를 모두 쓰면 기본 경로`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
        marketKinds.forEach { assertThat(routing.resolve(it)).isEqualTo(it.defaultRoute) }
    }

    @Test
    fun `Discord 가 꺼져 있으면 네 종류 모두 메일만`() {
        marketKinds.forEach {
            assertThat(OwnerAlertRouting.EMAIL_ONLY.resolve(it)).isEqualTo(OwnerAlertRoute(true, null))
        }
    }

    @Test
    fun `일일 요약만 메일을 쓴다`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
        assertThat(routing.resolve(DAILY_SUMMARY).email).isTrue()
        listOf(PREMIUM_SWING, PREMIUM_THRESHOLD_CROSSED, PREMIUM_NEW_EXTREME).forEach {
            assertThat(routing.resolve(it).email).isFalse()
        }
    }
}
