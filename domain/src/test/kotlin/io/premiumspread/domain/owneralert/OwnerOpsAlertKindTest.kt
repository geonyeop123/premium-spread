package io.premiumspread.domain.owneralert

import io.premiumspread.domain.owneralert.OwnerAlertKind.FX_INGESTION_DELAYED
import io.premiumspread.domain.owneralert.OwnerAlertKind.JOB_CONSECUTIVE_FAILURES
import io.premiumspread.domain.owneralert.OwnerAlertKind.MARKET_STREAM_DOWN
import io.premiumspread.domain.owneralert.OwnerAlertKind.OPS_RECOVERED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OwnerOpsAlertKindTest {

    private val opsKinds = listOf(FX_INGESTION_DELAYED, MARKET_STREAM_DOWN, JOB_CONSECUTIVE_FAILURES, OPS_RECOVERED)

    @Test
    fun `운영 알림 네 종류의 기본 경로는 설계 표와 같다`() {
        assertThat(FX_INGESTION_DELAYED.defaultRoute)
            .isEqualTo(OwnerAlertRoute(false, DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND)))
        assertThat(MARKET_STREAM_DOWN.defaultRoute)
            .isEqualTo(OwnerAlertRoute(false, DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND)))
        assertThat(JOB_CONSECUTIVE_FAILURES.defaultRoute)
            .isEqualTo(OwnerAlertRoute(true, DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND)))
        assertThat(OPS_RECOVERED.defaultRoute)
            .isEqualTo(OwnerAlertRoute(false, DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT)))
    }

    @Test
    fun `운영 알림 key 는 kebab 이고 fromKey 로 되돌아온다`() {
        assertThat(opsKinds.map { it.key }).containsExactly(
            "fx-ingestion-delayed",
            "market-stream-down",
            "job-consecutive-failures",
            "ops-recovered",
        )
        opsKinds.forEach { assertThat(OwnerAlertKind.fromKey(it.key)).isEqualTo(it) }
        assertThat(OwnerAlertKind.fromKey(" Ops-Recovered ")).isEqualTo(OPS_RECOVERED)
    }

    @Test
    fun `메일과 Discord 를 모두 쓰면 기본 경로`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
        opsKinds.forEach { assertThat(routing.resolve(it)).isEqualTo(it.defaultRoute) }
    }

    @Test
    fun `Discord 가 꺼져 있으면 네 종류 모두 메일만`() {
        opsKinds.forEach {
            assertThat(OwnerAlertRouting.EMAIL_ONLY.resolve(it)).isEqualTo(OwnerAlertRoute(true, null))
        }
    }

    @Test
    fun `복구는 무음이고 메일이 없다`() {
        val routing = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = true))
        assertThat(routing.resolve(OPS_RECOVERED))
            .isEqualTo(OwnerAlertRoute(false, DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT)))
    }
}
