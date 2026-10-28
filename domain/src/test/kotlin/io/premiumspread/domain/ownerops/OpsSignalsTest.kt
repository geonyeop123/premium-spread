package io.premiumspread.domain.ownerops

import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class OpsSignalsTest {
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val settings = OpsAlertSettings()
    private val btcKrw = Quote.coin(Symbol("BTC"), Currency.KRW)
    private val btcUsd = Quote.coin(Symbol("BTC"), Currency.USD)

    private fun tick(observedAt: Instant) = MarketTick(Exchange.BITHUMB, btcKrw, BigDecimal.ONE, observedAt)

    private fun streak(count: Int, firstFailureAt: Instant) =
        JobFailureStreak(JobId.FX_INGESTION, count, firstFailureAt, firstFailureAt)

    @Test
    fun `FX 정확히 30분은 정상이고 1ns 넘으면 장애`() {
        val thirty = now.minus(Duration.ofMinutes(30))
        assertThat(OpsSignals.fx(thirty, now.minusSeconds(86400), now)).isEqualTo(OpsObservation.Up)
        val over = thirty.minusNanos(1)
        assertThat(OpsSignals.fx(over, now.minusSeconds(86400), now)).isEqualTo(OpsObservation.Down(over))
    }

    @Test
    fun `FX 성공 기록이 없으면 첫 실행 기준 30분 동안 Unknown, 넘으면 since 없는 장애`() {
        val thirty = now.minus(Duration.ofMinutes(30))
        assertThat(OpsSignals.fx(null, thirty, now)).isEqualTo(OpsObservation.Unknown)
        assertThat(OpsSignals.fx(null, thirty.minusNanos(1), now)).isEqualTo(OpsObservation.Down(null))
    }

    @Test
    fun `시세 5분 미만 Up, 정확히 5분 Down(observedAt), 미래 observedAt Up`() {
        val first = now.minusSeconds(86400)
        val almost = now.minus(Duration.ofMinutes(5)).plusMillis(1)
        assertThat(OpsSignals.market(tick(almost), first, now, settings)).isEqualTo(OpsObservation.Up)
        val exact = now.minus(Duration.ofMinutes(5))
        assertThat(OpsSignals.market(tick(exact), first, now, settings)).isEqualTo(OpsObservation.Down(exact))
        assertThat(OpsSignals.market(tick(now.plusSeconds(30)), first, now, settings)).isEqualTo(OpsObservation.Up)
    }

    @Test
    fun `시세 tick 없음은 첫 실행 기준 5분 Unknown 뒤 Down(null)`() {
        val five = now.minus(Duration.ofMinutes(5))
        assertThat(OpsSignals.market(null, five.plusMillis(1), now, settings)).isEqualTo(OpsObservation.Unknown)
        assertThat(OpsSignals.market(null, five, now, settings)).isEqualTo(OpsObservation.Down(null))
    }

    @Test
    fun `Job 3회·5분 경계와 2회·1시간`() {
        val five = now.minus(Duration.ofMinutes(5))
        assertThat(OpsSignals.job(streak(3, five), null, now, settings)).isEqualTo(OpsObservation.Down(five, 3))
        assertThat(OpsSignals.job(streak(3, five.plusMillis(1)), now.minusSeconds(1), now, settings))
            .isEqualTo(OpsObservation.Up)
        assertThat(OpsSignals.job(streak(3, five.plusMillis(1)), null, now, settings)).isEqualTo(OpsObservation.Unknown)
        val hour = now.minus(Duration.ofHours(1))
        assertThat(OpsSignals.job(streak(2, hour), now.minusSeconds(1), now, settings)).isEqualTo(OpsObservation.Up)
        assertThat(OpsSignals.job(streak(2, hour), null, now, settings)).isEqualTo(OpsObservation.Unknown)
    }

    @Test
    fun `Job streak 없음 + 성공 있음 Up, 둘 다 없음 Unknown`() {
        assertThat(OpsSignals.job(null, now.minusSeconds(1), now, settings)).isEqualTo(OpsObservation.Up)
        assertThat(OpsSignals.job(null, null, now, settings)).isEqualTo(OpsObservation.Unknown)
    }

    @Test
    fun `신호 key 와 alertKind`() {
        val fx = OpsSignal.FxIngestion(Currency.USD, Currency.KRW)
        val bithumb = OpsSignal.MarketStream(Exchange.BITHUMB, btcKrw)
        val binance = OpsSignal.MarketStream(Exchange.BINANCE, btcUsd)
        val job = OpsSignal.JobFailures(JobId.FX_INGESTION)

        assertThat(fx.key).isEqualTo("fx-ingestion.usd-krw")
        assertThat(bithumb.key).isEqualTo("market-stream.bithumb.btc-krw")
        assertThat(binance.key).isEqualTo("market-stream.binance.btc-usd")
        assertThat(job.key).isEqualTo("job-failures.fx_ingestion")
        listOf(fx, bithumb, binance, job).forEach {
            assertThat(it.key).matches(OwnerAlertSignalState.SIGNAL_KEY_PATTERN.toString())
        }
        assertThat(fx.alertKind).isEqualTo(OwnerAlertKind.FX_INGESTION_DELAYED)
        assertThat(bithumb.alertKind).isEqualTo(OwnerAlertKind.MARKET_STREAM_DOWN)
        assertThat(binance.alertKind).isEqualTo(OwnerAlertKind.MARKET_STREAM_DOWN)
        assertThat(job.alertKind).isEqualTo(OwnerAlertKind.JOB_CONSECUTIVE_FAILURES)
    }

    @Test
    fun `설정 기본값과 거부`() {
        val d = OpsAlertSettings()
        assertThat(d.marketStaleAfter).isEqualTo(Duration.ofMinutes(5))
        assertThat(d.jobFailureCount).isEqualTo(3)
        assertThat(d.jobFailureDuration).isEqualTo(Duration.ofMinutes(5))
        assertThat(d.realertInterval).isEqualTo(Duration.ofHours(6))

        assertThatThrownBy { OpsAlertSettings(marketStaleAfter = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("marketStaleAfter must be positive.")
        assertThatThrownBy { OpsAlertSettings(jobFailureCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("jobFailureCount must be at least 1.")
        assertThatThrownBy { OpsAlertSettings(jobFailureDuration = Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("jobFailureDuration must not be negative.")
        assertThatThrownBy { OpsAlertSettings(realertInterval = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("realertInterval must be positive.")
    }

    @Test
    fun `JobFailureStreak 불변식`() {
        assertThatThrownBy { streak(0, now) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("consecutiveFailures must be at least 1.")
        assertThatThrownBy { JobFailureStreak(JobId.FX_INGESTION, 1, now, now.minusNanos(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("lastFailureAt must not be before firstFailureAt.")
        assertThat(JobFailureStreak(JobId.FX_INGESTION, 1, now, now).consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `Down 은 failureCount 0 을 거부한다`() {
        assertThatThrownBy { OpsObservation.Down(now, 0) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("failureCount must be at least 1.")
    }
}
