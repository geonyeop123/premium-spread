package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlLatest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import io.premiumspread.infrastructure.common.support.SharedMySqlContainer
import io.premiumspread.infrastructure.common.support.TestDatabase
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Tag("integration")
class JdbcAccountPnlLatestStoreIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcAccountPnlLatestStore

    @BeforeEach
    fun setUp() {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
        jdbcTemplate = JdbcTemplate(DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password))
        store = JdbcAccountPnlLatestStore(jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC))
    }

    @Test
    fun `h 빈 테이블은 null 이고 computed 와 noPosition upsert 는 단일 행을 덮어쓴다`() {
        assertThat(store.find()).isNull()

        val computed = AccountPnlLatest(true, BigDecimal("1.25"), BigDecimal("12345"), CALCULATED)
        store.record(computed)
        val first = store.find()!!
        assertThat(first.position).isTrue()
        assertThat(first.netPercent).isEqualByComparingTo("1.25")
        assertThat(first.netKrw).isEqualByComparingTo("12345")
        assertThat(first.calculatedAt).isEqualTo(CALCULATED)

        val later = CALCULATED.plusSeconds(60)
        store.record(AccountPnlLatest.noPosition(later))
        val second = store.find()!!
        assertThat(second.position).isFalse()
        assertThat(second.netPercent).isNull()
        assertThat(second.netKrw).isNull()
        assertThat(second.calculatedAt).isEqualTo(later)

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_pnl_latest", Int::class.java)).isEqualTo(1)
        val updatedAt = jdbcTemplate.queryForObject("SELECT updated_at FROM account_pnl_latest", Timestamp::class.java)
        assertThat(updatedAt!!.toInstant()).isEqualTo(NOW)

        store.record(computed)
        assertThat(store.find()!!.netPercent).isEqualByComparingTo("1.25")
    }

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) {
            "&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        } else {
            "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        }

    companion object {
        private val NOW = Instant.parse("2026-10-28T00:00:00Z")
        private val CALCULATED = Instant.parse("2026-10-27T23:59:00Z")

        val mysql: TestDatabase = SharedMySqlContainer.database("premiumspread_account_latest")
    }
}
