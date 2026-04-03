package io.premiumspread.interfaces.api.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.infrastructure.security.JwtTokenProvider
import io.premiumspread.infrastructure.security.JwtValidationResult
import io.premiumspread.infrastructure.security.LoginSuccessHandler
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = LoginSuccessHandler.REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any>> {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("code" to "INVALID_TOKEN", "message" to "리프레시 토큰이 없습니다."))
        }

        val result = jwtTokenProvider.validateAndGetClaims(refreshToken)
        if (result !is JwtValidationResult.Valid || result.tokenType != JwtTokenProvider.TOKEN_TYPE_REFRESH) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("code" to "INVALID_TOKEN", "message" to "유효하지 않은 리프레시 토큰입니다."))
        }

        val newAccessToken = jwtTokenProvider.generateAccessToken(result.memberId, result.email)
        val newRefreshToken = jwtTokenProvider.generateRefreshToken(result.memberId, result.email)

        val refreshCookie = ResponseCookie.from(LoginSuccessHandler.REFRESH_TOKEN_COOKIE, newRefreshToken)
            .httpOnly(true)
            .secure(true)
            .path("/")
            .maxAge(jwtTokenProvider.getRefreshTokenExpirySeconds())
            .sameSite("Strict")
            .build()

        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString())

        return ResponseEntity.ok(mapOf("accessToken" to newAccessToken as Any))
    }

    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): ResponseEntity<Map<String, String>> {
        val expiredCookie = ResponseCookie.from(LoginSuccessHandler.REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(true)
            .path("/")
            .maxAge(0)
            .sameSite("Strict")
            .build()

        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie.toString())

        return ResponseEntity.ok(mapOf("message" to "로그아웃 되었습니다."))
    }
}
