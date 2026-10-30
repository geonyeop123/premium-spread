package io.premiumspread.domain.alertmessage

/** 알림 하나의 채널별 문구. eventKey 는 outbox UNIQUE·SMTP Message-ID 형식(`[A-Za-z0-9._-]{1,128}`)을 지킨다. */
data class OwnerAlertMessage(val eventKey: String, val mail: MailDocument, val discord: EmbedDocument)
