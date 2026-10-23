package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMonitorState
import io.premiumspread.domain.accountpnl.OutageState
import io.premiumspread.domain.accountpnl.PnlAlertState
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.infrastructure.common.migration.V19OwnerAlertChannelMigrationIntegrationTest
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@Testcontainers
class JdbcAccountPnlAlertStoreIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var transaction: TransactionTemplate
    private lateinit var store: JdbcAccountPnlAlertStore

    @BeforeEach
    fun setUp() {
        migrateLatest()
        val dataSource = DriverManagerDataSource(jdbcUrl, mysql.username, mysql.password)
        val transactionManager = DataSourceTransactionManager(dataSource)
        jdbcTemplate = JdbcTemplate(dataSource)
        transaction = TransactionTemplate(transactionManager)
        store = JdbcAccountPnlAlertStore(jdbcTemplate, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC))
    }

    @Test
    fun `빈 테이블이면 초기 상태 version 0`() {
        assertThat(store.load()).isEqualTo(
            AccountPnlMonitorState(PnlAlertState.INITIAL, OutageState.INITIAL, 0),
        )
    }

    @Test
    fun `상태와 메일을 저장하고 다시 읽는다`() {
        assertThat(store.save(0, STATE, listOf(mail("profit-1")))).isTrue()

        assertThat(store.load()).isEqualTo(STATE.copy(version = 1))
        assertThat(mailRows()).singleElement().satisfies({ row ->
            assertThat(row["event_key"]).isEqualTo("profit-1")
            assertThat(row["subject"]).isEqualTo("subject profit-1")
            assertThat(row["body"]).isEqualTo("body profit-1")
            assertThat(row["status"]).isEqualTo("PENDING")
            assertThat(row["attempt_count"]).isEqualTo(0)
            assertThat(row["claim_token"]).isNull()
            assertThat(row["locked_by"]).isNull()
        })
        assertThat(instantOf("created_at", mailId("profit-1"))).isEqualTo(NOW)

        val next = AccountPnlMonitorState(PnlAlertState.INITIAL, OutageState.INITIAL, 1)
        assertThat(store.save(1, next, emptyList())).isTrue()
        assertThat(store.load()).isEqualTo(next.copy(version = 2))
    }

    @Test
    fun `version 이 다르면 상태도 메일도 저장하지 않는다`() {
        assertThat(store.save(1, STATE, listOf(mail("no-row")))).isFalse()
        assertThat(store.load().version).isZero()
        assertThat(mailRows()).isEmpty()

        assertThat(store.save(0, STATE, listOf(mail("first")))).isTrue()
        val overwrite = AccountPnlMonitorState(PnlAlertState.INITIAL, OutageState.INITIAL, 0)

        assertThat(store.save(0, overwrite, listOf(mail("stale-insert")))).isFalse()
        assertThat(store.save(5, overwrite, listOf(mail("stale-update")))).isFalse()

        assertThat(store.load()).isEqualTo(STATE.copy(version = 1))
        assertThat(mailRows().map { it["event_key"] }).containsExactly("first")
    }

    @Test
    fun `동시에 첫 저장을 해도 한쪽만 성공한다`() {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            executor.invokeAll(
                listOf("a", "b").map { key ->
                    Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        store.save(0, STATE, listOf(mail("concurrent-$key")))
                    }
                },
            ).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(results).containsExactlyInAnyOrder(true, false)
        assertThat(store.load().version).isEqualTo(1)
        assertThat(mailRows()).hasSize(1)
    }

    @Test
    fun `같은 eventKey 메일은 한 번만 저장된다`() {
        assertThat(store.save(0, STATE, listOf(mail("same"), mail("same").copy(subject = "dup in batch")))).isTrue()
        assertThat(store.save(1, STATE, listOf(mail("same").copy(subject = "later"), mail("other")))).isTrue()

        assertThat(mailRows().map { it["event_key"] to it["subject"] }).containsExactly(
            "same" to "subject same",
            "other" to "subject other",
        )
        assertThat(store.load().version).isEqualTo(2)
    }

    @Test
    fun `두 claim 이 같은 행을 중복으로 잡지 않는다`() {
        store.save(0, STATE, listOf(mail("only")))
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        val claims = try {
            executor.invokeAll(
                listOf("worker-a", "worker-b").map { owner ->
                    Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        store.claim(owner, 10, NOW)
                    }
                },
            ).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(claims.flatten()).hasSize(1)
        assertThat(claims.count { it.isEmpty() }).isEqualTo(1)
    }

    @Test
    fun `여러 행을 동시에 claim 해도 중복 없이 나눠 잡는다`() {
        store.save(0, STATE, (1..20).map { mail("bulk-$it") })
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        val claims = try {
            executor.invokeAll(
                listOf("worker-a", "worker-b").map { owner ->
                    Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        store.claim(owner, 20, NOW)
                    }
                },
            ).flatMap { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(claims).hasSize(20)
        assertThat(claims.map { it.id }).doesNotHaveDuplicates()
        assertThat(claims.map { it.claimToken }).doesNotHaveDuplicates()
    }

    @Test
    fun `다른 트랜잭션이 잠근 행은 건너뛰고 다음 행을 잡는다`() {
        store.save(0, STATE, listOf(mail("locked"), mail("free")))
        val lockedId = mailId("locked")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        val claimed = try {
            val holder = executor.submit {
                transaction.executeWithoutResult {
                    jdbcTemplate.queryForList("SELECT id FROM account_pnl_mail WHERE id = ? FOR UPDATE", lockedId)
                    locked.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            val result = store.claim("worker", 10, NOW)
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            result
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        assertThat(claimed.map { it.mail.eventKey }).containsExactly("free")
        assertThat(statusOf("locked")).isEqualTo("PENDING")
    }

    @Test
    fun `claim 은 commit 후 PROCESSING 이다`() {
        store.save(0, STATE, listOf(mail("first"), mail("second"), mail("third")))

        assertThatThrownBy {
            transaction.executeWithoutResult {
                store.claim("outer-worker", 1, NOW)
                throw RollbackMarker()
            }
        }.isInstanceOf(RollbackMarker::class.java)
        assertThat(statusOf("first")).isEqualTo("PROCESSING")

        val claimed = store.claim("worker", 1, NOW.plusSeconds(1))

        assertThat(claimed).singleElement().satisfies({ claim ->
            assertThat(claim.mail).isEqualTo(mail("second"))
            assertThat(claim.attemptCount).isZero()
            assertThat(claim.claimToken).hasSize(36)
        })
        val row = jdbcTemplate.queryForMap(
            "SELECT status, locked_by, claim_token, locked_at FROM account_pnl_mail WHERE id = ?",
            claimed.single().id,
        )
        assertThat(row["status"]).isEqualTo("PROCESSING")
        assertThat(row["locked_by"]).isEqualTo("worker")
        assertThat(row["claim_token"]).isEqualTo(claimed.single().claimToken)
        assertThat(instantOf("locked_at", claimed.single().id)).isEqualTo(NOW.plusSeconds(1))
        assertThat(statusOf("third")).isEqualTo("PENDING")
    }

    @Test
    fun `다른 token 의 markSent 는 false 이고 상태를 바꾸지 않는다`() {
        store.save(0, STATE, listOf(mail("sent")))
        val claimed = store.claim("worker", 10, NOW).single()

        assertThat(store.markSent(claimed.id, "00000000-0000-0000-0000-000000000000", NOW.plusSeconds(1))).isFalse()
        assertThat(store.markAttemptFailed(claimed.id, "00000000-0000-0000-0000-000000000000", NOW)).isFalse()
        val untouched = jdbcTemplate.queryForMap(
            "SELECT status, claim_token, attempt_count, sent_at FROM account_pnl_mail WHERE id = ?",
            claimed.id,
        )
        assertThat(untouched["status"]).isEqualTo("PROCESSING")
        assertThat(untouched["claim_token"]).isEqualTo(claimed.claimToken)
        assertThat(untouched["attempt_count"]).isEqualTo(0)
        assertThat(untouched["sent_at"]).isNull()

        assertThat(store.markSent(claimed.id, claimed.claimToken, NOW.plusSeconds(2))).isTrue()
        assertThat(store.markSent(claimed.id, claimed.claimToken, NOW.plusSeconds(3))).isFalse()
        val sent = jdbcTemplate.queryForMap(
            "SELECT status, claim_token, locked_by, sent_at FROM account_pnl_mail WHERE id = ?",
            claimed.id,
        )
        assertThat(sent["status"]).isEqualTo("SENT")
        assertThat(sent["claim_token"]).isNull()
        assertThat(sent["locked_by"]).isNull()
        assertThat(instantOf("sent_at", claimed.id)).isEqualTo(NOW.plusSeconds(2))
        assertThat(store.claim("worker", 10, NOW.plusSeconds(4))).isEmpty()
    }

    @Test
    fun `5번째 실패에서 FAILED`() {
        store.save(0, STATE, listOf(mail("retry")))

        repeat(AccountPnlAlertStore.MAX_ATTEMPTS - 1) { index ->
            val claimed = store.claim("worker", 10, NOW.plusSeconds(index.toLong())).single()
            assertThat(claimed.attemptCount).isEqualTo(index)
            assertThat(store.markAttemptFailed(claimed.id, claimed.claimToken, NOW.plusSeconds(index.toLong())))
                .isTrue()
            assertThat(statusOf("retry")).isEqualTo("PENDING")
            assertThat(attemptCountOf("retry")).isEqualTo(index + 1)
            assertThat(store.markAttemptFailed(claimed.id, claimed.claimToken, NOW)).isFalse()
        }

        val last = store.claim("worker", 10, NOW.plusSeconds(10)).single()
        assertThat(last.attemptCount).isEqualTo(4)
        assertThat(store.markAttemptFailed(last.id, last.claimToken, NOW.plusSeconds(10))).isTrue()

        val failed = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, claim_token, locked_by, locked_at FROM account_pnl_mail",
        )
        assertThat(failed["status"]).isEqualTo("FAILED")
        assertThat(failed["attempt_count"]).isEqualTo(5)
        assertThat(failed["claim_token"]).isNull()
        assertThat(failed["locked_by"]).isNull()
        assertThat(failed["locked_at"]).isNull()
        assertThat(store.claim("worker", 10, NOW.plusSeconds(11))).isEmpty()
        assertThat(store.recoverStale(NOW.plus(Duration.ofDays(1)), STALE_AFTER)).isZero()
    }

    @Test
    fun `stale PROCESSING 은 PENDING 으로 복구되고 이전 token 의 markSent 는 false`() {
        store.save(0, STATE, listOf(mail("stale")))
        val stale = store.claim("stale-worker", 10, NOW).single()

        assertThat(store.recoverStale(NOW.plus(STALE_AFTER), STALE_AFTER)).isZero()
        assertThat(statusOf("stale")).isEqualTo("PROCESSING")

        assertThat(store.recoverStale(NOW.plus(STALE_AFTER).plusNanos(1_000), STALE_AFTER)).isEqualTo(1)
        val recovered = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, claim_token, locked_by, locked_at FROM account_pnl_mail",
        )
        assertThat(recovered["status"]).isEqualTo("PENDING")
        assertThat(recovered["attempt_count"]).isEqualTo(1)
        assertThat(recovered["claim_token"]).isNull()
        assertThat(recovered["locked_by"]).isNull()
        assertThat(recovered["locked_at"]).isNull()

        assertThat(store.markSent(stale.id, stale.claimToken, NOW.plusSeconds(400))).isFalse()
        val current = store.claim("current-worker", 10, NOW.plusSeconds(400)).single()
        assertThat(current.claimToken).isNotEqualTo(stale.claimToken)
        assertThat(store.markSent(stale.id, stale.claimToken, NOW.plusSeconds(401))).isFalse()
        assertThat(store.markAttemptFailed(stale.id, stale.claimToken, NOW.plusSeconds(401))).isFalse()
        assertThat(store.markSent(current.id, current.claimToken, NOW.plusSeconds(402))).isTrue()
    }

    @Test
    fun `5번째 시도가 stale 로 끝나면 FAILED 이고 다시 claim 되지 않는다`() {
        store.save(0, STATE, listOf(mail("stale-last")))
        repeat(AccountPnlAlertStore.MAX_ATTEMPTS - 1) {
            val claimed = store.claim("worker", 10, NOW).single()
            assertThat(store.markAttemptFailed(claimed.id, claimed.claimToken, NOW)).isTrue()
        }
        assertThat(attemptCountOf("stale-last")).isEqualTo(4)
        val last = store.claim("crashed-worker", 10, NOW).single()
        assertThat(last.attemptCount).isEqualTo(4)

        assertThat(store.recoverStale(NOW.plus(STALE_AFTER).plusSeconds(1), STALE_AFTER)).isEqualTo(1)

        val failed = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, claim_token, locked_by, locked_at FROM account_pnl_mail",
        )
        assertThat(failed["status"]).isEqualTo("FAILED")
        assertThat(failed["attempt_count"]).isEqualTo(5)
        assertThat(failed["claim_token"]).isNull()
        assertThat(failed["locked_by"]).isNull()
        assertThat(failed["locked_at"]).isNull()
        assertThat(store.claim("worker", 10, NOW.plusSeconds(400))).isEmpty()
        assertThat(store.markSent(last.id, last.claimToken, NOW.plusSeconds(401))).isFalse()
        assertThat(statusOf("stale-last")).isEqualTo("FAILED")
    }

    @Test
    fun `body 가 TEXT 한도를 넘으면 저장하지 않는다`() {
        val oversized = AccountPnlMail("too-big", "subject", "가".repeat(65_535 / 3 + 1))

        assertThatThrownBy { store.save(0, STATE, listOf(oversized)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.load().version).isZero()
        assertThat(mailRows()).isEmpty()
        assertThat(store.save(0, STATE, listOf(AccountPnlMail("fits", "subject", "가".repeat(65_535 / 3))))).isTrue()
    }

    @Test
    fun `같은 eventKey 의 메일과 Discord 행은 각각 한 행이고 다시 저장해도 늘지 않는다`() {
        val mails = listOf(mail("k"), discordRow("k"))

        assertThat(store.save(0, STATE, mails)).isTrue()
        assertThat(mailRows().map { it["event_key"] to it["channel"] }).containsExactly(
            "k" to "EMAIL",
            "k" to "DISCORD",
        )

        assertThat(store.save(1, STATE, mails)).isTrue()
        assertThat(store.load().version).isEqualTo(2)
        assertThat(mailRows().map { it["event_key"] to it["channel"] }).containsExactly(
            "k" to "EMAIL",
            "k" to "DISCORD",
        )
    }

    @Test
    fun `Discord 행은 claim 에서 채널과 Discord 메타를 그대로 돌려준다`() {
        val discord = AccountPnlMail(
            "k-d",
            "📊 정시 보고 12:00",
            "설명",
            OwnerAlertChannel.DISCORD,
            DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT),
            0x3498DB,
        )
        assertThat(store.save(0, STATE, listOf(discord))).isTrue()

        val claimed = store.claim("worker", 10, NOW).single()

        assertThat(claimed.mail).isEqualTo(discord)
        val row = jdbcTemplate.queryForMap(
            "SELECT channel, discord_target, discord_push, discord_color FROM account_pnl_mail WHERE id = ?",
            claimed.id,
        )
        assertThat(row["channel"]).isEqualTo("DISCORD")
        assertThat(row["discord_target"]).isEqualTo("OPS")
        assertThat(row["discord_push"]).isEqualTo("SILENT")
        assertThat(row["discord_color"]).isEqualTo(0x3498DB)
    }

    @Test
    fun `channel 없이 넣은 행은 메일 행으로 읽힌다`() {
        jdbcTemplate.update(
            V19OwnerAlertChannelMigrationIntegrationTest.OLD_INSERT_MAIL.trimIndent(),
            "k",
            "s",
            "b",
            Timestamp.from(NOW),
        )

        val claimed = store.claim("worker", 10, NOW).single()

        assertThat(claimed.mail).isEqualTo(AccountPnlMail("k", "s", "b"))
    }

    @Test
    fun `markFailed 는 claim 된 행을 바로 FAILED 로 바꾸고 시도 횟수를 1 늘린다`() {
        store.save(0, STATE, listOf(discordRow("fail-now")))
        val claimed = store.claim("worker", 10, NOW).single()

        assertThat(store.markFailed(claimed.id, claimed.claimToken, NOW.plusSeconds(1))).isTrue()

        val failed = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, claim_token, locked_by, locked_at FROM account_pnl_mail WHERE id = ?",
            claimed.id,
        )
        assertThat(failed["status"]).isEqualTo("FAILED")
        assertThat(failed["attempt_count"]).isEqualTo(1)
        assertThat(failed["claim_token"]).isNull()
        assertThat(failed["locked_by"]).isNull()
        assertThat(failed["locked_at"]).isNull()
        assertThat(store.markFailed(claimed.id, claimed.claimToken, NOW.plusSeconds(2))).isFalse()
        assertThat(store.claim("worker", 10, NOW.plusSeconds(3))).isEmpty()
        assertThat(store.recoverStale(NOW.plus(Duration.ofDays(1)), STALE_AFTER)).isZero()
    }

    @Test
    fun `markFailed 는 token 이 다르거나 PENDING 이면 false 이고 행을 바꾸지 않는다`() {
        store.save(0, STATE, listOf(mail("claimed"), mail("pending")))
        val claimed = store.claim("worker", 1, NOW).single()
        val pendingId = mailId("pending")

        assertThat(store.markFailed(claimed.id, "00000000-0000-0000-0000-000000000000", NOW)).isFalse()
        assertThat(store.markFailed(pendingId, claimed.claimToken, NOW)).isFalse()

        val untouched = jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, claim_token, locked_by FROM account_pnl_mail WHERE id = ?",
            claimed.id,
        )
        assertThat(untouched["status"]).isEqualTo("PROCESSING")
        assertThat(untouched["attempt_count"]).isEqualTo(0)
        assertThat(untouched["claim_token"]).isEqualTo(claimed.claimToken)
        assertThat(untouched["locked_by"]).isEqualTo("worker")
        assertThat(instantOf("locked_at", claimed.id)).isEqualTo(NOW)
        assertThat(statusOf("pending")).isEqualTo("PENDING")
        assertThat(attemptCountOf("pending")).isZero()
    }

    private fun mail(eventKey: String) = AccountPnlMail(eventKey, "subject $eventKey", "body $eventKey")

    private fun discordRow(eventKey: String) = AccountPnlMail(
        eventKey,
        "subject $eventKey",
        "body $eventKey",
        OwnerAlertChannel.DISCORD,
        DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE),
        0x2ECC71,
    )

    private fun mailRows(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT * FROM account_pnl_mail ORDER BY id")

    private fun mailId(eventKey: String): Long = requireNotNull(
        jdbcTemplate.queryForObject("SELECT id FROM account_pnl_mail WHERE event_key = ?", Long::class.java, eventKey),
    )

    private fun instantOf(column: String, id: Long): Instant? = jdbcTemplate.query(
        "SELECT $column FROM account_pnl_mail WHERE id = ?",
        { rs, _ -> rs.getTimestamp(column)?.toInstant() },
        id,
    ).single()

    private fun statusOf(eventKey: String): String = requireNotNull(
        jdbcTemplate.queryForObject(
            "SELECT status FROM account_pnl_mail WHERE event_key = ?",
            String::class.java,
            eventKey,
        ),
    )

    private fun attemptCountOf(eventKey: String): Int = requireNotNull(
        jdbcTemplate.queryForObject(
            "SELECT attempt_count FROM account_pnl_mail WHERE event_key = ?",
            Int::class.java,
            eventKey,
        ),
    )

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
        private val NOW = Instant.parse("2026-07-15T01:00:00.123456Z")
        private val STALE_AFTER = Duration.ofMinutes(5)
        private val STATE = AccountPnlMonitorState(
            alert = PnlAlertState(
                inEpisode = true,
                positionFingerprint = "0.5|150000000|-0.5|100000",
                lastSentAt = Instant.parse("2026-07-15T00:59:00.654321Z"),
            ),
            outage = OutageState(
                lastSuccessAt = Instant.parse("2026-07-15T00:59:30.000001Z"),
                monitoringSince = Instant.parse("2026-07-14T23:00:00Z"),
                outageNotified = true,
            ),
            version = 0,
        )

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_account_pnl")
            .withUsername("test")
            .withPassword("test")
    }

    private class RollbackMarker : RuntimeException()
}
