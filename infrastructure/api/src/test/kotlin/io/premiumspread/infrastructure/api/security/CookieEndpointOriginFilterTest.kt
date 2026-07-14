package io.premiumspread.infrastructure.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CookieEndpointOriginFilterTest {
    private val filter = CookieEndpointOriginFilter(
        CorsProperties(
            allowedOrigins = listOf("https://premium.example.com"),
            allowedMethods = listOf("POST"),
            allowedHeaders = listOf("Content-Type"),
            allowCredentials = true,
        ),
    )

    @Test
    fun `cookie endpoint의 허용되지 않은 Origin은 403이다`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/refresh").apply {
            addHeader("Origin", "https://attacker.example.com")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentAsString).contains("INVALID_ORIGIN")
    }

    @Test
    fun `허용 Origin과 origin 없는 non-browser 요청은 통과한다`() {
        listOf("https://premium.example.com", null).forEach { origin ->
            val request = MockHttpServletRequest("POST", "/api/v1/auth/logout")
            origin?.let { request.addHeader("Origin", it) }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())

            assertThat(response.status).isEqualTo(200)
        }
    }

    @Test
    fun `Origin 없는 cross-site browser 요청은 403이다`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/refresh").apply {
            addHeader("Sec-Fetch-Site", "cross-site")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(403)
    }
}
