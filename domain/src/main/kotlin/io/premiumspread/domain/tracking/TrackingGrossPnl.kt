package io.premiumspread.domain.tracking

import java.math.BigDecimal
import java.time.Instant

/**
 * 수수료·펀딩비·슬리피지·환전 스프레드가 반영되지 않은 gross 손익.
 * 계정 손익이나 실제 체결 손익이 아니다 (SEM-4).
 */
data class TrackingGrossPnl(
    val premiumRateDelta: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val referencePremiumRate: BigDecimal,
    val koreaLegGrossPnlKrw: BigDecimal,
    val foreignLegGrossPnlKrw: BigDecimal,
    val totalGrossPnlKrw: BigDecimal,
    val koreaLegNotionalKrw: BigDecimal,
    val grossPnlPercentOfKoreaNotional: BigDecimal,
    val calculatedAt: Instant,
    val observedAt: Instant,
    val fxObservedAt: Instant,
) {
    val isGrossProfit: Boolean get() = totalGrossPnlKrw > BigDecimal.ZERO
}
