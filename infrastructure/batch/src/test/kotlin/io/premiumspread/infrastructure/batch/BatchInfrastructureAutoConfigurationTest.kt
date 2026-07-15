package io.premiumspread.infrastructure.batch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class BatchInfrastructureAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BatchInfrastructureAutoConfiguration::class.java))

    @Test
    fun `auto-configuration can be loaded independently`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(BatchInfrastructureAutoConfiguration::class.java)
        }
    }
}
