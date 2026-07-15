package io.premiumspread.infrastructure.common.cache.ticker

import io.premiumspread.domain.ticker.TickerSnapshot
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.BUSINESS_PAYLOAD_VERSION
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class TickerCacheWriter(private val redisTemplate: StringRedisTemplate, private val afterCommit: AfterCommitCacheExecutor) {
    fun save(snapshot: TickerSnapshot) {
        val key = RedisKeyGenerator.tickerKey(snapshot.exchange.lowercase(), snapshot.symbol.lowercase())
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "schema_version" to BUSINESS_PAYLOAD_VERSION,
                "exchange" to snapshot.exchange,
                "symbol" to snapshot.symbol,
                "currency" to snapshot.currency,
                "price" to snapshot.price.toPlainString(),
                "volume" to (snapshot.volume?.toPlainString() ?: ""),
                "timestamp" to snapshot.observedAt.toEpochMilli().toString(),
            ),
        )
        redisTemplate.expire(key, RedisTtl.TICKER)
    }

    fun saveAfterCommit(snapshot: TickerSnapshot) = afterCommit.execute { save(snapshot) }
}
