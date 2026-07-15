package io.premiumspread.infrastructure.common.persistence.jpa.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpringDataPositionRepository : JpaRepository<Position, Long> {

    fun findByIdAndDeletedAtIsNull(id: Long): Position?

    @Query(
        """
        SELECT p FROM Position p
        WHERE p.status = :status
          AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
        """,
    )
    fun findAllByStatus(@Param("status") status: PositionStatus): List<Position>

    @Query(
        """
        SELECT p FROM Position p
        WHERE p.memberId = :memberId
          AND p.status = :status
          AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
        """,
    )
    fun findAllByMemberIdAndStatus(
        @Param("memberId") memberId: Long,
        @Param("status") status: PositionStatus,
    ): List<Position>

    fun countByMemberIdAndStatusAndDeletedAtIsNull(memberId: Long, status: PositionStatus): Long
}
