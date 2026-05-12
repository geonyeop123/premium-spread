package io.premiumspread.email

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

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
            .hasMessageContaining("user@example.com")
    }
}
