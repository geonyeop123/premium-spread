package io.premiumspread.domain.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AuthPortsTest {
    @Test
    fun `회전 결과는 동시 요청과 재사용 및 이전 로그인 family를 구분한다`() {
        assertThat(RefreshRotationResult.entries).containsExactly(
            RefreshRotationResult.ROTATED,
            RefreshRotationResult.CONCURRENT_LOSER,
            RefreshRotationResult.REUSED_AND_FAMILY_REVOKED,
            RefreshRotationResult.STALE_LOGIN_FAMILY,
            RefreshRotationResult.SESSION_NOT_FOUND,
            RefreshRotationResult.INVALID_SESSION,
        )
    }

    @Test
    fun `refresh session은 원문 대신 hash와 family generation을 보유한다`() {
        val session = RefreshSession(
            tokenHash = "hmac-sha256",
            tokenId = "jti",
            memberId = 1L,
            expiresAt = Instant.parse("2026-07-21T00:00:00Z"),
            familyId = "family",
            generation = 3L,
        )

        assertThat(session.tokenHash).isEqualTo("hmac-sha256")
        assertThat(session.familyId).isEqualTo("family")
        assertThat(session.generation).isEqualTo(3L)
    }

    @Test
    fun `refresh cookie 이름과 property key는 단일 계약이다`() {
        assertThat(RefreshCookieConfiguration.NAME_PROPERTY).isEqualTo("auth.cookie.name")
        assertThat(RefreshCookieConfiguration.DEFAULT_NAME).isEqualTo("refresh_token")
        assertThat(RefreshCookieConfiguration.NAME_PLACEHOLDER).isEqualTo("\${auth.cookie.name:refresh_token}")
    }
}
