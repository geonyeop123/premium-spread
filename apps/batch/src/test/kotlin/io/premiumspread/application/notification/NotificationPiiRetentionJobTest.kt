package io.premiumspread.application.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.config.NotificationRetentionProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class NotificationPiiRetentionJobTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val transactions = mockk<NotificationRetentionTransactionService>()

    @Test
    fun `one tick drains full batches until a partial batch`() {
        every { transactions.scrubSentPii(any(), any(), 100) } returnsMany listOf(100, 100, 20)
        val job = newJob(maxBatches = 10)

        val scrubbed = job.scrubSentPii()

        assertThat(scrubbed).isEqualTo(220)
        verify(exactly = 3) {
            transactions.scrubSentPii(now.minus(Duration.ofDays(30)), now, 100)
        }
    }

    @Test
    fun `per-run cap bounds a continuously full backlog`() {
        every { transactions.scrubSentPii(any(), any(), 100) } returns 100
        val job = newJob(maxBatches = 3)

        val scrubbed = job.scrubSentPii()

        assertThat(scrubbed).isEqualTo(300)
        verify(exactly = 3) { transactions.scrubSentPii(any(), any(), 100) }
    }

    private fun newJob(maxBatches: Int) = NotificationPiiRetentionJob(
        transactions = transactions,
        properties = NotificationRetentionProperties(
            scrubBatchSize = 100,
            scrubMaxBatchesPerRun = maxBatches,
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
}
