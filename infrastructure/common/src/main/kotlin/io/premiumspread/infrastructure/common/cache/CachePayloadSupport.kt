package io.premiumspread.infrastructure.common.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.dao.DataAccessException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

internal const val BUSINESS_PAYLOAD_VERSION = "2"

internal fun Map<String, String>.hasSupportedVersion(allowUnversionedLegacy: Boolean): Boolean {
    val version = this["schema_version"]
    return version == BUSINESS_PAYLOAD_VERSION || (allowUnversionedLegacy && version == null)
}

/** Legacy cutover TTL은 줄이기만 하고 반복 조회로 연장하지 않는다. */
fun StringRedisTemplate.shortenTtl(key: String, maximum: Duration) {
    try {
        val remainingMillis = getExpire(key, TimeUnit.MILLISECONDS)
        if (remainingMillis < 0 || remainingMillis > maximum.toMillis()) {
            expire(key, maximum)
        }
    } catch (exception: DataAccessException) {
        LoggerFactory.getLogger("LegacyCacheTtl").warn("Failed to shorten legacy cache TTL: {}", key, exception)
    }
}
