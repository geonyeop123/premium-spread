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

    /**
     * `ORDER BY t.id ASC` 는 장식이 아니다. 이 질의는 조건 평가 Job 이 초마다 돌리고 그 결과를
     * **한 트랜잭션 안에서 순서대로** 전이시킨다 — 정렬이 없으면 처리 순서가 명세되지 않은 SQL
     * 순서(현재는 PK 순의 full scan, 인덱스가 붙으면 그 인덱스 순)에 좌우된다. 인덱스 하나로
     * 조용히 바뀌는 순서 위에 동작을 얹지 않는다.
     */
    @Query(
        """
        SELECT t FROM TradePreparation t
        WHERE t.status = :status
          AND t.symbol.code = :symbolCode
          AND t.koreaExchange = :koreaExchange
          AND t.foreignExchange = :foreignExchange
          AND t.deletedAt IS NULL
        ORDER BY t.id ASC
        """,
    )
    fun findAllByStatusAndPair(
        @Param("status") status: TradePreparationStatus,
        @Param("symbolCode") symbolCode: String,
        @Param("koreaExchange") koreaExchange: Exchange,
        @Param("foreignExchange") foreignExchange: Exchange,
    ): List<TradePreparation>
}
