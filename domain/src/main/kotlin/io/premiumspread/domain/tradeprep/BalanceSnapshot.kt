package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 표시용 잔고 read model (design.md D2). 캐시를 허용하며 [balanceBasis]로 신뢰 수준을 드러낸다.
 *
 * 빗썸 현물 KRW 잔고([koreaBalance])와 바이낸스 USDT-M 잔고([foreignBalance])를 하나의 관측
 * 시점([id])으로 묶는다 — 사이징이 두 잔고의 비율(`R`)을 쓰므로 서로 다른 시점의 값을 섞지 않는다
 * (design.md D5 "잔고 스냅샷 id에 결속").
 */
data class BalanceSnapshot(
    val id: String,
    val koreaBalance: BigDecimal,
    val foreignBalance: BigDecimal,
    val balanceBasis: BalanceBasis,
    val observedAt: Instant,
) {
    companion object {
        /**
         * owner가 신고한 잔고를 표시용 스냅샷으로 만든다 (design.md D1·D9).
         *
         * `UNVERIFIED`는 이 factory가 고정한다 — 신고값은 실계정과 대조되지 않았으므로
         * [VerifiedBalance.from]이 이 스냅샷을 판정용으로 승격하지 않는다. 신고값에서 스냅샷을
         * 만드는 규칙을 여기 한 곳에만 두어, `infrastructure`의 declared 어댑터와 `apps:api`
         * Facade가 각자 다른 basis를 붙일 여지를 없앤다.
         */
        fun declared(
            koreaBalance: BigDecimal,
            foreignBalance: BigDecimal,
            observedAt: Instant,
            id: String = "declared-${UUID.randomUUID()}",
        ): BalanceSnapshot = BalanceSnapshot(
            id = id,
            koreaBalance = koreaBalance,
            foreignBalance = foreignBalance,
            balanceBasis = BalanceBasis.UNVERIFIED,
            observedAt = observedAt,
        )
    }
}
