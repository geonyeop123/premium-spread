package io.premiumspread.domain.tracking

interface TrackingRepository {
    fun save(tracking: Tracking): Tracking
    fun findById(id: Long): Tracking?

    /**
     * 소유자의 삭제되지 않은 행만 잠근다.
     *
     * 잠근 뒤에 소유권을 검사하면 다른 사용자가 ID 추측만으로 남의 행을 잠글 수 있고,
     * 잠금 보유 중 수행되는 snapshot 조회 때문에 소유자의 archive 가 lock wait 에 걸린다.
     * 그래서 소유권과 soft-delete 를 잠금 술어에 함께 넣는다 (design.md §5.3.5).
     */
    fun findOwnedByIdForUpdate(id: Long, memberId: Long): Tracking?

    fun findAllByMemberIdAndStatus(memberId: Long, status: TrackingStatus): List<Tracking>
    fun countByMemberIdAndStatus(memberId: Long, status: TrackingStatus): Long

    fun findAllActiveByMemberId(memberId: Long): List<Tracking> =
        findAllByMemberIdAndStatus(memberId, TrackingStatus.ACTIVE)

    fun findAllArchivedByMemberId(memberId: Long): List<Tracking> =
        findAllByMemberIdAndStatus(memberId, TrackingStatus.ARCHIVED)

    fun countActiveByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, TrackingStatus.ACTIVE)

    fun countArchivedByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, TrackingStatus.ARCHIVED)
}
