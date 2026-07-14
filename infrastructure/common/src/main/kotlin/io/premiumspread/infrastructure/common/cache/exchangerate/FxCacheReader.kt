package io.premiumspread.infrastructure.common.cache.exchangerate

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.infrastructure.common.cache.CacheReadMetrics
import io.premiumspread.infrastructure.common.cache.CacheReadOutcome
import io.premiumspread.infrastructure.common.cache.hasSupportedVersion
import io.premiumspread.redis.RedisKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

data class CachedFxRate(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: BigDecimal,
    val timestamp: Instant,
    val source: Exchange = Exchange.FX_PROVIDER,
) {
    fun toDomain(): ExchangeRateSnapshot = ExchangeRateSnapshot(baseCurrency, quoteCurrency, rate, timestamp, source)
}

@Component
class FxCacheReader(
    private val redisTemplate: StringRedisTemplate,
    private val metrics: CacheReadMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(baseCurrency: String, quoteCurrency: String): CachedFxRate? {
        val key = RedisKeyGenerator.fxKey(baseCurrency.lowercase(), quoteCurrency.lowercase())
        val hash = try {
            redisTemplate.opsForHash<String, String>().entries(key)
        } catch (exception: DataAccessException) {
            log.warn("FX cache read failed: {}", key, exception)
            metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
            return null
        }
        if (hash.isEmpty()) return miss()

        val payload = runCatching { parse(hash, baseCurrency, quoteCurrency) }
            .onFailure { log.warn("Corrupt FX cache payload: {}", key, it) }
            .getOrNull()
        metrics.record(CACHE_NAME, if (payload == null) CacheReadOutcome.CORRUPT else CacheReadOutcome.HIT)
        return payload
    }

    fun getUsdKrw(): CachedFxRate? = get("usd", "krw")

    fun getUsdKrwRate(): BigDecimal? = getUsdKrw()?.rate

    fun getUsdKrwSnapshot(): ExchangeRateSnapshot? = getUsdKrw()?.toDomain()

    fun exists(baseCurrency: String, quoteCurrency: String): Boolean = try {
        redisTemplate.hasKey(RedisKeyGenerator.fxKey(baseCurrency.lowercase(), quoteCurrency.lowercase()))
    } catch (exception: DataAccessException) {
        log.warn("FX cache exists check failed", exception)
        metrics.record(CACHE_NAME, CacheReadOutcome.ERROR)
        false
    }

    private fun parse(hash: Map<String, String>, baseCurrency: String, quoteCurrency: String): CachedFxRate? {
        if (!hash.hasSupportedVersion(allowUnversionedLegacy = true)) return null
        val storedBase = hash["base"] ?: return null
        val storedQuote = hash["quote"] ?: return null
        if (!storedBase.equals(baseCurrency, ignoreCase = true) || !storedQuote.equals(quoteCurrency, ignoreCase = true)) {
            return null
        }
        return CachedFxRate(
            baseCurrency = storedBase,
            quoteCurrency = storedQuote,
            rate = hash["rate"]?.toBigDecimalOrNull() ?: return null,
            timestamp = hash["timestamp"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null,
            source = hash["source"]?.let(Exchange::valueOf) ?: Exchange.FX_PROVIDER,
        )
    }

    private fun miss(): CachedFxRate? {
        metrics.record(CACHE_NAME, CacheReadOutcome.MISS)
        return null
    }

    private companion object {
        const val CACHE_NAME = "fx"
    }
}
