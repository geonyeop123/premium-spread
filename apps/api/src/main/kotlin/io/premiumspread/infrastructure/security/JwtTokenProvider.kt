package io.premiumspread.infrastructure.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret-key}") private val secretKeyString: String,
    @Value("\${jwt.access-token-expiry-ms}") private val accessTokenExpiryMs: Long,
    @Value("\${jwt.refresh-token-expiry-ms}") private val refreshTokenExpiryMs: Long,
    @Value("\${jwt.issuer}") private val issuer: String,
    @Value("\${jwt.audience}") private val audience: String,
    @Value("\${jwt.clock-skew-seconds}") private val clockSkewSeconds: Long,
    private val clock: Clock,
) {

    init {
        require(secretKeyString.toByteArray().size >= MIN_SECRET_KEY_BYTES) {
            "jwt.secret-key는 32 bytes 이상이어야 합니다."
        }
        require(accessTokenExpiryMs > 0) { "jwt.access-token-expiry-ms는 양수여야 합니다." }
        require(refreshTokenExpiryMs > 0) { "jwt.refresh-token-expiry-ms는 양수여야 합니다." }
        require(issuer.isNotBlank()) { "jwt.issuer는 비어 있을 수 없습니다." }
        require(audience.isNotBlank()) { "jwt.audience는 비어 있을 수 없습니다." }
        require(clockSkewSeconds >= 0) { "jwt.clock-skew-seconds는 음수일 수 없습니다." }
    }

    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secretKeyString.toByteArray())
    }

    fun generateAccessToken(memberId: Long, email: String): String {
        return generateToken(memberId, email, accessTokenExpiryMs, TOKEN_TYPE_ACCESS)
    }

    fun generateRefreshToken(memberId: Long, email: String): String {
        return generateToken(memberId, email, refreshTokenExpiryMs, TOKEN_TYPE_REFRESH)
    }

    fun getRefreshTokenExpirySeconds(): Long = refreshTokenExpiryMs / 1000

    fun validateAndGetClaims(token: String): JwtValidationResult {
        return try {
            val claims = parseToken(token)
            JwtValidationResult.Valid(claims)
        } catch (e: ExpiredJwtException) {
            JwtValidationResult.Expired
        } catch (e: JwtException) {
            JwtValidationResult.Invalid
        } catch (e: IllegalArgumentException) {
            JwtValidationResult.Invalid
        }
    }

    private fun generateToken(memberId: Long, email: String, expiryMs: Long, tokenType: String): String {
        val now = Date.from(clock.instant())
        val expiry = Date(now.time + expiryMs)

        return Jwts.builder()
            .issuer(issuer)
            .subject(email)
            .audience().add(audience).and()
            .claim(CLAIM_MEMBER_ID, memberId)
            .claim(CLAIM_TOKEN_TYPE, tokenType)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()
    }

    private fun parseToken(token: String): Claims {
        return Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(issuer)
            .requireAudience(audience)
            .clock { Date.from(clock.instant()) }
            .clockSkewSeconds(clockSkewSeconds)
            .build()
            .parseSignedClaims(token)
            .payload
    }

    companion object {
        private const val MIN_SECRET_KEY_BYTES = 32
        const val CLAIM_MEMBER_ID = "memberId"
        const val CLAIM_TOKEN_TYPE = "tokenType"
        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
    }
}

sealed class JwtValidationResult {
    data class Valid(val claims: Claims) : JwtValidationResult() {
        val email: String get() = claims.subject
        val memberId: Long get() = claims[JwtTokenProvider.CLAIM_MEMBER_ID, java.lang.Long::class.java].toLong()
        val tokenType: String get() = claims[JwtTokenProvider.CLAIM_TOKEN_TYPE, String::class.java]
    }
    data object Expired : JwtValidationResult()
    data object Invalid : JwtValidationResult()
}
