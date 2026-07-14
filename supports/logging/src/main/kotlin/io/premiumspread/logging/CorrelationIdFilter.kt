package io.premiumspread.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

internal object CorrelationIdSupport {
    const val REQUEST_ID_ATTR = "io.premiumspread.logging.correlationRequestId"
    private val SAFE_REQUEST_ID = Regex("[A-Za-z0-9._-]{1,64}")

    fun resolve(request: HttpServletRequest): String =
        (request.getAttribute(REQUEST_ID_ATTR) as? String)
            ?: request.getHeader(RequestLoggingInterceptor.REQUEST_ID_HEADER)
                ?.trim()
                ?.takeIf(SAFE_REQUEST_ID::matches)
            ?: UUID.randomUUID().toString().replace("-", "")
}

/** MVC/security 성공 여부와 무관하게 모든 servlet 응답에 correlation ID를 제공한다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val previous = MDC.getCopyOfContextMap()
        val requestId = CorrelationIdSupport.resolve(request)
        request.setAttribute(CorrelationIdSupport.REQUEST_ID_ATTR, requestId)
        response.setHeader(RequestLoggingInterceptor.REQUEST_ID_HEADER, requestId)
        MDC.put(RequestLoggingInterceptor.MDC_REQUEST_ID, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MdcContext.restore(previous)
        }
    }
}
