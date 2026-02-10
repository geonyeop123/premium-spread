package io.premiumspread.infrastructure.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerRepository
import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.cache.TickerCacheReader
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class TickerRepositoryImpl(
    private val tickerJpaRepository: TickerJpaRepository,
    private val tickerCacheReader: TickerCacheReader,
    private val tickerAggregationQueryRepository: TickerAggregationQueryRepository,
) : TickerRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(ticker: Ticker): Ticker {
        return tickerJpaRepository.save(ticker)
    }

    override fun findById(id: Long): Ticker? {
        return tickerJpaRepository.findByIdOrNull(id)
    }

    override fun findLatest(exchange: Exchange, quote: Quote): Ticker? {
        return tickerJpaRepository.findLatest(
            exchange = exchange,
            baseCode = quote.baseCode,
            currency = quote.currency,
        )
    }

    override fun findLatestSnapshotByExchangeAndSymbol(exchange: String, symbol: String): TickerSnapshot? {
        // 1. 캐시에서 조회
        val cached = tickerCacheReader.get(exchange, symbol)
        if (cached != null) {
            log.debug("Ticker snapshot cache hit: {}:{}", exchange, symbol)
            return TickerSnapshot(
                exchange = cached.exchange,
                symbol = cached.symbol,
                currency = cached.currency,
                price = cached.price,
                volume = cached.volume,
                observedAt = cached.timestamp,
            )
        }

        // 2. 캐시 미스 → 집계 테이블 (ticker_minute)
        log.debug("Ticker snapshot cache miss, falling back to aggregation: {}:{}", exchange, symbol)
        val aggregation = tickerAggregationQueryRepository.findLatestMinute(exchange, symbol)
        if (aggregation != null) {
            log.debug("Ticker snapshot found in aggregation: {}:{}", exchange, symbol)
            return TickerSnapshot(
                exchange = aggregation.exchange,
                symbol = aggregation.symbol,
                currency = "KRW",
                price = aggregation.close,
                volume = null,
                observedAt = aggregation.observedAt,
            )
        }

        // 3. 집계 데이터도 없으면 ticker 테이블
        log.debug("Ticker snapshot falling back to DB: {}:{}", exchange, symbol)
        val exchangeEnum = try {
            Exchange.valueOf(exchange.uppercase())
        } catch (e: IllegalArgumentException) {
            log.warn("Unknown exchange: {}", exchange)
            return null
        }

        return tickerJpaRepository.findLatest(
            exchange = exchangeEnum,
            baseCode = symbol.uppercase(),
            currency = Currency.KRW,
        )?.let { ticker ->
            TickerSnapshot(
                exchange = ticker.exchange.name,
                symbol = ticker.quote.baseCode,
                currency = ticker.quote.currency.name,
                price = ticker.price,
                volume = null,
                observedAt = ticker.observedAt,
            )
        }
    }

    override fun findAllByExchangeAndSymbol(exchange: Exchange, symbol: Symbol): List<Ticker> {
        return tickerJpaRepository.findAllByExchangeAndSymbol(
            exchange = exchange,
            symbol = symbol.code,
        )
    }
}
