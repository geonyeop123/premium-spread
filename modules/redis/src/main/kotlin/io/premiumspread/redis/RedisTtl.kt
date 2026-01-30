package io.premiumspread.redis

import java.time.Duration

/**
 * Redis TTL 상수
 */
object RedisTtl {
    // Ticker 캐시: 5초 (1초 갱신 * 5회 여유)
    val TICKER: Duration = Duration.ofSeconds(5)

    // FX 캐시: 15분 (10분 갱신 * 1.5 여유)
    val FX: Duration = Duration.ofMinutes(15)

    // Premium 캐시: 5초
    val PREMIUM: Duration = Duration.ofSeconds(5)

    // Premium History: 1시간
    val PREMIUM_HISTORY: Duration = Duration.ofHours(1)

    // Position 캐시: 30초
    val POSITION: Duration = Duration.ofSeconds(30)

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
