package io.premiumspread.monitoring

/**
 * 알람 서비스 인터페이스
 */
interface AlertService {

    fun sendAlert(message: String, severity: Severity = Severity.WARNING)

    fun sendCriticalAlert(message: String) {
        sendAlert(message, Severity.CRITICAL)
    }

    enum class Severity {
        INFO,
        WARNING,
        CRITICAL,
    }
}
