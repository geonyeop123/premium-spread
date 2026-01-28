package io.premiumspread.domain.position

interface PositionRepository {
    fun save(position: Position): Position
    fun findById(id: Long): Position?
    fun findAllByStatus(status: PositionStatus): List<Position>
    fun findAllOpen(): List<Position> = findAllByStatus(PositionStatus.OPEN)
}
