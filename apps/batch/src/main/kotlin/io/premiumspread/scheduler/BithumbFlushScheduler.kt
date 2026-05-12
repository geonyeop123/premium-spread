package io.premiumspread.scheduler

import io.premiumspread.infrastructure.ingestion.bithumb.BithumbFlushJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 빗썸 1초 flush 스케줄러 — thin entrypoint.
 *
 * - 단일 인스턴스 전제. 분산 락 불필요 → `JobExecutor` 미사용.
 * - 비즈니스/예외/메트릭/last-run/알람은 [BithumbFlushJob] 내부.
 */
@Component
@ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
class BithumbFlushScheduler(
    private val flushJob: BithumbFlushJob,
) {
    @Scheduled(fixedRate = 1000)
    fun flush() {
        flushJob.run()
    }
}
