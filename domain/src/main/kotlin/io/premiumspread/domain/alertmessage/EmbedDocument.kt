package io.premiumspread.domain.alertmessage

import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import io.premiumspread.domain.owneralert.DiscordMessage
import java.time.Instant

data class EmbedCell(val name: String, val value: String)

/** Discord embed 문서. [rows] 한 행 = 나란히 놓이는 칸 1~3개. 빈 rows 금지. */
data class EmbedDocument(
    val author: String,
    val title: String,
    val color: Int,
    val rows: List<List<EmbedCell>>,
    val footer: String?,
    val timestamp: Instant,
) {
    /** design §3.3 규칙 1~5. 결과 layout 의 fields 는 채움 칸 포함. */
    init {
        require(rows.isNotEmpty()) { "rows must not be empty." }
        require(rows.all { it.size in 1..MAX_CELLS_PER_ROW }) { "each row must have 1..3 cells." }
    }

    fun toDiscordMessage(): DiscordMessage {
        val fields = buildList {
            rows.forEachIndexed { index, row ->
                row.forEach { cell ->
                    add(
                        DiscordField(
                            DiscordMessage.truncate(cell.name, DiscordMessage.FIELD_NAME_MAX),
                            DiscordMessage.truncate(cell.value, DiscordMessage.FIELD_VALUE_MAX),
                            inline = row.size > 1,
                        ),
                    )
                }
                if (row.size == 2 && (rows.getOrNull(index + 1)?.size ?: 0) >= 2) add(SPACER)
            }
        }
        val layout = DiscordLayout(
            author = DiscordMessage.truncate(author, DiscordMessage.AUTHOR_MAX),
            fields = fields,
            footer = footer?.let { DiscordMessage.truncate(it, DiscordMessage.FOOTER_MAX) },
            timestamp = timestamp,
        )
        val lines = rows.flatten().map { "${it.name}: ${it.value.replace("**", "").replace("\n", " / ")}" } +
            listOfNotNull(footer)
        return DiscordMessage.of(title, lines.joinToString("\n"), color, layout)
    }

    companion object {
        private const val MAX_CELLS_PER_ROW = 3

        /** U+200B(zero-width space) 한 글자. 보이지 않는 문자를 소스에 직접 넣지 않고 코드값으로 만든다. */
        private val ZWSP: String = Char(0x200B).toString()

        /** 2칸 행 뒤 채움 칸(design §3.3 규칙 2). */
        val SPACER: DiscordField = DiscordField(ZWSP, ZWSP, inline = true)
    }
}
