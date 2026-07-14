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
        require(from < to) { "Aggregation range must satisfy from < to." }

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
            Math.nextDown(to.toEpochMilli().toDouble()),
        )

        if (entries.isNullOrEmpty()) return null

        val snapshots = entries.map { entry ->
            val parts = entry.value?.split(":") ?: return null
            if (parts.size < 6) return null
            val timestamp = entry.score?.toLong()?.let(Instant::ofEpochMilli) ?: return null

            runCatching {
                PremiumAggregationSnapshot(
                    symbol = symbol.uppercase(),
                    high = parts[0].toBigDecimal(),
                    low = parts[1].toBigDecimal(),
                    open = parts[2].toBigDecimal(),
                    close = parts[3].toBigDecimal(),
                    avg = parts[4].toBigDecimal(),
                    count = parts[5].toInt(),
                    observedAt = timestamp,
                    fxRate = parts.getOrNull(6)?.takeIf(String::isNotBlank)?.toBigDecimal(),
                )
            }.getOrElse {
                log.warn("Corrupt premium aggregation cache entry: {}", key)
                return null
            }
        }
        return snapshots
    }
}
