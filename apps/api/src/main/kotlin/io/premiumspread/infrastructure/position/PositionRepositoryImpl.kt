package io.premiumspread.infrastructure.position

import io.premiumspread.domain.position.Position
import io.premiumspread.domain.position.PositionRepository
import io.premiumspread.domain.position.PositionStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class PositionRepositoryImpl(
    private val positionJpaRepository: PositionJpaRepository,
    private val positionCacheWriter: PositionCacheWriter,
) : PositionRepository {

    override fun save(position: Position): Position {
        val saved = positionJpaRepository.save(position)
        syncOpenPositionCache()
        return saved
    }

    override fun findById(id: Long): Position? {
        return positionJpaRepository.findByIdOrNull(id)
    }

    override fun findAllByStatus(status: PositionStatus): List<Position> {
        return positionJpaRepository.findAllByStatus(status)
    }

    private fun syncOpenPositionCache() {
        val openPositions = positionJpaRepository.findAllByStatus(PositionStatus.OPEN)
        positionCacheWriter.updateOpenPositionStatus(
            exists = openPositions.isNotEmpty(),
            count = openPositions.size,
        )
    }
}
