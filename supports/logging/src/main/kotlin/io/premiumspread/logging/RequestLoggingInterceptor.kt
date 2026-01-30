package io.premiumspread.logging

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

/**
 * HTTP 요청 로깅 인터셉터
 *
 * MDC에 요청 정보를 추가하고 요청/응답 로깅
 */
class RequestLoggingInterceptor : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val MDC_REQUEST_ID = "requestId"
        const val MDC_REQUEST_URI = "requestUri"
        const val MDC_REQUEST_METHOD = "requestMethod"
        const val MDC_CLIENT_IP = "clientIp"
        private const val START_TIME_ATTR = "startTime"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)

        // MDC 설정
        MDC.put(MDC_REQUEST_ID, requestId)
        MDC.put(MDC_REQUEST_URI, request.requestURI)
        MDC.put(MDC_REQUEST_METHOD, request.method)
        MDC.put(MDC_CLIENT_IP, getClientIp(request))

        // 응답 헤더에 요청 ID 추가
        response.setHeader(REQUEST_ID_HEADER, requestId)

        // 시작 시간 기록
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis())

        log.debug("Request started: {} {}", request.method, request.requestURI)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val startTime = request.getAttribute(START_TIME_ATTR) as? Long
        val duration = startTime?.let { System.currentTimeMillis() - it } ?: 0

        if (ex != null) {
            log.error(
                "Request failed: {} {} - status={}, duration={}ms",
                request.method,
                request.requestURI,
                response.status,
                duration,
                ex,
            )
        } else {
            log.debug(
                "Request completed: {} {} - status={}, duration={}ms",
                request.method,
                request.requestURI,
                response.status,
                duration,
            )
        }

        // MDC 정리
        MDC.remove(MDC_REQUEST_ID)
        MDC.remove(MDC_REQUEST_URI)
        MDC.remove(MDC_REQUEST_METHOD)
        MDC.remove(MDC_CLIENT_IP)
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        return if (xForwardedFor.isNullOrBlank()) {
            request.remoteAddr
        } else {
            xForwardedFor.split(",").first().trim()
        }
    }
}
