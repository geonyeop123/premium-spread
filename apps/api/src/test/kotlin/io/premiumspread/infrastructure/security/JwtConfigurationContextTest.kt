package io.premiumspread.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

class JwtConfigurationContextTest {

    private val validProperties = linkedMapOf(
        "jwt.secret-key" to "test-secret-key-must-be-at-least-32-bytes-long!!",
        "jwt.access-token-expiry-ms" to "1800000",
        "jwt.refresh-token-expiry-ms" to "604800000",
        "jwt.issuer" to "premium-spread",
        "jwt.audience" to "premium-spread-api",
        "jwt.clock-skew-seconds" to "30",
    )

    @Test
    fun `모든 JWT 설정이 유효하면 context가 시작된다`() {
        contextRunner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(JwtTokenProvider::class.java)
        }
    }

    @Test
    fun `secret이 빈 값이면 application context 시작에 실패한다`() {
        contextRunner("jwt.secret-key" to "")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("jwt.secret-key는 32 bytes 이상이어야 합니다.")
            }
    }

    @Test
    fun `TTL이 0이면 application context 시작에 실패한다`() {
        contextRunner("jwt.access-token-expiry-ms" to "0")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("jwt.access-token-expiry-ms는 양수여야 합니다.")
            }
    }

    @Test
    fun `refresh TTL이 0이면 application context 시작에 실패한다`() {
        contextRunner("jwt.refresh-token-expiry-ms" to "0").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage("jwt.refresh-token-expiry-ms는 양수여야 합니다.")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jwt.secret-key",
            "jwt.access-token-expiry-ms",
            "jwt.refresh-token-expiry-ms",
            "jwt.issuer",
            "jwt.audience",
            "jwt.clock-skew-seconds",
        ],
    )
    fun `필수 JWT property가 누락되면 context 시작에 실패한다`(missingKey: String) {
        contextRunner(missingKey = missingKey).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(missingKey)
        }
    }

    private fun contextRunner(
        override: Pair<String, String>? = null,
        missingKey: String? = null,
    ): ApplicationContextRunner {
        val properties = validProperties
            .filterKeys { it != missingKey }
            .toMutableMap()
            .apply { override?.let { put(it.first, it.second) } }
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withPropertyValues(*properties)
            .withUserConfiguration(ClockConfiguration::class.java, JwtTokenProvider::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    class ClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }
}
