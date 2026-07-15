package io.premiumspread.application.auth

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.auth.RefreshCookiePolicy
import io.premiumspread.domain.auth.RefreshCookie
import io.premiumspread.domain.auth.RefreshRotationResult
import io.premiumspread.domain.auth.RefreshSession
import io.premiumspread.domain.auth.RefreshSessionProof
import io.premiumspread.domain.auth.RefreshSessionStore
import io.premiumspread.domain.auth.RefreshTokenHasher
import io.premiumspread.domain.auth.TokenIssuer
import io.premiumspread.domain.auth.TokenSubject
import io.premiumspread.domain.auth.VerifiedRefreshToken
import io.premiumspread.domain.member.MemberService
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class AuthFacade(
    private val memberService: MemberService,
    private val tokenIssuer: TokenIssuer,
    private val refreshSessionStore: RefreshSessionStore,
    private val refreshTokenHasher: RefreshTokenHasher,
    private val refreshCookiePolicy: RefreshCookiePolicy,
    private val clock: Clock,
) {
    fun login(criteria: AuthCriteria.Login): AuthResult.Login {
        val member = memberService.authenticate(criteria.email, criteria.password)
            ?: throw ApplicationException(ApplicationError.AUTHENTICATION_FAILED)
        val now = clock.instant()
        val issued = tokenIssuer.issue(
            subject = TokenSubject(member.id, member.email),
            familyId = UUID.randomUUID().toString(),
            generation = INITIAL_GENERATION,
            issuedAt = now,
        )
        refreshSessionStore.replace(issued.toSession(member.id), now)

        return AuthResult.Login(
            accessToken = issued.accessToken.value,
            id = member.id,
            email = member.email,
            nickname = member.nickname,
            refreshCookie = refreshCookiePolicy
                .issue(issued.refreshToken.value, issued.refreshToken.expiresAt, now)
                .toResultCookie(),
        )
    }

    fun refresh(criteria: AuthCriteria.Refresh): AuthResult.Refresh {
        val rawToken = criteria.refreshToken
            ?: throw ApplicationException(ApplicationError.INVALID_REFRESH_TOKEN)
        val now = clock.instant()
        val verified = tokenIssuer.verifyRefresh(rawToken, now)
            ?: throw ApplicationException(ApplicationError.INVALID_REFRESH_TOKEN)
        val replacement = tokenIssuer.issue(
            subject = verified.subject,
            familyId = verified.familyId,
            generation = verified.generation + 1,
            issuedAt = now,
        )
        val result = refreshSessionStore.rotate(
            proof = verified.toProof(refreshTokenHasher.hash(rawToken)),
            replacement = replacement.toSession(verified.subject.memberId),
            rotatedAt = now,
        )
        if (result != RefreshRotationResult.ROTATED) {
            throw ApplicationException(ApplicationError.INVALID_REFRESH_TOKEN)
        }

        return AuthResult.Refresh(
            accessToken = replacement.accessToken.value,
            refreshCookie = refreshCookiePolicy
                .issue(replacement.refreshToken.value, replacement.refreshToken.expiresAt, now)
                .toResultCookie(),
        )
    }

    fun logout(criteria: AuthCriteria.Logout): AuthResult.Logout {
        val now = clock.instant()
        criteria.refreshToken
            ?.let { raw -> tokenIssuer.verifyRefresh(raw, now)?.toProof(refreshTokenHasher.hash(raw)) }
            ?.let { proof -> refreshSessionStore.revoke(proof, now) }
        return AuthResult.Logout(refreshCookiePolicy.expire().toResultCookie())
    }

    private fun io.premiumspread.domain.auth.IssuedTokenPair.toSession(memberId: Long): RefreshSession =
        RefreshSession(
            tokenHash = refreshTokenHasher.hash(refreshToken.value),
            tokenId = refreshToken.tokenId,
            memberId = memberId,
            expiresAt = refreshToken.expiresAt,
            familyId = familyId,
            generation = generation,
        )

    private fun VerifiedRefreshToken.toProof(tokenHash: String): RefreshSessionProof = RefreshSessionProof(
        tokenHash = tokenHash,
        tokenId = tokenId,
        memberId = subject.memberId,
        familyId = familyId,
        generation = generation,
    )

    private fun RefreshCookie.toResultCookie(): AuthResult.Cookie = AuthResult.Cookie(
        name = name,
        value = value,
        path = path,
        domain = domain,
        secure = secure,
        httpOnly = httpOnly,
        sameSite = sameSite,
        maxAgeSeconds = maxAge.seconds,
    )

    private companion object {
        const val INITIAL_GENERATION = 0L
    }
}
