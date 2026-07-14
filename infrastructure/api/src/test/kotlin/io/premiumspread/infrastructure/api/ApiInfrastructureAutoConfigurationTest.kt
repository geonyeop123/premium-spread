package io.premiumspread.infrastructure.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ApiInfrastructureAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiInfrastructureAutoConfiguration::class.java))

    @Test
    fun `auto-configuration can be loaded independently`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ApiInfrastructureAutoConfiguration::class.java)
        }
    }
}
