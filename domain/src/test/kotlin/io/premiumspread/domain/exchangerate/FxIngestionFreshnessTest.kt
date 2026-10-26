package io.premiumspread.domain.exchangerate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class FxIngestionFreshnessTest {
    private val t0 = Instant.parse("2026-10-23T05:00:00Z")

    @Test
    fun `허용 공백은 30분이다`() {
        assertThat(FxIngestionFreshness.MAX_INGESTION_GAP).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `경과가 0이면 신선하다`() {
        assertThat(FxIngestionFreshness.isStale(t0, t0)).isFalse()
    }

    @Test
    fun `경과가 정확히 30분이면 신선하다`() {
        assertThat(FxIngestionFreshness.isStale(t0, t0.plus(Duration.ofMinutes(30)))).isFalse()
    }

    @Test
    fun `경과가 30분을 1ms 넘으면 stale 이다`() {
        val now = t0.plus(Duration.ofMinutes(30)).plusMillis(1)

        assertThat(FxIngestionFreshness.isStale(t0, now)).isTrue()
    }

    @Test
    fun `마지막 성공이 미래이면 신선하다`() {
        assertThat(FxIngestionFreshness.isStale(t0, t0.minus(Duration.ofMinutes(1)))).isFalse()
    }
}
