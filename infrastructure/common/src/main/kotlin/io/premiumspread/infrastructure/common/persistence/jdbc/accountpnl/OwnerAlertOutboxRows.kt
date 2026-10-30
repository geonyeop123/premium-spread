package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.accountpnl.AccountPnlMail
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/** owner 알림 outbox(account_pnl_mail) 행 삽입·검증의 단일 소유자. JdbcAccountPnlAlertStore 의 기존 SQL·메시지를 그대로 옮긴 것이다. */
internal object OwnerAlertOutboxRows {
    const val MAX_EVENT_KEY_LENGTH = 128
    const val MAX_SUBJECT_LENGTH = 255
    const val MAX_BODY_BYTES = 65_535 // MySQL TEXT
    const val MAX_LAYOUT_BYTES = 65_535 // MySQL TEXT

    /** 값은 메시지에 넣지 않는다. */
    fun validate(mail: AccountPnlMail) {
        require(mail.eventKey.isNotBlank()) { "eventKey must not be blank." }
        require(mail.eventKey.length <= MAX_EVENT_KEY_LENGTH) {
            "eventKey must be at most $MAX_EVENT_KEY_LENGTH characters."
        }
        require(mail.subject.length <= MAX_SUBJECT_LENGTH) { "subject must be at most $MAX_SUBJECT_LENGTH characters." }
        require(mail.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "body must be at most $MAX_BODY_BYTES bytes in UTF-8."
        }
        mail.discordLayout?.let { layout ->
            require(DiscordLayoutJson.write(layout).toByteArray(Charsets.UTF_8).size <= MAX_LAYOUT_BYTES) {
                "discordLayout must be at most $MAX_LAYOUT_BYTES bytes in UTF-8."
            }
        }
    }

    /**
     * 같은 (event_key, channel) 은 무시한다. 다른 제약 위반·오류는 그대로 던진다(INSERT IGNORE 를 쓰지 않는 이유).
     * channel·discord_target·discord_push 는 enum 이름으로 저장한다. 호출자 트랜잭션에 참여한다.
     */
    fun insert(jdbcTemplate: JdbcTemplate, mail: AccountPnlMail, createdAt: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO account_pnl_mail (
                event_key, channel, subject, body, discord_target, discord_push, discord_color, discord_layout,
                status, attempt_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
            ON DUPLICATE KEY UPDATE id = id
            """.trimIndent(),
            mail.eventKey,
            mail.channel.name,
            mail.subject,
            mail.body,
            mail.discordRoute?.target?.name,
            mail.discordRoute?.push?.name,
            mail.discordColor,
            mail.discordLayout?.let(DiscordLayoutJson::write),
            Timestamp.from(createdAt),
        )
    }
}
