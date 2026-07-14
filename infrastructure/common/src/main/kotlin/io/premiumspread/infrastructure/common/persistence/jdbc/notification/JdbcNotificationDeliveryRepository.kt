package io.premiumspread.infrastructure.common.persistence.jdbc.notification

import io.premiumspread.domain.notification.ClaimedNotificationDelivery
import io.premiumspread.domain.notification.DeliveryClaim
import io.premiumspread.domain.notification.NewNotificationDelivery
import io.premiumspread.domain.notification.NotificationDeliveryEnqueueResult
import io.premiumspread.domain.notification.NotificationDeliveryPort
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcNotificationDeliveryRepository(
    private val jdbcTemplate: JdbcTemplate,
) : NotificationDeliveryPort {

    override fun enqueue(delivery: NewNotificationDelivery): NotificationDeliveryEnqueueResult {
        require(delivery.deliveryId.isNotBlank()) { "deliveryId must not be blank." }
        require(delivery.eventKey.isNotBlank()) { "eventKey must not be blank." }
        require(delivery.subscriptionId > 0) { "subscriptionId must be positive." }
        return try {
            jdbcTemplate.update(
                """
                INSERT INTO notification_delivery (
                    delivery_id, subscription_id, event_key,
                    recipient_email, subject, payload,
                    status, attempt_count, next_attempt_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """.trimIndent(),
                delivery.deliveryId,
                delivery.subscriptionId,
                delivery.eventKey,
                delivery.recipientEmail,
                delivery.subject,
                delivery.payload,
                delivery.nextAttemptAt.toSqlTimestamp(),
                delivery.createdAt.toSqlTimestamp(),
                delivery.createdAt.toSqlTimestamp(),
            )
            NotificationDeliveryEnqueueResult.ENQUEUED
        } catch (exception: DuplicateKeyException) {
            if (existsEventKey(delivery.eventKey)) {
                NotificationDeliveryEnqueueResult.DUPLICATE
            } else {
                throw exception
            }
        }
    }

    override fun claimReady(
        now: Instant,
        limit: Int,
        workerId: String,
    ): List<ClaimedNotificationDelivery> {
        require(limit > 0) { "limit must be positive." }
        require(workerId.isNotBlank()) { "workerId must not be blank." }

        val ready = jdbcTemplate.query(
            """
            SELECT id, delivery_id, subscription_id, event_key,
                   recipient_email, subject, payload, attempt_count, created_at
            FROM notification_delivery
            WHERE status = 'PENDING'
              AND next_attempt_at <= ?
            ORDER BY next_attempt_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ -> rs.toReadyDelivery() },
            now.toSqlTimestamp(),
            limit,
        )

        return ready.map { delivery ->
            val claimToken = UUID.randomUUID().toString()
            val affected = jdbcTemplate.update(
                """
                UPDATE notification_delivery
                SET status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    locked_at = ?,
                    locked_by = ?,
                    claim_token = ?,
                    updated_at = ?
                WHERE id = ?
                  AND status = 'PENDING'
                """.trimIndent(),
                now.toSqlTimestamp(),
                workerId,
                claimToken,
                now.toSqlTimestamp(),
                delivery.id,
            )
            check(affected == 1) { "Locked notification delivery could not be claimed: id=${delivery.id}" }
            delivery.claimedBy(workerId, claimToken)
        }
    }

    override fun markSent(claim: DeliveryClaim, sentAt: Instant): Boolean =
        jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET status = 'SENT',
                sent_at = ?,
                last_error = NULL,
                locked_at = NULL,
                locked_by = NULL,
                claim_token = NULL,
                updated_at = ?
            WHERE id = ?
              AND status = 'PROCESSING'
              AND locked_by = ?
              AND claim_token = ?
            """.trimIndent(),
            sentAt.toSqlTimestamp(),
            sentAt.toSqlTimestamp(),
            claim.id,
            claim.lockedBy,
            claim.claimToken,
        ) == 1

    override fun scheduleRetry(
        claim: DeliveryClaim,
        nextAttemptAt: Instant,
        reason: String,
        updatedAt: Instant,
    ): Boolean = jdbcTemplate.update(
        """
        UPDATE notification_delivery
        SET status = 'PENDING',
            next_attempt_at = ?,
            last_error = ?,
            locked_at = NULL,
            locked_by = NULL,
            claim_token = NULL,
            updated_at = ?
        WHERE id = ?
          AND status = 'PROCESSING'
          AND locked_by = ?
          AND claim_token = ?
        """.trimIndent(),
        nextAttemptAt.toSqlTimestamp(),
        reason.take(MAX_ERROR_LENGTH),
        updatedAt.toSqlTimestamp(),
        claim.id,
        claim.lockedBy,
        claim.claimToken,
    ) == 1

    override fun markFailed(claim: DeliveryClaim, reason: String, failedAt: Instant): Boolean =
        jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET status = 'FAILED',
                last_error = ?,
                locked_at = NULL,
                locked_by = NULL,
                claim_token = NULL,
                updated_at = ?
            WHERE id = ?
              AND status = 'PROCESSING'
              AND locked_by = ?
              AND claim_token = ?
            """.trimIndent(),
            reason.take(MAX_ERROR_LENGTH),
            failedAt.toSqlTimestamp(),
            claim.id,
            claim.lockedBy,
            claim.claimToken,
        ) == 1

    override fun recoverStale(staleBefore: Instant, recoveredAt: Instant): Int =
        jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET status = 'PENDING',
                next_attempt_at = ?,
                last_error = 'stale PROCESSING claim recovered',
                locked_at = NULL,
                locked_by = NULL,
                claim_token = NULL,
                updated_at = ?
            WHERE status = 'PROCESSING'
              AND locked_at < ?
            """.trimIndent(),
            recoveredAt.toSqlTimestamp(),
            recoveredAt.toSqlTimestamp(),
            staleBefore.toSqlTimestamp(),
        )

    override fun scrubSentPii(sentBefore: Instant, scrubbedAt: Instant, limit: Int): Int {
        require(limit > 0) { "limit must be positive." }
        return jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET recipient_email = NULL,
                subject = NULL,
                payload = NULL,
                scrubbed_at = ?,
                updated_at = ?
            WHERE status = 'SENT'
              AND sent_at < ?
              AND scrubbed_at IS NULL
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
            scrubbedAt.toSqlTimestamp(),
            scrubbedAt.toSqlTimestamp(),
            sentBefore.toSqlTimestamp(),
            limit,
        )
    }

    override fun redriveFailed(
        deliveryId: String,
        actor: String,
        reason: String,
        redrivenAt: Instant,
    ): Boolean {
        require(actor.isNotBlank()) { "actor must not be blank." }
        require(reason.isNotBlank()) { "reason must not be blank." }
        return jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET status = 'PENDING',
                attempt_count = 0,
                next_attempt_at = ?,
                locked_at = NULL,
                locked_by = NULL,
                claim_token = NULL,
                last_error = NULL,
                redrive_actor = ?,
                redrive_reason = ?,
                redriven_at = ?,
                updated_at = ?
            WHERE delivery_id = ?
              AND status = 'FAILED'
            """.trimIndent(),
            redrivenAt.toSqlTimestamp(),
            actor.take(MAX_ACTOR_LENGTH),
            reason.take(MAX_REDRIVE_REASON_LENGTH),
            redrivenAt.toSqlTimestamp(),
            redrivenAt.toSqlTimestamp(),
            deliveryId,
        ) == 1
    }

    private fun existsEventKey(eventKey: String): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification_delivery WHERE event_key = ?",
            Long::class.java,
            eventKey,
        ) != 0L

    private fun ResultSet.toReadyDelivery(): ReadyDelivery = ReadyDelivery(
        id = getLong("id"),
        deliveryId = getString("delivery_id"),
        subscriptionId = getLong("subscription_id"),
        eventKey = getString("event_key"),
        recipientEmail = requireNotNull(getString("recipient_email")) { "Pending delivery recipient was scrubbed." },
        subject = requireNotNull(getString("subject")) { "Pending delivery subject was scrubbed." },
        payload = requireNotNull(getString("payload")) { "Pending delivery payload was scrubbed." },
        attemptCount = getInt("attempt_count"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private data class ReadyDelivery(
        val id: Long,
        val deliveryId: String,
        val subscriptionId: Long,
        val eventKey: String,
        val recipientEmail: String,
        val subject: String,
        val payload: String,
        val attemptCount: Int,
        val createdAt: Instant,
    ) {
        fun claimedBy(workerId: String, claimToken: String): ClaimedNotificationDelivery =
            ClaimedNotificationDelivery(
                id = id,
                deliveryId = deliveryId,
                subscriptionId = subscriptionId,
                eventKey = eventKey,
                recipientEmail = recipientEmail,
                subject = subject,
                payload = payload,
                attemptCount = attemptCount + 1,
                lockedBy = workerId,
                claimToken = claimToken,
                createdAt = createdAt,
            )
    }

    private fun Instant.toSqlTimestamp(): Timestamp = Timestamp.from(this)

    private companion object {
        const val MAX_ERROR_LENGTH = 1_000
        const val MAX_ACTOR_LENGTH = 100
        const val MAX_REDRIVE_REASON_LENGTH = 500
    }
}
