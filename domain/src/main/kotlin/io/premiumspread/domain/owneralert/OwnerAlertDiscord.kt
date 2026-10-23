package io.premiumspread.domain.owneralert

/**
 * Discord embed 1개(제목·설명·색). 길이는 UTF-16 code unit 기준.
 * 기본 생성자는 상한을 검증만 하고, [of] 가 안전하게 절단한다 (design §6.3).
 */
data class DiscordMessage(val title: String, val description: String, val color: Int) {
    init {
        require(title.isNotBlank()) { "title must not be blank." }
        require(title.length <= TITLE_MAX) { "title must be at most $TITLE_MAX characters." }
        require(description.length <= DESCRIPTION_MAX) { "description must be at most $DESCRIPTION_MAX characters." }
        require(color in 0..COLOR_MAX) { "color must be in 0..$COLOR_MAX." }
    }

    companion object {
        const val TITLE_MAX = 255
        const val DESCRIPTION_MAX = 4096
        const val CONTENT_MAX = 2000
        const val EMBED_TOTAL_MAX = 6000
        const val COLOR_MAX = 0xFFFFFF
        const val ELLIPSIS = "…"

        fun of(title: String, description: String, color: Int): DiscordMessage =
            DiscordMessage(truncate(title, TITLE_MAX), truncate(description, DESCRIPTION_MAX), color)

        /** [max] 이하면 그대로. 넘치면 max-1 까지(surrogate pair 를 쪼개지 않게 필요하면 한 칸 더 앞) + [ELLIPSIS]. */
        fun truncate(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max - 1
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end) + ELLIPSIS
        }
    }
}

/** Discord 발송 port. 구현은 [DiscordRoute.target] 을 webhook 으로 해석한다. 실패는 [OwnerAlertSendException]. */
fun interface OwnerAlertDiscordSender {
    fun send(route: DiscordRoute, message: DiscordMessage)
}

/**
 * 채널 발송 실패. [permanent] 면 재시도해도 성공할 수 없다(즉시 FAILED).
 * 메시지에는 URL·token·수신 주소를 넣지 않고 `cause` 를 연결하지 않는다.
 */
class OwnerAlertSendException(val permanent: Boolean, message: String) : RuntimeException(message)
