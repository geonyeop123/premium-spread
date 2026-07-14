package io.premiumspread.infrastructure.common.persistence.jpa.member

import io.premiumspread.domain.member.Member
import org.springframework.data.jpa.repository.JpaRepository

interface SpringDataMemberRepository : JpaRepository<Member, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Member?
    fun findByEmailAndDeletedAtIsNull(email: String): Member?
    fun existsByEmailAndDeletedAtIsNull(email: String): Boolean
}
