package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler

class LoginFailureHandler(
    private val objectMapper: ObjectMapper,
) : AuthenticationFailureHandler {

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"

        val body = if (exception is LoginValidationException) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            mapOf(
                "code" to "LOGIN_VALIDATION_FAILED",
                "message" to "로그인 입력값이 올바르지 않습니다.",
                "errors" to exception.errors,
            )
        } else {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            mapOf(
                "code" to "AUTHENTICATION_FAILED",
                "message" to "이메일 또는 비밀번호가 올바르지 않습니다.",
            )
        }
        objectMapper.writeValue(response.writer, body)
    }
}
