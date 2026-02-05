package io.premiumspread.redis

import java.time.Duration

/**
 * Redis TTL 상수
 */
object RedisTtl {
    // Ticker 캐시: 5초 (1초 갱신 * 5회 여유)
    val TICKER: Duration = Duration.ofSeconds(5)

    // FX 캐시: 31분 (30분 스케줄 + 1분 버퍼)
    val FX: Duration = Duration.ofMinutes(31)

    // Premium 캐시: 5초
    val PREMIUM: Duration = Duration.ofSeconds(5)

    // Premium History: 1시간
    val PREMIUM_HISTORY: Duration = Duration.ofHours(1)

    // Position 캐시: 30초
    val POSITION: Duration = Duration.ofSeconds(30)

    // 초당 데이터 (ZSet): 5분
    val SECONDS_DATA: Duration = Duration.ofMinutes(5)

    // 분 집계 데이터: 2시간
    val MINUTES_DATA: Duration = Duration.ofHours(2)

    // 시간 집계 데이터: 25시간
    val HOURS_DATA: Duration = Duration.ofHours(25)

    // 서머리 캐시
    object Summary {
        val ONE_MINUTE: Duration = Duration.ofSeconds(10)
        val TEN_MINUTES: Duration = Duration.ofSeconds(30)
        val ONE_HOUR: Duration = Duration.ofMinutes(1)
        val ONE_DAY: Duration = Duration.ofMinutes(5)
    }

    // 배치 헬스 체크: 5분
    val BATCH_HEALTH: Duration = Duration.ofMinutes(5)

    // Lock lease time
    object Lock {
        // Ticker 락: 2초 (1초 갱신 + 1초 여유)
        val TICKER_LEASE: Duration = Duration.ofSeconds(2)

        // FX 락: 30초
        val FX_LEASE: Duration = Duration.ofSeconds(30)

        // Premium 락: 2초
        val PREMIUM_LEASE: Duration = Duration.ofSeconds(2)
    }
}
