package io.premiumspread.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
class RedissonConfig(
    @Value("\${spring.data.redis.host:localhost}")
    private val host: String,
    @Value("\${spring.data.redis.port:6379}")
    private val port: Int,
    @Value("\${spring.data.redis.password:#{null}}")
    private val password: String?,
    @Autowired(required = false)
    private val redisConnectionDetails: RedisConnectionDetails?,
) {
    @Bean
    @ConditionalOnMissingBean(RedissonClient::class)
    fun redissonClient(): RedissonClient {
        val actualHost = redisConnectionDetails?.standalone?.host ?: host
        val actualPort = redisConnectionDetails?.standalone?.port ?: port
        val actualPassword = redisConnectionDetails?.password ?: password

        val config = Config().apply {
            useSingleServer().apply {
                address = "redis://$actualHost:$actualPort"
                if (!actualPassword.isNullOrBlank()) {
                    setPassword(actualPassword)
                }
                connectionMinimumIdleSize = 2
                connectionPoolSize = 10
                retryAttempts = 3
                retryInterval = 1500
                timeout = 3000
                connectTimeout = 10000
            }
        }
        return Redisson.create(config)
    }
}
