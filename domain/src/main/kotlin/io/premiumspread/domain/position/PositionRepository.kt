package io.premiumspread.domain.position

interface PositionRepository {
    fun save(position: Position): Position
    fun findById(id: Long): Position?
    fun findAllByStatus(status: PositionStatus): List<Position>
    fun findAllOpen(): List<Position> = findAllByStatus(PositionStatus.OPEN)
    fun findAllByMemberIdAndStatus(memberId: Long, status: PositionStatus): List<Position>
    fun countByMemberIdAndStatus(memberId: Long, status: PositionStatus): Long
    fun findAllOpenByMemberId(memberId: Long): List<Position> =
        findAllByMemberIdAndStatus(memberId, PositionStatus.OPEN)
    fun findAllClosedByMemberId(memberId: Long): List<Position> =
        findAllByMemberIdAndStatus(memberId, PositionStatus.CLOSED)

    fun countOpenByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, PositionStatus.OPEN)

    fun countClosedByMemberId(memberId: Long): Long =
        countByMemberIdAndStatus(memberId, PositionStatus.CLOSED)
}
