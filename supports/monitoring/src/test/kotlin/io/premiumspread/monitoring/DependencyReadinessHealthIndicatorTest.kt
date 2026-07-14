package io.premiumspread.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.sql.Connection
import javax.sql.DataSource

class DependencyReadinessHealthIndicatorTest {
    @Test
    fun `api readiness requires database and request critical redis`() {
        val indicator = indicator("api", emptyList(), emptyList())

        assertThat(indicator.health().status).isEqualTo(Status.DOWN)
    }

    @Test
    fun `api readiness is up when database and redis are reachable`() {
        val indicator = indicator("api", listOf(validDataSource()), listOf(pingRedis()))

        assertThat(indicator.health().status).isEqualTo(Status.UP)
        assertThat(indicator.health().details["policy"]).isEqualTo("db_redis")
    }

    @Test
    fun `batch readiness includes mandatory ingestion policy`() {
        val downIngestion = CriticalIngestionHealth { Health.down().withDetail("ingestion", "binance_stale").build() }
        val indicator = indicator(
            "premium-spread-batch",
            listOf(validDataSource()),
            listOf(pingRedis()),
            listOf(downIngestion),
        )

        assertThat(indicator.health().status).isEqualTo(Status.DOWN)
        assertThat(indicator.health().details["ingestion"]).isEqualTo("binance_stale")
    }

    private fun indicator(
        appName: String,
        dataSources: List<DataSource>,
        redisTemplates: List<StringRedisTemplate>,
        ingestion: List<CriticalIngestionHealth> = emptyList(),
    ): DependencyReadinessHealthIndicator {
        val availability = Mockito.mock(ApplicationAvailability::class.java)
        Mockito.`when`(availability.readinessState).thenReturn(ReadinessState.ACCEPTING_TRAFFIC)
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", mapOf("spring.application.name" to appName)))
        }
        return DependencyReadinessHealthIndicator(
            availability,
            environment,
            provider(DataSource::class.java, dataSources),
            provider(StringRedisTemplate::class.java, redisTemplates),
            provider(CriticalIngestionHealth::class.java, ingestion),
        )
    }

    private fun validDataSource(): DataSource {
        val connection = Mockito.mock(Connection::class.java)
        Mockito.`when`(connection.isValid(2)).thenReturn(true)
        val dataSource = Mockito.mock(DataSource::class.java)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        return dataSource
    }

    private fun pingRedis(): StringRedisTemplate {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        Mockito.`when`(template.execute(ArgumentMatchers.any<RedisCallback<String>>())).thenReturn("PONG")
        return template
    }

    private fun <T : Any> provider(type: Class<T>, values: List<T>): ObjectProvider<T> {
        val factory = StaticListableBeanFactory()
        values.forEachIndexed { index, value -> factory.addBean("${type.simpleName}-$index", value) }
        return factory.getBeanProvider(type)
    }
}
