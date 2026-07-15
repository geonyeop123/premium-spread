package io.premiumspread.email

import jakarta.mail.internet.MimeMessage
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.nio.charset.StandardCharsets

class JavaMailEmailSender(private val mailSender: JavaMailSender, private val from: String) : EmailSender {

    override fun send(message: EmailMessage) {
        try {
            val deliveryId = message.deliveryId
            if (deliveryId == null) {
                sendSimpleMessage(message)
            } else {
                sendDurableMessage(message, deliveryId)
            }
        } catch (e: Exception) {
            // recipient를 예외 메시지에 넣으면 worker의 last_error/log로 PII가 복제될 수 있다.
            throw EmailDeliveryException("이메일 발송 실패", e)
        }
    }

    private fun sendSimpleMessage(message: EmailMessage) {
        val mail = SimpleMailMessage().apply {
            from = this@JavaMailEmailSender.from
            setTo(message.to)
            subject = message.subject
            text = message.text
        }
        mailSender.send(mail)
    }

    private fun sendDurableMessage(message: EmailMessage, deliveryId: String) {
        require(DELIVERY_ID_PATTERN.matches(deliveryId)) {
            "deliveryId must contain only RFC Message-ID safe characters"
        }

        val source = mailSender.createMimeMessage()
        MimeMessageHelper(source, false, StandardCharsets.UTF_8.name()).apply {
            setFrom(this@JavaMailEmailSender.from)
            setTo(message.to)
            setSubject(message.subject)
            setText(message.text, false)
        }
        source.saveChanges()
        val messageId = toSmtpMessageId(deliveryId)
        val mail = StableMessageIdMimeMessage(source, messageId)
        mail.saveChanges()
        mailSender.send(mail)
    }

    private fun toSmtpMessageId(deliveryId: String): String = "<$deliveryId@$MESSAGE_ID_DOMAIN>"

    /** Jakarta Mail이 transport 직전 saveChanges()를 호출해도 stable ID를 다시 설정한다. */
    private class StableMessageIdMimeMessage(source: MimeMessage, private val stableMessageId: String) : MimeMessage(source) {
        override fun updateMessageID() {
            setHeader(MESSAGE_ID_HEADER, stableMessageId)
        }
    }

    private companion object {
        const val MESSAGE_ID_HEADER = "Message-ID"
        const val MESSAGE_ID_DOMAIN = "premium-spread.local"
        val DELIVERY_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
