package io.premiumspread.domain.auth

import java.time.Duration
import java.time.Instant

data class TokenSubject(val memberId: Long, val email: String)

data class IssuedToken(val value: String, val tokenId: String, val expiresAt: Instant)

data class IssuedTokenPair(
    val accessToken: IssuedToken,
    val refreshToken: IssuedToken,
    val familyId: String,
    val generation: Long,
)

data class VerifiedAccessToken(val subject: TokenSubject, val tokenId: String, val expiresAt: Instant)

data class VerifiedRefreshToken(
    val subject: TokenSubject,
    val tokenId: String,
    val expiresAt: Instant,
    val familyId: String,
    val generation: Long,
)

interface TokenIssuer {
    fun issue(
        subject: TokenSubject,
        familyId: String,
        generation: Long,
        issuedAt: Instant,
    ): IssuedTokenPair

    fun verifyAccess(token: String, verifiedAt: Instant): VerifiedAccessToken?

    fun verifyRefresh(token: String, verifiedAt: Instant): VerifiedRefreshToken?
}

interface RefreshTokenHasher {
    fun hash(rawToken: String): String
}

data class RefreshSession(
    val tokenHash: String,
    val tokenId: String,
    val memberId: Long,
    val expiresAt: Instant,
    val familyId: String,
    val generation: Long,
)

data class RefreshSessionProof(
    val tokenHash: String,
    val tokenId: String,
    val memberId: Long,
    val familyId: String,
    val generation: Long,
)

enum class RefreshRotationResult {
    ROTATED,
    CONCURRENT_LOSER,
    REUSED_AND_FAMILY_REVOKED,
    STALE_LOGIN_FAMILY,
    SESSION_NOT_FOUND,
    INVALID_SESSION,
}

interface RefreshSessionStore {
    /** 로그인 성공 시 이 회원의 기존 family를 원자적으로 교체한다. */
    fun replace(session: RefreshSession, replacedAt: Instant)

    /** old session 검증과 replacement 저장을 하나의 Redis CAS로 수행한다. */
    fun rotate(
        proof: RefreshSessionProof,
        replacement: RefreshSession,
        rotatedAt: Instant,
    ): RefreshRotationResult

    /** 다른 로그인 family의 token은 현재 session을 폐기하지 않는다. */
    fun revoke(proof: RefreshSessionProof, revokedAt: Instant): Boolean
}

data class RefreshCookie(
    val name: String,
    val value: String,
    val path: String,
    val domain: String?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val sameSite: String,
    val maxAge: Duration,
)

interface RefreshCookiePolicy {
    fun issue(token: String, expiresAt: Instant, issuedAt: Instant): RefreshCookie

    fun expire(): RefreshCookie
}

object RefreshCookieConfiguration {
    const val PROPERTY_PREFIX = "auth.cookie"
    const val NAME_PROPERTY = "auth.cookie.name"
    const val DEFAULT_NAME = "refresh_token"
    const val NAME_PLACEHOLDER = "\${auth.cookie.name:refresh_token}"
}
