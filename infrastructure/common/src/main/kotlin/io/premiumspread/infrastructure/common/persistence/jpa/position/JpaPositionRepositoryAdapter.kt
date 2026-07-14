package io.premiumspread.infrastructure.common.persistence.jpa.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import org.springframework.stereotype.Repository

@Repository
class JpaPositionRepositoryAdapter(
    private val positionRepository: SpringDataPositionRepository,
) : PositionRepository {

    override fun save(position: Position): Position {
        return positionRepository.save(position)
    }

    override fun findById(id: Long): Position? {
        return positionRepository.findByIdAndDeletedAtIsNull(id)
    }

    override fun findAllByStatus(status: PositionStatus): List<Position> {
        return positionRepository.findAllByStatus(status)
    }

    override fun findAllByMemberIdAndStatus(memberId: Long, status: PositionStatus): List<Position> {
        return positionRepository.findAllByMemberIdAndStatus(memberId, status)
    }

    override fun countByMemberIdAndStatus(memberId: Long, status: PositionStatus): Long =
        positionRepository.countByMemberIdAndStatusAndDeletedAtIsNull(memberId, status)
}
