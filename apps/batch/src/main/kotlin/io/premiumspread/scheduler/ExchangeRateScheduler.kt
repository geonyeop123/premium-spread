package io.premiumspread.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.cache.FxCacheService
import io.premiumspread.client.exchangerate.ExchangeRateClient
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 환율 수집 스케줄러
 *
 * 10분 간격으로 USD/KRW 환율을 수집하여 Redis에 저장
 */
@Component
class ExchangeRateScheduler(
    private val exchangeRateClient: ExchangeRateClient,
    private val fxCacheService: FxCacheService,
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 600_000) // 10분
    fun fetchExchangeRate() {
        val lockKey = RedisKeyGenerator.lockFxKey()
        val leaseTime = RedisTtl.Lock.FX_LEASE.seconds

        val result = lockManager.withLock(
            lockKey = lockKey,
            waitTime = 0, // 즉시 시도, 실패 시 skip
            leaseTime = leaseTime,
            timeUnit = TimeUnit.SECONDS,
        ) {
            runBlocking {
                try {
                    val fxRate = exchangeRateClient.getUsdKrwRate()

                    // Redis에 저장
                    fxCacheService.save(fxRate)

                    // 마지막 실행 시각 기록
                    updateLastRunTime()

                    // 메트릭 기록
                    meterRegistry.counter("scheduler.fx.success").increment()
                    meterRegistry.gauge("fx.rate.current", fxRate.rate.toDouble())

                    log.info("Fetched exchange rate - USD/KRW: {}", fxRate.rate)
                } catch (e: Exception) {
                    meterRegistry.counter("scheduler.fx.error", "error", e.javaClass.simpleName).increment()
                    log.error("Failed to fetch exchange rate", e)
                    throw e
                }
            }
        }

        if (result.isNotAcquired()) {
            log.trace("FX scheduler skipped - lock not acquired")
            meterRegistry.counter("scheduler.fx.skipped").increment()
        }
    }

    /**
     * 애플리케이션 시작 시 즉시 환율 조회
     */
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE) // 시작 후 5초에 1회 실행
    fun fetchExchangeRateOnStartup() {
        log.info("Fetching initial exchange rate...")
        fetchExchangeRate()
    }

    private fun updateLastRunTime() {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.batchLastRunKey("fx"),
            System.currentTimeMillis().toString(),
            RedisTtl.BATCH_HEALTH,
        )
    }
}
