package io.premiumspread.infrastructure.batch.notification

import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Phase 7 durable delivery 전까지 기존 email threshold 경로를 보존하는 임시 adapter다. */
class LegacyPremiumThresholdEvaluatorAdapter(
    private val service: PremiumThresholdNotificationService,
) : PremiumThresholdEvaluator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(100),
        { task -> Thread(task, "legacy-premium-notification").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun evaluate(snapshot: PremiumSnapshot) {
        runCatching {
            executor.execute {
                service.process(
                    PremiumUpdatedEvent(
                        symbol = snapshot.symbol,
                        premiumRate = snapshot.premiumRate,
                    ),
                )
            }
        }.onFailure { log.error("Legacy premium notification queue saturated; event dropped", it) }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }
}
