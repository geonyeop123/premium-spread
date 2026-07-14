package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.NotificationSender
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender

class DurableEmailNotificationSender(
    private val emailSender: EmailSender,
) : NotificationSender {
    override fun deliver(delivery: ClaimedNotificationDelivery) {
        emailSender.send(
            EmailMessage(
                to = delivery.recipientEmail,
                subject = delivery.subject,
                text = delivery.payload,
                deliveryId = delivery.deliveryId,
            ),
        )
    }
}
