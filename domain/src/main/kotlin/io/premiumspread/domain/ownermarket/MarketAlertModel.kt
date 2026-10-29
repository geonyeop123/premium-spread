package io.premiumspread.domain.ownermarket

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertKind
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

object MarketKeys {
    const val MAX_PAIR_KEY_LENGTH = 40

    /**
     * "{symbol}-{korea}-{foreign}".lowercase(). 부분이 [a-z0-9]+ 가 아니거나 40자 초과면 IllegalArgumentException.
     */
    fun pairKey(pair: MarketPair): String {
        val parts = listOf(pair.symbol.code, pair.koreaExchange.name, pair.foreignExchange.name).map { it.lowercase() }
        val key = parts.joinToString("-")
        require(parts.all { PAIR_PART.matches(it) } && key.length <= MAX_PAIR_KEY_LENGTH) {
            "market pair key must be [a-z0-9]+ parts within 40 chars."
        }
        return key
    }

    fun swing(pairKey: String, direction: MarketDirection): String =
        "market.$pairKey.swing-${direction.name.lowercase()}"

    fun threshold(pairKey: String): String = "market.$pairKey.threshold"

    fun extreme(pairKey: String, window: ExtremeWindow, direction: MarketDirection): String {
        val span = if (window == ExtremeWindow.ONE_HOUR) "1h" else "24h"
        val side = if (direction == MarketDirection.UP) "high" else "low"
        return "market.$pairKey.extreme-$span-$side"
    }

    fun dailySummary(pairKey: String): String = "market.$pairKey.daily-summary"

    /** 정책 공통 key 검사. 다르면 IllegalArgumentException("market state key mismatch."). */
    fun requireKey(state: MarketAlertState, expected: String) {
        require(state.signalKey == expected) { "market state key mismatch." }
    }

    private val PAIR_PART = Regex("[a-z0-9]+")
}

enum class PremiumZone { ABOVE, BETWEEN, BELOW }

/** 기준선 구역을 정할 때 쓴 설정. 비교는 compareTo(scale 무관). upper·lower 중 하나 이상 non-null. */
data class ThresholdBaseline(val upper: BigDecimal?, val lower: BigDecimal?, val hysteresis: BigDecimal) {
    init {
        require(upper != null || lower != null) { "threshold baseline needs upper or lower." }
        require(hysteresis.signum() >= 0) { "hysteresis must not be negative." }
    }

    /** 세 값이 모두 compareTo == 0 (null 은 null 과만 같다). */
    fun sameAs(other: ThresholdBaseline): Boolean =
        sameValue(upper, other.upper) && sameValue(lower, other.lower) && hysteresis.compareTo(other.hysteresis) == 0

    private fun sameValue(a: BigDecimal?, b: BigDecimal?): Boolean =
        if (a == null || b == null) a == null && b == null else a.compareTo(b) == 0
}

/**
 * 시장 알림 신호 하나의 지속 상태 (owner_market_alert_state 한 행). [version] 0 = 아직 행이 없다.
 * zone·baseline 은 함께 있거나 함께 없다(기준선 신호만). summaryDate 는 일일 요약 신호만.
 */
data class MarketAlertState(
    val signalKey: String,
    val alertKind: OwnerAlertKind,
    val lastNotifiedAt: Instant?,
    val zone: PremiumZone?,
    val baseline: ThresholdBaseline?,
    val summaryDate: LocalDate?,
    val version: Long,
) {
    init {
        require(OwnerAlertSignalState.SIGNAL_KEY_PATTERN.matches(signalKey)) {
            "signalKey must match [a-z0-9._-]{1,64}."
        }
        require(version >= 0) { "version must not be negative." }
        require((zone == null) == (baseline == null)) { "zone and baseline must be set together." }
        require(zone == null || alertKind == OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED) {
            "zone is only for threshold signals."
        }
        require(summaryDate == null || alertKind == OwnerAlertKind.DAILY_SUMMARY) {
            "summaryDate is only for daily summary signals."
        }
    }

    companion object {
        fun initial(signalKey: String, alertKind: OwnerAlertKind): MarketAlertState =
            MarketAlertState(signalKey, alertKind, null, null, null, null, 0)
    }
}

/**
 * 시장 알림 상태 + outbox(account_pnl_mail) 행을 한 트랜잭션으로 쓴다 (DB-first). 계약은 OwnerAlertOutbox 와 같다.
 * 호출자 트랜잭션 밖에서 호출한다 - 구현이 자체 트랜잭션을 연다.
 */
interface OwnerMarketAlertStore {
    /** [signalKeys] 중 저장된 행만. 없는 key 는 map 에 없다. 빈 입력이면 질의 없이 빈 map. */
    fun load(signalKeys: Collection<String>): Map<String, MarketAlertState>

    /**
     * 한 트랜잭션: [states] 각각을 version 조건부로 쓰고(version 0 = INSERT, 그 밖 = `version = ?` UPDATE, 저장 후 +1)
     * 모두 성공하면 [rows] 를 넣는다. 같은 (eventKey, channel) 행은 무시한다. 하나라도 version 이 맞지 않으면
     * 아무것도 쓰지 않고 false. [states] 의 signalKey 는 서로 달라야 한다
     * (아니면 IllegalArgumentException("signal keys must be distinct.")). 둘 다 비면 트랜잭션 없이 true.
     */
    fun enqueue(states: List<MarketAlertState>, rows: List<AccountPnlMail>): Boolean
}

/** 기본값 = owner 합의 U2·U3. 단위: %p(김프 퍼센트 포인트). */
data class MarketAlertSettings(
    val swingWindow: Duration = Duration.ofMinutes(10),
    val swingThreshold: BigDecimal = BigDecimal("0.5"),
    val swingCooldown: Duration = Duration.ofMinutes(30),
    val upperThreshold: BigDecimal? = null,
    val lowerThreshold: BigDecimal? = null,
    val thresholdHysteresis: BigDecimal = BigDecimal("0.1"),
    val extremeMinDelta: BigDecimal = BigDecimal("0.1"),
    val extremeHourCooldown: Duration = Duration.ofMinutes(30),
    val extremeDayCooldown: Duration = Duration.ofHours(2),
    val summaryHour: Int = 9,
    val summaryPnlMaxAge: Duration = Duration.ofMinutes(10),
) {
    init {
        require(
            swingWindow >= Duration.ofMinutes(1) && swingWindow <= Duration.ofMinutes(60) &&
                swingWindow.toSecondsPart() == 0 && swingWindow.toNanosPart() == 0,
        ) {
            "swingWindow must be whole minutes between 1m and 60m."
        }
        require(swingThreshold.signum() > 0) { "swingThreshold must be positive." }
        require(swingCooldown > Duration.ZERO) { "swingCooldown must be positive." }
        // owner_market_alert_state 의 DECIMAL(10,4) 에 그대로 저장되는 값만 받는다 (반올림되면 sameAs 가 영원히 false)
        listOfNotNull(upperThreshold, lowerThreshold, thresholdHysteresis).forEach {
            require(it.scale() <= MAX_THRESHOLD_SCALE && it.abs() < MAX_THRESHOLD_ABS) {
                "threshold values must have at most 4 decimal places and an absolute value below 1000000."
            }
        }
        require(thresholdHysteresis.signum() >= 0) { "thresholdHysteresis must not be negative." }
        require(
            upperThreshold == null || lowerThreshold == null ||
                upperThreshold - lowerThreshold > thresholdHysteresis.multiply(BigDecimal(2)),
        ) {
            "upperThreshold must exceed lowerThreshold by more than twice the hysteresis."
        }
        require(extremeMinDelta.signum() > 0) { "extremeMinDelta must be positive." }
        require(extremeHourCooldown > Duration.ZERO) { "extremeHourCooldown must be positive." }
        require(extremeDayCooldown > Duration.ZERO) { "extremeDayCooldown must be positive." }
        require(summaryHour in 1..23) { "summaryHour must be between 1 and 23." }
        require(summaryPnlMaxAge > Duration.ZERO) { "summaryPnlMaxAge must be positive." }
    }

    /** upper·lower 가 모두 null 이면 null(기준선 꺼짐). */
    val thresholdBaseline: ThresholdBaseline? =
        if (upperThreshold == null && lowerThreshold == null) {
            null
        } else {
            ThresholdBaseline(upperThreshold, lowerThreshold, thresholdHysteresis)
        }

    companion object {
        const val MAX_THRESHOLD_SCALE = 4
        val MAX_THRESHOLD_ABS: BigDecimal = BigDecimal("1000000")
    }
}
