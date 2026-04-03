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
            ?: throw UsernameNotFoundException("인증에 실패했습니다.")
        return CustomUserDetails(
            memberId = member.id,
            email = member.email,
            nickname = member.nickname,
            encodedPassword = member.password,
        )
    }
}
