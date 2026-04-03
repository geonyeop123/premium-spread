package io.premiumspread.infrastructure.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret-key}") private val secretKeyString: String,
    @Value("\${jwt.access-token-expiry-ms:1800000}") private val accessTokenExpiryMs: Long,
    @Value("\${jwt.refresh-token-expiry-ms:604800000}") private val refreshTokenExpiryMs: Long,
) {

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
        val now = Date()
        val expiry = Date(now.time + expiryMs)

        return Jwts.builder()
            .subject(email)
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
            .build()
            .parseSignedClaims(token)
            .payload
    }

    companion object {
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
