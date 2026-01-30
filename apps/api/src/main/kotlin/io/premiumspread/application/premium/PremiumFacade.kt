package io.premiumspread.application.premium

import io.premiumspread.domain.premium.PremiumCommand
import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PremiumFacade(
    private val tickerService: TickerService,
    private val premiumService: PremiumService,
) {

    @Transactional
    fun calculateAndSave(criteria: PremiumCriteria.Create): PremiumResult.Detail {
        val symbol = Symbol(criteria.symbol)

        val koreaTicker = tickerService.findLatest(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(symbol, Currency.KRW),
        ) ?: throw TickerNotFoundException("Korea ticker not found for symbol: ${criteria.symbol}")

        val foreignTicker = tickerService.findLatest(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(symbol, Currency.USD),
        ) ?: throw TickerNotFoundException("Foreign ticker not found for symbol: ${criteria.symbol}")

        val fxTicker = tickerService.findLatest(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
        ) ?: throw TickerNotFoundException("FX ticker not found")

        val command = PremiumCommand.Create(
            koreaTicker = koreaTicker,
            foreignTicker = foreignTicker,
            fxTicker = fxTicker,
        )
        val premium = premiumService.create(command)
        return PremiumResult.Detail.from(premium)
    }

    @Transactional(readOnly = true)
    fun findLatest(symbol: String): PremiumResult.Detail? {
        return premiumService.findLatestBySymbol(Symbol(symbol))
            ?.let { PremiumResult.Detail.from(it) }
    }

    @Transactional(readOnly = true)
    fun findByPeriod(symbol: String, from: Instant, to: Instant): List<PremiumResult.Detail> {
        return premiumService.findAllBySymbolAndPeriod(Symbol(symbol), from, to)
            .map { PremiumResult.Detail.from(it) }
    }
}

class TickerNotFoundException(message: String) : RuntimeException(message)
