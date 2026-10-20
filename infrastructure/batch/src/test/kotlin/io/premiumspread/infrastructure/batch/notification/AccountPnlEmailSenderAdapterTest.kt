package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.domain.accountpnl.AccountHedgePosition
import io.premiumspread.domain.accountpnl.AccountNetPnl
import io.premiumspread.domain.accountpnl.AccountPnlMailContent
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import io.premiumspread.domain.accountpnl.ForeignPerpAccountSnapshot
import io.premiumspread.domain.accountpnl.KoreaSpotAccountSnapshot
import io.premiumspread.domain.accountpnl.OutageNotice
import io.premiumspread.domain.accountpnl.PriceLevel
import io.premiumspread.email.JavaMailEmailSender
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * 실제 [JavaMailEmailSender] 의 deliveryId 검증 경로까지 통과하는지 본다. SMTP 연결 직전(`doSend`)만 가로챈다.
 */
class AccountPnlEmailSenderAdapterTest {

    private val transport = RecordingJavaMailSender()
    private val adapter = AccountPnlEmailSenderAdapter(
        JavaMailEmailSender(transport, from = "alert@example.test"),
        recipient = "owner@example.test",
    )
    private val now = Instant.parse("2026-10-20T01:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `실제 eventKey 로 만든 중단·복구 메일이 예외 없이 SMTP 직전까지 간다`() {
        val mails = listOf(
            AccountPnlMailContent.outage(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, null), now, zone),
            AccountPnlMailContent.recovered(now, zone),
        )

        mails.forEach(adapter::send)

        assertThat(transport.sent).hasSize(2)
        assertThat(transport.sent.map { it.getHeader("Message-ID", null) }).containsExactly(
            "<${mails[0].eventKey}@premium-spread.local>",
            "<${mails[1].eventKey}@premium-spread.local>",
        )
        assertThat(transport.sent.map { it.allRecipients.single().toString() }).containsOnly("owner@example.test")
    }

    @Test
    fun `실제 eventKey 로 만든 수익 메일이 예외 없이 SMTP 직전까지 간다`() {
        val pnl = AccountNetPnl.calculate(position(), BigDecimal("1400"), now.minusSeconds(60), now)
        val mail = AccountPnlMailContent.profit(pnl, zone)

        adapter.send(mail)

        assertThat(transport.sent.single().getHeader("Message-ID", null))
            .isEqualTo("<${mail.eventKey}@premium-spread.local>")
    }

    private fun position() = AccountHedgePosition(
        KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.1"),
            averageBuyPrice = BigDecimal("100000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = BigDecimal("4000"),
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("110000000"), BigDecimal("1"))),
            lastPrice = BigDecimal("110000000"),
            bidObservedAt = now,
        ),
        ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.1"),
            entryPrice = BigDecimal("70000"),
            entryCommissionUsdt = BigDecimal("3"),
            fundingUsdt = BigDecimal("1"),
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("70000"), BigDecimal("1"))),
            lastPrice = BigDecimal("70000"),
            askObservedAt = now,
        ),
    )

    private class RecordingJavaMailSender : JavaMailSenderImpl() {
        val sent = mutableListOf<MimeMessage>()

        override fun doSend(mimeMessages: Array<out MimeMessage>, originalMessages: Array<out Any>?) {
            mimeMessages.forEach { it.saveChanges() }
            sent += mimeMessages
        }
    }
}
