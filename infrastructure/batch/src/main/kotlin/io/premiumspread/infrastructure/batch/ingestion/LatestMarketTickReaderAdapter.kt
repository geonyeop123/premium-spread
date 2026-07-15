package io.premiumspread.infrastructure.batch.ingestion

import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.ingestion.bithumb.BithumbTickerIngestion

class LatestMarketTickReaderAdapter(private val binance: BinanceTickerIngestion, private val bithumb: BithumbTickerIngestion) :
    LatestMarketTickReadPort {
    override fun findLatest(exchange: Exchange, quote: Quote): MarketTick? {
        val ticker = when (exchange) {
            Exchange.BINANCE -> binance.latest()?.ticker
            Exchange.BITHUMB -> bithumb.latest()?.ticker
            else -> null
        }
        return ticker?.toMarketTick()?.takeIf { it.quote == quote }
    }
}
