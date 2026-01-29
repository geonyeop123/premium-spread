package io.premiumspread.redis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
@ConditionalOnProperty(name = ["redis.enabled"], havingValue = "true", matchIfMissing = true)
class RedisConfig {

    @Bean
    fun redisObjectMapper(): ObjectMapper =
        ObjectMapper().apply {
            registerModule(kotlinModule())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

    @Bean
    fun redisTemplate(
        connectionFactory: RedisConnectionFactory,
        redisObjectMapper: ObjectMapper,
    ): RedisTemplate<String, Any> =
        RedisTemplate<String, Any>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = GenericJackson2JsonRedisSerializer(redisObjectMapper)
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = GenericJackson2JsonRedisSerializer(redisObjectMapper)
        }

    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(connectionFactory)
}
