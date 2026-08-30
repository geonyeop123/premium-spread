package io.premiumspread.infrastructure.common.persistence.jpa.tradeprep

import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpringDataTradePreparationRepository : JpaRepository<TradePreparation, Long> {

    fun findByIdAndDeletedAtIsNull(id: Long): TradePreparation?

    /**
     * owner당 활성(`WATCHING`·`ARMED`) 계획은 최대 하나다 (D16·D23) — V16의
     * `uk_trade_preparation_owner_active`(active_key)와 같은 정의를 쓴다. 두 정의가 어긋나면
     * DB가 막는 것과 앱이 찾는 것이 달라진다.
     */
    fun findByOwnerIdAndStatusInAndDeletedAtIsNull(
        ownerId: Long,
        statuses: List<TradePreparationStatus>,
    ): TradePreparation?

    @Query(
        """
        SELECT t FROM TradePreparation t
        WHERE t.status = :status
          AND t.symbol.code = :symbolCode
          AND t.koreaExchange = :koreaExchange
          AND t.foreignExchange = :foreignExchange
          AND t.deletedAt IS NULL
        """,
    )
    fun findAllByStatusAndPair(
        @Param("status") status: TradePreparationStatus,
        @Param("symbolCode") symbolCode: String,
        @Param("koreaExchange") koreaExchange: Exchange,
        @Param("foreignExchange") foreignExchange: Exchange,
    ): List<TradePreparation>
}
