package io.premiumspread.infrastructure.common.persistence.jpa.tracking

import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingRepository
import io.premiumspread.domain.tracking.TrackingStatus
import org.springframework.stereotype.Repository

@Repository
class JpaTrackingRepositoryAdapter(private val trackingRepository: SpringDataTrackingRepository) : TrackingRepository {

    override fun save(tracking: Tracking): Tracking = trackingRepository.save(tracking)

    override fun findById(id: Long): Tracking? = trackingRepository.findByIdAndDeletedAtIsNull(id)

    override fun findOwnedByIdForUpdate(id: Long, memberId: Long): Tracking? =
        trackingRepository.findOwnedByIdForUpdate(id, memberId)

    override fun findAllByMemberIdAndStatus(memberId: Long, status: TrackingStatus): List<Tracking> =
        trackingRepository.findAllByMemberIdAndStatus(memberId, status)

    override fun countByMemberIdAndStatus(memberId: Long, status: TrackingStatus): Long =
        trackingRepository.countByMemberIdAndStatusAndDeletedAtIsNull(memberId, status)
}
