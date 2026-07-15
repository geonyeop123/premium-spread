package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.PremiumAggregationScheduler
import io.premiumspread.interfaces.scheduling.TickerAggregationScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled

class AggregationSchedulerZoneTest {
    @Test
    fun `모든 cron 기반 집계 trigger는 설정된 업무 시간대를 명시한다`() {
        val scheduledMethods = listOf(
            PremiumAggregationScheduler::class.java.getDeclaredMethod("aggregateMinute"),
            PremiumAggregationScheduler::class.java.getDeclaredMethod("aggregateHour"),
            PremiumAggregationScheduler::class.java.getDeclaredMethod("aggregateDay"),
            TickerAggregationScheduler::class.java.getDeclaredMethod("aggregateMinute"),
            TickerAggregationScheduler::class.java.getDeclaredMethod("aggregateHour"),
            TickerAggregationScheduler::class.java.getDeclaredMethod("aggregateDay"),
        )

        assertThat(scheduledMethods)
            .allSatisfy { method ->
                assertThat(method.getAnnotation(Scheduled::class.java).zone)
                    .isEqualTo("\${batch.scheduling.zone:Asia/Seoul}")
            }
    }
}
