package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PremiumThresholdNotificationListenerTest {

    private val service = mockk<PremiumThresholdNotificationService>(relaxed = true)
    private val sut = PremiumThresholdNotificationListener(service)

    @Test
    fun `이벤트 수신 시 service process를 호출한다`() {
        val event = PremiumUpdatedEvent("BTC", BigDecimal("5.20"))

        sut.on(event)

        verify(exactly = 1) { service.process(event) }
    }

    @Test
    fun `service가 예외를 던져도 외부로 전파하지 않는다`() {
        val event = PremiumUpdatedEvent("BTC", BigDecimal("5.20"))
        every { service.process(any()) } throws RuntimeException("downstream failure")

        assertThatCode { sut.on(event) }.doesNotThrowAnyException()
    }
}
