package io.premiumspread.config

import io.premiumspread.redis.DistributedLockManager
import org.mockito.Mockito.mock
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 테스트용 설정
 *
 * Redis 관련 Bean을 Mock으로 제공
 */
@TestConfiguration
@EnableAutoConfiguration(
    exclude = [
    RedisAutoConfiguration::class,
    RedisRepositoriesAutoConfiguration::class,
],
)
class TestConfig {

    @Bean
    @Primary
    fun mockRedisConnectionFactory(): RedisConnectionFactory =
        mock(RedisConnectionFactory::class.java)

    @Bean
    @Primary
    fun mockRedisTemplate(): RedisTemplate<String, Any> =
        mock(RedisTemplate::class.java) as RedisTemplate<String, Any>

    @Bean
    @Primary
    fun mockStringRedisTemplate(): StringRedisTemplate =
        mock(StringRedisTemplate::class.java)

    @Bean
    @Primary
    fun mockRedissonClient(): RedissonClient =
        mock(RedissonClient::class.java)

    @Bean
    @Primary
    fun mockDistributedLockManager(): DistributedLockManager =
        mock(DistributedLockManager::class.java)
}
