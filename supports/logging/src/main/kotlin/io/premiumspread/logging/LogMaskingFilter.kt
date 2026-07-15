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
        private const val SENSITIVE_KEY_PATTERN =
            "api[_-]?key|secret[_-]?key|password|authorization|" +
                "(?:access|refresh)?[_-]?token|set[_-]?cookie|cookie|email"
        private const val SENSITIVE_VALUE_PATTERN = """(["'\s:=]+["'\s]?)([^"'\s,}]+)"""

        private val SENSITIVE_PATTERNS = listOf(
            Regex("""(bearer\s+)(\S+)""", RegexOption.IGNORE_CASE),
            // JSON, key=value, HTTP header 형식을 모두 같은 정책으로 처리한다.
            Regex(
                "((?:$SENSITIVE_KEY_PATTERN))$SENSITIVE_VALUE_PATTERN",
                RegexOption.IGNORE_CASE,
            ),
            // key 없이 예외 메시지에 포함된 이메일도 노출하지 않는다.
            Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE),
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
