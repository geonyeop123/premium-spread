package io.premiumspread.infrastructure.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

class JwtTokenProviderTest {

    private lateinit var provider: JwtTokenProvider

    @BeforeEach
    fun setUp() {
        provider = JwtTokenProvider(
            secretKeyString = "test-secret-key-must-be-at-least-32-bytes-long!!",
            accessTokenExpiryMs = 1800000L,
            refreshTokenExpiryMs = 604800000L,
            issuer = TEST_ISSUER,
            audience = TEST_AUDIENCE,
            clockSkewSeconds = 30L,
            clock = FIXED_CLOCK,
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
            assertThat(valid.claims.issuedAt).isEqualTo(Date.from(FIXED_NOW))
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
            val strictProvider = tokenProvider(clockSkewSeconds = 0L)
            val token = signedToken(expirationOffsetMs = -1_000L)

            val result = strictProvider.validateAndGetClaims(token)

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
                issuer = TEST_ISSUER,
                audience = TEST_AUDIENCE,
                clockSkewSeconds = 30L,
                clock = FIXED_CLOCK,
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

        @Test
        fun `issuer가 다르면 Invalid를 반환한다`() {
            val otherIssuerProvider = tokenProvider(issuer = "another-issuer")
            val token = otherIssuerProvider.generateAccessToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isEqualTo(JwtValidationResult.Invalid)
        }

        @Test
        fun `audience가 다르면 Invalid를 반환한다`() {
            val otherAudienceProvider = tokenProvider(audience = "another-audience")
            val token = otherAudienceProvider.generateAccessToken(1L, "test@example.com")

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isEqualTo(JwtValidationResult.Invalid)
        }

        @Test
        fun `clock skew 이내로 만료된 토큰은 검증을 통과한다`() {
            val token = signedToken(expirationOffsetMs = -1_000L)

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isInstanceOf(JwtValidationResult.Valid::class.java)
        }

        @Test
        fun `clock skew를 초과해 만료된 토큰은 Expired를 반환한다`() {
            val token = signedToken(expirationOffsetMs = -31_000L)

            val result = provider.validateAndGetClaims(token)

            assertThat(result).isEqualTo(JwtValidationResult.Expired)
        }
    }

    @Nested
    inner class RefreshTokenExpirySeconds {

        @Test
        fun `리프레시 토큰 만료 시간을 초 단위로 반환한다`() {
            assertThat(provider.getRefreshTokenExpirySeconds()).isEqualTo(604800L)
        }
    }

    @Nested
    inner class ConfigurationValidation {

        @Test
        fun `secret은 비어 있거나 32 bytes보다 짧을 수 없다`() {
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(secret = "") }
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(secret = "short-secret") }
        }

        @Test
        fun `access와 refresh TTL은 양수여야 한다`() {
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(accessTokenExpiryMs = 0L) }
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(refreshTokenExpiryMs = -1L) }
        }

        @Test
        fun `issuer와 audience는 비어 있을 수 없다`() {
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(issuer = " ") }
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(audience = "") }
        }

        @Test
        fun `clock skew는 음수일 수 없다`() {
            assertThatIllegalArgumentException().isThrownBy { tokenProvider(clockSkewSeconds = -1L) }
        }
    }

    private fun tokenProvider(
        secret: String = "test-secret-key-must-be-at-least-32-bytes-long!!",
        accessTokenExpiryMs: Long = 1_800_000L,
        refreshTokenExpiryMs: Long = 604_800_000L,
        issuer: String = TEST_ISSUER,
        audience: String = TEST_AUDIENCE,
        clockSkewSeconds: Long = 30L,
    ): JwtTokenProvider = JwtTokenProvider(
        secretKeyString = secret,
        accessTokenExpiryMs = accessTokenExpiryMs,
        refreshTokenExpiryMs = refreshTokenExpiryMs,
        issuer = issuer,
        audience = audience,
        clockSkewSeconds = clockSkewSeconds,
        clock = FIXED_CLOCK,
    )

    private fun signedToken(expirationOffsetMs: Long): String {
        val now = Date.from(FIXED_NOW)
        return Jwts.builder()
            .issuer(TEST_ISSUER)
            .subject("test@example.com")
            .audience().add(TEST_AUDIENCE).and()
            .claim(JwtTokenProvider.CLAIM_MEMBER_ID, 1L)
            .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_ACCESS)
            .issuedAt(now)
            .expiration(Date(now.time + expirationOffsetMs))
            .signWith(Keys.hmacShaKeyFor("test-secret-key-must-be-at-least-32-bytes-long!!".toByteArray()))
            .compact()
    }

    companion object {
        private const val TEST_ISSUER = "premium-spread"
        private const val TEST_AUDIENCE = "premium-spread-api"
        private val FIXED_NOW: Instant = Instant.parse("2026-07-14T03:00:00Z")
        private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
    }
}
