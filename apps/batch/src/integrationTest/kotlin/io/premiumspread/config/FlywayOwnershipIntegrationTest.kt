package io.premiumspread.config

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment

class FlywayOwnershipIntegrationTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `Batch는 Flyway 없이 test fixture로 schema를 초기화한다`() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false")
        assertThat(applicationContext.beanDefinitionNames)
            .noneMatch { beanName -> beanName.contains("flyway", ignoreCase = true) }
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = 'premium_minute'",
                Long::class.java,
            ),
        ).isEqualTo(1L)
    }
}
