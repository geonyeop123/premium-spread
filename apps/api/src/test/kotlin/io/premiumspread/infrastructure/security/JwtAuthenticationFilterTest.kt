package io.premiumspread.infrastructure.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.util.Date

class JwtAuthenticationFilterTest {

    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        jwtTokenProvider = JwtTokenProvider(
            secretKeyString = "test-secret-key-must-be-at-least-32-bytes-long!!",
            accessTokenExpiryMs = 1800000L,
            refreshTokenExpiryMs = 604800000L,
            issuer = "premium-spread",
            audience = "premium-spread-api",
            clockSkewSeconds = 30L,
            clock = Clock.systemUTC(),
        )
        filter = JwtAuthenticationFilter(jwtTokenProvider)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Nested
    inner class ValidAccessToken {

        @Test
        fun `유효한 Access Token이 있으면 SecurityContext에 인증 정보를 설정한다`() {
            val token = jwtTokenProvider.generateAccessToken(1L, "test@example.com")
            val request = MockHttpServletRequest()
            request.addHeader("Authorization", "Bearer $token")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            val auth = SecurityContextHolder.getContext().authentication
            assertThat(auth).isNotNull
            val userDetails = auth.principal as CustomUserDetails
            assertThat(userDetails.memberId).isEqualTo(1L)
            assertThat(userDetails.email).isEqualTo("test@example.com")
        }
    }

    @Nested
    inner class RefreshTokenNotAllowed {

        @Test
        fun `Refresh Token으로는 인증되지 않는다`() {
            val token = jwtTokenProvider.generateRefreshToken(1L, "test@example.com")
            val request = MockHttpServletRequest()
            request.addHeader("Authorization", "Bearer $token")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }

    @Nested
    inner class NoToken {

        @Test
        fun `Authorization 헤더가 없으면 SecurityContext가 비어있다`() {
            val request = MockHttpServletRequest()

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        @Test
        fun `Bearer 접두사가 없으면 SecurityContext가 비어있다`() {
            val request = MockHttpServletRequest()
            request.addHeader("Authorization", "Basic sometoken")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }

    @Nested
    inner class InvalidToken {

        @Test
        fun `잘못된 토큰이면 SecurityContext가 비어있다`() {
            val request = MockHttpServletRequest()
            request.addHeader("Authorization", "Bearer invalid-token")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        @Test
        fun `만료된 토큰이면 SecurityContext가 비어있다`() {
            val now = Date()
            val token = Jwts.builder()
                .issuer("premium-spread")
                .subject("test@example.com")
                .audience().add("premium-spread-api").and()
                .claim(JwtTokenProvider.CLAIM_MEMBER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_ACCESS)
                .issuedAt(now)
                .expiration(Date(now.time - 31_000L))
                .signWith(
                    Keys.hmacShaKeyFor(
                        "test-secret-key-must-be-at-least-32-bytes-long!!".toByteArray(),
                    ),
                )
                .compact()
            val request = MockHttpServletRequest()
            request.addHeader("Authorization", "Bearer $token")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
