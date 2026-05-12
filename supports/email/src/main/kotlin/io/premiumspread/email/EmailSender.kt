package io.premiumspread.email

interface EmailSender {
    fun send(message: EmailMessage)
}
