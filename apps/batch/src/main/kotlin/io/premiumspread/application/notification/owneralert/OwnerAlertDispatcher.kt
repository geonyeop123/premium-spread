package io.premiumspread.application.notification.owneralert

import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.ClaimedAccountPnlMail
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * owner 알림 outbox 전달 1회분 (design §5.2 전이표). claim 은 실행 가능한 concurrency(1)만큼, 회차 상한 [MAX_DELIVERIES_PER_RUN].
 * 재시도 가능 실패는 회차 끝에 되돌린다(같은 회차 재claim 방지). 매 claim 전 interrupt 를 확인한다.
 * #90 의 독립 전달 Job 이 그대로 재사용한다.
 */
class OwnerAlertDispatcher(
    private val store: AccountPnlAlertStore,
    private val mailSender: AccountPnlMailSender,
    private val discordSender: OwnerAlertDiscordSender?,
    private val clock: Clock,
    private val owner: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 빈 결과·회차 상한·취소(interrupt) 중 먼저 오는 것에서 멈춘다.
     *
     * 재시도 가능 실패 행은 회차 끝에서 시도 실패로 되돌린다. 바로 `PENDING` 으로 돌리면 id 순 claim 이 같은 회차에
     * 그 행을 다시 잡아 한 회차에 시도 횟수를 소진한다. 되돌리기 전까지는 이 owner 의 `PROCESSING` 이므로
     * 프로세스가 죽으면 stale 복구가 한 번의 시도로 센다.
     */
    fun dispatch(): JobResult {
        val failed = mutableListOf<ClaimedAccountPnlMail>()
        var outcome: JobResult? = null
        var claims = 0
        while (outcome == null && claims < MAX_DELIVERIES_PER_RUN) {
            claims++
            outcome = deliverNext(failed)
        }
        return releaseFailed(failed, outcome ?: JobResult.Success)
    }

    /**
     * 한 건을 claim·발송·전이한다. 계속하면 null, 멈추면 이 회차의 결과.
     * 성공은 `markSent`, 재시도 불가 실패는 `markFailed` 를 즉시, 재시도 가능 실패 건은 [failed] 에 모아 회차 끝에 되돌린다.
     */
    private fun deliverNext(failed: MutableList<ClaimedAccountPnlMail>): JobResult? {
        if (Thread.currentThread().isInterrupted) {
            log.warn("Owner alert delivery was cancelled; remaining rows stay pending")
            return JobResult.Success
        }
        val row = try {
            store.claim(owner, CLAIM_CONCURRENCY, clock.instant()).firstOrNull()
        } catch (exception: Exception) {
            return storeFailure("claim", exception)
        }
        if (row == null) return JobResult.Success
        return when (send(row)) {
            Delivery.SENT -> transition("markSent", row) { store.markSent(row.id, row.claimToken, clock.instant()) }

            Delivery.PERMANENT_FAILURE ->
                transition("markFailed", row) { store.markFailed(row.id, row.claimToken, clock.instant()) }

            Delivery.RETRYABLE_FAILURE -> {
                failed += row
                null
            }
        }
    }

    /** 즉시 전이. fencing 되면 경고 후 계속(null), 저장소 예외면 이 회차의 결과([JobResult.Failure]). */
    private fun transition(operation: String, row: ClaimedAccountPnlMail, mark: () -> Boolean): JobResult? = try {
        if (!mark()) logFenced(row)
        null
    } catch (exception: Exception) {
        storeFailure(operation, exception)
    }

    private fun send(row: ClaimedAccountPnlMail): Delivery = try {
        when (row.mail.channel) {
            OwnerAlertChannel.EMAIL -> mailSender.send(row.mail)
            OwnerAlertChannel.DISCORD -> sendDiscord(row.mail)
        }
        Delivery.SENT
    } catch (exception: Exception) {
        logSendFailure(row, exception)
        val permanent = (exception as? OwnerAlertSendException)?.permanent == true
        if (permanent) Delivery.PERMANENT_FAILURE else Delivery.RETRYABLE_FAILURE
    }

    /** Discord 채널이 꺼진 기동(sender 없음)에서는 재시도해도 보낼 수 없으므로 재시도 불가 실패다. */
    private fun sendDiscord(mail: AccountPnlMail) {
        val sender = discordSender ?: throw OwnerAlertSendException(true, DISCORD_UNAVAILABLE)
        // DISCORD 행의 route·color 는 AccountPnlMail 생성 시 검증된다
        val route = checkNotNull(mail.discordRoute) { "discordRoute is missing on a DISCORD row." }
        val color = checkNotNull(mail.discordColor) { "discordColor is missing on a DISCORD row." }
        sender.send(route, DiscordMessage(mail.subject, mail.body, color))
    }

    /**
     * 수신 주소·webhook URL·본문을 남기지 않는다. 우리가 만든 [OwnerAlertSendException] 의 메시지만 `reason` 으로 싣고,
     * 그 밖 예외(SMTP 예외는 주소를 담을 수 있다)는 예외·원인 타입만 남긴다.
     */
    private fun logSendFailure(row: ClaimedAccountPnlMail, exception: Exception) {
        val sendException = exception as? OwnerAlertSendException
        log.warn(
            "Owner alert send failed: id={}, channel={}, attempt={}, permanent={}, error={}, cause={}, reason={}",
            row.id,
            row.mail.channel,
            row.attemptCount + 1,
            sendException?.permanent ?: false,
            exception.javaClass.simpleName,
            exception.cause?.javaClass?.simpleName ?: "none",
            sendException?.message ?: "-",
        )
    }

    /** 모은 재시도 가능 실패 건을 시도 실패로 되돌린다. 저장소 예외가 나도 나머지 건은 계속 되돌린다. */
    private fun releaseFailed(failed: List<ClaimedAccountPnlMail>, result: JobResult): JobResult {
        var outcome = result
        for (row in failed) {
            try {
                if (!store.markAttemptFailed(row.id, row.claimToken, clock.instant())) logFenced(row)
            } catch (exception: Exception) {
                if (outcome !is JobResult.Failure) outcome = storeFailure("markAttemptFailed", exception)
            }
        }
        return outcome
    }

    private fun logFenced(row: ClaimedAccountPnlMail) {
        log.warn("Owner alert {} transition was fenced (claim no longer owned)", row.id)
    }

    private fun storeFailure(operation: String, exception: Exception): JobResult {
        log.error("Owner alert store failed during {}", operation, exception)
        return JobResult.Failure(exception)
    }

    private enum class Delivery { SENT, PERMANENT_FAILURE, RETRYABLE_FAILURE }

    companion object {
        const val CLAIM_CONCURRENCY = 1
        const val MAX_DELIVERIES_PER_RUN = 10

        /** 메일 채널이 없는 기동에서 Job 에 넘긴다. 남은 메일 행은 1회 시도 후 FAILED. */
        val UNAVAILABLE_MAIL_SENDER: AccountPnlMailSender =
            AccountPnlMailSender { throw OwnerAlertSendException(true, "owner alert channel unavailable: EMAIL") }

        private const val DISCORD_UNAVAILABLE = "owner alert channel unavailable: DISCORD"
    }
}
