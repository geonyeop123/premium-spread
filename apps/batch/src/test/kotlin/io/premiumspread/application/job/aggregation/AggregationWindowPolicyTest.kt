package io.premiumspread.application.job.aggregation

import io.premiumspread.config.AggregationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

    @Test
    fun `KST 일 bucket은 여름과 겨울 모두 24시간이며 DST 보정을 하지 않는다`() {
        val policy = AggregationWindowPolicy(AggregationProperties())

        val winter = policy.previous(Instant.parse("2026-01-15T12:00:00Z"), ChronoUnit.DAYS)
        val summer = policy.previous(Instant.parse("2026-07-15T12:00:00Z"), ChronoUnit.DAYS)

        assertThat(Duration.between(winter.from, winter.to)).isEqualTo(Duration.ofHours(24))
        assertThat(Duration.between(summer.from, summer.to)).isEqualTo(Duration.ofHours(24))
        assertThat(winter.from.atZone(winter.zone.zoneId).offset).isEqualTo(ZoneOffset.ofHours(9))
        assertThat(summer.from.atZone(summer.zone.zoneId).offset).isEqualTo(ZoneOffset.ofHours(9))
    }
}
