package io.premiumspread.config.jpa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

class DatabasePropertiesTest {
    @Test
    fun `minimum idle은 maximum pool size를 넘을 수 없다`() {
        assertThatThrownBy { HikariPoolProperties(maximumPoolSize = 4, minimumIdle = 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("minimum-idle")
    }

    @Test
    fun `validation timeout은 connection timeout보다 짧아야 한다`() {
        assertThatThrownBy { HikariPoolProperties(connectionTimeout = 1_000, validationTimeout = 1_000) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("validation-timeout")
    }

    @Test
    fun `prd profile은 local datasource fallback으로 기동하지 않는다`() {
        val environment = MockEnvironment().apply { setActiveProfiles("prd") }
        val properties = DatabaseProperties(
            url = LOCAL_URL,
            username = "application",
            password = "application",
        )

        assertThatThrownBy { ProductionDatabaseSettingsValidator(environment, properties) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("prd datasource")
    }

    @Test
    fun `prd local datasource 설정은 application context startup을 실패시킨다`() {
        ApplicationContextRunner()
            .withUserConfiguration(DatabaseSettingsConfiguration::class.java)
            .withPropertyValues(
                "spring.profiles.active=prd",
                "spring.datasource.url=$LOCAL_URL",
                "spring.datasource.username=application",
                "spring.datasource.password=application",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage("prd datasource must not use a local address")
            }
    }

    @Test
    fun `prd profile은 명시적인 원격 datasource 설정을 허용한다`() {
        val environment = MockEnvironment().apply { setActiveProfiles("prd") }
        val properties = DatabaseProperties(
            url = "jdbc:mysql://db.internal:3306/premiumspread$UTC_PARAMETERS",
            username = "premium_service",
            password = "non-default-secret",
        )

        assertThat(ProductionDatabaseSettingsValidator(environment, properties)).isNotNull
    }

    private companion object {
        const val UTC_PARAMETERS = "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        const val LOCAL_URL = "jdbc:mysql://localhost:3306/premiumspread$UTC_PARAMETERS"
    }
}
