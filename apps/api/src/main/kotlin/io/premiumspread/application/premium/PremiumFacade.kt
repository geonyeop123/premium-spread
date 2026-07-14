package io.premiumspread.application.premium

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.config.AggregationProperties
import io.premiumspread.domain.InvalidPremiumInputException
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumCommand
import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class PremiumFacade(
    private val tickerService: TickerService,
    private val premiumService: PremiumService,
    private val aggregationProperties: AggregationProperties,
) {
    @Transactional
    fun calculateAndSave(criteria: PremiumCriteria.Create): PremiumResult.Detail = translateInvalidInput {
        val symbol = parseSymbol(criteria.symbol)
        val koreaTicker = tickerService.findLatest(
            exchange = Exchange.BITHUMB,
            quote = Quote.coin(symbol, Currency.KRW),
        ) ?: throw ApplicationException(ApplicationError.TICKER_NOT_FOUND)
        val foreignTicker = tickerService.findLatest(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(symbol, Currency.USD),
        ) ?: throw ApplicationException(ApplicationError.TICKER_NOT_FOUND)
        val fxTicker = tickerService.findLatest(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
        ) ?: throw ApplicationException(ApplicationError.TICKER_NOT_FOUND)

        toDetail(
            premiumService.create(
                PremiumCommand.Create(
                    koreaTicker = koreaTicker,
                    foreignTicker = foreignTicker,
                    fxTicker = fxTicker,
                ),
            ),
        )
    }

    @Transactional(readOnly = true)
    fun findCurrent(criteria: PremiumCriteria.FindCurrent): PremiumResult.Current = translateInvalidInput {
        premiumService.findLatestSnapshotBySymbol(parseSymbol(criteria.symbol))
            ?.let(::toCurrent)
            ?: throw ApplicationException(ApplicationError.PREMIUM_NOT_FOUND)
    }

    @Transactional(readOnly = true)
    fun findByPeriod(criteria: PremiumCriteria.FindHistory): PremiumResult.Details = translateInvalidInput {
        requireValidRange(criteria.from <= criteria.to)
        PremiumResult.Details(
            premiumService.findAllBySymbolAndPeriod(parseSymbol(criteria.symbol), criteria.from, criteria.to)
                .map(::toDetail),
        )
    }

    @Transactional(readOnly = true)
    fun findAggregation(criteria: PremiumCriteria.FindAggregation): PremiumResult.AggregationPage =
        translateInvalidInput {
            requireValidRange(criteria.from < criteria.to)
            val maxRange = MAX_RANGE[criteria.interval]
                ?: throw ApplicationException(ApplicationError.INVALID_PREMIUM_INPUT)
            val data = premiumService.findAggregation(
                pair = MarketPair.default(parseSymbol(criteria.symbol)),
                interval = criteria.interval,
                from = criteria.from,
                to = criteria.to,
                zone = aggregationProperties.aggregationZone,
            ).map(::toAggregation)
            PremiumResult.AggregationPage(
                data = data,
                hasMore = criteria.from > criteria.to.minus(maxRange),
            )
        }

    private inline fun <T> translateInvalidInput(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: InvalidPremiumInputException) {
            throw ApplicationException(ApplicationError.INVALID_PREMIUM_INPUT, ex)
        }

    private fun parseSymbol(raw: String): Symbol =
        try {
            Symbol(raw)
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.INVALID_PREMIUM_INPUT, ex)
        }

    private fun requireValidRange(valid: Boolean) {
        if (!valid) throw ApplicationException(ApplicationError.INVALID_PREMIUM_INPUT)
    }

    private fun toDetail(premium: Premium): PremiumResult.Detail = PremiumResult.Detail(
        id = premium.id,
        symbol = premium.symbol.code,
        koreaTickerId = premium.koreaTickerId,
        foreignTickerId = premium.foreignTickerId,
        fxTickerId = premium.fxTickerId,
        premiumRate = premium.premiumRate,
        observedAt = premium.observedAt,
    )

    private fun toCurrent(snapshot: PremiumSnapshot): PremiumResult.Current = PremiumResult.Current(
        symbol = snapshot.symbol,
        premiumRate = snapshot.apiDisplayPremiumRate,
        koreaPrice = snapshot.koreaPrice,
        foreignPrice = snapshot.foreignPrice,
        foreignPriceInKrw = snapshot.foreignPriceInKrw,
        fxRate = snapshot.fxRate,
        observedAt = snapshot.observedAt,
    )

    private fun toAggregation(snapshot: PremiumAggregationSnapshot): PremiumResult.Aggregation =
        PremiumResult.Aggregation(
            symbol = snapshot.symbol,
            high = snapshot.high,
            low = snapshot.low,
            open = snapshot.open,
            close = snapshot.close,
            avg = snapshot.avg,
            count = snapshot.count,
            observedAt = snapshot.observedAt,
            fxRate = snapshot.fxRate,
        )

    private companion object {
        val MAX_RANGE = mapOf(
            "1m" to Duration.ofHours(24),
            "1h" to Duration.ofDays(30),
            "1d" to Duration.ofDays(365),
        )
    }
}
