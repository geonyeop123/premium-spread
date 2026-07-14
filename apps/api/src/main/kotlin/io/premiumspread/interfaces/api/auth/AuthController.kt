package io.premiumspread.interfaces.api.auth

import io.premiumspread.application.auth.AuthCriteria
import io.premiumspread.application.auth.AuthCookieContract
import io.premiumspread.application.auth.AuthFacade
import io.premiumspread.application.auth.AuthResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(
    private val authFacade: AuthFacade,
) {
    @PostMapping("/api/v1/members/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        val result = authFacade.login(AuthCriteria.Login(request.email, request.password))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, result.refreshCookie.toResponseCookie().toString())
            .body(LoginResponse(result.accessToken, result.id, result.email, result.nickname))
    }

    @PostMapping("/api/v1/auth/refresh")
    fun refresh(
        @CookieValue(name = AuthCookieContract.NAME_PLACEHOLDER, required = false) refreshToken: String?,
    ): ResponseEntity<RefreshResponse> {
        val result = authFacade.refresh(AuthCriteria.Refresh(refreshToken))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, result.refreshCookie.toResponseCookie().toString())
            .body(RefreshResponse(result.accessToken))
    }

    @PostMapping("/api/v1/auth/logout")
    fun logout(
        @CookieValue(name = AuthCookieContract.NAME_PLACEHOLDER, required = false) refreshToken: String?,
    ): ResponseEntity<Void> {
        val result = authFacade.logout(AuthCriteria.Logout(refreshToken))
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, result.refreshCookie.toResponseCookie().toString())
            .build()
    }

    data class LoginRequest(
        @field:NotBlank @field:Email @field:Size(max = 254)
        val email: String = "",
        @field:NotBlank @field:Size(min = 8, max = 100)
        val password: String = "",
    )

    data class LoginResponse(
        val accessToken: String,
        val id: Long,
        val email: String,
        val nickname: String,
    )

    data class RefreshResponse(val accessToken: String)

    private fun AuthResult.Cookie.toResponseCookie(): ResponseCookie {
        val builder = ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(secure)
            .path(path)
            .maxAge(maxAgeSeconds)
            .sameSite(sameSite)
        domain?.takeIf(String::isNotBlank)?.let(builder::domain)
        return builder.build()
    }
}
