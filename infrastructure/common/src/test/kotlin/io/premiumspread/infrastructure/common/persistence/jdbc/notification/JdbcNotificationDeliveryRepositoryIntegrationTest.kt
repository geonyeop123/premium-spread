package io.premiumspread.infrastructure.common.persistence.jdbc.notification

import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationDeliveryEnqueueResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Tag("integration")
@Testcontainers
class JdbcNotificationDeliveryRepositoryIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var transaction: TransactionTemplate
    private lateinit var repository: JdbcNotificationDeliveryRepository
    private var subscriptionId: Long = 0

    @BeforeEach
    fun setUp() {
        migrateLatest()
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        jdbcTemplate = JdbcTemplate(dataSource)
        transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
        repository = JdbcNotificationDeliveryRepository(jdbcTemplate)
        subscriptionId = insertSubscription()
    }

    @Test
    fun `동일 event key enqueue는 한 건으로 dedupe된다`() {
        val delivery = newDelivery("event-1")

        assertThat(transaction.execute { repository.enqueue(delivery) })
            .isEqualTo(NotificationDeliveryEnqueueResult.ENQUEUED)
        assertThat(
            transaction.execute {
                repository.enqueue(delivery.copy(deliveryId = UUID.randomUUID().toString()))
            },
        )
            .isEqualTo(NotificationDeliveryEnqueueResult.DUPLICATE)
        assertThat(countDeliveries()).isEqualTo(1)
    }

    @Test
    fun `동일 event key 동시 enqueue도 한 건만 저장하고 정상 dedupe된다`() {
        val executor = Executors.newFixedThreadPool(2)
        val deliveries = listOf(newDelivery("concurrent-event"), newDelivery("concurrent-event"))

        val results = try {
            executor.invokeAll(
                deliveries.map { delivery ->
                    Callable { transaction.execute { repository.enqueue(delivery) } }
                },
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(results).containsExactlyInAnyOrder(
            NotificationDeliveryEnqueueResult.ENQUEUED,
            NotificationDeliveryEnqueueResult.DUPLICATE,
        )
        assertThat(countDeliveries()).isEqualTo(1)
    }

    @Test
    fun `outer transaction이 rollback되면 enqueue delivery도 남지 않는다`() {
        assertThatThrownBy {
            transaction.executeWithoutResult {
                repository.enqueue(newDelivery("rollback-event"))
                throw RollbackMarker()
            }
        }.isInstanceOf(RollbackMarker::class.java)

        assertThat(countDeliveries()).isZero()
    }

    @Test
    fun `두 poller는 SKIP LOCKED로 중복 없이 claim하고 delivery별 token을 생성한다`() {
        repeat(10) { repository.enqueue(newDelivery("event-$it")) }
        val executor = Executors.newFixedThreadPool(2)

        val claims = try {
            executor.invokeAll(
                listOf(
                    Callable { transaction.execute { repository.claimReady(NOW, 10, "worker-a") }.orEmpty() },
                    Callable { transaction.execute { repository.claimReady(NOW, 10, "worker-b") }.orEmpty() },
                ),
            ).flatMap { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(claims).hasSize(10)
        assertThat(claims.map { it.id }).doesNotHaveDuplicates()
        assertThat(claims.map { it.claimToken }).doesNotHaveDuplicates()
    }

    @Test
    fun `stale recovery 뒤 이전 owner는 새 claim 상태를 변경하지 못한다`() {
        repository.enqueue(newDelivery("event-stale"))
        val staleClaim = claim("stale-worker", NOW).single()

        assertThat(transaction.execute { repository.recoverStale(NOW.plusSeconds(1), NOW.plusSeconds(2)) })
            .isEqualTo(1)
        val currentClaim = claim("current-worker", NOW.plusSeconds(2)).single()

        assertThat(transaction.execute { repository.markSent(staleClaim.claim, NOW.plusSeconds(3)) }).isFalse()
        assertThat(transaction.execute { repository.markSent(currentClaim.claim, NOW.plusSeconds(3)) }).isTrue()
    }

    @Test
    fun `SENT PII scrub 뒤에도 event key audit과 dedupe는 보존된다`() {
        val delivery = newDelivery("event-scrub")
        repository.enqueue(delivery)
        val claimed = claim("worker", NOW).single()
        transaction.execute { repository.markSent(claimed.claim, NOW.plusSeconds(1)) }

        assertThat(
            transaction.execute {
                repository.scrubSentPii(NOW.plusSeconds(2), NOW.plusSeconds(3), 10)
            },
        ).isEqualTo(1)
        val scrubbed = jdbcTemplate.queryForMap(
            "SELECT event_key, status, recipient_email, subject, payload, scrubbed_at FROM notification_delivery",
        )
        assertThat(scrubbed["event_key"]).isEqualTo(delivery.eventKey)
        assertThat(scrubbed["status"]).isEqualTo("SENT")
        assertThat(scrubbed["recipient_email"]).isNull()
        assertThat(scrubbed["subject"]).isNull()
        assertThat(scrubbed["payload"]).isNull()
        assertThat(scrubbed["scrubbed_at"]).isNotNull()
        assertThat(repository.enqueue(delivery.copy(deliveryId = UUID.randomUUID().toString())))
            .isEqualTo(NotificationDeliveryEnqueueResult.DUPLICATE)
    }

    @Test
    fun `FAILED redrive는 operator audit을 남기고 다음 poller가 새 claim token을 발급한다`() {
        repository.enqueue(newDelivery("event-redrive"))
        val failed = claim("initial-worker", NOW).single()
        transaction.execute { repository.markFailed(failed.claim, "smtp failed", NOW.plusSeconds(1)) }

        val redriven = transaction.execute {
            repository.redriveFailed(
                deliveryId = failed.deliveryId,
                actor = "operator@example.com",
                reason = "incident-42 approved",
                redrivenAt = NOW.plusSeconds(2),
            )
        }

        assertThat(redriven).isTrue()
        val reclaimed = claim("redrive-worker", NOW.plusSeconds(2)).single()
        assertThat(reclaimed.claimToken).isNotEqualTo(failed.claimToken)
        assertThat(reclaimed.attemptCount).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, redrive_actor, redrive_reason, redriven_at FROM notification_delivery",
            ),
        ).containsEntry("status", "PROCESSING")
            .containsEntry("redrive_actor", "operator@example.com")
            .containsEntry("redrive_reason", "incident-42 approved")
    }

    private fun claim(workerId: String, now: Instant) =
        transaction.execute { repository.claimReady(now, 10, workerId) }.orEmpty()

    private fun newDelivery(eventKey: String) = NewNotificationDelivery(
        deliveryId = UUID.randomUUID().toString(),
        subscriptionId = subscriptionId,
        eventKey = eventKey,
        recipientEmail = "member@example.com",
        subject = "subject",
        payload = "payload",
        nextAttemptAt = NOW,
        createdAt = NOW,
    )

    private fun countDeliveries(): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_delivery", Int::class.java))

    private fun insertSubscription(): Long {
        jdbcTemplate.update(
            """
            INSERT INTO member (email, password, nickname, status, created_at, updated_at)
            VALUES ('member@example.com', 'encoded', 'member', 'ACTIVE', ?, ?)
            """.trimIndent(),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW),
        )
        val memberId = requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM member", Long::class.java))
        jdbcTemplate.update(
            """
            INSERT INTO notification_subscription (
                member_id, symbol, korea_exchange, foreign_exchange, revision,
                direction, threshold, status, created_at, updated_at
            ) VALUES (?, 'BTC', 'BITHUMB', 'BINANCE', 1, 'ABOVE', 5.0, 'ACTIVE', ?, ?)
            """.trimIndent(),
            memberId,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW),
        )
        return requireNotNull(jdbcTemplate.queryForObject("SELECT id FROM notification_subscription", Long::class.java))
    }

    private fun migrateLatest() {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
    }

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) {
            "&sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        } else {
            "?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        }

    companion object {
        private val NOW = Instant.parse("2026-07-15T01:00:00Z")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_notification")
            .withUsername("test")
            .withPassword("test")
    }

    private class RollbackMarker : RuntimeException()
}
