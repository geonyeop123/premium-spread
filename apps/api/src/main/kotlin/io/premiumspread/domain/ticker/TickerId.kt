package io.premiumspread.domain.ticker

import java.util.UUID

@JvmInline
value class TickerId(val value: UUID) {
    companion object {
        fun random(): TickerId = TickerId(UUID.randomUUID())
    }
}
