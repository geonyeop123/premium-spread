package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.RefreshCookiePolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant

class SecurityPolicyTest {
    @Test
    fun `refresh token hasher는 원문이 아닌 결정적 HMAC-SHA-256을 반환한다`() {
        val hasher = HmacRefreshTokenHasher(
            RefreshProperties("refresh-hmac-key-must-be-at-least-32-bytes!!", 2_000, "auth:refresh"),
        )

        val hashed = hasher.hash("raw-refresh-token")

        assertThat(hashed).isEqualTo(hasher.hash("raw-refresh-token"))
        assertThat(hashed).doesNotContain("raw-refresh-token")
        assertThat(hashed).hasSize(43)
    }

    @Test
    fun `cookie policy는 local과 prd secure 설정을 그대로 반영하고 삭제 속성을 일치시킨다`() {
        val local = cookiePolicy(secure = false)
        val production = cookiePolicy(secure = true)

        assertThat(local.issue("token", Instant.ofEpochSecond(100), Instant.EPOCH).secure).isFalse()
        assertThat(production.issue("token", Instant.ofEpochSecond(100), Instant.EPOCH).secure).isTrue()
        assertThat(production.expire())
            .extracting("name", "path", "domain", "secure", "sameSite")
            .containsExactly("refresh_token", "/api/v1/auth", null, true, "Strict")
    }

    @Test
    fun `보안 설정은 짧은 secret과 wildcard credential CORS를 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy {
            JwtProperties("short", "issuer", "audience", 1, 2, 0)
        }
        assertThatIllegalArgumentException().isThrownBy {
            CorsProperties(listOf("*"), listOf("GET"), listOf("Content-Type"), true)
        }
    }

    @Test
    fun `prd는 secure false와 local 기본 key를 거부한다`() {
        val cors = CorsProperties(
            listOf("https://premium.example.com"),
            listOf("GET", "POST"),
            listOf("Content-Type"),
            true,
        )
        val validJwt = JwtProperties(
            "production-secret-key-must-be-at-least-32-bytes!!",
            "premium-spread",
            "premium-spread-api",
            1_800_000,
            604_800_000,
            30,
        )

        assertThatIllegalArgumentException().isThrownBy {
            ProductionSecurityPolicyValidator(
                validJwt,
                CookieProperties(secure = false),
                cors,
                RefreshProperties("production-refresh-hmac-key-must-be-32-bytes!!"),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            ProductionSecurityPolicyValidator(
                validJwt.copy(secretKey = JwtProperties.LOCAL_DEFAULT_SECRET),
                CookieProperties(secure = true),
                cors,
                RefreshProperties("production-refresh-hmac-key-must-be-32-bytes!!"),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            ProductionSecurityPolicyValidator(
                validJwt,
                CookieProperties(secure = true),
                cors,
                RefreshProperties(RefreshProperties.LOCAL_DEFAULT_HMAC_KEY),
            )
        }
    }

    private fun cookiePolicy(secure: Boolean): RefreshCookiePolicy = RefreshCookiePolicyAdapter(
        CookieProperties(
            name = "refresh_token",
            path = "/api/v1/auth",
            domain = null,
            secure = secure,
            httpOnly = true,
            sameSite = "Strict",
        ),
    )
}
