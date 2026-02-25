package io.premiumspread.infrastructure.member

import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class MemberRepositoryImpl(
    private val memberJpaRepository: MemberJpaRepository,
) : MemberRepository {

    override fun save(member: Member): Member {
        return memberJpaRepository.save(member)
    }

    override fun findByEmail(email: String): Member? {
        return memberJpaRepository.findByEmailAndDeletedAtIsNull(email)
    }

    override fun findById(id: Long): Member? {
        return memberJpaRepository.findByIdOrNull(id)
    }

    override fun existsByEmail(email: String): Boolean {
        return memberJpaRepository.existsByEmailAndDeletedAtIsNull(email)
    }
}
