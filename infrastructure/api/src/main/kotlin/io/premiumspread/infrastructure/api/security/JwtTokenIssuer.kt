package io.premiumspread.infrastructure.api.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.premiumspread.domain.auth.IssuedToken
import io.premiumspread.domain.auth.IssuedTokenPair
import io.premiumspread.domain.auth.TokenIssuer
import io.premiumspread.domain.auth.TokenSubject
import io.premiumspread.domain.auth.VerifiedAccessToken
import io.premiumspread.domain.auth.VerifiedRefreshToken
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

class JwtTokenIssuer(
    private val properties: JwtProperties,
) : TokenIssuer {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(properties.secretKey.toByteArray(Charsets.UTF_8))

    override fun issue(
        subject: TokenSubject,
        familyId: String,
        generation: Long,
        issuedAt: Instant,
    ): IssuedTokenPair {
        require(familyId.isNotBlank()) { "familyId must not be blank" }
        require(generation >= 0) { "generation must not be negative" }
        val access = issueToken(subject, ACCESS, issuedAt, properties.accessTokenExpiryMs)
        val refresh = issueToken(
            subject = subject,
            tokenType = REFRESH,
            issuedAt = issuedAt,
            expiryMs = properties.refreshTokenExpiryMs,
            additionalClaims = mapOf(FAMILY_ID to familyId, GENERATION to generation),
        )
        return IssuedTokenPair(access, refresh, familyId, generation)
    }

    override fun verifyAccess(token: String, verifiedAt: Instant): VerifiedAccessToken? =
        verify(token, verifiedAt, ACCESS)?.let { verified ->
            VerifiedAccessToken(verified.subject, verified.tokenId, verified.expiresAt)
        }

    override fun verifyRefresh(token: String, verifiedAt: Instant): VerifiedRefreshToken? =
        verify(token, verifiedAt, REFRESH)?.let { verified ->
            val familyId = verified.claims[FAMILY_ID] as? String ?: return null
            val generation = (verified.claims[GENERATION] as? Number)?.toLong() ?: return null
            if (familyId.isBlank() || generation < 0) return null
            VerifiedRefreshToken(
                subject = verified.subject,
                tokenId = verified.tokenId,
                expiresAt = verified.expiresAt,
                familyId = familyId,
                generation = generation,
            )
        }

    private fun issueToken(
        subject: TokenSubject,
        tokenType: String,
        issuedAt: Instant,
        expiryMs: Long,
        additionalClaims: Map<String, Any> = emptyMap(),
    ): IssuedToken {
        val tokenId = UUID.randomUUID().toString()
        val expiresAt = issuedAt.plusMillis(expiryMs)
        val builder = Jwts.builder()
            .issuer(properties.issuer)
            .audience().add(properties.audience).and()
            .subject(subject.email)
            .id(tokenId)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .claim(MEMBER_ID, subject.memberId)
            .claim(TOKEN_TYPE, tokenType)
        additionalClaims.forEach { (name, value) -> builder.claim(name, value) }
        return IssuedToken(builder.signWith(secretKey).compact(), tokenId, expiresAt)
    }

    private fun verify(token: String, verifiedAt: Instant, expectedType: String): ParsedToken? = runCatching {
        require(token.isNotBlank())
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(properties.issuer)
            .requireAudience(properties.audience)
            .clock { Date.from(verifiedAt) }
            .clockSkewSeconds(properties.clockSkewSeconds)
            .build()
            .parseSignedClaims(token)
            .payload
        require(claims[TOKEN_TYPE] == expectedType)
        val memberId = (claims[MEMBER_ID] as? Number)?.toLong() ?: error("memberId claim missing")
        val email = claims.subject?.takeIf(String::isNotBlank) ?: error("subject missing")
        val tokenId = claims.id?.takeIf(String::isNotBlank) ?: error("jti missing")
        val expiresAt = claims.expiration?.toInstant() ?: error("expiration missing")
        ParsedToken(TokenSubject(memberId, email), tokenId, expiresAt, claims)
    }.getOrNull()

    private data class ParsedToken(
        val subject: TokenSubject,
        val tokenId: String,
        val expiresAt: Instant,
        val claims: Claims,
    )

    companion object {
        private const val MEMBER_ID = "memberId"
        private const val TOKEN_TYPE = "tokenType"
        private const val FAMILY_ID = "familyId"
        private const val GENERATION = "generation"
        private const val ACCESS = "access"
        private const val REFRESH = "refresh"
    }
}
