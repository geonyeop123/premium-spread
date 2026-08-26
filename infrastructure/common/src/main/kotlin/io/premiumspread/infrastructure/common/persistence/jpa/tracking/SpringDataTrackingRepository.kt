package io.premiumspread.infrastructure.common.persistence.jpa.tracking

import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpringDataTrackingRepository : JpaRepository<Tracking, Long> {

    fun findByIdAndDeletedAtIsNull(id: Long): Tracking?

    /**
     * 소유자의 삭제되지 않은 행만 잠근다. 소유권을 술어에 넣지 않으면 남의 행을 잠글 수 있다
     * (design.md §5.3.5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT t FROM Tracking t
        WHERE t.id = :id
          AND t.memberId = :memberId
          AND t.deletedAt IS NULL
        """,
    )
    fun findOwnedByIdForUpdate(@Param("id") id: Long, @Param("memberId") memberId: Long): Tracking?

    @Query(
        """
        SELECT t FROM Tracking t
        WHERE t.memberId = :memberId
          AND t.status = :status
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt DESC
        """,
    )
    fun findAllByMemberIdAndStatus(
        @Param("memberId") memberId: Long,
        @Param("status") status: TrackingStatus,
    ): List<Tracking>

    fun countByMemberIdAndStatusAndDeletedAtIsNull(memberId: Long, status: TrackingStatus): Long
}
