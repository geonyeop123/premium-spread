package io.premiumspread.support

import io.premiumspread.config.BatchTestConfig
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, BatchTestConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class BatchIntegrationTestBase {

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanUp() {
        // batch 전용 테이블 truncate (JPA Entity가 없으므로 직접 처리)
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0")
        BatchTestConfig.BATCH_TABLES.forEach { table ->
            jdbcTemplate.execute("TRUNCATE TABLE `$table`")
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1")

        // Redis flush
        redisTemplate.connectionFactory?.connection?.use { conn: RedisConnection ->
            conn.serverCommands().flushAll()
        }
    }
}
