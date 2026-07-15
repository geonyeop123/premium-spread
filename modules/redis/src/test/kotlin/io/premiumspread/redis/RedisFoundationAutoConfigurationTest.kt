package io.premiumspread.redis

import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock

class RedisFoundationAutoConfigurationTest {
    @Test
    fun `foundation owns lock and time-series beans without component scanning`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedisFoundationAutoConfiguration::class.java,
                    RedisTimeSeriesAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("redis.enabled=true")
            .withBean(StringRedisTemplate::class.java, { mock(StringRedisTemplate::class.java) })
            .withBean(RedissonClient::class.java, { mock(RedissonClient::class.java) })
            .run { context ->
                assertThat(context).hasSingleBean(RedisFoundationAutoConfiguration::class.java)
                assertThat(context).hasSingleBean(TimeSeriesCacheSupport::class.java)
                assertThat(context).hasSingleBean(DistributedLockManager::class.java)
                assertThat(context).hasSingleBean(Clock::class.java)
            }
    }

    @Test
    fun `foundation is published as a Boot auto-configuration`() {
        val imports = PathMatchingResourcePatternResolver()
            .getResources("classpath*:$AUTO_CONFIGURATION_IMPORTS")
            .flatMap { resource -> resource.inputStream.bufferedReader().use { it.readLines() } }

        assertThat(imports).contains(
            RedisFoundationAutoConfiguration::class.java.name,
            RedisTimeSeriesAutoConfiguration::class.java.name,
        )
    }

    @Test
    fun `redis disabled context does not create custom Redis or Redisson infrastructure`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedisFoundationAutoConfiguration::class.java,
                    RedisTimeSeriesAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("redis.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(StringRedisTemplate::class.java)
                assertThat(context).doesNotHaveBean(RedissonClient::class.java)
                assertThat(context).doesNotHaveBean(DistributedLockManager::class.java)
                assertThat(context).doesNotHaveBean(TimeSeriesCacheSupport::class.java)
            }
    }

    private companion object {
        const val AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }
}
