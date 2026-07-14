package io.premiumspread.redis

import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock

/**
 * Owns the Redis/Redisson foundation independently of an application's component scan.
 */
@AutoConfiguration(before = [RedissonAutoConfigurationV2::class, RedisAutoConfiguration::class])
@Import(
    RedisConfig::class,
    RedissonConfig::class,
    DistributedLockManager::class,
)
class RedisFoundationAutoConfiguration

@AutoConfiguration(after = [RedisAutoConfiguration::class, RedisFoundationAutoConfiguration::class])
@ConditionalOnBean(StringRedisTemplate::class)
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
@Import(TimeSeriesCacheSupport::class)
class RedisTimeSeriesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun redisFoundationClock(): Clock = Clock.systemUTC()
}
