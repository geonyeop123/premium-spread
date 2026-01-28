package io.premiumspread.domain.position

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionService(
    private val positionRepository: PositionRepository,
) {

    @Transactional
    fun save(position: Position): Position {
        return positionRepository.save(position)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): Position? {
        return positionRepository.findById(id)
    }

    @Transactional(readOnly = true)
    fun findAllOpen(): List<Position> {
        return positionRepository.findAllOpen()
    }
}
