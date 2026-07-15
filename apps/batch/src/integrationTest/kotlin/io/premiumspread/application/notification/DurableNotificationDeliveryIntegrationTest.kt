package io.premiumspread.application.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.email.EmailDeliveryException
import io.premiumspread.email.EmailMessage
import io.premiumspread.email.EmailSender
import io.premiumspread.support.BatchIntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestPropertySource(
    properties = [
        "notification.email.enabled=true",
        "notification.email.from=alert@example.com",
        "notification.email.smtp.host=smtp.example.com",
        "notification.email.smtp.port=587",
        "notification.email.smtp.username=user",
        "notification.email.smtp.password=secret",
        "notification.email.smtp.connect-timeout=10ms",
        "notification.email.smtp.read-timeout=10ms",
        "notification.email.smtp.write-timeout=10ms",
        "notification.delivery.batch-size=2",
        "notification.delivery.concurrency=1",
        "notification.delivery.hard-send-deadline=200ms",
        "notification.delivery.db-queue-safety-margin=100ms",
        "notification.delivery.stale-threshold=5s",
        "notification.delivery.max-attempts=2",
        "notification.delivery.retry-delays=2s",
        "notification.delivery.retry-jitter-ratio=0",
    ],
)
class DurableNotificationDeliveryIntegrationTest : BatchIntegrationTestBase() {
    @Autowired lateinit var evaluator: PremiumThresholdEvaluator

    @Autowired lateinit var job: NotificationDeliveryJob

    @Autowired lateinit var transactions: NotificationDeliveryTransactionService

    @Autowired lateinit var meterRegistry: MeterRegistry

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @MockkBean(relaxed = true)
    lateinit var emailSender: EmailSender

    @Test
    fun `threshold match enqueues durable row and sends with stable delivery id`() {
        insertSubscription()
        val sent = slot<EmailMessage>()
        every { emailSender.send(capture(sent)) } returns Unit

        evaluator.evaluate(snapshot())

        val pending = deliveryRow()
        assertThat(pending["status"]).isEqualTo("PENDING")
        assertThat(pending["event_key"].toString()).contains(
            "|subscriptionRevision=1|marketPair=BTC:BITHUMB:BINANCE|direction=ABOVE|threshold=5|cooldownMillis=",
        )

        job.poll()

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            job.poll()
            val completed = deliveryRow()
            assertThat(completed["status"]).isEqualTo("SENT")
            assertThat(completed["sent_at"]).isNotNull()
            assertThat(sent.captured.deliveryId).isEqualTo(completed["delivery_id"])
        }
    }

    @Test
    fun `SMTP failures retry then move to failed at max attempts`() {
        insertSubscription()
        every { emailSender.send(any()) } throws EmailDeliveryException("smtp unavailable")
        evaluator.evaluate(snapshot())

        val failureStartedAt = Instant.now()
        job.poll()

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(deliveryRow()["status"]).isEqualTo("PENDING")
            assertThat(deliveryRow()["attempt_count"]).isEqualTo(1)
            assertThat(deliveryRow()["last_error"]).isEqualTo("EmailDeliveryException")
        }
        val retrying = deliveryRow()
        val nextAttemptAt = (retrying["next_attempt_at"] as LocalDateTime).toInstant(ZoneOffset.UTC)
        assertThat(nextAttemptAt).isBetween(
            failureStartedAt.plusSeconds(2),
            Instant.now().plusSeconds(2),
        )
        makeReady()

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            job.poll()
            assertThat(deliveryRow()["status"]).isEqualTo("FAILED")
            assertThat(deliveryRow()["attempt_count"]).isEqualTo(2)
            assertThat(deliveryRow()["last_error"]).isEqualTo("EmailDeliveryException")
        }
    }

    @Test
    fun `pending metric is recorded only after outer transaction commit`() {
        insertSubscription()
        val before = transitionCount("pending")

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            evaluator.evaluate(snapshot())
            status.setRollbackOnly()
        }

        assertThat(deliveryCount()).isZero()
        assertThat(transitionCount("pending")).isEqualTo(before)

        evaluator.evaluate(snapshot())

        assertThat(deliveryCount()).isEqualTo(1)
        assertThat(transitionCount("pending")).isEqualTo(before + 1.0)
    }

    @Test
    fun `duplicate metric is recorded only after outer transaction commit`() {
        insertSubscription()
        val sameSnapshot = snapshot()
        evaluator.evaluate(sameSnapshot)
        val before = transitionCount("duplicate")

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            evaluator.evaluate(sameSnapshot)
            status.setRollbackOnly()
        }

        assertThat(deliveryCount()).isEqualTo(1)
        assertThat(transitionCount("duplicate")).isEqualTo(before)

        evaluator.evaluate(sameSnapshot)

        assertThat(deliveryCount()).isEqualTo(1)
        assertThat(transitionCount("duplicate")).isEqualTo(before + 1.0)
    }

    @Test
    fun `slow SMTP timeout retains old claim and stale recovery fences its late success`() {
        insertSubscription()
        evaluator.evaluate(snapshot())
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { emailSender.send(any()) } answers {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    // hard deadline interrupt를 무시하는 SMTP transport를 재현한다.
                }
            }
        }

        val startedAt = System.nanoTime()
        job.poll()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2))
        assertThat(deliveryRow()["status"]).isEqualTo("PROCESSING")
        val oldToken = deliveryRow()["claim_token"]

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
        job.poll()
        assertThat(deliveryRow()["claim_token"]).isEqualTo(oldToken)
        assertThat(deliveryRow()["attempt_count"]).isEqualTo(1)

        val lockedAt = (deliveryRow()["locked_at"] as LocalDateTime).toInstant(ZoneOffset.UTC)
        val recoveredAt = lockedAt.plusSeconds(2)
        assertThat(transactions.recoverStale(lockedAt.plusSeconds(1), recoveredAt)).isEqualTo(1)
        val current = transactions.claimReady(recoveredAt, 1, "replacement-worker").single()
        assertThat(current.claimToken).isNotEqualTo(oldToken)
        val staleBefore = staleOwnershipCount()

        release.countDown()

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(staleOwnershipCount()).isGreaterThan(staleBefore)
            val row = deliveryRow()
            assertThat(row["status"]).isEqualTo("PROCESSING")
            assertThat(row["claim_token"]).isEqualTo(current.claimToken)
        }
        assertThat(transactions.markSent(current, Instant.now())).isTrue()
        assertThat(deliveryRow()["status"]).isEqualTo("SENT")
    }

    @Test
    fun `SMTP accepted but guarded mark lost leaves retriable row and may send again`() {
        insertSubscription()
        evaluator.evaluate(snapshot())
        val sendCount = AtomicInteger()
        every { emailSender.send(any()) } answers {
            sendCount.incrementAndGet()
            jdbcTemplate.update(
                """
                UPDATE notification_delivery
                SET status='PENDING', locked_at=NULL, locked_by=NULL, claim_token=NULL,
                    next_attempt_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6)
                """.trimIndent(),
            )
        }

        job.poll()

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(sendCount).hasValue(1)
            assertThat(deliveryRow()["status"]).isEqualTo("PENDING")
        }
        await().atMost(2, TimeUnit.SECONDS).until {
            if (sendCount.get() < 2) job.poll()
            sendCount.get() >= 2
        }
        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertThat(deliveryRow()["status"]).isEqualTo("PENDING")
        }
        verify(exactly = 2) { emailSender.send(any()) }
    }

    private fun insertSubscription() {
        jdbcTemplate.update(
            """
            INSERT INTO member (email, password, nickname, status, created_at, updated_at)
            VALUES ('member@example.com', 'encoded', 'member', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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

    private fun makeReady() {
        jdbcTemplate.update(
            "UPDATE notification_delivery SET next_attempt_at=UTC_TIMESTAMP(6) - INTERVAL 1 SECOND",
        )
    }

    private fun deliveryRow(): Map<String, Any> = jdbcTemplate.queryForMap(
        """
        SELECT delivery_id, event_key, status, attempt_count, next_attempt_at,
               locked_at, locked_by, claim_token, sent_at, last_error
        FROM notification_delivery
        """.trimIndent(),
    )

    private fun staleOwnershipCount(): Double = meterRegistry.counter(
        "premiumspread.notification.delivery.transitions",
        "outcome",
        "stale_ownership",
    ).count()

    private fun transitionCount(outcome: String): Double = meterRegistry.counter(
        "premiumspread.notification.delivery.transitions",
        "outcome",
        outcome,
    ).count()

    private fun deliveryCount(): Int = requireNotNull(
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_delivery", Int::class.java),
    )

    private fun snapshot(): PremiumSnapshot {
        val pair = MarketPair.default(Symbol("BTC"))
        return PremiumSnapshot(
            pair = pair,
            premiumRate = BigDecimal("6"),
            koreaPrice = BigDecimal("100"),
            foreignPrice = BigDecimal("90"),
            foreignPriceInKrw = BigDecimal("94"),
            fxRate = BigDecimal("1400"),
            observedAt = Instant.now(),
        )
    }
}
