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

    @Transactional(readOnly = true)
    fun authenticate(email: String, rawPassword: String): Member? {
        val member = memberRepository.findByEmail(email) ?: return null
        return member.takeIf { passwordEncoder.matches(rawPassword, it.password) }
    }
}
