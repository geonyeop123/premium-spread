package io.premiumspread.domain.owneralert

import java.time.Instant

/** embed 칸 하나. 이름 1~256자·값 1~1024자(UTF-16), 둘 다 공백만이면 안 된다. 빈 칸은 U+200B 한 글자(zero-width space — isBlank 가 false). */
data class DiscordField(val name: String, val value: String, val inline: Boolean) {
    init {
        require(name.isNotBlank() && name.length <= DiscordMessage.FIELD_NAME_MAX) { "field name must be 1..256 characters." }
        require(value.isNotBlank() && value.length <= DiscordMessage.FIELD_VALUE_MAX) { "field value must be 1..1024 characters." }
    }
}

/** title·color·description 밖의 embed 요소. null 이면 그 요소를 보내지 않는다(fields 는 항상 보낸다). */
data class DiscordLayout(
    val author: String?,
    val fields: List<DiscordField>,
    val footer: String?,
    val timestamp: Instant?,
) {
    init {
        require(author == null || (author.isNotBlank() && author.length <= DiscordMessage.AUTHOR_MAX)) {
            "author must be 1..256 characters."
        }
        require(footer == null || (footer.isNotBlank() && footer.length <= DiscordMessage.FOOTER_MAX)) {
            "footer must be 1..2048 characters."
        }
        require(fields.size <= DiscordMessage.FIELDS_MAX) { "fields must be at most 25." }
    }
}

/**
 * Discord embed 1개. [layout] 이 있으면 adapter 는 description 을 보내지 않는다(description = 롤백용 대체 평문).
 * 길이는 UTF-16 code unit 기준. 기본 생성자는 상한을 검증만 하고, [of] 가 title·description 을 절단한다 (design §6.3).
 */
data class DiscordMessage(
    val title: String,
    val description: String,
    val color: Int,
    val layout: DiscordLayout? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank." }
        require(title.length <= TITLE_MAX) { "title must be at most $TITLE_MAX characters." }
        require(description.length <= DESCRIPTION_MAX) { "description must be at most $DESCRIPTION_MAX characters." }
        require(color in 0..COLOR_MAX) { "color must be in 0..$COLOR_MAX." }
        require(layout == null || layout.totalLength(title) <= EMBED_TOTAL_MAX) {
            "embed must be at most $EMBED_TOTAL_MAX characters in total."
        }
    }

    companion object {
        const val TITLE_MAX = 255
        const val DESCRIPTION_MAX = 4096
        const val CONTENT_MAX = 2000
        const val EMBED_TOTAL_MAX = 6000
        const val COLOR_MAX = 0xFFFFFF
        const val FIELD_NAME_MAX = 256
        const val FIELD_VALUE_MAX = 1024
        const val FIELDS_MAX = 25
        const val FOOTER_MAX = 2048
        const val AUTHOR_MAX = 256
        const val ELLIPSIS = "…"

        fun of(title: String, description: String, color: Int, layout: DiscordLayout? = null): DiscordMessage =
            DiscordMessage(truncate(title, TITLE_MAX), truncate(description, DESCRIPTION_MAX), color, layout)

        /** [max] 이하면 그대로. 넘치면 max-1 까지(surrogate pair 를 쪼개지 않게 필요하면 한 칸 더 앞) + [ELLIPSIS]. */
        fun truncate(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max - 1
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end) + ELLIPSIS
        }
    }
}

/** title + author + footer + 칸 이름·값 길이 합(description 제외). [DiscordMessage] 검증용. */
internal fun DiscordLayout.totalLength(title: String): Int =
    title.length + (author?.length ?: 0) + (footer?.length ?: 0) + fields.sumOf { it.name.length + it.value.length }

/** Discord 발송 port. 구현은 [DiscordRoute.target] 을 webhook 으로 해석한다. 실패는 [OwnerAlertSendException]. */
fun interface OwnerAlertDiscordSender {
    fun send(route: DiscordRoute, message: DiscordMessage)
}

/**
 * 채널 발송 실패. [permanent] 면 재시도해도 성공할 수 없다(즉시 FAILED).
 * 메시지에는 URL·token·수신 주소를 넣지 않고 `cause` 를 연결하지 않는다.
 */
class OwnerAlertSendException(val permanent: Boolean, message: String) : RuntimeException(message)
