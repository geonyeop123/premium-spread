package io.premiumspread.infrastructure.common.cache.premium

import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.infrastructure.common.cache.AfterCommitCacheExecutor
import io.premiumspread.infrastructure.common.cache.BUSINESS_PAYLOAD_VERSION
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class PremiumCacheWriter(
    private val redisTemplate: StringRedisTemplate,
    private val timeSeriesCache: TimeSeriesCacheSupport,
    private val afterCommit: AfterCommitCacheExecutor,
) {
    /** v2만 기록한다. symbol-only legacy key는 cutover TTL 만료 후 자연 제거된다. */
    fun save(snapshot: PremiumSnapshot) {
        val pair = snapshot.pair
        val key = RedisKeyGenerator.premiumV2Key(pair.koreaExchange.name, pair.foreignExchange.name, pair.symbol.code)
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "schema_version" to BUSINESS_PAYLOAD_VERSION,
                "symbol" to snapshot.symbol,
                "rate" to snapshot.premiumRate.toPlainString(),
                "korea_price" to snapshot.koreaPrice.toPlainString(),
                "foreign_price" to snapshot.foreignPrice.toPlainString(),
                "foreign_price_krw" to snapshot.foreignPriceInKrw.toPlainString(),
                "fx_rate" to snapshot.fxRate.toPlainString(),
                "observed_at" to snapshot.observedAt.toEpochMilli().toString(),
                "korea_exchange" to pair.koreaExchange.name,
                "foreign_exchange" to pair.foreignExchange.name,
                "fx_source" to snapshot.fxSource.name,
                "fx_observed_at" to snapshot.fxObservedAt.toEpochMilli().toString(),
            ),
        )
        redisTemplate.expire(key, RedisTtl.PREMIUM)
    }

    fun saveHistory(snapshot: PremiumSnapshot) {
        val pair = snapshot.pair
        val key = RedisKeyGenerator.premiumV2HistoryKey(pair.koreaExchange.name, pair.foreignExchange.name, pair.symbol.code)
        val value = "${snapshot.premiumRate}:${snapshot.koreaPrice}:${snapshot.foreignPrice}"
        timeSeriesCache.add(key, value, snapshot.observedAt, RedisTtl.PREMIUM_HISTORY)
    }

    fun saveAfterCommit(snapshot: PremiumSnapshot) = afterCommit.execute { save(snapshot) }

    fun saveHistoryAfterCommit(snapshot: PremiumSnapshot) = afterCommit.execute { saveHistory(snapshot) }
}
