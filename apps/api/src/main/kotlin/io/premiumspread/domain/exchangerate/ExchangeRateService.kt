package io.premiumspread.domain.exchangerate

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExchangeRateService(
    private val exchangeRateRepository: ExchangeRateRepository,
) {

    @Transactional(readOnly = true)
    fun findLatestSnapshot(baseCurrency: String, quoteCurrency: String): ExchangeRateSnapshot? {
        return exchangeRateRepository.findLatestSnapshot(baseCurrency, quoteCurrency)
    }
}
