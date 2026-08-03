package io.premiumspread.domain.tracking

interface TrackingRepository {
    fun save(tracking: Tracking): Tracking
    fun findById(id: Long): Tracking?
    fun findAllByStatus(status: TrackingStatus): List<Tracking>
    fun findAllOpen(): List<Tracking> = findAllByStatus(TrackingStatus.OPEN)
    fun findAllByMemberIdAndStatus(memberId: Long, status: TrackingStatus): List<Tracking>
    fun countByMemberIdAndStatus(memberId: Long, status: TrackingStatus): Long
    fun findAllActiveByMemberId(memberId: Long): List<Tracking> =
        findAllByMemberIdAndStatus(memberId, TrackingStatus.OPEN)
    fun findAllArchivedByMemberId(memberId: Long): List<Tracking> =
        findAllByMemberIdAndStatus(memberId, TrackingStatus.CLOSED)

    fun countActiveByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, TrackingStatus.OPEN)

    fun countArchivedByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, TrackingStatus.CLOSED)
}
