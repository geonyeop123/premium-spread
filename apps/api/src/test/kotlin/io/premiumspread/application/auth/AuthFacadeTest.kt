package io.premiumspread.application.auth

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.auth.IssuedToken
import io.premiumspread.domain.auth.IssuedTokenPair
import io.premiumspread.domain.auth.RefreshCookie
import io.premiumspread.domain.auth.RefreshCookiePolicy
import io.premiumspread.domain.auth.RefreshRotationResult
import io.premiumspread.domain.auth.RefreshSession
import io.premiumspread.domain.auth.RefreshSessionProof
import io.premiumspread.domain.auth.RefreshSessionStore
import io.premiumspread.domain.auth.RefreshTokenHasher
import io.premiumspread.domain.auth.TokenIssuer
import io.premiumspread.domain.auth.TokenSubject
import io.premiumspread.domain.auth.VerifiedRefreshToken
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuthFacadeTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val memberService = mockk<MemberService>()
    private val tokenIssuer = mockk<TokenIssuer>()
    private val refreshSessionStore = mockk<RefreshSessionStore>()
    private val refreshTokenHasher = mockk<RefreshTokenHasher>()
    private val refreshCookiePolicy = mockk<RefreshCookiePolicy>()
    private val facade = AuthFacade(
        memberService,
        tokenIssuer,
        refreshSessionStore,
        refreshTokenHasher,
        refreshCookiePolicy,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `로그인 성공은 토큰 세션을 교체하고 응답 쿠키를 반환한다`() {
        val member = member()
        val issued = issuedPair("login", generation = 0L)
        val familyId = slot<String>()
        every { memberService.authenticate(EMAIL, PASSWORD) } returns member
        every { tokenIssuer.issue(TokenSubject(MEMBER_ID, EMAIL), capture(familyId), 0L, now) } returns issued
        every { refreshTokenHasher.hash(issued.refreshToken.value) } returns "login-refresh-hash"
        justRun { refreshSessionStore.replace(any(), now) }
        every {
            refreshCookiePolicy.issue(issued.refreshToken.value, issued.refreshToken.expiresAt, now)
        } returns issuedCookie()

        val result = facade.login(AuthCriteria.Login(EMAIL, PASSWORD))

        assertThat(UUID.fromString(familyId.captured)).isNotNull
        assertThat(result.accessToken).isEqualTo(issued.accessToken.value)
        assertThat(result.id).isEqualTo(MEMBER_ID)
        assertThat(result.email).isEqualTo(EMAIL)
        assertThat(result.nickname).isEqualTo(NICKNAME)
        assertCookie(result.refreshCookie, issuedCookie())
        verify(exactly = 1) {
            refreshSessionStore.replace(
                RefreshSession(
                    tokenHash = "login-refresh-hash",
                    tokenId = issued.refreshToken.tokenId,
                    memberId = MEMBER_ID,
                    expiresAt = issued.refreshToken.expiresAt,
                    familyId = issued.familyId,
                    generation = 0L,
                ),
                now,
            )
        }
    }

    @Test
    fun `로그인 인증 실패는 토큰을 발급하지 않는다`() {
        every { memberService.authenticate(EMAIL, PASSWORD) } returns null

        assertInvalid(ApplicationError.AUTHENTICATION_FAILED) {
            facade.login(AuthCriteria.Login(EMAIL, PASSWORD))
        }

        verify(exactly = 0) { tokenIssuer.issue(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshSessionStore.replace(any(), any()) }
    }

    @Test
    fun `refresh token이 없으면 검증을 시도하지 않는다`() {
        assertInvalid(ApplicationError.INVALID_REFRESH_TOKEN) {
            facade.refresh(AuthCriteria.Refresh(null))
        }

        verify(exactly = 0) { tokenIssuer.verifyRefresh(any(), any()) }
        verify(exactly = 0) { tokenIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh 검증 실패는 새 토큰을 발급하지 않는다`() {
        every { tokenIssuer.verifyRefresh(RAW_REFRESH, now) } returns null

        assertInvalid(ApplicationError.INVALID_REFRESH_TOKEN) {
            facade.refresh(AuthCriteria.Refresh(RAW_REFRESH))
        }

        verify(exactly = 0) { tokenIssuer.issue(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshSessionStore.rotate(any(), any(), any()) }
    }

    @Test
    fun `refresh 회전 실패는 새 쿠키를 노출하지 않는다`() {
        val verified = verifiedRefresh()
        val replacement = issuedPair("replacement", generation = verified.generation + 1)
        stubRefreshRotation(verified, replacement, RefreshRotationResult.REUSED_AND_FAMILY_REVOKED)

        assertInvalid(ApplicationError.INVALID_REFRESH_TOKEN) {
            facade.refresh(AuthCriteria.Refresh(RAW_REFRESH))
        }

        verify(exactly = 0) { refreshCookiePolicy.issue(any(), any(), any()) }
    }

    @Test
    fun `refresh 성공은 기존 proof로 세션을 회전하고 다음 세대 토큰을 반환한다`() {
        val verified = verifiedRefresh()
        val replacement = issuedPair("replacement", generation = verified.generation + 1)
        val proof = slot<RefreshSessionProof>()
        val session = slot<RefreshSession>()
        every { tokenIssuer.verifyRefresh(RAW_REFRESH, now) } returns verified
        every {
            tokenIssuer.issue(verified.subject, verified.familyId, verified.generation + 1, now)
        } returns replacement
        every { refreshTokenHasher.hash(RAW_REFRESH) } returns "old-refresh-hash"
        every { refreshTokenHasher.hash(replacement.refreshToken.value) } returns "replacement-refresh-hash"
        every {
            refreshSessionStore.rotate(capture(proof), capture(session), now)
        } returns RefreshRotationResult.ROTATED
        every {
            refreshCookiePolicy.issue(replacement.refreshToken.value, replacement.refreshToken.expiresAt, now)
        } returns issuedCookie()

        val result = facade.refresh(AuthCriteria.Refresh(RAW_REFRESH))

        assertThat(result.accessToken).isEqualTo(replacement.accessToken.value)
        assertCookie(result.refreshCookie, issuedCookie())
        assertThat(proof.captured).isEqualTo(
            RefreshSessionProof(
                tokenHash = "old-refresh-hash",
                tokenId = verified.tokenId,
                memberId = MEMBER_ID,
                familyId = verified.familyId,
                generation = verified.generation,
            ),
        )
        assertThat(session.captured).isEqualTo(
            RefreshSession(
                tokenHash = "replacement-refresh-hash",
                tokenId = replacement.refreshToken.tokenId,
                memberId = MEMBER_ID,
                expiresAt = replacement.refreshToken.expiresAt,
                familyId = replacement.familyId,
                generation = replacement.generation,
            ),
        )
    }

    @Test
    fun `logout token이 없으면 세션을 건드리지 않고 만료 쿠키를 반환한다`() {
        every { refreshCookiePolicy.expire() } returns expiredCookie()

        val result = facade.logout(AuthCriteria.Logout(null))

        assertCookie(result.refreshCookie, expiredCookie())
        verify(exactly = 0) { tokenIssuer.verifyRefresh(any(), any()) }
        verify(exactly = 0) { refreshSessionStore.revoke(any(), any()) }
    }

    @Test
    fun `logout token 검증 실패는 세션을 폐기하지 않는다`() {
        every { tokenIssuer.verifyRefresh(RAW_REFRESH, now) } returns null
        every { refreshCookiePolicy.expire() } returns expiredCookie()

        val result = facade.logout(AuthCriteria.Logout(RAW_REFRESH))

        assertCookie(result.refreshCookie, expiredCookie())
        verify(exactly = 0) { refreshTokenHasher.hash(any()) }
        verify(exactly = 0) { refreshSessionStore.revoke(any(), any()) }
    }

    @Test
    fun `logout의 유효한 token은 proof를 폐기하고 만료 쿠키를 반환한다`() {
        val verified = verifiedRefresh()
        val proof = slot<RefreshSessionProof>()
        every { tokenIssuer.verifyRefresh(RAW_REFRESH, now) } returns verified
        every { refreshTokenHasher.hash(RAW_REFRESH) } returns "logout-refresh-hash"
        every { refreshSessionStore.revoke(capture(proof), now) } returns true
        every { refreshCookiePolicy.expire() } returns expiredCookie()

        val result = facade.logout(AuthCriteria.Logout(RAW_REFRESH))

        assertCookie(result.refreshCookie, expiredCookie())
        assertThat(proof.captured.tokenHash).isEqualTo("logout-refresh-hash")
        assertThat(proof.captured.tokenId).isEqualTo(verified.tokenId)
        assertThat(proof.captured.memberId).isEqualTo(MEMBER_ID)
        assertThat(proof.captured.familyId).isEqualTo(verified.familyId)
        assertThat(proof.captured.generation).isEqualTo(verified.generation)
    }

    private fun stubRefreshRotation(
        verified: VerifiedRefreshToken,
        replacement: IssuedTokenPair,
        rotationResult: RefreshRotationResult,
    ) {
        every { tokenIssuer.verifyRefresh(RAW_REFRESH, now) } returns verified
        every {
            tokenIssuer.issue(verified.subject, verified.familyId, verified.generation + 1, now)
        } returns replacement
        every { refreshTokenHasher.hash(RAW_REFRESH) } returns "old-refresh-hash"
        every { refreshTokenHasher.hash(replacement.refreshToken.value) } returns "replacement-refresh-hash"
        every { refreshSessionStore.rotate(any(), any(), now) } returns rotationResult
    }

    private fun member(): Member = mockk {
        every { id } returns MEMBER_ID
        every { email } returns EMAIL
        every { nickname } returns NICKNAME
    }

    private fun issuedPair(prefix: String, generation: Long): IssuedTokenPair = IssuedTokenPair(
        accessToken = IssuedToken("$prefix-access", "$prefix-access-id", now.plusSeconds(300)),
        refreshToken = IssuedToken("$prefix-refresh", "$prefix-refresh-id", now.plusSeconds(3_600)),
        familyId = "family-1",
        generation = generation,
    )

    private fun verifiedRefresh(): VerifiedRefreshToken = VerifiedRefreshToken(
        subject = TokenSubject(MEMBER_ID, EMAIL),
        tokenId = "old-refresh-id",
        expiresAt = now.plusSeconds(1_800),
        familyId = "family-1",
        generation = 2L,
    )

    private fun issuedCookie(): RefreshCookie = RefreshCookie(
        name = "refresh_token",
        value = "cookie-refresh",
        path = "/api/auth",
        domain = "example.com",
        secure = true,
        httpOnly = true,
        sameSite = "Strict",
        maxAge = Duration.ofHours(1),
    )

    private fun expiredCookie(): RefreshCookie = issuedCookie().copy(value = "", maxAge = Duration.ZERO)

    private fun assertCookie(actual: AuthResult.Cookie, expected: RefreshCookie) {
        assertThat(actual.name).isEqualTo(expected.name)
        assertThat(actual.value).isEqualTo(expected.value)
        assertThat(actual.path).isEqualTo(expected.path)
        assertThat(actual.domain).isEqualTo(expected.domain)
        assertThat(actual.secure).isEqualTo(expected.secure)
        assertThat(actual.httpOnly).isEqualTo(expected.httpOnly)
        assertThat(actual.sameSite).isEqualTo(expected.sameSite)
        assertThat(actual.maxAgeSeconds).isEqualTo(expected.maxAge.seconds)
    }

    private fun assertInvalid(expected: ApplicationError, block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", expected)
    }

    private companion object {
        const val MEMBER_ID = 7L
        const val EMAIL = "member@example.com"
        const val PASSWORD = "password123"
        const val NICKNAME = "member"
        const val RAW_REFRESH = "old-refresh"
    }
}
