package io.premiumspread.email

interface EmailSender {
    /** 발송 실패 시 [EmailDeliveryException]을 던진다. */
    @Throws(EmailDeliveryException::class)
    fun send(message: EmailMessage)
}
