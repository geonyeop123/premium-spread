package io.premiumspread.application.ticker

import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Premium
import io.premiumspread.domain.ticker.PremiumRepository
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PremiumFacade(
    private val tickerRepository: TickerRepository,
    private val premiumRepository: PremiumRepository,
) {

    @Transactional
    fun calculateAndSave(criteria: PremiumCreateCriteria): PremiumResult {
        val symbol = Symbol(criteria.symbol)

        val koreaTicker = tickerRepository.findLatest(
            exchange = Exchange.UPBIT,
            quote = Quote.coin(symbol, Currency.KRW),
        ) ?: throw TickerNotFoundException("Korea ticker not found for symbol: ${criteria.symbol}")

        val foreignTicker = tickerRepository.findLatest(
            exchange = Exchange.BINANCE,
            quote = Quote.coin(symbol, Currency.USD),
        ) ?: throw TickerNotFoundException("Foreign ticker not found for symbol: ${criteria.symbol}")

        val fxTicker = tickerRepository.findLatest(
            exchange = Exchange.FX_PROVIDER,
            quote = Quote.fx(Currency.USD, Currency.KRW),
        ) ?: throw TickerNotFoundException("FX ticker not found")

        val premium = Premium.create(koreaTicker, foreignTicker, fxTicker)
        val savedPremium = premiumRepository.save(premium)

        return PremiumResult.from(savedPremium)
    }

    @Transactional(readOnly = true)
    fun findLatest(symbol: String): PremiumResult? {
        return premiumRepository.findLatestBySymbol(Symbol(symbol))
            ?.let { PremiumResult.from(it) }
    }

    @Transactional(readOnly = true)
    fun findByPeriod(symbol: String, from: Instant, to: Instant): List<PremiumResult> {
        return premiumRepository.findAllBySymbolAndPeriod(Symbol(symbol), from, to)
            .map { PremiumResult.from(it) }
    }
}

class TickerNotFoundException(message: String) : RuntimeException(message)
