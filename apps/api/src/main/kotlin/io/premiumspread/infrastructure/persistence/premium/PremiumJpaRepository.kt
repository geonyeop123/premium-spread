package io.premiumspread.infrastructure.persistence.premium

import io.premiumspread.domain.premium.Premium
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
