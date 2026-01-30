package io.premiumspread.monitoring

import org.slf4j.LoggerFactory

/**
 * 알람 서비스
 *
 * TODO: Slack, PagerDuty 등 외부 알람 시스템 연동
 */
class AlertService {

    private val logger = LoggerFactory.getLogger(AlertService::class.java)

    fun sendAlert(message: String, severity: Severity = Severity.WARNING) {
        logger.warn("[ALERT][{}] {}", severity, message)
        // TODO: 외부 알람 시스템 연동
        // - Slack Webhook
        // - PagerDuty API
        // - Email
    }

    fun sendCriticalAlert(message: String) {
        sendAlert(message, Severity.CRITICAL)
    }

    enum class Severity {
        INFO,
        WARNING,
        CRITICAL,
    }
}
