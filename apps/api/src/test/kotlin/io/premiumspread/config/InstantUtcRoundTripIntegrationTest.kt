package io.premiumspread.config

import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.infrastructure.member.MemberJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(
    MySqlTestContainersConfig::class,
    RedisTestContainersConfig::class,
    TestConfig::class,
    InstantUtcRoundTripIntegrationTest.FixedClockConfiguration::class,
)
class InstantUtcRoundTripIntegrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val memberJpaRepository: MemberJpaRepository,
) {
    @AfterEach
    fun cleanUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS instant_utc_round_trip")
        memberJpaRepository.deleteAll()
    }

    @Test
    fun `DATETIME 6은 UTC Instant를 microsecond 손실이나 9시간 offset 없이 왕복한다`() {
        val expected = Instant.parse("2026-07-14T03:04:05.123456Z")
        jdbcTemplate.execute(
            "CREATE TABLE instant_utc_round_trip (id BIGINT PRIMARY KEY, observed_at DATETIME(6) NOT NULL)",
        )

        jdbcTemplate.update(
            "INSERT INTO instant_utc_round_trip (id, observed_at) VALUES (?, ?)",
            1L,
            Timestamp.from(expected),
        )

        val actual = jdbcTemplate.queryForObject(
            "SELECT observed_at FROM instant_utc_round_trip WHERE id = 1",
            Timestamp::class.java,
        )!!.toInstant()
        val sessionTimeZone = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String::class.java)

        assertThat(actual).isEqualTo(expected)
        assertThat(sessionTimeZone).isIn("+00:00", "UTC")
    }

    @Test
    fun `JPA auditing은 주입된 Clock으로 createdAt과 updatedAt을 기록한다`() {
        val member = memberJpaRepository.saveAndFlush(
            Member.create("utc-audit@example.com", "encoded-password"),
        )

        assertThat(member.createdAt).isEqualTo(FIXED_NOW)
        assertThat(member.updatedAt).isEqualTo(FIXED_NOW)
    }

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
    }

    companion object {
        private val FIXED_NOW = Instant.parse("2026-07-14T03:04:05.123456Z")
    }
}
