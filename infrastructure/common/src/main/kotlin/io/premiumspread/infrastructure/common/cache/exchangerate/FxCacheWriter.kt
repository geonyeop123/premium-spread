package io.premiumspread.infrastructure.common.cache.exchangerate

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.BUSINESS_PAYLOAD_VERSION
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class FxCacheWriter(private val redisTemplate: StringRedisTemplate, private val afterCommit: AfterCommitCacheExecutor) {
    fun save(snapshot: ExchangeRateSnapshot) {
        val key = RedisKeyGenerator.fxKey(snapshot.baseCurrency.lowercase(), snapshot.quoteCurrency.lowercase())
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "schema_version" to BUSINESS_PAYLOAD_VERSION,
                "base" to snapshot.baseCurrency,
                "quote" to snapshot.quoteCurrency,
                "rate" to snapshot.rate.toPlainString(),
                "timestamp" to snapshot.observedAt.toEpochMilli().toString(),
                "source" to snapshot.source.name,
            ),
        )
        redisTemplate.expire(key, RedisTtl.FX)
    }

    fun saveAfterCommit(snapshot: ExchangeRateSnapshot) = afterCommit.execute { save(snapshot) }
}
