package io.premiumspread.config.jpa

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.validation.annotation.Validated

/**
 * Spring Boot datasource와 같은 prefix를 읽는 fail-fast 계약이다.
 * 실제 DataSource 생성은 Boot auto-configuration이 담당하므로 설정의 SSOT는 spring.datasource 하나다.
 */
@Validated
@ConfigurationProperties(prefix = "spring.datasource")
data class DatabaseProperties(
    @field:NotBlank val url: String,
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    @field:Valid val hikari: HikariPoolProperties = HikariPoolProperties(),
) {
    init {
        require(url.startsWith("jdbc:mysql://")) { "spring.datasource.url must be a MySQL JDBC URL" }
        require(url.contains("connectionTimeZone=UTC") && url.contains("forceConnectionTimeZoneToSession=true")) {
            "spring.datasource.url must enforce a UTC session timezone"
        }
    }
}

data class HikariPoolProperties(
    @field:Min(1) @field:Max(200) val maximumPoolSize: Int = 20,
    @field:Min(0) @field:Max(200) val minimumIdle: Int = 5,
    @field:Min(250) val connectionTimeout: Long = 3_000,
    @field:Min(250) val validationTimeout: Long = 2_000,
    @field:Min(0) val keepaliveTime: Long = 0,
    @field:Min(30_000) val maxLifetime: Long = 1_800_000,
) {
    init {
        require(minimumIdle <= maximumPoolSize) {
            "spring.datasource.hikari.minimum-idle must be <= maximum-pool-size"
        }
        require(validationTimeout < connectionTimeout) {
            "spring.datasource.hikari.validation-timeout must be < connection-timeout"
        }
        require(keepaliveTime == 0L || keepaliveTime < maxLifetime) {
            "spring.datasource.hikari.keepalive-time must be 0 or < max-lifetime"
        }
        require(keepaliveTime == 0L || keepaliveTime >= MIN_KEEPALIVE_MILLIS) {
            "spring.datasource.hikari.keepalive-time must be 0 or >= 30000ms"
        }
    }

    private companion object {
        const val MIN_KEEPALIVE_MILLIS = 30_000L
    }
}

/** 운영 profile이 실수로 local DB 기본값을 재사용하는 것을 startup 단계에서 차단한다. */
class ProductionDatabaseSettingsValidator(environment: Environment, properties: DatabaseProperties) {
    init {
        if (environment.activeProfiles.contains(PRODUCTION_PROFILE)) {
            require(!properties.url.isLocalAddress()) { "prd datasource must not use a local address" }
            require(properties.username !in LOCAL_CREDENTIALS) { "prd datasource must not use a local username" }
            require(properties.password !in LOCAL_CREDENTIALS) { "prd datasource must not use a local/default password" }
        }
    }

    private fun String.isLocalAddress(): Boolean = contains("localhost", ignoreCase = true) || contains("127.0.0.1")

    private companion object {
        const val PRODUCTION_PROFILE = "prd"
        val LOCAL_CREDENTIALS = setOf("application", "root", "password", "")
    }
}
