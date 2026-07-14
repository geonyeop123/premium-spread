package io.premiumspread.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
class RedissonConfig(
    private val redis: RedisRuntimeProperties,
    private val redisson: RedissonClientProperties,
    private val redisConnectionDetails: ObjectProvider<RedisConnectionDetails>,
) {
    @Bean
    @ConditionalOnMissingBean(RedissonClient::class)
    fun redissonClient(): RedissonClient {
        val connectionDetails = redisConnectionDetails.getIfAvailable()
        val actualHost = connectionDetails?.standalone?.host ?: redis.host
        val actualPort = connectionDetails?.standalone?.port ?: redis.port
        val actualPassword = connectionDetails?.password ?: redis.password

        val config = Config().apply {
            useSingleServer().apply {
                address = "redis://$actualHost:$actualPort"
                if (!actualPassword.isNullOrBlank()) {
                    setPassword(actualPassword)
                }
                connectionMinimumIdleSize = redisson.minimumIdle
                connectionPoolSize = redisson.poolSize
                retryAttempts = redisson.retryAttempts
                retryInterval = redisson.retryInterval.toMillis().toInt()
                timeout = redisson.commandTimeout.toMillis().toInt()
                connectTimeout = redisson.connectTimeout.toMillis().toInt()
            }
        }
        return Redisson.create(config)
    }
}
