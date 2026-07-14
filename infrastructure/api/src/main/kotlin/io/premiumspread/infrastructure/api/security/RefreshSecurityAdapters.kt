package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.RefreshCookie
import io.premiumspread.domain.auth.RefreshCookiePolicy
import io.premiumspread.domain.auth.RefreshTokenHasher
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacRefreshTokenHasher(
    properties: RefreshProperties,
) : RefreshTokenHasher {
    private val key = SecretKeySpec(properties.hmacKey.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)

    override fun hash(rawToken: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(rawToken.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

class RefreshCookiePolicyAdapter(
    private val properties: CookieProperties,
) : RefreshCookiePolicy {
    override fun issue(token: String, expiresAt: Instant, issuedAt: Instant): RefreshCookie = RefreshCookie(
        name = properties.name,
        value = token,
        path = properties.path,
        domain = properties.domain,
        secure = properties.secure,
        httpOnly = properties.httpOnly,
        sameSite = properties.sameSite,
        maxAge = Duration.between(issuedAt, expiresAt).coerceAtLeast(Duration.ZERO),
    )

    override fun expire(): RefreshCookie = RefreshCookie(
        name = properties.name,
        value = "",
        path = properties.path,
        domain = properties.domain,
        secure = properties.secure,
        httpOnly = properties.httpOnly,
        sameSite = properties.sameSite,
        maxAge = Duration.ZERO,
    )
}
