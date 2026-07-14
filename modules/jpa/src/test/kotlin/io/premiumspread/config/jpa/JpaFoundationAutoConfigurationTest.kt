package io.premiumspread.config.jpa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

class JpaFoundationAutoConfigurationTest {
    @Test
    fun `foundation auto-configuration backs off for a user datasource`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaFoundationAutoConfiguration::class.java))
            .withBean(DataSource::class.java, { mock(DataSource::class.java) })
            .run { context ->
                assertThat(context).hasSingleBean(JpaFoundationAutoConfiguration::class.java)
                assertThat(context).hasSingleBean(DataSource::class.java)
                assertThat(context).doesNotHaveBean(DataSourceConfig::class.java)
            }
    }

    @Test
    fun `foundation configurations are published as Boot auto-configurations`() {
        val imports = PathMatchingResourcePatternResolver()
            .getResources("classpath*:$AUTO_CONFIGURATION_IMPORTS")
            .flatMap { resource -> resource.inputStream.bufferedReader().use { it.readLines() } }

        assertThat(imports).contains(
            JpaFoundationAutoConfiguration::class.java.name,
            JpaAuditingAutoConfiguration::class.java.name,
        )
    }

    private companion object {
        const val AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }
}
