package io.premiumspread.redis

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

class RedisRuntimePropertiesTest {
    @Test
    fun `Redis connection은 spring data redis 단일 설정을 사용한다`() {
        val yaml = ClassPathResource("redis.yml").inputStream.bufferedReader().use { it.readText() }

        org.assertj.core.api.Assertions.assertThat(yaml)
            .contains("spring:", "redis:", "REDIS_POOL_SIZE")
            .doesNotContain("datasource:")
    }

    @Test
    fun `Redisson minimum idle은 pool size를 넘을 수 없다`() {
        assertThatThrownBy { RedissonClientProperties(poolSize = 2, minimumIdle = 3) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("minimum-idle")
    }

    @Test
    fun `Redisson timeout은 양수여야 한다`() {
        assertThatThrownBy { RedissonClientProperties(commandTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("command-timeout")
    }

    @Test
    fun `prd profile은 local Redis fallback으로 기동하지 않는다`() {
        val environment = MockEnvironment().apply { setActiveProfiles("prd") }

        assertThatThrownBy {
            ProductionRedisSettingsValidator(environment, RedisRuntimeProperties())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("prd redis")
    }

    @Test
    fun `prd local Redis 설정은 application context startup을 실패시킨다`() {
        ApplicationContextRunner()
            .withUserConfiguration(ProductionRedisTestConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=prd",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6379",
                "spring.data.redis.password=",
            )
            .run { context ->
                org.assertj.core.api.Assertions.assertThat(context).hasFailed()
                org.assertj.core.api.Assertions.assertThat(context.startupFailure)
                    .hasRootCauseMessage("prd redis must not use a local address")
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisRuntimeProperties::class)
    @Import(ProductionRedisSettingsValidator::class)
    class ProductionRedisTestConfiguration
}
