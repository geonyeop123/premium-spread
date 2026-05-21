package io.premiumspread.scheduler

import io.premiumspread.infrastructure.ingestion.binance.BinanceFlushJob
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 바이낸스 1초 flush 스케줄러 — thin entrypoint.
 *
 * - 단일 인스턴스 전제. 분산 락 불필요 → `JobExecutor` 미사용.
 * - 비즈니스/예외/메트릭/last-run/알람은 [BinanceFlushJob] 내부.
 */
@Component
@Profile("!test")
class BinanceFlushScheduler(
    private val flushJob: BinanceFlushJob,
) {
    @Scheduled(fixedRate = 1000)
    fun flush() {
        flushJob.run()
    }
}
