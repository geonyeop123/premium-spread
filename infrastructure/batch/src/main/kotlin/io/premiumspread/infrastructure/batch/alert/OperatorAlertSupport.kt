package io.premiumspread.infrastructure.batch.alert

import io.premiumspread.domain.job.AlertSeverity
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.domain.job.OperatorAlertMessage
import java.time.Instant

fun OperatorAlert.sendCritical(
    code: String,
    message: String,
    occurredAt: Instant,
    attributes: Map<String, String> = emptyMap(),
) {
    send(OperatorAlertMessage(code, message, AlertSeverity.CRITICAL, occurredAt, attributes))
}
