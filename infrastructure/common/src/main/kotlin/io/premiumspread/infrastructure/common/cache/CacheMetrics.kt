package io.premiumspread.infrastructure.common.cache

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

enum class CacheReadOutcome(val tagValue: String) {
    HIT("hit"),
    MISS("miss"),
    CORRUPT("corrupt"),
    LEGACY_HIT("legacy_hit"),
    ERROR("error"),
}

fun interface CacheReadMetrics {
    fun record(cache: String, outcome: CacheReadOutcome)
}

@Component
class MicrometerCacheReadMetrics(
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) : CacheReadMetrics {
    override fun record(cache: String, outcome: CacheReadOutcome) {
        val registry = meterRegistry.getIfAvailable() ?: return
        Counter.builder(METRIC_NAME)
            .tag("cache", cache)
            .tag("outcome", outcome.tagValue)
            .register(registry)
            .increment()
    }

    private companion object {
        const val METRIC_NAME = "cache.read.total"
    }
}
