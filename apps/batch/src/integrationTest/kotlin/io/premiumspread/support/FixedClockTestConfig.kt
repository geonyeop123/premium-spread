package io.premiumspread.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@TestConfiguration
class FixedClockTestConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

    companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-05-12T00:00:10Z")
    }
}
