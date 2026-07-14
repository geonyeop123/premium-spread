package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.TokenSubject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JwtAuthenticationFilterTest {
    private val now = Instant.parse("2026-07-14T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val issuer = JwtTokenIssuer(
        JwtProperties(
            "test-secret-key-must-be-at-least-32-bytes-long!!",
            "premium-spread",
            "premium-spread-api",
            1_800_000,
            604_800_000,
            30,
        ),
    )
    private val filter = JwtAuthenticationFilter(issuer, clock)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `access token principal은 infrastructure class가 아닌 member id 문자열이다`() {
        val pair = issuer.issue(TokenSubject(7L, "member@example.com"), "family", 0, now)
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${pair.accessToken.value}")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication.name).isEqualTo("7")
        assertThat(SecurityContextHolder.getContext().authentication.principal).isInstanceOf(String::class.java)
    }

    @Test
    fun `refresh token은 access 인증에 사용할 수 없다`() {
        val pair = issuer.issue(TokenSubject(7L, "member@example.com"), "family", 0, now)
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${pair.refreshToken.value}")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }
}
