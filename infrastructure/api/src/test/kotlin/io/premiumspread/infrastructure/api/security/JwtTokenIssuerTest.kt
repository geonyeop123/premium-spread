package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.TokenSubject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant

class JwtTokenIssuerTest {
    private val now = Instant.parse("2026-07-14T03:00:00Z")
    private val properties = JwtProperties(
        secretKey = "test-secret-key-must-be-at-least-32-bytes-long!!",
        issuer = "premium-spread",
        audience = "premium-spread-api",
        accessTokenExpiryMs = 1_800_000,
        refreshTokenExpiryMs = 604_800_000,
        clockSkewSeconds = 30,
    )
    private val issuer = JwtTokenIssuer(properties)

    @Test
    fun `access와 refresh는 서로 다른 jti를 갖고 token type을 교차 사용할 수 없다`() {
        val pair = issuer.issue(TokenSubject(1L, "member@example.com"), "family", 0, now)

        assertThat(pair.accessToken.tokenId).isNotEqualTo(pair.refreshToken.tokenId)
        assertThat(pair.accessToken.expiresAt).isEqualTo(now.plusMillis(properties.accessTokenExpiryMs))
        assertThat(pair.refreshToken.expiresAt).isEqualTo(now.plusMillis(properties.refreshTokenExpiryMs))
        assertThat(issuer.verifyAccess(pair.accessToken.value, now)).isNotNull
        assertThat(issuer.verifyRefresh(pair.accessToken.value, now)).isNull()
        val refresh = requireNotNull(issuer.verifyRefresh(pair.refreshToken.value, now))
        assertThat(refresh.familyId).isEqualTo("family")
        assertThat(refresh.generation).isZero()
    }

    @Test
    fun `issuer 또는 audience가 다른 token은 거부한다`() {
        val subject = TokenSubject(1L, "member@example.com")
        val otherIssuerToken = JwtTokenIssuer(properties.copy(issuer = "other"))
            .issue(subject, "family", 0, now).accessToken.value
        val otherAudienceToken = JwtTokenIssuer(properties.copy(audience = "other"))
            .issue(subject, "family", 0, now).accessToken.value

        assertThat(issuer.verifyAccess(otherIssuerToken, now)).isNull()
        assertThat(issuer.verifyAccess(otherAudienceToken, now)).isNull()
    }

    @Test
    fun `만료 clock skew 이내는 허용하고 초과하면 거부한다`() {
        val token = issuer.issue(TokenSubject(1L, "member@example.com"), "family", 0, now).accessToken

        assertThat(issuer.verifyAccess(token.value, token.expiresAt.plusSeconds(29))).isNotNull
        assertThat(issuer.verifyAccess(token.value, token.expiresAt.plusSeconds(31))).isNull()
    }

    @Test
    fun `refresh TTL은 access TTL보다 길어야 한다`() {
        assertThatIllegalArgumentException().isThrownBy {
            properties.copy(refreshTokenExpiryMs = properties.accessTokenExpiryMs)
        }
    }
}
