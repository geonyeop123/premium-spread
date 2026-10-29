package io.premiumspread.domain.ownermarket

import java.time.Duration

enum class MarketDirection { UP, DOWN }

enum class ExtremeWindow(val duration: Duration) {
    ONE_HOUR(Duration.ofHours(1)),
    ONE_DAY(Duration.ofHours(24)),
}
