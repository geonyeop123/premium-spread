package io.premiumspread.infrastructure.security

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.userdetails.UsernameNotFoundException

class CustomUserDetailsServiceTest {

    private lateinit var memberRepository: MemberRepository
    private lateinit var service: CustomUserDetailsService

    @BeforeEach
    fun setUp() {
        memberRepository = mockk()
        service = CustomUserDetailsService(memberRepository)
    }

    @Test
    fun `존재하는 이메일로 조회하면 UserDetails를 반환한다`() {
        val member = Member.create(
            email = "test@example.com",
            encodedPassword = "encoded_password",
        ).withId(1L)

        every { memberRepository.findByEmail("test@example.com") } returns member

        val result = service.loadUserByUsername("test@example.com")

        assertThat(result.username).isEqualTo("test@example.com")
        assertThat(result.password).isEqualTo("encoded_password")
    }

    @Test
    fun `존재하지 않는 이메일이면 에러 메시지에 이메일이 포함되지 않는다`() {
        every { memberRepository.findByEmail("unknown@example.com") } returns null

        assertThatThrownBy { service.loadUserByUsername("unknown@example.com") }
            .isInstanceOf(UsernameNotFoundException::class.java)
            .hasMessage("인증에 실패했습니다.")
            .hasMessageNotContaining("unknown@example.com")
    }
}
