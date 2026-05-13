package io.premiumspread.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * `Clock` 빈 — `Instant.now(clock)` 사용처(WebSocket ingestion 등)에서 테스트 제어 가능.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
