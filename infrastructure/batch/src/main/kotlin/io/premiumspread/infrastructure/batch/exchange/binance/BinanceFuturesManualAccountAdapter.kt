package io.premiumspread.infrastructure.batch.exchange.binance

import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.ForeignPerpPositionSource
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/** owner 가 env 로 입력한 바이낸스 숏 포지션 + 공개 호가·체결가. 인증 정보를 쓰지 않는다. */
class BinanceFuturesManualAccountAdapter(
    webClient: WebClient,
    private val shortQuantity: BigDecimal,
    private val entryPrice: BigDecimal,
    private val entryFeeUsdt: BigDecimal?,
    private val fundingUsdt: BigDecimal?,
    private val takerFeeRate: BigDecimal,
    timeout: Duration = Duration.ofSeconds(5),
) : ForeignPerpAccountReadPort {
    private val client = BinanceFuturesReadClient(webClient, timeout)

    override fun read(): ForeignPerpAccountSnapshot {
        val lastPrice = client.price(SYMBOL)
        val depth = client.askDepth(SYMBOL)
        return ForeignPerpAccountSnapshot(
            signedQuantity = shortQuantity.negate(),
            entryPrice = entryPrice,
            entryCommissionUsdt = entryFeeUsdt,
            fundingUsdt = fundingUsdt,
            takerFeeRate = takerFeeRate,
            askLevels = depth.levels,
            lastPrice = lastPrice,
            askObservedAt = depth.observedAt,
            positionSource = ForeignPerpPositionSource.MANUAL_ENV,
        )
    }

    private companion object {
        const val SYMBOL = "BTCUSDT"
    }
}
