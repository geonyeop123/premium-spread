package io.premiumspread.infrastructure.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PositionJpaRepository : JpaRepository<Position, Long> {

    @Query(
        """
        SELECT p FROM Position p
        WHERE p.status = :status
          AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
        """,
    )
    fun findAllByStatus(@Param("status") status: PositionStatus): List<Position>
}
