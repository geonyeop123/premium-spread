package io.premiumspread.domain.member

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService(private val memberRepository: MemberRepository, private val passwordEncoder: PasswordEncoder) {

    @Transactional
    fun register(command: MemberCommand.Register): Member {
        if (memberRepository.existsByEmail(command.email)) {
            throw DuplicateEmailException("이미 사용 중인 이메일입니다: ${command.email}")
        }

        val encodedPassword = passwordEncoder.encode(command.rawPassword)
        val member = Member.create(
            email = command.email,
            encodedPassword = encodedPassword,
        )
        return memberRepository.save(member)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): Member? = memberRepository.findById(id)

    /**
     * owner 단위 직렬화용 비관적 잠금 (design.md D18). 호출자의 트랜잭션에 반드시 참여해야 하므로
     * 별도 트랜잭션 애노테이션을 두지 않는다
     * ([io.premiumspread.domain.tracking.TrackingService.findOwnedByIdForUpdate]와 같은 패턴).
     */
    fun findByIdForUpdate(id: Long): Member? = memberRepository.findByIdForUpdate(id)

    @Transactional(readOnly = true)
    fun authenticate(email: String, rawPassword: String): Member? {
        val member = memberRepository.findByEmail(email) ?: return null
        return member.takeIf { passwordEncoder.matches(rawPassword, it.password) }
    }
}
