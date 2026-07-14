package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class V12MigrationSafetyAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(V12MigrationSafetyAutoConfiguration::class.java))

    @Test
    fun `Flyway enabled API에는 V12 preflight bean을 등록한다`() {
        contextRunner
            .withPropertyValues("spring.flyway.enabled=true")
            .run { context ->
                assertThat(context.containsBean("v12MigrationSafetyCallback")).isTrue()
                assertThat(context.containsBean("v12MigrationSafetyFlywayCustomizer")).isTrue()
            }
    }

    @Test
    fun `Flyway disabled Batch에는 V12 preflight bean을 등록하지 않는다`() {
        contextRunner
            .withPropertyValues("spring.flyway.enabled=false")
            .run { context ->
                assertThat(context.containsBean("v12MigrationSafetyCallback")).isFalse()
                assertThat(context.containsBean("v12MigrationSafetyFlywayCustomizer")).isFalse()
            }
    }
}
