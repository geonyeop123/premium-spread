package io.premiumspread.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class BatchHealthIndicatorTest {
    @Test
    fun `health uses injected clock for deterministic stale decision`() {
        val clock = MutableClock(Instant.parse("2026-07-15T00:00:00Z"))
        val indicator = BatchHealthIndicator("premium_realtime", Duration.ofSeconds(30), clock)
        indicator.recordSuccess()

        clock.advance(Duration.ofSeconds(31))

        assertThat(indicator.health().status).isEqualTo(Status.DOWN)
        assertThat(indicator.health().details["elapsedSeconds"]).isEqualTo(31L)
    }

    @Test
    fun `record success and failure timestamps come only from injected clock`() {
        val recordedAt = Instant.parse("2026-07-15T01:02:03Z")
        val clock = MutableClock(recordedAt)
        val indicator = BatchHealthIndicator("ticker_flush", Duration.ofMinutes(1), clock)

        indicator.recordFailure("bounded-error")
        assertThat(indicator.getLastRunTime()).isEqualTo(recordedAt)

        clock.advance(Duration.ofSeconds(10))
        indicator.recordSuccess()
        assertThat(indicator.getLastRunTime()).isEqualTo(recordedAt.plusSeconds(10))
        assertThat(indicator.health().status).isEqualTo(Status.UP)
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
