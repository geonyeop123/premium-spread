package io.premiumspread.infrastructure.common.persistence.jpa.member

import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.member.DuplicateEmailException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class JpaMemberRepositoryAdapter(private val memberRepository: SpringDataMemberRepository) : MemberRepository {

    override fun save(member: Member): Member = try {
            memberRepository.saveAndFlush(member)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.hasConstraint("uk_member_email")) {
                throw DuplicateEmailException("이미 사용 중인 이메일입니다: ${member.email}")
            }
            throw ex
        }

    override fun findByEmail(email: String): Member? = memberRepository.findByEmailAndDeletedAtIsNull(email)

    override fun findById(id: Long): Member? = memberRepository.findByIdAndDeletedAtIsNull(id)

    override fun existsByEmail(email: String): Boolean = memberRepository.existsByEmailAndDeletedAtIsNull(email)

    private fun Throwable.hasConstraint(constraint: String): Boolean =
        generateSequence(this) { it.cause }
            .mapNotNull(Throwable::message)
            .any { it.contains(constraint, ignoreCase = true) }
}
