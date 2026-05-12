package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.cache.NotificationCooldownStore
import io.premiumspread.email.EmailDeliveryException
import io.premiumspread.email.EmailSender
import io.premiumspread.repository.ActiveSubscriptionReadRepository
import io.premiumspread.repository.ActiveSubscriptionView
import io.premiumspread.repository.ThresholdDirectionView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PremiumThresholdNotificationServiceTest {

    private val readRepo = mockk<ActiveSubscriptionReadRepository>(relaxed = true)
    private val cooldownStore = mockk<NotificationCooldownStore>(relaxed = true)
    private val emailSender = mockk<EmailSender>(relaxed = true)
    private val sut = PremiumThresholdNotificationService(readRepo, cooldownStore, emailSender)

    private fun view(
        id: Long,
        direction: ThresholdDirectionView,
        threshold: String,
        email: String = "u$id@x.com",
    ) = ActiveSubscriptionView(
        id = id,
        memberId = id,
        memberEmail = email,
        memberNickname = "user$id",
        symbol = "BTC",
        direction = direction,
        threshold = BigDecimal(threshold),
    )

    @Test
    fun `매칭 - ABOVE 경계값 5,00 == 5,00 -- match`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        assertThat(v.matches(BigDecimal("5.00"))).isTrue()
    }

    @Test
    fun `매칭 - ABOVE 5,00 vs 4,99 -- no match`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        assertThat(v.matches(BigDecimal("4.99"))).isFalse()
    }

    @Test
    fun `매칭 - BELOW 경계값 -2,00 == -2,00 -- match`() {
        val v = view(1L, ThresholdDirectionView.BELOW, "-2.00")
        assertThat(v.matches(BigDecimal("-2.00"))).isTrue()
    }

    @Test
    fun `매칭 - BELOW -2,00 vs -1,99 -- no match`() {
        val v = view(1L, ThresholdDirectionView.BELOW, "-2.00")
        assertThat(v.matches(BigDecimal("-1.99"))).isFalse()
    }

    @Test
    fun `활성 구독 없음 - send 호출 안 함`() {
        every { readRepo.findActiveBySymbol("BTC") } returns emptyList()

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `매칭 1건 - tryAcquireCooldown 후 send 1회, release 안 함`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns true
        every { emailSender.send(any()) } returns Unit

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { cooldownStore.tryAcquireCooldown(1L) }
        verify(exactly = 1) { emailSender.send(any()) }
        verify(exactly = 0) { cooldownStore.release(any()) }
    }

    @Test
    fun `매칭 안 되는 구독 - acquire 미호출, send 미호출`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("4.99")))

        verify(exactly = 0) { cooldownStore.tryAcquireCooldown(any()) }
        verify(exactly = 0) { emailSender.send(any()) }
    }

    @Test
    fun `이미 reservation 있음 (acquire false) - send 미호출, release 미호출`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns false

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 0) { emailSender.send(any()) }
        verify(exactly = 0) { cooldownStore.release(any()) }
    }

    @Test
    fun `SMTP 실패 시 release 호출되어 cooldown이 해제된다`() {
        val v = view(1L, ThresholdDirectionView.ABOVE, "5.00")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v)
        every { cooldownStore.tryAcquireCooldown(1L) } returns true
        every { emailSender.send(any()) } throws EmailDeliveryException("SMTP down")

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { cooldownStore.tryAcquireCooldown(1L) }
        verify(exactly = 1) { emailSender.send(any()) }
        verify(exactly = 1) { cooldownStore.release(1L) }
    }

    @Test
    fun `여러 구독 중 한 건 발송 실패해도 나머지 구독은 계속 처리된다`() {
        val v1 = view(1L, ThresholdDirectionView.ABOVE, "5.00", email = "fail@x.com")
        val v2 = view(2L, ThresholdDirectionView.ABOVE, "4.00", email = "ok@x.com")
        every { readRepo.findActiveBySymbol("BTC") } returns listOf(v1, v2)
        every { cooldownStore.tryAcquireCooldown(any()) } returns true
        every { emailSender.send(match { it.to == "fail@x.com" }) } throws EmailDeliveryException("SMTP")
        every { emailSender.send(match { it.to == "ok@x.com" }) } returns Unit

        sut.process(PremiumUpdatedEvent("BTC", BigDecimal("5.20")))

        verify(exactly = 1) { emailSender.send(match { it.to == "fail@x.com" }) }
        verify(exactly = 1) { cooldownStore.release(1L) }
        verify(exactly = 1) { emailSender.send(match { it.to == "ok@x.com" }) }
        verify(exactly = 0) { cooldownStore.release(2L) }
    }
}
