package io.premiumspread.infrastructure.batch.cache

import io.premiumspread.domain.aggregation.AggregationUnit
import io.premiumspread.domain.aggregation.AggregationWindow
import io.premiumspread.domain.aggregation.TickerAggregatePort
import io.premiumspread.domain.aggregation.TickerAggregationSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregation
import io.premiumspread.infrastructure.common.persistence.jdbc.ticker.TickerAggregationRepository
import io.premiumspread.redis.TickerAggregationTimeUnit

class TickerAggregateAdapter(private val cache: TickerCacheService, private val repository: TickerAggregationRepository) :
    TickerAggregatePort {
    override fun aggregate(
        exchange: Exchange,
        quote: Quote,
        sourceUnit: AggregationUnit?,
        window: AggregationWindow,
    ): TickerAggregationSnapshot? {
        val symbol = quote.baseSymbolOrNull()?.code
            ?: error("Ticker aggregation requires a symbol quote: $quote")
        val aggregate = if (sourceUnit == null) {
            cache.aggregateSecondsData(exchange.name, symbol, quote.currency.code, window.from, window.to)
        } else {
            cache.aggregateData(
                sourceUnit.toTickerCacheUnit(),
                exchange.name,
                symbol,
                quote.currency.code,
                window.from,
                window.to,
            )
        } ?: return null

        return aggregate.toDomain(exchange, quote, window.from)
    }

    override fun save(unit: AggregationUnit, window: AggregationWindow, snapshot: TickerAggregationSnapshot) {
        val symbol = snapshot.quote.baseSymbolOrNull()?.code
            ?: error("Ticker aggregation requires a symbol quote: ${snapshot.quote}")
        val aggregate = snapshot.toInfrastructure()
        when (unit) {
            AggregationUnit.MINUTE -> {
                repository.saveMinute(snapshot.exchange.name, symbol, window.from, aggregate)
                cache.saveAggregation(TickerAggregationTimeUnit.MINUTES, snapshot.exchange.name, symbol, window.from, aggregate)
            }

            AggregationUnit.HOUR -> {
                repository.saveHour(snapshot.exchange.name, symbol, window.from, aggregate)
                cache.saveAggregation(TickerAggregationTimeUnit.HOURS, snapshot.exchange.name, symbol, window.from, aggregate)
            }

            AggregationUnit.DAY -> repository.saveDay(
                snapshot.exchange.name,
                symbol,
                window.from.atZone(window.zone.zoneId).toLocalDate(),
                aggregate,
            )
        }
    }

    private fun AggregationUnit.toTickerCacheUnit(): TickerAggregationTimeUnit = when (this) {
        AggregationUnit.MINUTE -> TickerAggregationTimeUnit.MINUTES
        AggregationUnit.HOUR -> TickerAggregationTimeUnit.HOURS
        AggregationUnit.DAY -> error("Ticker day aggregation is persisted in DB and cannot be a cache source")
    }

    private fun TickerAggregation.toDomain(
        exchange: Exchange,
        quote: Quote,
        observedAt: java.time.Instant,
    ) = TickerAggregationSnapshot(
        exchange = exchange,
        quote = quote,
        high = high,
        low = low,
        open = open,
        close = close,
        average = avg,
        count = count,
        observedAt = observedAt,
    )

    private fun TickerAggregationSnapshot.toInfrastructure() = TickerAggregation(
        exchange = exchange.name,
        symbol = quote.baseSymbolOrNull()?.code
            ?: error("Ticker aggregation requires a symbol quote: $quote"),
        currency = quote.currency.code,
        high = high,
        low = low,
        open = open,
        close = close,
        avg = average,
        count = count,
    )
}
