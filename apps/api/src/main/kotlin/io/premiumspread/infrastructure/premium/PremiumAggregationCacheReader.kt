package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.redis.AggregationTimeUnit
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PremiumAggregationCacheReader(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findByInterval(
        symbol: String,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot>? {
        val timeUnit = when (interval) {
            "1m" -> AggregationTimeUnit.MINUTES
            "1h" -> AggregationTimeUnit.HOURS
            "1d" -> AggregationTimeUnit.DAYS
            else -> return null
        }

        val key = timeUnit.keyFor(symbol)
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        )

        if (entries.isNullOrEmpty()) return null

        return entries.mapNotNull { entry ->
            val parts = entry.value?.split(":") ?: return@mapNotNull null
            if (parts.size < 6) return@mapNotNull null
            val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null

            PremiumAggregationSnapshot(
                symbol = symbol.uppercase(),
                high = parts[0].toBigDecimalOrNull() ?: return@mapNotNull null,
                low = parts[1].toBigDecimalOrNull() ?: return@mapNotNull null,
                open = parts[2].toBigDecimalOrNull() ?: return@mapNotNull null,
                close = parts[3].toBigDecimalOrNull() ?: return@mapNotNull null,
                avg = parts[4].toBigDecimalOrNull() ?: return@mapNotNull null,
                count = parts[5].toIntOrNull() ?: return@mapNotNull null,
                observedAt = timestamp,
            )
        }
    }
}
