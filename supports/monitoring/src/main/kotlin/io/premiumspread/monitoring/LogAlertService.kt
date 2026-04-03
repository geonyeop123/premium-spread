package io.premiumspread.monitoring

import org.slf4j.LoggerFactory

/**
 * 로그 기반 알림 서비스 (기본 구현)
 *
 * Slack Webhook URL이 설정되지 않은 환경(local, test)에서 사용된다.
 */
class LogAlertService : AlertService {

    private val logger = LoggerFactory.getLogger(LogAlertService::class.java)

    override fun sendAlert(message: String, severity: AlertService.Severity) {
        logger.warn("[ALERT][{}] {}", severity, message)
    }
}
