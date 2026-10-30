package io.premiumspread.support

import io.premiumspread.config.BatchTestConfig
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.TimeUnit

/**
 * 같은 설정의 하위 클래스는 Spring context 를 공유한다(@DirtiesContext 없음).
 * 공용 상태(DB·Redis·MockWebServer)는 매 테스트 전에 [cleanUp] 이 초기화한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, BatchTestConfig::class)
abstract class BatchIntegrationTestBase {

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    /** BatchTestConfig 의 공용 서버. 하위 클래스의 `exchangeRateMockServer` 와 이름이 겹치지 않게 다른 이름을 쓴다. */
    @Autowired
    private lateinit var sharedExchangeRateMockServer: MockWebServer

    /**
     * 공용 서버는 대기 응답(dispatcher)과 요청 기록을 비운다.
     * 공용 서버의 `requestCount` 는 누적값이라 단언하지 않는다.
     */
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

        // 공용 MockWebServer 초기화: 대기 응답 큐와 요청 기록
        sharedExchangeRateMockServer.dispatcher = QueueDispatcher()
        while (sharedExchangeRateMockServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }
}
