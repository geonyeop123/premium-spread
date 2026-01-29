package io.premiumspread.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
class RedissonConfig(
    @Value("\${spring.data.redis.host:localhost}")
    private val host: String,
    @Value("\${spring.data.redis.port:6379}")
    private val port: Int,
    @Value("\${spring.data.redis.password:}")
    private val password: String,
) {

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config().apply {
            useSingleServer().apply {
                address = "redis://$host:$port"
                if (password.isNotBlank()) {
                    setPassword(password)
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
