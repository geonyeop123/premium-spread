package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.RefreshCookieConfiguration
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    @field:NotBlank @field:Size(min = 32)
    val secretKey: String,
    @field:NotBlank
    val issuer: String,
    @field:NotBlank
    val audience: String,
    @field:Min(1)
    val accessTokenExpiryMs: Long,
    @field:Min(1)
    val refreshTokenExpiryMs: Long,
    @field:Min(0)
    val clockSkewSeconds: Long,
) {
    init {
        require(secretKey.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "jwt.secret-key는 32 bytes 이상이어야 합니다."
        }
        require(issuer.isNotBlank()) { "jwt.issuer는 비어 있을 수 없습니다." }
        require(audience.isNotBlank()) { "jwt.audience는 비어 있을 수 없습니다." }
        require(accessTokenExpiryMs > 0) { "jwt.access-token-expiry-ms는 양수여야 합니다." }
        require(refreshTokenExpiryMs > accessTokenExpiryMs) {
            "jwt.refresh-token-expiry-ms는 access token TTL보다 길어야 합니다."
        }
        require(clockSkewSeconds >= 0) { "jwt.clock-skew-seconds는 음수일 수 없습니다." }
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
        const val LOCAL_DEFAULT_SECRET = "default-local-secret-key-must-be-at-least-32-bytes-long!!"
    }
}

@Validated
@ConfigurationProperties(prefix = RefreshCookieConfiguration.PROPERTY_PREFIX)
data class CookieProperties(
    @field:NotBlank
    val name: String = RefreshCookieConfiguration.DEFAULT_NAME,
    @field:NotBlank
    val path: String = "/api/v1/auth",
    val domain: String? = null,
    val secure: Boolean,
    val httpOnly: Boolean = true,
    @field:NotBlank
    val sameSite: String = "Strict",
) {
    init {
        require(path.startsWith('/')) { "auth.cookie.path는 /로 시작해야 합니다." }
        require(sameSite in SUPPORTED_SAME_SITE) { "auth.cookie.same-site는 Strict, Lax, None 중 하나여야 합니다." }
        require(!sameSite.equals("None", ignoreCase = true) || secure) {
            "SameSite=None cookie는 secure=true여야 합니다."
        }
    }

    companion object {
        private val SUPPORTED_SAME_SITE = setOf("Strict", "Lax", "None")
    }
}

@Validated
@ConfigurationProperties(prefix = "auth.cors")
data class CorsProperties(
    @field:NotEmpty
    val allowedOrigins: List<@NotBlank String>,
    @field:NotEmpty
    val allowedMethods: List<@NotBlank String>,
    @field:NotEmpty
    val allowedHeaders: List<@NotBlank String>,
    val allowCredentials: Boolean = true,
) {
    init {
        require(!allowCredentials || "*" !in allowedOrigins) {
            "credential CORS에는 wildcard origin을 사용할 수 없습니다."
        }
        require(allowedOrigins.all { it.startsWith("http://") || it.startsWith("https://") }) {
            "auth.cors.allowed-origins는 명시적 http(s) origin이어야 합니다."
        }
        require(allowedMethods.all { it in SUPPORTED_METHODS }) {
            "auth.cors.allowed-methods에는 명시적인 표준 HTTP method만 사용할 수 있습니다."
        }
        require("*" !in allowedHeaders) { "auth.cors.allowed-headers에는 wildcard를 사용할 수 없습니다." }
    }

    private companion object {
        val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}

@Validated
@ConfigurationProperties(prefix = "auth.refresh")
data class RefreshProperties(
    @field:NotBlank @field:Size(min = 32)
    val hmacKey: String,
    @field:Min(1)
    val concurrentGraceMs: Long = 2_000,
    @field:NotBlank
    val keyPrefix: String = "auth:refresh",
) {
    init {
        require(hmacKey.toByteArray(Charsets.UTF_8).size >= MIN_HMAC_KEY_BYTES) {
            "auth.refresh.hmac-key는 32 bytes 이상이어야 합니다."
        }
        require(concurrentGraceMs > 0) { "auth.refresh.concurrent-grace-ms는 양수여야 합니다." }
        require(keyPrefix.isNotBlank()) { "auth.refresh.key-prefix는 비어 있을 수 없습니다." }
    }

    companion object {
        private const val MIN_HMAC_KEY_BYTES = 32
        const val LOCAL_DEFAULT_HMAC_KEY = "default-local-refresh-hmac-key-must-be-32-bytes!!"
    }
}

class ProductionSecurityPolicyValidator(
    jwt: JwtProperties,
    cookie: CookieProperties,
    cors: CorsProperties,
    refresh: RefreshProperties,
) {
    init {
        require(jwt.secretKey != JwtProperties.LOCAL_DEFAULT_SECRET) { "prd에서는 기본 JWT secret을 사용할 수 없습니다." }
        require(refresh.hmacKey != RefreshProperties.LOCAL_DEFAULT_HMAC_KEY) {
            "prd에서는 기본 refresh HMAC key를 사용할 수 없습니다."
        }
        require(cookie.secure) { "prd refresh cookie는 secure=true여야 합니다." }
        require(cors.allowedOrigins.isNotEmpty()) { "prd CORS origin은 한 개 이상 명시해야 합니다." }
    }
}
