package io.premiumspread.domain.premium

import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PremiumService(
    private val premiumRepository: PremiumRepository,
) {

    @Transactional
    fun create(command: PremiumCommand.Create): Premium {
        val premium = Premium.create(
            koreaTicker = command.koreaTicker,
            foreignTicker = command.foreignTicker,
            fxTicker = command.fxTicker,
        )
        return premiumRepository.save(premium)
    }

    @Transactional
    fun save(premium: Premium): Premium {
        return premiumRepository.save(premium)
    }

    @Transactional(readOnly = true)
    fun findLatestBySymbol(symbol: Symbol): Premium? {
        return premiumRepository.findLatestBySymbol(symbol)
    }

    @Transactional(readOnly = true)
    fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium> {
        return premiumRepository.findAllBySymbolAndPeriod(symbol, from, to)
    }
}
