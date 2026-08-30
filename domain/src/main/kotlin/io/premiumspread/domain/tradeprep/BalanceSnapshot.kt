package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.time.Instant

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
)
