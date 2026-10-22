package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.time.Instant

enum class AccountReadFailureCategory {
    BITHUMB_AUTH,
    BINANCE_AUTH,
    NETWORK,
    STALE_MARKET_DATA,
    STALE_FX,
    INSUFFICIENT_DEPTH,
    UNEXPECTED,
}

data class PriceLevel(val price: BigDecimal, val quantity: BigDecimal)

class AccountReadException(val category: AccountReadFailureCategory, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

data class KoreaSpotAccountSnapshot(
    val quantity: BigDecimal,
    val averageBuyPrice: BigDecimal,
    val averageBuyPriceModified: Boolean,
    val entryFeeKrw: BigDecimal?,
    val sellFeeRate: BigDecimal,
    val bidLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val bidObservedAt: Instant,
)

/** 바이낸스 leg 의 포지션·수수료·펀딩·taker 율이 어디서 왔는가. 호가·최근 체결가는 두 경우 모두 거래소 공개 API 다. */
enum class ForeignPerpPositionSource {
    EXCHANGE_API,
    MANUAL_ENV,
}

data class ForeignPerpAccountSnapshot(
    val signedQuantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryCommissionUsdt: BigDecimal?,
    val fundingUsdt: BigDecimal?,
    val takerFeeRate: BigDecimal,
    val askLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val askObservedAt: Instant,
    val positionSource: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
)

fun interface KoreaSpotAccountReadPort {
    fun read(): KoreaSpotAccountSnapshot
}

fun interface ForeignPerpAccountReadPort {
    fun read(): ForeignPerpAccountSnapshot
}
