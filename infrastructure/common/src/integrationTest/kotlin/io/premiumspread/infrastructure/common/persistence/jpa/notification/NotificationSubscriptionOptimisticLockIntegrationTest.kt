package io.premiumspread.infrastructure.common.persistence.jpa.notification

import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.ThresholdDirection
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.RollbackException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

@Tag("integration")
@Testcontainers
class NotificationSubscriptionOptimisticLockIntegrationTest {

    private lateinit var entityManagerFactory: EntityManagerFactory
    private var subscriptionId: Long = 0

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
        subscriptionId = insertSubscription(JdbcTemplate(dataSource))
        entityManagerFactory = createEntityManagerFactory(dataSource)
    }

    @AfterEach
    fun tearDown() {
        if (::entityManagerFactory.isInitialized) entityManagerFactory.close()
    }

    @Test
    fun `겹친 두 transaction 중 stale update는 optimistic lock 실패하고 revision을 유실하지 않는다`() {
        val first = entityManagerFactory.createEntityManager()
        val second = entityManagerFactory.createEntityManager()
        try {
            first.transaction.begin()
            second.transaction.begin()
            val firstSubscription = first.find(NotificationSubscription::class.java, subscriptionId)
            val staleSubscription = second.find(NotificationSubscription::class.java, subscriptionId)

            firstSubscription.changeThreshold(BigDecimal("7.5"))
            staleSubscription.changeDirection(ThresholdDirection.BELOW)
            first.transaction.commit()

            assertThatThrownBy { second.transaction.commit() }
                .isInstanceOf(RollbackException::class.java)
                .hasCauseInstanceOf(OptimisticLockException::class.java)
        } finally {
            if (first.transaction.isActive) first.transaction.rollback()
            if (second.transaction.isActive) second.transaction.rollback()
            first.close()
            second.close()
        }

        entityManagerFactory.createEntityManager().use { verifier ->
            val persisted = verifier.find(NotificationSubscription::class.java, subscriptionId)
            assertThat(persisted.threshold).isEqualByComparingTo("7.5")
            assertThat(persisted.direction).isEqualTo(ThresholdDirection.ABOVE)
            assertThat(persisted.revision).isEqualTo(2)
        }
    }

    private fun insertSubscription(jdbcTemplate: JdbcTemplate): Long {
        val now = Timestamp.from(Instant.parse("2026-07-15T01:00:00Z"))
        jdbcTemplate.update(
            """
            INSERT INTO member (email, password, nickname, status, created_at, updated_at)
            VALUES ('lock@example.com', 'encoded', 'lock-test', 'ACTIVE', ?, ?)
            """.trimIndent(),
            now,
            now,
        )
        val memberId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM member", Long::class.java))
        jdbcTemplate.update(
            """
            INSERT INTO notification_subscription (
                member_id, symbol, korea_exchange, foreign_exchange, revision, lock_version,
                direction, threshold, status, created_at, updated_at
            ) VALUES (?, 'BTC', 'BITHUMB', 'BINANCE', 1, 0, 'ABOVE', 5.0, 'ACTIVE', ?, ?)
            """.trimIndent(),
            memberId,
            now,
            now,
        )
        return requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM notification_subscription", Long::class.java))
    }

    private fun createEntityManagerFactory(dataSource: DriverManagerDataSource): EntityManagerFactory {
        val factory = LocalContainerEntityManagerFactoryBean().apply {
            this.dataSource = dataSource
            setPackagesToScan("io.premiumspread.domain")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(
                mapOf(
                    "hibernate.hbm2ddl.auto" to "none",
                    "hibernate.jdbc.time_zone" to "UTC",
                    "hibernate.show_sql" to "false",
                ),
            )
            afterPropertiesSet()
        }
        return requireNotNull(factory.`object`)
    }

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) {
            "&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        } else {
            "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_notification_lock")
            .withUsername("test")
            .withPassword("test")
    }
}
