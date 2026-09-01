package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.market.TickerReadPort
import io.premiumspread.domain.premium.PremiumReadPort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.common.cache.exchangerate.FxCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.ticker.TickerCacheReader

class FxRateReadAdapter(private val reader: FxCacheReader) : FxRateReadPort {
    override fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot? =
        reader.get(base.code, quote.code)?.toDomain()
}

class TickerReadAdapter(private val reader: TickerCacheReader) : TickerReadPort {
    override fun findLatest(exchange: Exchange, quote: Quote): TickerSnapshot? {
        val symbol = quote.baseSymbolOrNull()?.code ?: return null
        return reader.getSnapshot(exchange.name, symbol)
            ?.takeIf { snapshot ->
                snapshot.currency.equals(quote.currency.code, ignoreCase = true) ||
                    (
                        exchange == Exchange.BINANCE &&
                        quote.currency == Currency.USD &&
                        snapshot.currency.equals("USDT", ignoreCase = true)
                    )
            }
    }
}

/**
 * `PremiumRealtimeJob` 이 1초마다 갱신하는 current premium 이 batch 의 프리미엄 stream 이다.
 * 조건 평가 Job 은 그 최신값 하나만 읽으므로 이 어댑터도 current 캐시만 본다.
 *
 * `null` 은 "현재 관측값 없음"이고, 그것이 D14 가 말하는 stream unavailable 이다. 오래된 DB row 로
 * 보정하지 않는다 — 보정해봐야 신선도 판정에서 걸릴 값이고, 그 사이 "값이 있었다"는 잘못된 신호만
 * 남는다. `PremiumCacheReader` 는 payload 의 pair 가 요청 pair 와 다르면 이미 miss 로 돌려준다.
 */
class PremiumReadAdapter(private val reader: PremiumCacheReader) : PremiumReadPort {
    override fun findLatest(pair: MarketPair): PremiumSnapshot? = reader.get(pair)?.toDomain()
}
