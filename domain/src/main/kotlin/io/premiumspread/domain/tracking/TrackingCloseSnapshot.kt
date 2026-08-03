package io.premiumspread.domain.tracking

import java.math.BigDecimal
import java.time.Instant

/** 종료 시점에 확정 저장하는 시세. 신선도 판정을 통과한 경우에만 만들어진다. */
data class TrackingCloseSnapshot(
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val fxRate: BigDecimal,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
    val fxObservedAt: Instant,
)

/**
 * 종료 시점 시세의 출처.
 *
 * `null` 은 별도 값이 아니라 "V15 이전에 종료" 또는 "V15 적용 후 이전 image 가 종료" 를 뜻하며,
 * 확정 판정에서 fail-closed 로 처리된다 (design.md §5.3.2).
 */
enum class TrackingClosePriceSource {
    MARKET_SNAPSHOT,
    SNAPSHOT_UNAVAILABLE,
}
