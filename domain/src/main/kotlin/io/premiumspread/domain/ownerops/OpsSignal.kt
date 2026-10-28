package io.premiumspread.domain.ownerops

import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import java.time.Duration
import java.time.Instant

/** 운영 신호 하나. [key] 는 owner_alert_signal_state.signal_key 이자 eventKey 의 일부다 (design §5.1). */
sealed interface OpsSignal {
    val key: String
    val alertKind: OwnerAlertKind

    /** key = "fx-ingestion.{base}-{quote}" (소문자). */
    data class FxIngestion(val base: Currency, val quote: Currency) : OpsSignal {
        override val key: String get() = "fx-ingestion.${base.code.lowercase()}-${quote.code.lowercase()}"
        override val alertKind: OwnerAlertKind get() = OwnerAlertKind.FX_INGESTION_DELAYED
    }

    /** key = "market-stream.{exchange}.{quote.baseCode}-{quote.currency.code}" (소문자). */
    data class MarketStream(val exchange: Exchange, val quote: Quote) : OpsSignal {
        override val key: String
            get() = "market-stream.${exchange.name.lowercase()}.${quote.baseCode.lowercase()}-${quote.currency.code.lowercase()}"
        override val alertKind: OwnerAlertKind get() = OwnerAlertKind.MARKET_STREAM_DOWN
    }

    /** key = "job-failures.{jobId.tag}". */
    data class JobFailures(val jobId: JobId) : OpsSignal {
        override val key: String get() = "job-failures.${jobId.tag.lowercase()}"
        override val alertKind: OwnerAlertKind get() = OwnerAlertKind.JOB_CONSECUTIVE_FAILURES
    }
}

/** 운영 신호의 이번 회차 관측. */
sealed interface OpsObservation {
    /** 장애 조건 충족. [since] = 마지막 정상 근거 시각, 없으면 null. [failureCount] 는 Job 만. */
    data class Down(val since: Instant?, val failureCount: Int? = null) : OpsObservation {
        init {
            require(failureCount == null || failureCount >= 1) { "failureCount must be at least 1." }
        }
    }

    /** 정상 근거가 있다. */
    data object Up : OpsObservation

    /** 판정할 근거가 아직 없다(재시작 직후 등). 장애를 열지도 닫지도 않는다. */
    data object Unknown : OpsObservation
}

/** 임계값. 기본값 = 사용자 합의 U2·U4. */
data class OpsAlertSettings(
    val marketStaleAfter: Duration = Duration.ofMinutes(5),
    val jobFailureCount: Int = 3,
    val jobFailureDuration: Duration = Duration.ofMinutes(5),
    val realertInterval: Duration = Duration.ofHours(6),
) {
    init {
        require(marketStaleAfter > Duration.ZERO) { "marketStaleAfter must be positive." }
        require(jobFailureCount >= 1) { "jobFailureCount must be at least 1." }
        require(!jobFailureDuration.isNegative) { "jobFailureDuration must not be negative." }
        require(realertInterval > Duration.ZERO) { "realertInterval must be positive." }
    }
}

/** 관측 함수 — 순수. 시각은 모두 호출자가 준 [now](주입 Clock) 기준. 판정표는 위에서부터 먼저 맞는 행이다. */
object OpsSignals {
    fun fx(lastSuccessAt: Instant?, firstRunAt: Instant, now: Instant): OpsObservation = when {
        lastSuccessAt != null && FxIngestionFreshness.isStale(lastSuccessAt, now) -> OpsObservation.Down(lastSuccessAt)
        lastSuccessAt != null -> OpsObservation.Up
        FxIngestionFreshness.isStale(firstRunAt, now) -> OpsObservation.Down(null)
        else -> OpsObservation.Unknown
    }

    fun market(tick: MarketTick?, firstRunAt: Instant, now: Instant, settings: OpsAlertSettings): OpsObservation = when {
        tick != null && age(tick.observedAt, now) >= settings.marketStaleAfter -> OpsObservation.Down(tick.observedAt)
        tick != null -> OpsObservation.Up
        Duration.between(firstRunAt, now) >= settings.marketStaleAfter -> OpsObservation.Down(null)
        else -> OpsObservation.Unknown
    }

    fun job(
        streak: JobFailureStreak?,
        lastSuccessAt: Instant?,
        now: Instant,
        settings: OpsAlertSettings,
    ): OpsObservation = when {
        streak != null &&
            streak.consecutiveFailures >= settings.jobFailureCount &&
            Duration.between(streak.firstFailureAt, now) >= settings.jobFailureDuration ->
            OpsObservation.Down(streak.firstFailureAt, streak.consecutiveFailures)
        lastSuccessAt != null -> OpsObservation.Up
        else -> OpsObservation.Unknown
    }

    /** 미래 observedAt 은 나이 0 으로 본다. */
    private fun age(observedAt: Instant, now: Instant): Duration =
        Duration.between(observedAt, now).let { if (it.isNegative) Duration.ZERO else it }
}
