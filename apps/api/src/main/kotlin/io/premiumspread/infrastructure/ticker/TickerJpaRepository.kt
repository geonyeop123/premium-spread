package io.premiumspread.infrastructure.ticker

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Ticker
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TickerJpaRepository : JpaRepository<Ticker, Long> {

    @Query(
        """
        SELECT t FROM Ticker t
        WHERE t.exchange = :exchange
          AND t.quote.baseCode = :baseCode
          AND t.quote.currency = :currency
          AND t.deletedAt IS NULL
        ORDER BY t.observedAt DESC
        LIMIT 1
        """,
    )
    fun findLatest(
        @Param("exchange") exchange: Exchange,
        @Param("baseCode") baseCode: String,
        @Param("currency") currency: io.premiumspread.domain.ticker.Currency,
    ): Ticker?

    @Query(
        """
        SELECT t FROM Ticker t
        WHERE t.exchange = :exchange
          AND t.quote.baseCode = :symbol
          AND t.deletedAt IS NULL
        ORDER BY t.observedAt DESC
        """,
    )
    fun findAllByExchangeAndSymbol(
        @Param("exchange") exchange: Exchange,
        @Param("symbol") symbol: String,
    ): List<Ticker>
}
