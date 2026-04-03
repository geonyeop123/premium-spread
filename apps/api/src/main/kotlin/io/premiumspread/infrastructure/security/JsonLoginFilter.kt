package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
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
        validate(body)
        val token = UsernamePasswordAuthenticationToken(body.email, body.password)
        return authenticationManager.authenticate(token)
    }

    private fun validate(request: LoginRequest) {
        val errors = mutableListOf<String>()
        if (request.email.isBlank()) {
            errors.add("이메일은 필수입니다.")
        } else if (!EMAIL_REGEX.matches(request.email)) {
            errors.add("올바른 이메일 형식이 아닙니다.")
        } else if (request.email.length > MAX_EMAIL_LENGTH) {
            errors.add("이메일은 ${MAX_EMAIL_LENGTH}자 이하여야 합니다.")
        }
        if (request.password.isBlank()) {
            errors.add("비밀번호는 필수입니다.")
        } else if (request.password.length < MIN_PASSWORD_LENGTH || request.password.length > MAX_PASSWORD_LENGTH) {
            errors.add("비밀번호는 ${MIN_PASSWORD_LENGTH}~${MAX_PASSWORD_LENGTH}자여야 합니다.")
        }
        if (errors.isNotEmpty()) {
            throw LoginValidationException(errors)
        }
    }

    private data class LoginRequest(
        val email: String = "",
        val password: String = "",
    )

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private const val MAX_EMAIL_LENGTH = 254
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_PASSWORD_LENGTH = 100
    }
}

class LoginValidationException(val errors: List<String>) :
    AuthenticationException(errors.joinToString(", "))
