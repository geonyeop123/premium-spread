package io.premiumspread.infrastructure.member

import io.premiumspread.domain.member.Member
import org.springframework.data.jpa.repository.JpaRepository

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findByEmailAndDeletedAtIsNull(email: String): Member?
    fun existsByEmailAndDeletedAtIsNull(email: String): Boolean
}
