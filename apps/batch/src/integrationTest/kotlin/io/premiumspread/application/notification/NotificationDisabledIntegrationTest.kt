package io.premiumspread.application.notification

import com.ninjasquad.springmockk.SpykBean
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.email.EmailAutoConfiguration
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.BatchInfrastructureAutoConfiguration
import io.premiumspread.infrastructure.common.persistence.jdbc.notification.ActiveSubscriptionReadRepository
import io.premiumspread.infrastructure.common.persistence.jdbc.notification.JdbcNotificationDeliveryRepository
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Instant

@TestPropertySource(properties = ["notification.email.enabled=false"])
class NotificationDisabledIntegrationTest : BatchIntegrationTestBase() {
    @Autowired lateinit var evaluator: PremiumThresholdEvaluator

    @Autowired lateinit var context: ApplicationContext

    @SpykBean
    lateinit var subscriptions: ActiveSubscriptionReadRepository

    @Test
    fun `disabled notification skips subscription DB lookup delivery insert and SMTP path`() {
        insertActiveSubscription()

        evaluator.evaluate(snapshot())

        verify(exactly = 0) { subscriptions.findActiveByPair(any()) }
        assertThat(deliveryCount()).isZero()
        assertThat(context.getBeansOfType(BatchInfrastructureAutoConfiguration::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(JdbcNotificationDeliveryRepository::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(EmailAutoConfiguration::class.java)).isEmpty()
        assertThat(context.getBeansOfType(EmailSender::class.java)).isEmpty()
        assertThat(context.getBeansOfType(JavaMailSender::class.java)).isEmpty()
        assertThat(context.getBeansOfType(NotificationDeliveryJob::class.java)).isEmpty()
        assertThat(context.getBeansOfType(PremiumThresholdDeliveryService::class.java)).isEmpty()
    }

    private fun insertActiveSubscription() {
        jdbcTemplate.update(
            """
            INSERT INTO member (email, password, nickname, status, created_at, updated_at)
            VALUES ('disabled@example.com', 'encoded', 'disabled', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
        )
        val memberId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM member", Long::class.java))
        jdbcTemplate.update(
            """
            INSERT INTO notification_subscription (
                member_id, symbol, korea_exchange, foreign_exchange, revision, lock_version,
                direction, threshold, status, created_at, updated_at
            ) VALUES (?, 'BTC', 'BITHUMB', 'BINANCE', 1, 0, 'ABOVE', 5.0, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
            memberId,
        )
    }

    private fun deliveryCount(): Int = requireNotNull(
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_delivery", Int::class.java),
    )

    private fun snapshot(): PremiumSnapshot = PremiumSnapshot(
        pair = MarketPair.default(Symbol("BTC")),
        premiumRate = BigDecimal("6"),
        koreaPrice = BigDecimal("100"),
        foreignPrice = BigDecimal("90"),
        foreignPriceInKrw = BigDecimal("94"),
        fxRate = BigDecimal("1400"),
        observedAt = Instant.now(),
    )
}
