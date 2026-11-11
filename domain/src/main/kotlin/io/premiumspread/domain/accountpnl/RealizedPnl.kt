package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class FillSide { BUY, SELL }

/** 빗썸 현물 체결 한 건. [fundsKrw] 는 수수료 제외 체결금액. 실계정 값이므로 toString 에 숫자를 넣지 않는다. */
data class KoreaSpotFill(
    val id: String,
    val side: FillSide,
    val quantity: BigDecimal,
    val fundsKrw: BigDecimal,
    val feeKrw: BigDecimal,
    val filledAt: Instant,
) {
    override fun toString(): String = "KoreaSpotFill(filledAt=$filledAt)"
}

/** 바이낸스 선물 체결 한 건. [commissionUsdt] 는 USDT 환산 비용(>= 0). */
data class ForeignPerpFill(
    val id: Long,
    val signedQuantity: BigDecimal,
    val realizedPnlUsdt: BigDecimal,
    val commissionUsdt: BigDecimal,
    val filledAt: Instant,
) {
    override fun toString(): String = "ForeignPerpFill(filledAt=$filledAt)"
}

/** 바이낸스 FUNDING_FEE income 한 건. */
data class ForeignFunding(val id: Long, val incomeUsdt: BigDecimal, val at: Instant) {
    override fun toString(): String = "ForeignFunding(at=$at)"
}

/** 빗썸 실현 손익(KRW, 반올림 전). */
data class KoreaRealized(val realizedKrw: BigDecimal) {
    override fun toString(): String = "KoreaRealized"
}

/** 바이낸스 실현 손익(USDT). [commissionUsdt] 는 비용(>= 0). */
data class ForeignRealized(val realizedPnlUsdt: BigDecimal, val commissionUsdt: BigDecimal, val fundingUsdt: BigDecimal) {
    /** 순 = 실현 손익 − 수수료 + 펀딩. */
    val netUsdt: BigDecimal get() = realizedPnlUsdt.subtract(commissionUsdt).add(fundingUsdt)

    override fun toString(): String = "ForeignRealized"
}

/** 빗썸 체결 조회. 범위는 (fromExclusive, toInclusive]. */
fun interface KoreaSpotFillReadPort {
    fun read(fromExclusive: Instant, toInclusive: Instant): List<KoreaSpotFill>
}

/** 바이낸스 체결·펀딩 조회. 범위는 (fromExclusive, toInclusive]. */
interface ForeignPerpFillReadPort {
    fun fills(fromExclusive: Instant, toInclusive: Instant): List<ForeignPerpFill>

    fun funding(fromExclusive: Instant, toInclusive: Instant): List<ForeignFunding>
}

/** 조회 창·페이지 한도 초과. AccountReadException 과 별개(모니터 장애 범주를 늘리지 않는다). */
class FillHistoryWindowLimitException(message: String) : RuntimeException(message)

enum class SettlementStatus { PENDING, CONFIRMED, MANUAL }

enum class SettlementFailure { KOREA_READ, FOREIGN_READ, KOREA_QUANTITY, FOREIGN_QUANTITY, FX, WINDOW_LIMIT }

/** 정산 대상 사건. [legs] 는 사건 뒤 정규화 leg, [source] 는 그 사건의 position_source. */
data class SettlementTarget(
    val episodeId: Long,
    val seq: Int,
    val occurredAt: Instant,
    val legs: PositionLegs,
    val source: ForeignPerpPositionSource,
    val lowerExclusive: Instant,
    val attempts: Int,
) {
    override fun toString(): String = "SettlementTarget(episodeId=$episodeId, seq=$seq, occurredAt=$occurredAt)"
}

/** 정산에 적용한 환율과 그 관측 시각. */
data class AppliedFx(val rate: BigDecimal, val observedAt: Instant) {
    override fun toString(): String = "AppliedFx(observedAt=$observedAt)"
}

data class SettlementOutcome(
    val target: SettlementTarget,
    val koreaStatus: SettlementStatus,
    val korea: KoreaRealized?,
    val foreignStatus: SettlementStatus,
    val foreign: ForeignRealized?,
    val fx: AppliedFx?,
    val totalKrw: BigDecimal?,
    val failure: SettlementFailure?,
    val decidedAt: Instant,
) {
    /** 빗썸 CONFIRMED 이고 바이낸스가 CONFIRMED 또는 MANUAL. */
    val settled: Boolean
        get() = koreaStatus == SettlementStatus.CONFIRMED &&
            (foreignStatus == SettlementStatus.CONFIRMED || foreignStatus == SettlementStatus.MANUAL)

    override fun toString(): String =
        "SettlementOutcome(target=$target, koreaStatus=$koreaStatus, foreignStatus=$foreignStatus, " +
            "failure=$failure, decidedAt=$decidedAt)"
}

interface AccountRealizedPnlStore {
    fun findTarget(now: Instant, settleDelay: Duration, maxAge: Duration, firstLookback: Duration): SettlementTarget?

    fun findFxAt(at: Instant, tolerance: Duration): AppliedFx?

    /** settled 면 settled_seq·settled_at 을 쓰고 attempts·next_at 을 비운다. 아니면 attempts+1, next_at = [nextAttemptAt]. 단조 조건에 막히면 false. */
    fun record(outcome: SettlementOutcome, nextAttemptAt: Instant?): Boolean
}
