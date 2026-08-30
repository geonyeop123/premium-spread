package io.premiumspread.config

import io.premiumspread.infrastructure.batch.exchange.ExchangeRateClient
import io.premiumspread.infrastructure.batch.exchange.ExchangeRateClientProperties
import io.premiumspread.redis.DistributedLockManager
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.mockwebserver.MockWebServer
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.ConstantDelay
import org.redisson.config.Config
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.reactive.function.client.WebClient
import org.testcontainers.containers.GenericContainer
import java.time.Duration

@TestConfiguration
class BatchTestConfig {

    companion object {
        val BATCH_TABLES = listOf(
            "notification_delivery", "notification_subscription", "trade_preparation", "position", "member",
            "exchange_rate",
            "ticker_minute", "ticker_hour", "ticker_day",
            "premium_minute", "premium_hour", "premium_day",
        )
    }

    @Bean
    fun batchSchemaInitializer(jdbcTemplate: JdbcTemplate) = InitializingBean {
        jdbcTemplate.execute("SET time_zone = '+00:00'")
        val sql = ClassPathResource("batch-schema.sql").inputStream.bufferedReader().readText()
        sql.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { jdbcTemplate.execute(it) }
    }

    @Bean
    fun distributedLockManager(redissonClient: RedissonClient) = DistributedLockManager(redissonClient)

    @Bean
    fun redissonClient(redisContainer: GenericContainer<*>): RedissonClient {
        val config = Config().apply {
            useSingleServer().apply {
                address = "redis://${redisContainer.host}:${redisContainer.firstMappedPort}"
                connectionMinimumIdleSize = 1
                connectionPoolSize = 5
                retryAttempts = 3
                retryDelay = ConstantDelay(Duration.ofMillis(1500))
                timeout = 3000
                connectTimeout = 10000
            }
        }
        return Redisson.create(config)
    }

    @Bean
    fun exchangeRateMockServer() = MockWebServer().apply { start() }

    @Bean
    @Primary
    fun exchangeRateClient(exchangeRateMockServer: MockWebServer, meterRegistry: MeterRegistry): ExchangeRateClient =
        ExchangeRateClient(
            WebClient.create(exchangeRateMockServer.url("/").toString()),
            meterRegistry,
            ExchangeRateClientProperties(apiKey = "test-dummy-key", maxRetries = 2),
        )
}
