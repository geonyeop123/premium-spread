package io.premiumspread.domain.tracking

import java.math.BigDecimal
import java.time.Instant

/**
 * 종료 시점에 확정 저장하는 시세. 신선도 판정을 통과한 경우에만 만들어진다.
 *
 * **양수 불변식을 여기서 강제한다.** 캐시 파서는 `toBigDecimalOrNull` 을 쓰므로 `0` 이나 음수도
 * 문법상 유효한 값으로 통과한다. 그런 값을 확정으로 저장하면 `hasConfirmedClose` 가 참인데
 * `grossPnl` 의 `require` 가 실패해 **되돌릴 수 없는 500 상태**가 남는다.
 * 확정 저장 직전에 막아야 하며, 호출자는 [of] 로 만들어 실패를 확정 불가로 다룬다.
 */
data class TrackingCloseSnapshot(
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val fxRate: BigDecimal,
    val premiumRate: BigDecimal,
    val observedAt: Instant,
    val fxObservedAt: Instant,
) {
    init {
        require(koreaPrice > BigDecimal.ZERO) { "koreaPrice must be positive" }
        require(foreignPrice > BigDecimal.ZERO) { "foreignPrice must be positive" }
        require(fxRate > BigDecimal.ZERO) { "fxRate must be positive" }
    }

    companion object {
        /** 값이 확정에 쓸 수 없으면 `null` 을 준다. 예외로 종료를 막지 않는다. */
        fun of(
            koreaPrice: BigDecimal,
            foreignPrice: BigDecimal,
            fxRate: BigDecimal,
            premiumRate: BigDecimal,
            observedAt: Instant,
            fxObservedAt: Instant,
        ): TrackingCloseSnapshot? =
            if (koreaPrice > BigDecimal.ZERO && foreignPrice > BigDecimal.ZERO && fxRate > BigDecimal.ZERO) {
                TrackingCloseSnapshot(koreaPrice, foreignPrice, fxRate, premiumRate, observedAt, fxObservedAt)
            } else {
                null
            }
    }
}

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
