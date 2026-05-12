package io.premiumspread.email

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

class JavaMailEmailSender(
    private val mailSender: JavaMailSender,
    private val from: String,
) : EmailSender {

    override fun send(message: EmailMessage) {
        try {
            val mail = SimpleMailMessage().apply {
                from = this@JavaMailEmailSender.from
                setTo(message.to)
                subject = message.subject
                text = message.text
            }
            mailSender.send(mail)
        } catch (e: Exception) {
            throw EmailDeliveryException("이메일 발송 실패 to=${message.to}", e)
        }
    }
}
