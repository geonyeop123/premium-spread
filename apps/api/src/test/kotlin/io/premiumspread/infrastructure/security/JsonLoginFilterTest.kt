package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class JsonLoginFilterTest {

    private val objectMapper = ObjectMapper()
    private lateinit var authenticationManager: AuthenticationManager
    private lateinit var filter: JsonLoginFilter

    @BeforeEach
    fun setUp() {
        authenticationManager = mockk()
        filter = JsonLoginFilter(objectMapper, authenticationManager)
    }

    private fun createRequest(body: Map<String, String>): MockHttpServletRequest {
        val request = MockHttpServletRequest("POST", "/api/v1/members/login")
        request.contentType = "application/json"
        request.setContent(objectMapper.writeValueAsBytes(body))
        return request
    }

    @Nested
    inner class Validation {

        @Test
        fun `이메일이 빈 문자열이면 LoginValidationException이 발생한다`() {
            val request = createRequest(mapOf("email" to "", "password" to "password123"))

            assertThatThrownBy {
                filter.attemptAuthentication(request, MockHttpServletResponse())
            }.isInstanceOf(LoginValidationException::class.java)
                .satisfies({ ex ->
                    val errors = (ex as LoginValidationException).errors
                    assertThat(errors).contains("이메일은 필수입니다.")
                })
        }

        @Test
        fun `이메일 형식이 올바르지 않으면 LoginValidationException이 발생한다`() {
            val request = createRequest(mapOf("email" to "not-an-email", "password" to "password123"))

            assertThatThrownBy {
                filter.attemptAuthentication(request, MockHttpServletResponse())
            }.isInstanceOf(LoginValidationException::class.java)
                .satisfies({ ex ->
                    val errors = (ex as LoginValidationException).errors
                    assertThat(errors).contains("올바른 이메일 형식이 아닙니다.")
                })
        }

        @Test
        fun `비밀번호가 빈 문자열이면 LoginValidationException이 발생한다`() {
            val request = createRequest(mapOf("email" to "test@example.com", "password" to ""))

            assertThatThrownBy {
                filter.attemptAuthentication(request, MockHttpServletResponse())
            }.isInstanceOf(LoginValidationException::class.java)
                .satisfies({ ex ->
                    val errors = (ex as LoginValidationException).errors
                    assertThat(errors).contains("비밀번호는 필수입니다.")
                })
        }

        @Test
        fun `비밀번호가 8자 미만이면 LoginValidationException이 발생한다`() {
            val request = createRequest(mapOf("email" to "test@example.com", "password" to "short"))

            assertThatThrownBy {
                filter.attemptAuthentication(request, MockHttpServletResponse())
            }.isInstanceOf(LoginValidationException::class.java)
                .satisfies({ ex ->
                    val errors = (ex as LoginValidationException).errors
                    assertThat(errors).contains("비밀번호는 8~100자여야 합니다.")
                })
        }

        @Test
        fun `이메일과 비밀번호가 모두 빈 문자열이면 복수 에러가 반환된다`() {
            val request = createRequest(mapOf("email" to "", "password" to ""))

            assertThatThrownBy {
                filter.attemptAuthentication(request, MockHttpServletResponse())
            }.isInstanceOf(LoginValidationException::class.java)
                .satisfies({ ex ->
                    val errors = (ex as LoginValidationException).errors
                    assertThat(errors).hasSize(2)
                    assertThat(errors).contains("이메일은 필수입니다.", "비밀번호는 필수입니다.")
                })
        }

        @Test
        fun `유효한 입력이면 AuthenticationManager에 위임한다`() {
            val request = createRequest(mapOf("email" to "test@example.com", "password" to "password123"))
            val expectedAuth = UsernamePasswordAuthenticationToken("test@example.com", "password123")

            every { authenticationManager.authenticate(any()) } returns expectedAuth

            val result = filter.attemptAuthentication(request, MockHttpServletResponse())

            assertThat(result).isNotNull
            assertThat(result.principal).isEqualTo("test@example.com")
        }
    }
}
