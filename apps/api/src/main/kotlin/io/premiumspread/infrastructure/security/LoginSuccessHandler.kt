package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

class LoginSuccessHandler(
    private val objectMapper: ObjectMapper,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val userDetails = authentication.principal as CustomUserDetails
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val body = mapOf(
            "id" to userDetails.memberId,
            "email" to userDetails.email,
            "nickname" to userDetails.nickname,
        )
        objectMapper.writeValue(response.writer, body)
    }
}
