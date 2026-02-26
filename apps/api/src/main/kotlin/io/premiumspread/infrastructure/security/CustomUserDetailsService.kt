package io.premiumspread.infrastructure.security

import io.premiumspread.domain.member.MemberRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val memberRepository: MemberRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val member = memberRepository.findByEmail(username)
            ?: throw UsernameNotFoundException("회원을 찾을 수 없습니다: $username")
        return CustomUserDetails(
            memberId = member.id,
            email = member.email,
            nickname = member.nickname,
            encodedPassword = member.password,
        )
    }
}
