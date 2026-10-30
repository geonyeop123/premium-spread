package io.premiumspread.domain.alertmessage

import java.time.Instant
import java.time.ZoneId

/** 메일 한 항목. [children] 은 부모 아래 "  - " 하위 항목(예: 수수료 내역). */
data class MailPair(val label: String, val value: String, val children: List<MailPair> = emptyList())

sealed interface MailBlock {
    /** 줄 그대로. 빈 목록 금지. */
    data class Text(val lines: List<String>) : MailBlock {
        init {
            require(lines.isNotEmpty()) { "text lines must not be empty." }
        }
    }

    /** 라벨 정렬 블록. 빈 목록 금지. */
    data class Pairs(val pairs: List<MailPair>) : MailBlock {
        init {
            require(pairs.isNotEmpty()) { "pairs must not be empty." }
        }
    }

    /** "- 항목". 빈 목록 금지. */
    data class Bullets(val items: List<String>) : MailBlock {
        init {
            require(items.isNotEmpty()) { "bullet items must not be empty." }
        }
    }

    /** "■ {title}" + 안쪽 블록(사이 빈 줄 없음). Section 중첩 금지. */
    data class Section(val title: String, val blocks: List<MailBlock>) : MailBlock {
        init {
            require(blocks.isNotEmpty()) { "section blocks must not be empty." }
            require(blocks.none { it is Section }) { "sections must not be nested." }
        }
    }
}

/** plain text 메일 한 통. [basis] null = "기준 시각" 줄 없음. [signature] = 본문 끝 "--" 아래 줄. */
data class MailDocument(
    val subject: String,
    val blocks: List<MailBlock>,
    val basis: Instant?,
    val zone: ZoneId,
    val signature: String? = null,
) {
    /** design §3.2 렌더 규칙 1~5. */
    fun body(): String {
        val parts = blocks.map { it.lines().joinToString("\n") }.toMutableList()
        if (basis != null) parts += "기준 시각 ${AlertFormat.stamp(basis, zone)}"
        if (signature != null) parts += "--\n$signature"
        return parts.joinToString("\n\n") { text -> text.lines().joinToString("\n") { it.trimEnd() } }
    }

    private fun MailBlock.lines(): List<String> = when (this) {
        is MailBlock.Text -> lines
        is MailBlock.Bullets -> items.map { "- $it" }
        is MailBlock.Pairs -> pairLines(pairs, "")
        is MailBlock.Section -> listOf("■ $title") + blocks.flatMap { it.lines() }
    }

    private fun pairLines(pairs: List<MailPair>, prefix: String): List<String> {
        val width = pairs.maxOf { AlertFormat.displayWidth(it.label) } + 2
        return pairs.flatMap { pair ->
            val padding = " ".repeat(width - AlertFormat.displayWidth(pair.label))
            listOf("$prefix${pair.label}$padding${pair.value}") + if (pair.children.isEmpty()) {
                emptyList()
            } else {
                pairLines(pair.children, "  - ")
            }
        }
    }
}
