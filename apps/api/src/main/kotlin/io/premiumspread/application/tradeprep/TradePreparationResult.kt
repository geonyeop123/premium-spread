package io.premiumspread.application.tradeprep

import java.math.BigDecimal
import java.time.Instant

/**
 * 거래 준비 유스케이스 출력이다 (`.ai/rules/naming.md` inner class 패턴).
 *
 * Domain·Infrastructure 타입을 그대로 노출하지 않고 enum 은 전부 문자열로 평탄화한다.
 * **Domain → Result 변환은 [TradePreparationFacade] 의 private 매핑이 소유한다** — 여기에
 * `from(entity)` companion 을 두면 Application 계약의 public 시그니처가 Domain 타입을 노출해
 * `architectureTest` 의 facade contract 규칙을 어긴다 (`TrackingResult` 도 같은 이유로 companion 이
 * 없고 `TrackingFacade.toDetail` 이 매핑을 갖는다).
 */
class TradePreparationResult private constructor() {

    /**
     * `prepare` 응답 (`dod.md` AC1·AC3).
     *
     * **캡을 위반하면 [planId] 와 [status] 가 `null` 이다** — design.md §3 이 "하나라도 위반하면
     * 계획을 만들지 않고 위반한 캡을 응답에 명시한다"고 규정하므로, 계획은 만들지 않되 산출값과
     * [capViolations] 는 그대로 실어 owner 가 무엇이 걸렸는지 본다. 오류 코드만으로는 어느 캡이
     * 걸렸는지 말할 수 없어 이 형태를 택했다.
     *
     * [balanceBasis] 와 [balanceObservedAt] 은 D2·D3 이 요구하는 신선도 라벨이다. `prepare` 는
     * 표시용이므로 `STALE` 을 감추거나 거절하지 않고 라벨만 붙인다.
     */
    @Suppress("LongParameterList")
    data class Preparation(
        val planId: Long?,
        val status: String?,
        val symbol: String,
        val koreaExchange: String,
        val foreignExchange: String,
        // 잔고 (D2·D3)
        val balanceSnapshotId: String,
        val koreaBalance: BigDecimal,
        val foreignBalance: BigDecimal,
        val balanceBasis: String,
        val balanceObservedAt: Instant,
        // 사이징 (ECO-5 §2, D12)
        val balanceRatio: BigDecimal,
        val rawLeverage: BigDecimal,
        val rawQuantity: BigDecimal,
        val koreaRoundedQuantity: BigDecimal,
        val foreignRoundedQuantity: BigDecimal,
        val quantity: BigDecimal,
        val leverage: BigDecimal,
        // 캡 판정 (§3)
        val koreaShare: BigDecimal,
        val liquidationDistance: BigDecimal,
        val capViolations: List<String>,
        val plannable: Boolean,
        // provenance (D12)
        val referenceForeignPrice: BigDecimal,
        val referenceFxRate: BigDecimal,
        val referencePremiumRate: BigDecimal,
        val referenceObservedAt: Instant,
        val referenceFxSource: String,
        val referenceFxObservedAt: Instant,
        // 재진입 참조값 (D8)
        val previousTracking: PreviousTracking?,
    )

    /**
     * 직전 **종료된** 추적의 진입 프리미엄과 현재 프리미엄의 gap (design.md D8).
     * 보유 중 포지션이 아니다 — 거래 준비는 포지션이 없을 때만 성립한다 (D13).
     */
    data class PreviousTracking(
        val trackingId: Long,
        val entryPremiumRate: BigDecimal,
        val closedAt: Instant?,
        val currentPremiumRate: BigDecimal,
        val premiumRateGap: BigDecimal,
    )

    /** 저장된 계획 단건 (design.md D5·D12). */
    @Suppress("LongParameterList")
    data class Detail(
        val id: Long,
        val symbol: String,
        val koreaExchange: String,
        val foreignExchange: String,
        val status: String,
        val boundBalanceSnapshotId: String,
        val boundBalanceBasis: String,
        val quantity: BigDecimal,
        val leverage: BigDecimal,
        val referenceForeignPrice: BigDecimal,
        val referenceFxRate: BigDecimal,
        val referencePremiumRate: BigDecimal,
        val referenceObservedAt: Instant,
        val referenceFxSource: String,
        val referenceFxObservedAt: Instant,
        val desiredEntryPremiumRate: BigDecimal?,
        val conditionFirstMetAt: Instant?,
        val conditionFirstMetPremiumRate: BigDecimal?,
        val invalidationReason: String?,
        val invalidatedAt: Instant?,
        val version: Long,
    )
}
