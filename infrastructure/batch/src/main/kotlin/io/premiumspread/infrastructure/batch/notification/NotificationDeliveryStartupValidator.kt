package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.email.SmtpConnectionProperties
import java.time.Duration

class NotificationDeliveryStartupValidator(smtp: SmtpConnectionProperties, hardSendDeadline: Duration) {
    init {
        smtp.requireWithin(hardSendDeadline)
        val configuredOperationBudget = smtp.connectTimeout
            .plus(smtp.readTimeout)
            .plus(smtp.writeTimeout)
        require(configuredOperationBudget <= hardSendDeadline) {
            "configured SMTP connect/read/write timeout budget must not exceed notification delivery hardSendDeadline"
        }
    }
}
