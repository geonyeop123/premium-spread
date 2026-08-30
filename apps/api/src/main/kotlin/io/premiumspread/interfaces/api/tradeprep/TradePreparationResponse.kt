package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.tradeprep.TradePreparationResult
import java.math.BigDecimal
import java.time.Instant

/**
 * 거래 준비 응답 DTO (`.ai/rules/naming.md` inner class 패턴).
 *
 * Application `*Result` 를 그대로 노출하지 않고 transport 표현으로 옮긴다. 시각은 전부
 * timezone 이 명확한 ISO-8601 `Instant` 다 (`.ai/rules/http.md`).
 */
class TradePreparationResponse private constructor() {

    /**
     * `prepare` 응답 (`dod.md` AC1·AC3).
     *
     * ## 캡 위반이 오류 envelope 로 나가지 않는 이유
     *
     * design.md §3 은 캡을 위반하면 "계획을 만들지 않고 **위반한 캡을 응답에 명시한다**"고
     * 규정한다. `GlobalExceptionHandler` 의 `ErrorResponse` 는 `code`·`message`·`timestamp`
     * 뿐이라 어느 캡이 걸렸는지를 담을 자리가 없다 — 예외로 던지면 그 정보가 사라진다. 그래서
     * Facade 가 `planId = null` 인 결과를 돌려주고 controller 가 422 로 매핑하되 본문은 이
     * 타입을 그대로 쓴다.
     *
     * [code] 는 그 422 응답에서 오류 envelope 와 **같은 방식으로 분기**할 수 있게 안정된
     * `ApplicationError` 이름을 싣는다. 성공 응답에서만 `null` 이다 — **모든 422 는 code 를
     * 갖는다.** 캡을 위반했으면 `CAP_VIOLATED`, 반올림으로 물량이 0 이 됐으면 `NOT_PLANNABLE`
     * 이다. 둘을 합치거나 후자를 `null` 로 두면 클라이언트가 이 응답을 파싱 실패와 구별하지
     * 못한다. 응답 키 집합은 성공·실패가 같다 — 결과에 따라 응답 형태가 갈라지지 않는 것이
     * 계약의 일부다.
     */
    @Suppress("LongParameterList")
    data class Preparation(
        val planId: Long?,
        val status: String?,
        val code: String?,
        val symbol: String,
        val koreaExchange: String,
        val foreignExchange: String,
        val balanceSnapshotId: String,
        val koreaBalance: BigDecimal,
        val foreignBalance: BigDecimal,
        val balanceBasis: String,
        val balanceObservedAt: Instant,
        val balanceRatio: BigDecimal,
        val rawLeverage: BigDecimal,
        val rawQuantity: BigDecimal,
        val koreaRoundedQuantity: BigDecimal,
        val foreignRoundedQuantity: BigDecimal,
        val quantity: BigDecimal,
        val leverage: BigDecimal,
        val koreaShare: BigDecimal,
        val liquidationDistance: BigDecimal,
        val capViolations: List<String>,
        val plannable: Boolean,
        val referenceForeignPrice: BigDecimal,
        val referenceFxRate: BigDecimal,
        val referencePremiumRate: BigDecimal,
        val referenceObservedAt: Instant,
        val referenceFxSource: String,
        val referenceFxObservedAt: Instant,
        val previousTracking: PreviousTracking?,
    ) {
        companion object {
            fun from(result: TradePreparationResult.Preparation): Preparation = Preparation(
                planId = result.planId,
                status = result.status,
                code = when {
                    result.capViolations.isNotEmpty() -> ApplicationError.CAP_VIOLATED.name
                    !result.plannable -> ApplicationError.NOT_PLANNABLE.name
                    else -> null
                },
                symbol = result.symbol,
                koreaExchange = result.koreaExchange,
                foreignExchange = result.foreignExchange,
                balanceSnapshotId = result.balanceSnapshotId,
                koreaBalance = result.koreaBalance,
                foreignBalance = result.foreignBalance,
                balanceBasis = result.balanceBasis,
                balanceObservedAt = result.balanceObservedAt,
                balanceRatio = result.balanceRatio,
                rawLeverage = result.rawLeverage,
                rawQuantity = result.rawQuantity,
                koreaRoundedQuantity = result.koreaRoundedQuantity,
                foreignRoundedQuantity = result.foreignRoundedQuantity,
                quantity = result.quantity,
                leverage = result.leverage,
                koreaShare = result.koreaShare,
                liquidationDistance = result.liquidationDistance,
                capViolations = result.capViolations,
                plannable = result.plannable,
                referenceForeignPrice = result.referenceForeignPrice,
                referenceFxRate = result.referenceFxRate,
                referencePremiumRate = result.referencePremiumRate,
                referenceObservedAt = result.referenceObservedAt,
                referenceFxSource = result.referenceFxSource,
                referenceFxObservedAt = result.referenceFxObservedAt,
                previousTracking = result.previousTracking?.let { PreviousTracking.from(it) },
            )
        }
    }

    /** 직전 **종료된** 추적의 진입 프리미엄과 현재 프리미엄의 gap (design.md D8). */
    data class PreviousTracking(
        val trackingId: Long,
        val entryPremiumRate: BigDecimal,
        val closedAt: Instant?,
        val currentPremiumRate: BigDecimal,
        val premiumRateGap: BigDecimal,
    ) {
        companion object {
            fun from(result: TradePreparationResult.PreviousTracking): PreviousTracking = PreviousTracking(
                trackingId = result.trackingId,
                entryPremiumRate = result.entryPremiumRate,
                closedAt = result.closedAt,
                currentPremiumRate = result.currentPremiumRate,
                premiumRateGap = result.premiumRateGap,
            )
        }
    }

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
    ) {
        companion object {
            fun from(result: TradePreparationResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                koreaExchange = result.koreaExchange,
                foreignExchange = result.foreignExchange,
                status = result.status,
                boundBalanceSnapshotId = result.boundBalanceSnapshotId,
                boundBalanceBasis = result.boundBalanceBasis,
                quantity = result.quantity,
                leverage = result.leverage,
                referenceForeignPrice = result.referenceForeignPrice,
                referenceFxRate = result.referenceFxRate,
                referencePremiumRate = result.referencePremiumRate,
                referenceObservedAt = result.referenceObservedAt,
                referenceFxSource = result.referenceFxSource,
                referenceFxObservedAt = result.referenceFxObservedAt,
                desiredEntryPremiumRate = result.desiredEntryPremiumRate,
                conditionFirstMetAt = result.conditionFirstMetAt,
                conditionFirstMetPremiumRate = result.conditionFirstMetPremiumRate,
                invalidationReason = result.invalidationReason,
                invalidatedAt = result.invalidatedAt,
                version = result.version,
            )
        }
    }
}
