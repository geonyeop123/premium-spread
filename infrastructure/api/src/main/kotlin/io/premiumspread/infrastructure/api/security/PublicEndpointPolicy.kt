package io.premiumspread.infrastructure.api.security

import org.springframework.http.HttpMethod
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.util.AntPathMatcher

data class PublicEndpoint(
    val method: HttpMethod,
    val pattern: String,
) {
    fun requestMatcher(): AntPathRequestMatcher = AntPathRequestMatcher(pattern, method.name())
}

object PublicEndpointPolicy {
    val endpoints: List<PublicEndpoint> = listOf(
        PublicEndpoint(HttpMethod.POST, "/api/v1/members/register"),
        PublicEndpoint(HttpMethod.POST, "/api/v1/members/login"),
        PublicEndpoint(HttpMethod.POST, "/api/v1/auth/refresh"),
        PublicEndpoint(HttpMethod.POST, "/api/v1/auth/logout"),
        PublicEndpoint(HttpMethod.GET, "/api/v1/premiums/**"),
        PublicEndpoint(HttpMethod.GET, "/api/v1/tickers/**"),
        PublicEndpoint(HttpMethod.GET, "/actuator/health/liveness"),
        PublicEndpoint(HttpMethod.GET, "/actuator/health/readiness"),
        // Management port는 Docker 내부/host loopback으로 제한되며 Prometheus가 인증 없이 scrape한다.
        PublicEndpoint(HttpMethod.GET, "/actuator/prometheus"),
        PublicEndpoint(HttpMethod.GET, "/swagger-ui/**"),
        PublicEndpoint(HttpMethod.GET, "/swagger-ui.html"),
        PublicEndpoint(HttpMethod.GET, "/v3/api-docs/**"),
    )

    private val pathMatcher = AntPathMatcher()

    fun isPublic(method: HttpMethod, path: String): Boolean = endpoints.any {
        it.method == method && pathMatcher.match(it.pattern, path)
    }
}
