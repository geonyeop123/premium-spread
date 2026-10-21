package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender

/** 실계정 순손익 메일을 설정된 수신자 한 명에게 보낸다. [AccountPnlMail.eventKey] 가 SMTP Message-ID 식별자가 된다. */
class AccountPnlEmailSenderAdapter(private val emailSender: EmailSender, private val recipient: String) :
    AccountPnlMailSender {
    override fun send(mail: AccountPnlMail) {
        emailSender.send(
            EmailMessage(to = recipient, subject = mail.subject, text = mail.body, deliveryId = mail.eventKey),
        )
    }
}
