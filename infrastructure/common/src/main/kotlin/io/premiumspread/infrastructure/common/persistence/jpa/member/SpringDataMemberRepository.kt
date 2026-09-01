package io.premiumspread.infrastructure.common.persistence.jpa.member

import io.premiumspread.domain.member.Member
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpringDataMemberRepository : JpaRepository<Member, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Member?
    fun findByEmailAndDeletedAtIsNull(email: String): Member?
    fun existsByEmailAndDeletedAtIsNull(email: String): Boolean

    /**
     * owner 단위 직렬화용 행 잠금 (design.md D18). 삭제되지 않은 행만 잠근다.
     * 잠금 순서는 항상 member → tracking/plan 이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT m FROM Member m
        WHERE m.id = :id
          AND m.deletedAt IS NULL
        """,
    )
    fun findByIdForUpdate(@Param("id") id: Long): Member?
}
