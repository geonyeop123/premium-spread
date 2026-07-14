package io.premiumspread.application.job.aggregation

import io.premiumspread.config.AggregationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class AggregationWindowPolicyTest {

    @Test
    fun `기본 KST 일 집계는 현지 자정 경계를 UTC Instant로 변환한다`() {
        val policy = AggregationWindowPolicy(AggregationProperties())

        val window = policy.previous(Instant.parse("2026-05-12T01:00:00Z"), ChronoUnit.DAYS)

        assertThat(window.from).isEqualTo(Instant.parse("2026-05-10T15:00:00Z"))
        assertThat(window.to).isEqualTo(Instant.parse("2026-05-11T15:00:00Z"))
        assertThat(window.zone.zoneId.id).isEqualTo("Asia/Seoul")
    }

    @Test
    fun `UTC 일 집계는 UTC 자정 경계를 사용한다`() {
        val policy = AggregationWindowPolicy(AggregationProperties(zone = "UTC"))

        val window = policy.previous(Instant.parse("2026-05-12T01:00:00Z"), ChronoUnit.DAYS)

        assertThat(window.from).isEqualTo(Instant.parse("2026-05-11T00:00:00Z"))
        assertThat(window.to).isEqualTo(Instant.parse("2026-05-12T00:00:00Z"))
        assertThat(window.zone.zoneId.id).isEqualTo("UTC")
    }
}
