package io.premiumspread.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate

/**
 * Slack Webhook 기반 알림 서비스
 *
 * alert.slack.webhook-url 설정이 존재할 때 활성화된다.
 */
class SlackAlertService(
    private val webhookUrl: String,
    private val objectMapper: ObjectMapper,
) : AlertService {

    private val logger = LoggerFactory.getLogger(SlackAlertService::class.java)
    private val restTemplate = RestTemplate()

    override fun sendAlert(message: String, severity: AlertService.Severity) {
        logger.warn("[ALERT][{}] {}", severity, message)

        try {
            val payload = buildPayload(message, severity)
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            }
            val request = HttpEntity(payload, headers)
            restTemplate.postForEntity(webhookUrl, request, String::class.java)
        } catch (e: Exception) {
            logger.error("Slack 알림 전송 실패: {}", e.message, e)
        }
    }

    private fun buildPayload(message: String, severity: AlertService.Severity): String {
        val icon = when (severity) {
            AlertService.Severity.CRITICAL -> ":rotating_light:"
            AlertService.Severity.WARNING -> ":warning:"
            AlertService.Severity.INFO -> ":information_source:"
        }
        val payload = mapOf(
            "text" to "$icon *[$severity]* $message",
        )
        return objectMapper.writeValueAsString(payload)
    }
}
