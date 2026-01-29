package io.premiumspread.monitoring

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap

/**
 * 메트릭 설정 및 레지스트리
 */
@Configuration
class MetricsConfig(
    private val meterRegistry: MeterRegistry,
) {

    private val counters = ConcurrentHashMap<String, Counter>()
    private val timers = ConcurrentHashMap<String, Timer>()

    // Ticker 수집 메트릭
    fun tickerFetchCounter(exchange: String): Counter =
        counters.computeIfAbsent("ticker.fetch.total.$exchange") {
            Counter.builder("ticker.fetch.total")
                .tag("exchange", exchange)
                .description("Total number of ticker fetches")
                .register(meterRegistry)
        }

    fun tickerFetchTimer(exchange: String): Timer =
        timers.computeIfAbsent("ticker.fetch.latency.$exchange") {
            Timer.builder("ticker.fetch.latency")
                .tag("exchange", exchange)
                .description("Ticker fetch latency")
                .register(meterRegistry)
        }

    fun tickerFetchErrorCounter(exchange: String, errorType: String): Counter =
        counters.computeIfAbsent("ticker.fetch.error.total.$exchange.$errorType") {
            Counter.builder("ticker.fetch.error.total")
                .tag("exchange", exchange)
                .tag("error_type", errorType)
                .description("Total number of ticker fetch errors")
                .register(meterRegistry)
        }

    // Premium 계산 메트릭
    fun premiumCalculationCounter(): Counter =
        counters.computeIfAbsent("premium.calculation.total") {
            Counter.builder("premium.calculation.total")
                .description("Total number of premium calculations")
                .register(meterRegistry)
        }

    // 캐시 메트릭
    fun cacheHitCounter(cacheName: String): Counter =
        counters.computeIfAbsent("cache.hit.total.$cacheName") {
            Counter.builder("cache.hit.total")
                .tag("cache", cacheName)
                .description("Total number of cache hits")
                .register(meterRegistry)
        }

    fun cacheMissCounter(cacheName: String): Counter =
        counters.computeIfAbsent("cache.miss.total.$cacheName") {
            Counter.builder("cache.miss.total")
                .tag("cache", cacheName)
                .description("Total number of cache misses")
                .register(meterRegistry)
        }

    // 외부 API 메트릭
    fun externalApiTimer(api: String): Timer =
        timers.computeIfAbsent("external.api.latency.$api") {
            Timer.builder("external.api.latency")
                .tag("api", api)
                .description("External API call latency")
                .register(meterRegistry)
        }

    fun externalApiErrorCounter(api: String, errorType: String): Counter =
        counters.computeIfAbsent("external.api.error.total.$api.$errorType") {
            Counter.builder("external.api.error.total")
                .tag("api", api)
                .tag("error_type", errorType)
                .description("Total number of external API errors")
                .register(meterRegistry)
        }

    // 배치 메트릭
    fun batchStaleCounter(job: String): Counter =
        counters.computeIfAbsent("batch.stale.$job") {
            Counter.builder("batch.stale")
                .tag("job", job)
                .description("Number of times batch job was detected as stale")
                .register(meterRegistry)
        }
}
