package io.premiumspread.redis

/**
 * Redis 키 생성 유틸리티
 *
 * 키 패턴: {domain}:{sub-domain}:{identifier}:{optional-qualifier}
 */
// One namespace owner prevents incompatible key formats from being duplicated across adapters.
@Suppress("TooManyFunctions")
object RedisKeyGenerator {

    private fun canonical(value: String): String = value.trim().lowercase()

    // Ticker 키
    fun tickerKey(exchange: String, symbol: String): String =
        "ticker:${canonical(exchange)}:${canonical(symbol)}"

    // FX 키
    fun fxKey(base: String, quote: String): String =
        "fx:${canonical(base)}:${canonical(quote)}"

    // Premium 키
    fun premiumKey(symbol: String): String =
        "premium:${canonical(symbol)}"

    fun premiumHistoryKey(symbol: String): String =
        "premium:${canonical(symbol)}:history"

    /** Pair-aware premium v2 key. Legacy symbol-only keys remain read-only during cutover. */
    fun premiumV2Key(koreaExchange: String, foreignExchange: String, symbol: String): String =
        "premium:${canonical(koreaExchange)}:${canonical(foreignExchange)}:${canonical(symbol)}"

    fun premiumV2HistoryKey(koreaExchange: String, foreignExchange: String, symbol: String): String =
        "${premiumV2Key(koreaExchange, foreignExchange, symbol)}:history"

    fun premiumV2SecondsKey(koreaExchange: String, foreignExchange: String, symbol: String): String =
        "${premiumV2Key(koreaExchange, foreignExchange, symbol)}:seconds"

    fun premiumV2AggregationKey(
        koreaExchange: String,
        foreignExchange: String,
        symbol: String,
        timeUnit: String,
    ): String = "${premiumV2Key(koreaExchange, foreignExchange, symbol)}:${canonical(timeUnit)}"

    fun premiumV2SummaryKey(
        koreaExchange: String,
        foreignExchange: String,
        symbol: String,
        interval: String,
    ): String = "${premiumV2Key(koreaExchange, foreignExchange, symbol)}:summary:${canonical(interval)}"

    // 초당 데이터 키 (ZSet)
    fun tickerSecondsKey(exchange: String, symbol: String): String =
        "ticker:seconds:$exchange:$symbol"

    fun premiumSecondsKey(symbol: String): String =
        "premium:seconds:${canonical(symbol)}"

    // 집계 데이터 키 (ZSet)
    fun premiumMinutesKey(symbol: String): String =
        "premium:minutes:${canonical(symbol)}"

    fun premiumHoursKey(symbol: String): String =
        "premium:hours:${canonical(symbol)}"

    fun premiumDaysKey(symbol: String): String =
        "premium:days:${canonical(symbol)}"

    // 서머리 캐시 키 (Hash)
    fun summaryKey(interval: String, symbol: String): String =
        "summary:${canonical(interval)}:${canonical(symbol)}"

    // Lock 키
    fun lockFxKey(): String =
        "lock:fx"

    fun lockPremiumKey(): String =
        "lock:premium"

    // Batch 헬스 키
    fun batchLastRunKey(job: String): String =
        "batch:last_run:$job"

    fun batchHealthKey(server: String): String =
        "batch:health:$server"
}
