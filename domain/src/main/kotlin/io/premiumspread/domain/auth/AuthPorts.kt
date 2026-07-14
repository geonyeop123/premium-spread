package io.premiumspread.domain.auth

import java.time.Instant

data class TokenSubject(val memberId: Long, val email: String)

data class IssuedToken(
    val value: String,
    val expiresAt: Instant,
)

data class IssuedTokenPair(
    val accessToken: IssuedToken,
    val refreshToken: IssuedToken,
)

data class VerifiedToken(
    val subject: TokenSubject,
    val tokenId: String,
    val expiresAt: Instant,
)

interface TokenIssuer {
    fun issue(subject: TokenSubject, issuedAt: Instant): IssuedTokenPair

    fun verifyRefresh(token: String, verifiedAt: Instant): VerifiedToken?
}

data class RefreshSession(
    val tokenId: String,
    val memberId: Long,
    val expiresAt: Instant,
)

interface RefreshSessionStore {
    fun save(session: RefreshSession)

    fun rotate(previousTokenId: String, replacement: RefreshSession, rotatedAt: Instant): Boolean

    fun revoke(tokenId: String, revokedAt: Instant)
}
