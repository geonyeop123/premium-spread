package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import io.premiumspread.infrastructure.cache.CachedTicker
import io.premiumspread.infrastructure.cache.FxCacheReader
import io.premiumspread.infrastructure.cache.TickerCacheReader
import io.premiumspread.infrastructure.cache.CachedFxRate
import io.premiumspread.infrastructure.exchangerate.ExchangeRateQueryRepository
import io.premiumspread.infrastructure.exchangerate.ExchangeRateSnapshot
import io.premiumspread.infrastructure.ticker.TickerAggregationQueryRepository
import io.premiumspread.infrastructure.ticker.TickerAggregationSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * 티커 캐시 우선 조회 Facade
 *
 * 캐시에서 먼저 조회하고, 없으면 DB에서 조회
 */
@Service
class TickerCacheFacade(
    private val tickerCacheReader: TickerCacheReader,
    private val fxCacheReader: FxCacheReader,
    private val tickerService: TickerService,
    private val exchangeRateQueryRepository: ExchangeRateQueryRepository,
    private val tickerAggregationQueryRepository: TickerAggregationQueryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 티커 조회 (캐시 우선)
     */
    fun findLatest(exchange: String, symbol: String): TickerCacheResult? {
        // 1. 캐시에서 조회
        val cached = tickerCacheReader.get(exchange, symbol)
        if (cached != null) {
            log.debug("Ticker found in cache: {}:{} = {}", exchange, symbol, cached.price)
            return TickerCacheResult.fromCache(cached)
        }

        // 2. 캐시 미스 시 DB에서 조회
        log.debug("Ticker cache miss, falling back to DB: {}:{}", exchange, symbol)
        return findFromDb(exchange, symbol)
    }

    /**
     * Bithumb BTC 티커 조회
     */
    fun findBithumbBtc(): TickerCacheResult? = findLatest("bithumb", "btc")

    /**
     * Binance BTC 티커 조회
     */
    fun findBinanceBtc(): TickerCacheResult? = findLatest("binance", "btc")

    /**
     * 환율 조회 (캐시 우선)
     */
    fun findFxRate(baseCurrency: String, quoteCurrency: String): FxRateCacheResult? {
        // 1. 캐시에서 조회
        val cached = fxCacheReader.get(baseCurrency, quoteCurrency)
        if (cached != null) {
            log.debug("FX rate found in cache: {}/{} = {}", baseCurrency, quoteCurrency, cached.rate)
            return FxRateCacheResult.fromCache(cached)
        }

        // 2. 캐시 미스 시 DB에서 조회
        log.debug("FX cache miss, falling back to DB: {}/{}", baseCurrency, quoteCurrency)
        return findFxFromDb(baseCurrency, quoteCurrency)
    }

    /**
     * USD/KRW 환율 조회
     */
    fun findUsdKrwRate(): FxRateCacheResult? = findFxRate("usd", "krw")

    /**
     * DB에서 티커 조회 (fallback)
     * ticker_minute 테이블에서 최신 집계 데이터의 close 가격 사용
     */
    @Transactional(readOnly = true)
    fun findFromDb(exchange: String, symbol: String): TickerCacheResult? {
        // 1. 먼저 집계 테이블에서 조회 (ticker_minute)
        val aggregation = tickerAggregationQueryRepository.findLatestMinute(exchange, symbol)
        if (aggregation != null) {
            log.debug("Ticker found in aggregation table: {}:{} = {}", exchange, symbol, aggregation.close)
            return TickerCacheResult.fromAggregation(aggregation)
        }

        // 2. 집계 데이터도 없으면 기존 ticker 테이블에서 조회
        val exchangeEnum = try {
            Exchange.valueOf(exchange.uppercase())
        } catch (e: Exception) {
            log.warn("Unknown exchange: {}", exchange)
            return null
        }

        return tickerService.findLatest(
            exchange = exchangeEnum,
            quote = Quote.coin(Symbol(symbol.uppercase()), Currency.KRW),
        )?.let { TickerCacheResult.fromDomain(it) }
    }

    /**
     * DB에서 환율 조회 (fallback)
     * exchange_rate 테이블에서 최신 스냅샷 조회
     */
    @Transactional(readOnly = true)
    fun findFxFromDb(baseCurrency: String, quoteCurrency: String): FxRateCacheResult? {
        val snapshot = exchangeRateQueryRepository.findLatest(baseCurrency, quoteCurrency)
        if (snapshot != null) {
            log.debug("FX rate found in DB: {}/{} = {}", baseCurrency, quoteCurrency, snapshot.rate)
            return FxRateCacheResult.fromSnapshot(snapshot)
        }

        log.warn("FX rate not found in DB: {}/{}", baseCurrency, quoteCurrency)
        return null
    }

    /**
     * 캐시 존재 여부
     */
    fun isCached(exchange: String, symbol: String): Boolean {
        return tickerCacheReader.exists(exchange, symbol)
    }
}

/**
 * 티커 캐시 조회 결과
 */
data class TickerCacheResult(
    val exchange: String,
    val symbol: String,
    val currency: String,
    val price: BigDecimal,
    val volume: BigDecimal?,
    val timestamp: Instant,
    val source: DataSource,
) {
    enum class DataSource { CACHE, DATABASE }

    companion object {
        fun fromCache(cached: CachedTicker) = TickerCacheResult(
            exchange = cached.exchange,
            symbol = cached.symbol,
            currency = cached.currency,
            price = cached.price,
            volume = cached.volume,
            timestamp = cached.timestamp,
            source = DataSource.CACHE,
        )

        fun fromDomain(ticker: io.premiumspread.domain.ticker.Ticker) = TickerCacheResult(
            exchange = ticker.exchange.name,
            symbol = ticker.quote.baseCode,
            currency = ticker.quote.currency.name,
            price = ticker.price,
            volume = null,
            timestamp = ticker.observedAt,
            source = DataSource.DATABASE,
        )

        fun fromAggregation(agg: TickerAggregationSnapshot) = TickerCacheResult(
            exchange = agg.exchange,
            symbol = agg.symbol,
            currency = "KRW", // 집계 데이터는 KRW 기준
            price = agg.close, // 종가 사용
            volume = null,
            timestamp = agg.observedAt,
            source = DataSource.DATABASE,
        )
    }
}

/**
 * 환율 캐시 조회 결과
 */
data class FxRateCacheResult(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val timestamp: Instant,
    val source: DataSource,
) {
    enum class DataSource { CACHE, DATABASE }

    companion object {
        fun fromCache(cached: CachedFxRate) = FxRateCacheResult(
            baseCurrency = cached.baseCurrency,
            quoteCurrency = cached.quoteCurrency,
            rate = cached.rate,
            timestamp = cached.timestamp,
            source = DataSource.CACHE,
        )

        fun fromDomain(ticker: io.premiumspread.domain.ticker.Ticker, base: String, quote: String) = FxRateCacheResult(
            baseCurrency = base,
            quoteCurrency = quote,
            rate = ticker.price,
            timestamp = ticker.observedAt,
            source = DataSource.DATABASE,
        )

        fun fromSnapshot(snapshot: ExchangeRateSnapshot) = FxRateCacheResult(
            baseCurrency = snapshot.baseCurrency,
            quoteCurrency = snapshot.quoteCurrency,
            rate = snapshot.rate,
            timestamp = snapshot.observedAt,
            source = DataSource.DATABASE,
        )
    }
}
