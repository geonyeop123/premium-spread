package io.premiumspread.infrastructure.persistence.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class PositionRepositoryImpl(
    private val positionJpaRepository: PositionJpaRepository,
) : PositionRepository {

    override fun save(position: Position): Position {
        return positionJpaRepository.save(position)
    }

    override fun findById(id: Long): Position? {
        return positionJpaRepository.findByIdOrNull(id)
    }

    override fun findAllByStatus(status: PositionStatus): List<Position> {
        return positionJpaRepository.findAllByStatus(status)
    }
}
