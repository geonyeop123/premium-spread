package io.premiumspread.config

import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class FlywayOwnershipIntegrationTest {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `API는 공통 migration resource를 V13까지 적용한다`() {
        assertThat(applicationContext.containsBean("flyway")).isTrue()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '13' AND success = 1",
                Long::class.java,
            ),
        ).isEqualTo(1L)
    }
}
