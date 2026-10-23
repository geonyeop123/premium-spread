package io.premiumspread.domain.owneralert

import io.premiumspread.domain.owneralert.DiscordPush.HERE
import io.premiumspread.domain.owneralert.DiscordPush.SILENT
import io.premiumspread.domain.owneralert.DiscordPush.SOUND
import io.premiumspread.domain.owneralert.DiscordTarget.OPS
import io.premiumspread.domain.owneralert.DiscordTarget.PNL
import io.premiumspread.domain.owneralert.OwnerAlertKind.HOURLY_REPORT
import io.premiumspread.domain.owneralert.OwnerAlertKind.MONITORING_OUTAGE
import io.premiumspread.domain.owneralert.OwnerAlertKind.MONITORING_RECOVERED
import io.premiumspread.domain.owneralert.OwnerAlertKind.PROFIT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OwnerAlertRoutingTest {

    private val both = OwnerAlertChannels(email = true, discord = true)
    private val discordOnly = OwnerAlertChannels(email = false, discord = true)
    private val emailOnly = OwnerAlertChannels(email = true, discord = false)

    @Test
    fun `종류 목록과 기본 경로는 설계 표와 같다`() {
        assertThat(OwnerAlertKind.entries.map { it.name })
            .containsExactly("PROFIT", "HOURLY_REPORT", "MONITORING_OUTAGE", "MONITORING_RECOVERED", "POSITION_CHANGED", "NET_PERCENT_SWING", "UNRECOVERED_REMINDER")
        assertThat(PROFIT.defaultRoute).isEqualTo(OwnerAlertRoute(true, DiscordRoute(PNL, HERE)))
        assertThat(HOURLY_REPORT.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(PNL, SILENT)))
        assertThat(MONITORING_OUTAGE.defaultRoute).isEqualTo(OwnerAlertRoute(true, DiscordRoute(OPS, SOUND)))
        assertThat(MONITORING_RECOVERED.defaultRoute).isEqualTo(OwnerAlertRoute(false, DiscordRoute(OPS, SOUND)))
    }

    @Test
    fun `key 는 소문자 kebab 이고 fromKey 로 되돌아온다`() {
        assertThat(OwnerAlertKind.entries.map { it.key })
            .containsExactly("profit", "hourly-report", "monitoring-outage", "monitoring-recovered", "position-changed", "net-percent-swing", "unrecovered-reminder")
        OwnerAlertKind.entries.forEach { assertThat(OwnerAlertKind.fromKey(it.key)).isEqualTo(it) }
        assertThat(OwnerAlertKind.fromKey(" Hourly-Report ")).isEqualTo(HOURLY_REPORT)
        assertThat(OwnerAlertKind.fromKey("nope")).isNull()
    }

    @Test
    fun `EMAIL_ONLY 는 모든 종류가 메일만`() {
        OwnerAlertKind.entries.forEach {
            assertThat(OwnerAlertRouting.EMAIL_ONLY.resolve(it)).isEqualTo(OwnerAlertRoute(true, null))
        }
    }

    @Test
    fun `메일과 Discord 를 모두 쓰면 기본 표대로`() {
        val routing = OwnerAlertRouting(both)
        OwnerAlertKind.entries.forEach { assertThat(routing.resolve(it)).isEqualTo(it.defaultRoute) }
    }

    @Test
    fun `Discord 만 쓰면 메일 없이 Discord 기본값`() {
        val routing = OwnerAlertRouting(discordOnly)
        OwnerAlertKind.entries.forEach {
            assertThat(routing.resolve(it)).isEqualTo(OwnerAlertRoute(false, it.defaultRoute.discord))
        }
    }

    @Test
    fun `Discord 가 없으면 메일이 유일한 채널이라 기본 메일 false 인 종류도 메일로 간다`() {
        val routing = OwnerAlertRouting(emailOnly)
        assertThat(routing.resolve(HOURLY_REPORT)).isEqualTo(OwnerAlertRoute(true, null))
        assertThat(routing.resolve(MONITORING_RECOVERED)).isEqualTo(OwnerAlertRoute(true, null))
    }

    @Test
    fun `Discord 가 없어도 email=false 를 명시하면 그 종류는 행이 없다`() {
        val routing = OwnerAlertRouting(emailOnly, mapOf(HOURLY_REPORT to OwnerAlertRouteOverride(email = false)))
        assertThat(routing.resolve(HOURLY_REPORT)).isEqualTo(OwnerAlertRoute(false, null))
        assertThat(routing.resolve(PROFIT)).isEqualTo(OwnerAlertRoute(true, null))
    }

    @Test
    fun `override 는 지정한 필드만 바꾼다`() {
        val routing = OwnerAlertRouting(
            both,
            mapOf(HOURLY_REPORT to OwnerAlertRouteOverride(email = true)),
        )
        assertThat(routing.resolve(HOURLY_REPORT)).isEqualTo(OwnerAlertRoute(true, DiscordRoute(PNL, SILENT)))

        val target = OwnerAlertRouting(both, mapOf(PROFIT to OwnerAlertRouteOverride(discordTarget = OPS)))
        assertThat(target.resolve(PROFIT)).isEqualTo(OwnerAlertRoute(true, DiscordRoute(OPS, HERE)))

        val push = OwnerAlertRouting(both, mapOf(PROFIT to OwnerAlertRouteOverride(push = SOUND)))
        assertThat(push.resolve(PROFIT)).isEqualTo(OwnerAlertRoute(true, DiscordRoute(PNL, SOUND)))
    }

    @Test
    fun `discordDisabled 면 Discord 가 없다`() {
        val routing = OwnerAlertRouting(both, mapOf(PROFIT to OwnerAlertRouteOverride(discordDisabled = true)))
        assertThat(routing.resolve(PROFIT)).isEqualTo(OwnerAlertRoute(true, null))
    }

    @Test
    fun `discordDisabled 와 discordTarget 을 함께 주면 거부`() {
        assertThatThrownBy { OwnerAlertRouteOverride(discordDisabled = true, discordTarget = OPS) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `채널이 모두 없으면 모든 종류가 빈 경로`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = false, discord = false))
        OwnerAlertKind.entries.forEach { assertThat(routing.resolve(it)).isEqualTo(OwnerAlertRoute(false, null)) }
    }
}
