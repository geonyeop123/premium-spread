package io.premiumspread.redis

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "redis")
data class RedisFeatureProperties(val enabled: Boolean = true)

/** Spring Data Redis와 Redisson이 함께 사용하는 단일 접속 설정 계약. */
@Validated
@ConfigurationProperties(prefix = "spring.data.redis")
data class RedisRuntimeProperties(
    @field:NotBlank val host: String = "localhost",
    @field:Min(1) @field:Max(65_535) val port: Int = 6_379,
    val password: String? = null,
)

@Validated
@ConfigurationProperties(prefix = "spring.data.redis.redisson")
data class RedissonClientProperties(
    @field:Min(1) @field:Max(1_000) val poolSize: Int = 10,
    @field:Min(0) @field:Max(1_000) val minimumIdle: Int = 2,
    @field:Min(0) @field:Max(10) val retryAttempts: Int = 3,
    val retryInterval: Duration = Duration.ofMillis(1_500),
    val commandTimeout: Duration = Duration.ofSeconds(3),
    val connectTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(minimumIdle <= poolSize) { "spring.data.redis.redisson.minimum-idle must be <= pool-size" }
        require(retryInterval.isPositive()) { "spring.data.redis.redisson.retry-interval must be positive" }
        require(commandTimeout.isPositive()) { "spring.data.redis.redisson.command-timeout must be positive" }
        require(connectTimeout.isPositive()) { "spring.data.redis.redisson.connect-timeout must be positive" }
        require(listOf(retryInterval, commandTimeout, connectTimeout).all { it.toMillis() <= Int.MAX_VALUE }) {
            "redisson timeout is too large"
        }
    }
}

/** 운영 profile에서 Spring Redis의 localhost/empty-password fallback을 차단한다. */
class ProductionRedisSettingsValidator(environment: Environment, properties: RedisRuntimeProperties) {
    init {
        if (environment.activeProfiles.contains("prd")) {
            require(properties.host != "localhost" && properties.host != "127.0.0.1") {
                "prd redis must not use a local address"
            }
            require(!properties.password.isNullOrBlank()) { "prd redis password must not be empty" }
        }
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
