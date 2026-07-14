package io.premiumspread.infrastructure.common.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.ZoneId

class AggregationInfrastructurePropertiesTest {
    @Test
    fun `기본 집계 시간대는 DST를 사용하지 않는 Asia Seoul이다`() {
        val zone = AggregationInfrastructureProperties().aggregationZone.zoneId

        assertThat(zone).isEqualTo(ZoneId.of("Asia/Seoul"))
        assertThat(zone.rules.isDaylightSavings(java.time.Instant.parse("2026-01-15T00:00:00Z"))).isFalse()
        assertThat(zone.rules.isDaylightSavings(java.time.Instant.parse("2026-07-15T00:00:00Z"))).isFalse()
    }

    @Test
    fun `유효하지 않은 집계 시간대는 기동 전에 거부한다`() {
        assertThatThrownBy { AggregationInfrastructureProperties("Mars/Olympus") }
            .isInstanceOf(DateTimeException::class.java)
    }
}
