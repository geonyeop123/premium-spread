package io.premiumspread.application.notification

import io.premiumspread.config.NotificationRetentionProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class NotificationPiiRetentionJob(
    private val transactions: NotificationRetentionTransactionService,
    private val properties: NotificationRetentionProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scrubSentPii(): Int {
        val now = clock.instant()
        var total = 0
        repeat(properties.scrubMaxBatchesPerRun) {
            val scrubbed = transactions.scrubSentPii(
                sentBefore = now.minus(properties.scrubRetention),
                scrubbedAt = now,
                limit = properties.scrubBatchSize,
            )
            total += scrubbed
            if (scrubbed < properties.scrubBatchSize) return total
        }
        log.warn(
            "Notification PII scrub reached per-run safety cap - scrubbed={}, maxBatches={}, batchSize={}",
            total,
            properties.scrubMaxBatchesPerRun,
            properties.scrubBatchSize,
        )
        return total
    }
}
