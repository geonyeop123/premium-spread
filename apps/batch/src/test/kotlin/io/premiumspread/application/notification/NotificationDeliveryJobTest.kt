package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.premiumspread.config.NotificationDeliveryProperties
import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.NotificationSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotificationDeliveryJobTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val transactions = mockk<NotificationDeliveryTransactionService>(relaxed = true)
    private val sender = mockk<NotificationSender>()
    private var job: NotificationDeliveryJob? = null

    @AfterEach
    fun close() {
        job?.close()
    }

    @Test
    fun `successful SMTP marks the current claim sent`() {
        val delivery = claimed(attemptCount = 1)
        justRun { sender.deliver(delivery) }
        val sut = newJob()

        sut.sendAndTransition(delivery)

        verifyOrder {
            sender.deliver(delivery)
            transactions.markSent(delivery, now)
        }
    }

    @Test
    fun `retryable failure schedules configured backoff with sanitized reason`() {
        val delivery = claimed(attemptCount = 1)
        every { sender.deliver(delivery) } throws IllegalStateException("recipient@example.com must not persist")
        val sut = newJob(maxAttempts = 2)

        sut.sendAndTransition(delivery)

        verify {
            transactions.scheduleRetry(
                delivery,
                now.plus(Duration.ofMinutes(1)),
                "IllegalStateException",
                now,
            )
        }
        verify(exactly = 0) { transactions.markFailed(any(), any(), any()) }
    }

    @Test
    fun `last configured attempt moves delivery to failed`() {
        val delivery = claimed(attemptCount = 2)
        every { sender.deliver(delivery) } throws IllegalStateException("smtp down")
        val sut = newJob(maxAttempts = 2)

        sut.sendAndTransition(delivery)

        verify { transactions.markFailed(delivery, "IllegalStateException", now) }
        verify(exactly = 0) { transactions.scheduleRetry(any(), any(), any(), any()) }
    }

    @Test
    fun `poll recovers stale claims before selecting ready work`() {
        every { transactions.claimReady(now, 1, any()) } returns emptyList()
        val sut = newJob()

        sut.poll()

        verifyOrder {
            transactions.recoverStale(now.minusSeconds(1), now)
            transactions.claimReady(now, 1, any())
        }
    }

    @Test
    fun `worker id fits locked-by column while preserving UUID`() {
        val uuid = "00000000-0000-0000-0000-000000000001"

        val workerId = NotificationDeliveryJob.createWorkerId("host".repeat(100), uuid)

        assertThat(workerId).hasSizeLessThanOrEqualTo(100)
        assertThat(workerId).endsWith("-$uuid")
    }

    @Test
    fun `interrupt ignoring SMTP keeps capacity and prevents another claim until actual completion`() {
        val delivery = claimed(attemptCount = 1)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transitioned = CountDownLatch(1)
        every { transactions.claimReady(now, 1, any()) } returns listOf(delivery)
        every { sender.deliver(delivery) } answers {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    // timeout cancel을 무시하는 SMTP transport를 재현한다.
                }
            }
        }
        every { transactions.markSent(delivery, now) } answers {
            transitioned.countDown()
            true
        }
        val sut = newJob(hardDeadline = Duration.ofMillis(50))

        val startedAt = System.nanoTime()
        sut.poll()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1))
        verify(exactly = 0) { transactions.markSent(delivery, any()) }

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
        sut.poll()
        verify(exactly = 1) { transactions.claimReady(now, 1, any()) }

        release.countDown()

        assertThat(transitioned.await(1, TimeUnit.SECONDS)).isTrue()
        verify(exactly = 1) { transactions.markSent(delivery, now) }
    }

    private fun newJob(
        maxAttempts: Int = 2,
        hardDeadline: Duration = Duration.ofMillis(200),
    ): NotificationDeliveryJob = NotificationDeliveryJob(
        transactions = transactions,
        sender = sender,
        properties = NotificationDeliveryProperties(
            batchSize = 1,
            concurrency = 1,
            hardSendDeadline = hardDeadline,
            dbQueueSafetyMargin = Duration.ZERO,
            staleThreshold = Duration.ofSeconds(1),
            maxAttempts = maxAttempts,
            retryDelays = listOf(Duration.ofMinutes(1)),
            retryJitterRatio = 0.0,
        ),
        clock = clock,
    ).also { job = it }

    private fun claimed(attemptCount: Int) = ClaimedNotificationDelivery(
        id = 1,
        deliveryId = "00000000-0000-0000-0000-000000000001",
        subscriptionId = 10,
        eventKey = "event",
        recipientEmail = "member@example.com",
        subject = "subject",
        payload = "payload",
        attemptCount = attemptCount,
        lockedBy = "worker",
        claimToken = "00000000-0000-0000-0000-000000000002",
        createdAt = now,
    )
}
