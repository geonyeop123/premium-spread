package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

class JsonLoginFilter(
    private val objectMapper: ObjectMapper,
    authenticationManager: AuthenticationManager,
) : AbstractAuthenticationProcessingFilter(
    AntPathRequestMatcher("/api/v1/members/login", "POST"),
    authenticationManager,
) {

    override fun attemptAuthentication(request: HttpServletRequest, response: HttpServletResponse): Authentication {
        val body = objectMapper.readValue(request.inputStream, LoginRequest::class.java)
        val token = UsernamePasswordAuthenticationToken(body.email, body.password)
        return authenticationManager.authenticate(token)
    }

    private data class LoginRequest(
        val email: String = "",
        val password: String = "",
    )
}
