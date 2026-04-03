package io.premiumspread.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private lateinit var provider: JwtTokenProvider

    @BeforeEach
    fun setUp() {
        provider = JwtTokenProvider(
            secretKeyString = "test-secret-key-must-be-at-least-32-bytes-long!!",
            accessTokenExpiryMs = 1800000L,
            refreshTokenExpiryMs = 604800000L,
        )
    }

    @Nested
    inner class GenerateAccessToken {

        @Test
        fun `Access Token을 생성하고 검증할 수 있다`() {
            val token = provider.generateAccessToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isInstanceOf(JwtValidationResult.Valid::class.java)
            val valid = result as JwtValidationResult.Valid
            assertThat(valid.email).isEqualTo("test@example.com")
            assertThat(valid.memberId).isEqualTo(1L)
            assertThat(valid.tokenType).isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS)
        }
    }

    @Nested
    inner class GenerateRefreshToken {

        @Test
        fun `Refresh Token을 생성하고 검증할 수 있다`() {
            val token = provider.generateRefreshToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isInstanceOf(JwtValidationResult.Valid::class.java)
            val valid = result as JwtValidationResult.Valid
            assertThat(valid.email).isEqualTo("test@example.com")
            assertThat(valid.memberId).isEqualTo(1L)
            assertThat(valid.tokenType).isEqualTo(JwtTokenProvider.TOKEN_TYPE_REFRESH)
        }
    }

    @Nested
    inner class ValidateToken {

        @Test
        fun `만료된 토큰은 Expired를 반환한다`() {
            val expiredProvider = JwtTokenProvider(
                secretKeyString = "test-secret-key-must-be-at-least-32-bytes-long!!",
                accessTokenExpiryMs = -1000L,
                refreshTokenExpiryMs = -1000L,
            )
            val token = expiredProvider.generateAccessToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isEqualTo(JwtValidationResult.Expired)
        }

        @Test
        fun `잘못된 토큰은 Invalid를 반환한다`() {
            val result = provider.validateAndGetClaims("invalid-token")

            assertThat(result).isEqualTo(JwtValidationResult.Invalid)
        }

        @Test
        fun `다른 시크릿키로 서명된 토큰은 Invalid를 반환한다`() {
            val otherProvider = JwtTokenProvider(
                secretKeyString = "other-secret-key-must-be-at-least-32-bytes-long!!!",
                accessTokenExpiryMs = 1800000L,
                refreshTokenExpiryMs = 604800000L,
            )
            val token = otherProvider.generateAccessToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isEqualTo(JwtValidationResult.Invalid)
        }

        @Test
        fun `빈 문자열은 Invalid를 반환한다`() {
            val result = provider.validateAndGetClaims("")

            assertThat(result).isEqualTo(JwtValidationResult.Invalid)
        }
    }

    @Nested
    inner class RefreshTokenExpirySeconds {

        @Test
        fun `리프레시 토큰 만료 시간을 초 단위로 반환한다`() {
            assertThat(provider.getRefreshTokenExpirySeconds()).isEqualTo(604800L)
        }
    }
}
