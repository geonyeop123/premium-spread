package io.premiumspread.infrastructure.common

import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheReader
import io.premiumspread.infrastructure.common.cache.premium.PremiumCacheWriter
import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateRepositoryAdapter
import io.premiumspread.config.jpa.JpaAuditingAutoConfiguration
import io.premiumspread.config.jpa.JpaFoundationAutoConfiguration
import io.premiumspread.redis.RedisFoundationAutoConfiguration
import io.premiumspread.redis.RedisTimeSeriesAutoConfiguration
import io.premiumspread.redis.support.TimeSeriesCacheSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import javax.sql.DataSource

class CommonInfrastructureAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonInfrastructureAutoConfiguration::class.java))

    @Test
    fun `auto-configuration can be loaded independently`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(CommonInfrastructureAutoConfiguration::class.java)
            assertThat(context).doesNotHaveBean(CommonJdbcConfiguration::class.java)
            assertThat(context).doesNotHaveBean(CommonJpaConfiguration::class.java)
            assertThat(context).doesNotHaveBean(CommonCacheConfiguration::class.java)
        }
    }

    @Test
    fun `Redis-only context owns foundation and common cache beans without component scanning`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedisFoundationAutoConfiguration::class.java,
                    RedisTimeSeriesAutoConfiguration::class.java,
                    JpaFoundationAutoConfiguration::class.java,
                    JpaAuditingAutoConfiguration::class.java,
                    CommonInfrastructureAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("redis.enabled=true")
            .withBean(StringRedisTemplate::class.java, { mock(StringRedisTemplate::class.java) })
            .withBean(RedissonClient::class.java, { mock(RedissonClient::class.java) })
            .run { context ->
                assertThat(context).hasSingleBean(RedisFoundationAutoConfiguration::class.java)
                assertThat(context).hasSingleBean(TimeSeriesCacheSupport::class.java)
                assertThat(context).hasSingleBean(CommonCacheConfiguration::class.java)
                assertThat(context).hasSingleBean(PremiumCacheReader::class.java)
                assertThat(context).hasSingleBean(PremiumCacheWriter::class.java)
                assertThat(context).doesNotHaveBean(CommonJdbcConfiguration::class.java)
                assertThat(context).doesNotHaveBean(CommonJpaConfiguration::class.java)
                assertThat(context).doesNotHaveBean(DataSource::class.java)
            }
    }

    @Test
    fun `redis disabled context does not register time-series or common cache beans`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedisTimeSeriesAutoConfiguration::class.java,
                    CommonInfrastructureAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("redis.enabled=false")
            .withBean(StringRedisTemplate::class.java, { mock(StringRedisTemplate::class.java) })
            .run { context ->
                assertThat(context).doesNotHaveBean(TimeSeriesCacheSupport::class.java)
                assertThat(context).doesNotHaveBean(CommonCacheConfiguration::class.java)
                assertThat(context).doesNotHaveBean(PremiumCacheReader::class.java)
            }
    }

    @Test
    fun `JDBC-only context registers persistence beans without JPA or Redis`() {
        contextRunner
            .withBean(JdbcTemplate::class.java, { mock(JdbcTemplate::class.java) })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .run { context ->
                assertThat(context).hasSingleBean(CommonJdbcConfiguration::class.java)
                assertThat(context).hasSingleBean(JdbcExchangeRateRepositoryAdapter::class.java)
                assertThat(context).doesNotHaveBean(CommonJpaConfiguration::class.java)
                assertThat(context).doesNotHaveBean(CommonCacheConfiguration::class.java)
            }
    }
}
