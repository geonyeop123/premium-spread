package io.premiumspread.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * 민감 정보 마스킹 필터
 *
 * API Key, Secret, Password 등을 로그에서 마스킹
 */
class LogMaskingFilter : Filter<ILoggingEvent>() {

    companion object {
        private val SENSITIVE_PATTERNS = listOf(
            // JSON 형식: "api_key": "value" or "apiKey": "value"
            Regex("""(api[_-]?key)(["\s:]+["\s]?)([^"\s,}]+)""", RegexOption.IGNORE_CASE),
            Regex("""(secret[_-]?key)(["\s:]+["\s]?)([^"\s,}]+)""", RegexOption.IGNORE_CASE),
            Regex("""(password)(["\s:]+["\s]?)([^"\s,}]+)""", RegexOption.IGNORE_CASE),
            Regex("""(authorization)(["\s:]+["\s]?)([^"\s,}]+)""", RegexOption.IGNORE_CASE),
            Regex("""(bearer\s+)(\S+)""", RegexOption.IGNORE_CASE),
        )

        fun mask(message: String): String {
            var masked = message
            SENSITIVE_PATTERNS.forEach { pattern ->
                masked = pattern.replace(masked) { match ->
                    when (match.groupValues.size) {
                        4 -> "${match.groupValues[1]}${match.groupValues[2]}***MASKED***"
                        3 -> "${match.groupValues[1]}***MASKED***"
                        else -> "***MASKED***"
                    }
                }
            }
            return masked
        }
    }

    override fun decide(event: ILoggingEvent): FilterReply = FilterReply.NEUTRAL
}
