package io.premiumspread.domain.exchangerate

interface ExchangeRateRepository {
    fun findLatestSnapshot(baseCurrency: String, quoteCurrency: String): ExchangeRateSnapshot?
}
