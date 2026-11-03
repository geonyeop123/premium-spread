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

data class ForeignPerpBalance(val marginBalanceUsdt: BigDecimal, val availableBalanceUsdt: BigDecimal) {
    init {
        require(marginBalanceUsdt.signum() >= 0) { "marginBalanceUsdt must not be negative." }
        require(availableBalanceUsdt.signum() >= 0) { "availableBalanceUsdt must not be negative." }
    }

    override fun toString(): String = "ForeignPerpBalance"
}

data class KoreaSpotAccountSnapshot(
    val quantity: BigDecimal,
    val averageBuyPrice: BigDecimal,
    val averageBuyPriceModified: Boolean,
    val entryFeeKrw: BigDecimal?,
    val sellFeeRate: BigDecimal,
    val bidLevels: List<PriceLevel>,
    val lastPrice: BigDecimal,
    val bidObservedAt: Instant,
    val krwBalance: BigDecimal? = null,
    val usdtLastPriceKrw: BigDecimal? = null,
) {
    init {
        require(krwBalance == null || krwBalance.signum() >= 0) { "krwBalance must not be negative." }
        require(usdtLastPriceKrw == null || usdtLastPriceKrw.signum() > 0) { "usdtLastPriceKrw must be positive." }
    }

    override fun toString(): String = "KoreaSpotAccountSnapshot(bidObservedAt=$bidObservedAt)"
}

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
    val balance: ForeignPerpBalance? = null,
) {
    override fun toString(): String =
        "ForeignPerpAccountSnapshot(positionSource=$positionSource, askObservedAt=$askObservedAt)"
}

/** 잔고·USDT 시세 실패는 read() 를 실패시키지 않는다(해당 필드 null). 취소는 예외로 전파한다 (#105). */
fun interface KoreaSpotAccountReadPort {
    fun read(): KoreaSpotAccountSnapshot
}

/** 잔고·USDT 시세 실패는 read() 를 실패시키지 않는다(해당 필드 null). 취소는 예외로 전파한다 (#105). */
fun interface ForeignPerpAccountReadPort {
    fun read(): ForeignPerpAccountSnapshot
}
