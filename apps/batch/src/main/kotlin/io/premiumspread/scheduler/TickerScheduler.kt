package io.premiumspread.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.client.bithumb.BithumbClient
import io.premiumspread.client.binance.BinanceClient
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 티커 수집 스케줄러
 *
 * 1초 간격으로 Bithumb(BTC/KRW)와 Binance(BTCUSDT) 티커를 수집하여 Redis에 저장
 */
@Component
class TickerScheduler(
    private val bithumbClient: BithumbClient,
    private val binanceClient: BinanceClient,
    private val tickerCacheService: TickerCacheService,
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 1000) // 1초
    fun fetchTickers() {
        val lockKey = RedisKeyGenerator.lockTickerKey()
        val leaseTime = RedisTtl.Lock.TICKER_LEASE.seconds

        val result = lockManager.withLock(
            lockKey = lockKey,
            waitTime = 0, // 즉시 시도, 실패 시 skip
            leaseTime = leaseTime,
            timeUnit = TimeUnit.SECONDS,
        ) {
            runBlocking {
                try {
                    // 병렬로 두 거래소 호출
                    val bithumbDeferred = async { bithumbClient.getBtcTicker() }
                    val binanceDeferred = async { binanceClient.getBtcFuturesTicker() }

                    val bithumbTicker = bithumbDeferred.await()
                    val binanceTicker = binanceDeferred.await()

                    // Redis에 저장
                    tickerCacheService.saveAll(bithumbTicker, binanceTicker)

                    // 마지막 실행 시각 기록
                    updateLastRunTime()

                    // 메트릭 기록
                    meterRegistry.counter("scheduler.ticker.success").increment()

                    log.debug(
                        "Fetched tickers - Bithumb: {} KRW, Binance: {} USDT",
                        bithumbTicker.price,
                        binanceTicker.price,
                    )
                } catch (e: Exception) {
                    meterRegistry.counter("scheduler.ticker.error", "error", e.javaClass.simpleName).increment()
                    log.error("Failed to fetch tickers", e)
                    throw e
                }
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Ticker scheduler skipped - lock not acquired")
            meterRegistry.counter("scheduler.ticker.skipped").increment()
        }
    }

    private fun updateLastRunTime() {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.batchLastRunKey("ticker"),
            System.currentTimeMillis().toString(),
            RedisTtl.BATCH_HEALTH,
        )
    }
}
