package io.premiumspread.email

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties

class JavaMailEmailSenderTest {

    private val mailSender = mockk<JavaMailSender>(relaxed = true)
    private val sut = JavaMailEmailSender(mailSender, from = "alert@example.com")

    @Test
    fun `이메일 발송 시 SimpleMailMessage에 from, to, subject, text를 채워 호출한다`() {
        val captured = slot<SimpleMailMessage>()
        every { mailSender.send(capture(captured)) } returns Unit

        sut.send(EmailMessage(to = "user@example.com", subject = "제목", text = "본문"))

        val msg = captured.captured
        assertThat(msg.from).isEqualTo("alert@example.com")
        assertThat(msg.to).containsExactly("user@example.com")
        assertThat(msg.subject).isEqualTo("제목")
        assertThat(msg.text).isEqualTo("본문")
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `JavaMailSender가 예외를 던지면 EmailDeliveryException으로 wrap하여 던진다`() {
        every { mailSender.send(any<SimpleMailMessage>()) } throws RuntimeException("SMTP down")

        assertThatThrownBy {
            sut.send(EmailMessage(to = "user@example.com", subject = "s", text = "t"))
        }
            .isInstanceOf(EmailDeliveryException::class.java)
            .hasMessage("이메일 발송 실패")
            .hasMessageNotContaining("user@example.com")
    }

    @Test
    fun `deliveryId를 제공하면 고정 Message-ID를 MIME 헤더에 추가한다`() {
        val mimeMessage = MimeMessage(Session.getInstance(Properties()))
        val captured = slot<MimeMessage>()
        every { mailSender.createMimeMessage() } returns mimeMessage
        every { mailSender.send(capture(captured)) } returns Unit

        sut.send(
            EmailMessage(
                to = "user@example.com",
                subject = "제목",
                text = "본문",
                deliveryId = "35b56c5a-7f72-48ac-8b98-cfb34b1cd229",
            ),
        )

        // JavaMailSender가 transport 직전에 saveChanges()를 호출해도 수동 Message-ID가 유지되어야 한다.
        val sentMessage = captured.captured
        sentMessage.saveChanges()
        assertThat(sentMessage.getHeader("Message-ID", null))
            .isEqualTo("<35b56c5a-7f72-48ac-8b98-cfb34b1cd229@premium-spread.local>")
        assertThat(sentMessage.from.single().toString()).isEqualTo("alert@example.com")
        assertThat(sentMessage.allRecipients.single().toString()).isEqualTo("user@example.com")
        assertThat(sentMessage.subject).isEqualTo("제목")
        verify(exactly = 1) { mailSender.send(sentMessage) }
        verify(exactly = 0) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `deliveryId에 헤더 삽입 문자가 있으면 SMTP를 호출하지 않는다`() {
        assertThatThrownBy {
            sut.send(
                EmailMessage(
                    to = "user@example.com",
                    subject = "s",
                    text = "t",
                    deliveryId = "delivery-id\r\nBcc: attacker@example.com",
                ),
            )
        }
            .isInstanceOf(EmailDeliveryException::class.java)
            .hasMessage("이메일 발송 실패")
            .hasMessageNotContaining("attacker@example.com")

        verify(exactly = 0) { mailSender.createMimeMessage() }
    }
}
