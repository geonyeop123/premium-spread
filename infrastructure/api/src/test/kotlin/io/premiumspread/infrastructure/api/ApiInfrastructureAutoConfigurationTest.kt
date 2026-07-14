package io.premiumspread.infrastructure.api

import io.premiumspread.domain.auth.RefreshCookiePolicy
import io.premiumspread.domain.auth.RefreshTokenHasher
import io.premiumspread.domain.auth.TokenIssuer
import io.premiumspread.domain.member.PasswordEncoder
import io.premiumspread.infrastructure.api.security.ProductionSecurityPolicyValidator
import io.premiumspread.infrastructure.api.security.CookieProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ApiInfrastructureAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiInfrastructureAutoConfiguration::class.java))
            .withPropertyValues(
                "jwt.secret-key=test-secret-key-must-be-at-least-32-bytes-long!!",
                "jwt.issuer=premium-spread",
                "jwt.audience=premium-spread-api",
                "jwt.access-token-expiry-ms=1800000",
                "jwt.refresh-token-expiry-ms=604800000",
                "jwt.clock-skew-seconds=30",
                "auth.cookie.secure=false",
                "auth.cors.allowed-origins[0]=http://localhost:3000",
                "auth.cors.allowed-methods[0]=GET",
                "auth.cors.allowed-headers[0]=Content-Type",
                "auth.refresh.hmac-key=test-refresh-hmac-key-must-be-at-least-32-bytes!!",
            )

    @Test
    fun `auto-configuration can be loaded independently`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ApiInfrastructureAutoConfiguration::class.java)
            assertThat(context).hasSingleBean(TokenIssuer::class.java)
            assertThat(context).hasSingleBean(RefreshTokenHasher::class.java)
            assertThat(context).hasSingleBean(RefreshCookiePolicy::class.java)
            assertThat(context).hasSingleBean(PasswordEncoder::class.java)
            assertThat(context.getBean(CookieProperties::class.java))
                .extracting("secure", "path", "domain", "sameSite")
                .containsExactly(false, "/api/v1/auth", null, "Strict")
        }
    }

    @Test
    fun `prd profile은 insecure refresh cookie로 기동하지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prd")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("prd refresh cookie는 secure=true")
            }
    }

    @Test
    fun `prd profile은 검증된 보안 설정으로 기동한다`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prd",
                "auth.cookie.secure=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ProductionSecurityPolicyValidator::class.java)
                assertThat(context.getBean(CookieProperties::class.java).secure).isTrue()
            }
    }

    @Test
    fun `prd profile은 빈 CORS origin으로 기동하지 않는다`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prd",
                "auth.cookie.secure=true",
                "auth.cors.allowed-origins=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("auth.cors")
            }
    }

    @Test
    fun `refresh HMAC key 누락은 설정 binding 단계에서 기동을 차단한다`() {
        contextRunner
            .withPropertyValues("auth.refresh.hmac-key=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("auth.refresh")
            }
    }
}
