package io.premiumspread.infrastructure.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockHttpServletRequest

class PublicEndpointPolicyTest {
    @Test
    fun `premium과 ticker는 GET만 공개한다`() {
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.GET, "/api/v1/premiums/current/BTC")).isTrue()
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.POST, "/api/v1/premiums/calculate/BTC")).isFalse()
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.GET, "/api/v1/tickers/BTC")).isTrue()
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.POST, "/api/v1/tickers")).isFalse()
    }

    @Test
    fun `설정된 Swagger 진입 경로는 GET만 공개한다`() {
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.GET, "/swagger-ui.html")).isTrue()
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.POST, "/swagger-ui.html")).isFalse()
    }

    @Test
    fun `내부 management Prometheus scrape는 GET만 허용한다`() {
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.GET, "/actuator/prometheus")).isTrue()
        assertThat(PublicEndpointPolicy.isPublic(HttpMethod.POST, "/actuator/prometheus")).isFalse()
    }

    @Test
    fun `Security RequestMatcher도 HTTP method와 경로를 함께 검증한다`() {
        val endpoint = PublicEndpoint(HttpMethod.GET, "/api/v1/premiums/**")

        assertThat(endpoint.requestMatcher().matches(request("GET", "/api/v1/premiums/current/BTC"))).isTrue()
        assertThat(endpoint.requestMatcher().matches(request("POST", "/api/v1/premiums/current/BTC"))).isFalse()
        assertThat(endpoint.requestMatcher().matches(request("GET", "/api/v1/tickers/BTC"))).isFalse()
    }

    private fun request(method: String, servletPath: String) = MockHttpServletRequest(method, servletPath).apply {
        this.servletPath = servletPath
    }
}
