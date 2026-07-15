package io.premiumspread.infrastructure.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

class CookieEndpointOriginFilter(private val properties: CorsProperties) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || request.requestURI !in COOKIE_ENDPOINTS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val origin = request.getHeader(ORIGIN_HEADER)
        val explicitlyCrossSite = request.getHeader(SEC_FETCH_SITE_HEADER) == CROSS_SITE
        if ((origin != null && origin !in properties.allowedOrigins) || (origin == null && explicitlyCrossSite)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write("{\"code\":\"INVALID_ORIGIN\",\"message\":\"허용되지 않은 요청 출처입니다.\"}")
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        val COOKIE_ENDPOINTS = setOf("/api/v1/auth/refresh", "/api/v1/auth/logout")
        const val ORIGIN_HEADER = "Origin"
        const val SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site"
        const val CROSS_SITE = "cross-site"
    }
}
