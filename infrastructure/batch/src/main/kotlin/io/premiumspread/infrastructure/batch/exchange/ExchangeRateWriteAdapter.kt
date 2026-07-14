package io.premiumspread.infrastructure.batch.exchange

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.market.FxRateWritePort
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateWriteRepository

class ExchangeRateWriteAdapter(
    private val repository: JdbcExchangeRateWriteRepository,
) : FxRateWritePort {
    override fun save(snapshot: ExchangeRateSnapshot) {
        repository.save(
            baseCurrency = snapshot.baseCurrency,
            quoteCurrency = snapshot.quoteCurrency,
            rate = snapshot.rate,
            observedAt = snapshot.observedAt,
        )
    }
}
