package io.premiumspread.scheduler

import io.micrometer.core.instrument.MeterRegistry
import io.premiumspread.cache.FxCacheService
import io.premiumspread.cache.PositionCacheService
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.calculator.PremiumCalculator
import io.premiumspread.redis.DistributedLockManager
import io.premiumspread.redis.RedisKeyGenerator
import io.premiumspread.redis.RedisTtl
import io.premiumspread.repository.PremiumSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 프리미엄 계산 스케줄러
 *
 * 1초 간격으로 김치 프리미엄을 계산하여 Redis에 저장
 * Open Position이 있을 경우 히스토리도 함께 저장
 */
@Component
class PremiumScheduler(
    private val tickerCacheService: TickerCacheService,
    private val fxCacheService: FxCacheService,
    private val premiumCacheService: PremiumCacheService,
    private val positionCacheService: PositionCacheService,
    private val premiumCalculator: PremiumCalculator,
    private val premiumSnapshotRepository: PremiumSnapshotRepository,
    private val lockManager: DistributedLockManager,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BITHUMB = "bithumb"
        private const val BINANCE = "binance"
        private const val BTC = "btc"
    }

    @Scheduled(fixedRate = 1000) // 1초
    fun calculatePremium() {
        val lockKey = RedisKeyGenerator.lockPremiumKey()
        val leaseTime = RedisTtl.Lock.PREMIUM_LEASE.seconds

        // 즉시 시도, 실패 시 skip
        val result = lockManager.withLock(
            lockKey = lockKey,
            waitTime = 0,
            leaseTime = leaseTime,
            timeUnit = TimeUnit.SECONDS,
        ) {
            try {
                // 캐시에서 데이터 조회
                val bithumbTicker = tickerCacheService.get(BITHUMB, BTC)
                val binanceTicker = tickerCacheService.get(BINANCE, BTC)
                val fxRate = fxCacheService.getUsdKrw()

                // 데이터 유효성 검증
                if (bithumbTicker == null || binanceTicker == null || fxRate == null) {
                    log.warn(
                        "Missing data for premium calculation - Bithumb: {}, Binance: {}, FX: {}",
                        bithumbTicker != null,
                        binanceTicker != null,
                        fxRate != null,
                    )
                    meterRegistry.counter("scheduler.premium.skipped", "reason", "missing_data").increment()
                    return@withLock
                }

                // 프리미엄 계산
                val premium = premiumCalculator.calculate(
                    koreaTicker = bithumbTicker,
                    foreignTicker = binanceTicker,
                    fxRate = fxRate,
                )

                // 프리미엄 캐시 저장
                premiumCacheService.save(premium)

                // DB에 스냅샷 저장
                premiumSnapshotRepository.save(premium)

                // Open Position이 있으면 히스토리도 저장
                if (positionCacheService.hasOpenPosition()) {
                    premiumCacheService.saveHistory(premium)
                    log.debug("Saved premium history for open positions")
                }

                // 마지막 실행 시각 기록
                updateLastRunTime()

                // 메트릭 기록
                meterRegistry.counter("scheduler.premium.success").increment()
                meterRegistry.gauge("premium.rate.current", premium.premiumRate.toDouble())

                log.debug(
                    "Calculated premium: {}% (Korea: {} KRW, Foreign: {} USDT = {} KRW)",
                    premium.premiumRate,
                    premium.koreaPrice,
                    premium.foreignPrice,
                    premium.foreignPriceInKrw,
                )
            } catch (e: Exception) {
                meterRegistry.counter("scheduler.premium.error", "error", e.javaClass.simpleName).increment()
                log.error("Failed to calculate premium", e)
                throw e
            }
        }

        if (result.isNotAcquired()) {
            log.trace("Premium scheduler skipped - lock not acquired")
            meterRegistry.counter("scheduler.premium.skipped", "reason", "lock").increment()
        }
    }

    private fun updateLastRunTime() {
        redisTemplate.opsForValue().set(
            RedisKeyGenerator.batchLastRunKey("premium"),
            System.currentTimeMillis().toString(),
            RedisTtl.BATCH_HEALTH,
        )
    }
}
