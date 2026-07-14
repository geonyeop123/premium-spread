package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import java.math.BigDecimal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PremiumJpaRepository : JpaRepository<Premium, Long> {

    @Query(
        """
        SELECT p FROM Premium p
        WHERE p.symbol.code = :symbol
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestBySymbol(@Param("symbol") symbol: String): Premium?

    @Query(
        """
        SELECT p FROM Premium p
        JOIN Ticker kt ON kt.id = p.koreaTickerId
        JOIN Ticker ft ON ft.id = p.foreignTickerId
        WHERE p.symbol.code = :symbol
          AND kt.exchange = :koreaExchange
          AND ft.exchange = :foreignExchange
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestByPair(
        @Param("symbol") symbol: String,
        @Param("koreaExchange") koreaExchange: Exchange,
        @Param("foreignExchange") foreignExchange: Exchange,
    ): Premium?

    @Query(
        """
        SELECT new io.premiumspread.infrastructure.premium.PremiumSnapshotRow(
            p.symbol.code,
            p.premiumRate,
            kt.price,
            ft.price,
            ft.price * fx.price,
            fx.price,
            p.observedAt,
            kt.exchange,
            ft.exchange,
            fx.exchange,
            fx.observedAt
        )
        FROM Premium p
        JOIN Ticker kt ON kt.id = p.koreaTickerId
        JOIN Ticker ft ON ft.id = p.foreignTickerId
        JOIN Ticker fx ON fx.id = p.fxTickerId
        WHERE p.symbol.code = :symbol
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestSnapshotBySymbol(@Param("symbol") symbol: String): PremiumSnapshotRow?

    @Query(
        """
        SELECT new io.premiumspread.infrastructure.premium.PremiumSnapshotRow(
            p.symbol.code,
            p.premiumRate,
            kt.price,
            ft.price,
            ft.price * fx.price,
            fx.price,
            p.observedAt,
            kt.exchange,
            ft.exchange,
            fx.exchange,
            fx.observedAt
        )
        FROM Premium p
        JOIN Ticker kt ON kt.id = p.koreaTickerId
        JOIN Ticker ft ON ft.id = p.foreignTickerId
        JOIN Ticker fx ON fx.id = p.fxTickerId
        WHERE p.symbol.code = :symbol
          AND kt.exchange = :koreaExchange
          AND ft.exchange = :foreignExchange
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestSnapshotByPair(
        @Param("symbol") symbol: String,
        @Param("koreaExchange") koreaExchange: Exchange,
        @Param("foreignExchange") foreignExchange: Exchange,
    ): PremiumSnapshotRow?

    @Query(
        """
        SELECT p FROM Premium p
        WHERE p.symbol.code = :symbol
          AND p.observedAt BETWEEN :from AND :to
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt ASC
        """,
    )
    fun findAllBySymbolAndPeriod(
        @Param("symbol") symbol: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Premium>

    @Query(
        """
        SELECT p FROM Premium p
        JOIN Ticker kt ON kt.id = p.koreaTickerId
        JOIN Ticker ft ON ft.id = p.foreignTickerId
        WHERE p.symbol.code = :symbol
          AND kt.exchange = :koreaExchange
          AND ft.exchange = :foreignExchange
          AND p.observedAt BETWEEN :from AND :to
          AND p.deletedAt IS NULL
        ORDER BY p.observedAt ASC
        """,
    )
    fun findAllByPairAndPeriod(
        @Param("symbol") symbol: String,
        @Param("koreaExchange") koreaExchange: Exchange,
        @Param("foreignExchange") foreignExchange: Exchange,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Premium>
}

data class PremiumSnapshotRow(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    val foreignPrice: BigDecimal,
    val foreignPriceInKrw: BigDecimal,
    val fxRate: BigDecimal,
    val observedAt: Instant,
    val koreaExchange: Exchange,
    val foreignExchange: Exchange,
    val fxSource: Exchange,
    val fxObservedAt: Instant,
) {
    fun toDomain(): PremiumSnapshot = PremiumSnapshot(
        pair = MarketPair(Symbol(symbol), koreaExchange, foreignExchange),
        premiumRate = premiumRate,
        koreaPrice = koreaPrice,
        foreignPrice = foreignPrice,
        foreignPriceInKrw = foreignPriceInKrw,
        fxRate = fxRate,
        observedAt = observedAt,
        fxSource = fxSource,
        fxObservedAt = fxObservedAt,
    )
}
