package io.premiumspread.application.auth

import io.premiumspread.domain.auth.RefreshCookieConfiguration

object AuthCookieContract {
    const val NAME_PLACEHOLDER = RefreshCookieConfiguration.NAME_PLACEHOLDER
}

object AuthCriteria {
    data class Login(
        val email: String,
        val password: String,
    )

    data class Refresh(val refreshToken: String?)

    data class Logout(val refreshToken: String?)
}

object AuthResult {
    data class Login(
        val accessToken: String,
        val id: Long,
        val email: String,
        val nickname: String,
        val refreshCookie: Cookie,
    )

    data class Refresh(
        val accessToken: String,
        val refreshCookie: Cookie,
    )

    data class Logout(val refreshCookie: Cookie)

    data class Cookie(
        val name: String,
        val value: String,
        val path: String,
        val domain: String?,
        val secure: Boolean,
        val httpOnly: Boolean,
        val sameSite: String,
        val maxAgeSeconds: Long,
    )
}
