package io.premiumspread.infrastructure.common.persistence.jpa.tracking

import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpringDataTrackingRepository : JpaRepository<Tracking, Long> {

    fun findByIdAndDeletedAtIsNull(id: Long): Tracking?

    @Query(
        """
        SELECT p FROM Tracking p
        WHERE p.status = :status
          AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
        """,
    )
    fun findAllByStatus(@Param("status") status: TrackingStatus): List<Tracking>

    @Query(
        """
        SELECT p FROM Tracking p
        WHERE p.memberId = :memberId
          AND p.status = :status
          AND p.deletedAt IS NULL
        ORDER BY p.createdAt DESC
        """,
    )
    fun findAllByMemberIdAndStatus(
        @Param("memberId") memberId: Long,
        @Param("status") status: TrackingStatus,
    ): List<Tracking>

    fun countByMemberIdAndStatusAndDeletedAtIsNull(memberId: Long, status: TrackingStatus): Long
}
