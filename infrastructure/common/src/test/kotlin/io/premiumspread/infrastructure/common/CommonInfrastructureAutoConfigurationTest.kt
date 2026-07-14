package io.premiumspread.infrastructure.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CommonInfrastructureAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonInfrastructureAutoConfiguration::class.java))

    @Test
    fun `auto-configuration can be loaded independently`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(CommonInfrastructureAutoConfiguration::class.java)
        }
    }
}
