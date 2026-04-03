package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumSnapshot
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
        SELECT new io.premiumspread.domain.premium.PremiumSnapshot(
            p.symbol.code,
            p.premiumRate,
            kt.price,
            ft.price,
            ft.price * fx.price,
            fx.price,
            p.observedAt
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
    fun findLatestSnapshotBySymbol(@Param("symbol") symbol: String): PremiumSnapshot?

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
}
