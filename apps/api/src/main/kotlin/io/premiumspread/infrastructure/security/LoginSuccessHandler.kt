package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

class LoginSuccessHandler(
    private val objectMapper: ObjectMapper,
    private val jwtTokenProvider: JwtTokenProvider,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val userDetails = authentication.principal as CustomUserDetails
        val accessToken = jwtTokenProvider.generateAccessToken(userDetails.memberId, userDetails.email)
        val refreshToken = jwtTokenProvider.generateRefreshToken(userDetails.memberId, userDetails.email)

        val refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(true)
            .path("/")
            .maxAge(jwtTokenProvider.getRefreshTokenExpirySeconds())
            .sameSite("Strict")
            .build()

        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString())

        val body = mapOf(
            "accessToken" to accessToken,
            "id" to userDetails.memberId,
            "email" to userDetails.email,
            "nickname" to userDetails.nickname,
        )
        objectMapper.writeValue(response.writer, body)
    }

    companion object {
        const val REFRESH_TOKEN_COOKIE = "refresh_token"
    }
}
