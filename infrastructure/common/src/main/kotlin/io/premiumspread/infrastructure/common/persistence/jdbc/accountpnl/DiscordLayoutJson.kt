package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import java.time.Instant
import java.time.format.DateTimeParseException

internal enum class DiscordLayoutUnreadableReason { JSON, VERSION, SHAPE }

/** 메시지 = "discord layout unreadable: {json|version|shape}". 입력 내용·cause 를 담지 않는다. */
internal class DiscordLayoutUnreadableException(val reason: DiscordLayoutUnreadableReason) :
    RuntimeException("discord layout unreadable: ${reason.name.lowercase()}")

/**
 * account_pnl_mail.discord_layout 의 v1 JSON. 키 순서 v·author·fields(name·value·inline)·footer·timestamp, null 은 JSON null.
 * fields 는 빈 목록이어도 `[]` 로 항상 쓴다(design §4·§5.2). Jackson tree API 만 쓴다(Kotlin module·reflection 없음).
 */
internal object DiscordLayoutJson {
    const val VERSION = 1

    private val mapper = ObjectMapper()
    private val nodes = JsonNodeFactory.instance

    fun write(layout: DiscordLayout): String {
        val root = nodes.objectNode()
        root.put("v", VERSION)
        root.putText("author", layout.author)
        val fields = root.putArray("fields")
        layout.fields.forEach { field ->
            fields.addObject()
                .put("name", field.name)
                .put("value", field.value)
                .put("inline", field.inline)
        }
        root.putText("footer", layout.footer)
        root.putText("timestamp", layout.timestamp?.toString())
        return mapper.writeValueAsString(root)
    }

    /** JSON 오류 → JSON, v 가 1 이 아님 → VERSION, 키 누락·타입 불일치·domain 불변식 위반 → SHAPE. */
    fun read(json: String): DiscordLayout {
        val root = try {
            mapper.readTree(json)
        } catch (_: JsonProcessingException) {
            null
        }
        if (root == null || root.isMissingNode) throw unreadable(DiscordLayoutUnreadableReason.JSON)
        if (!root.isObject) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        val version = root.get("v")
        if (version == null || !version.isInt) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        if (version.intValue() != VERSION) throw unreadable(DiscordLayoutUnreadableReason.VERSION)
        return try {
            DiscordLayout(
                author = root.nullableText("author"),
                fields = root.requiredArray("fields").map { it.toField() },
                footer = root.nullableText("footer"),
                timestamp = root.nullableText("timestamp")?.let(Instant::parse),
            )
        } catch (_: IllegalArgumentException) {
            throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        } catch (_: DateTimeParseException) {
            throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        }
    }

    private fun JsonNode.toField(): DiscordField {
        if (!isObject) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        val inline = get("inline")
        if (inline == null || !inline.isBoolean) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        return DiscordField(requiredText("name"), requiredText("value"), inline.booleanValue())
    }

    private fun ObjectNode.putText(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun JsonNode.requiredText(key: String): String {
        val node = get(key)
        if (node == null || !node.isTextual) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        return node.textValue()
    }

    /** 키가 있어야 하고 값은 JSON null 이거나 문자열이다. */
    private fun JsonNode.nullableText(key: String): String? {
        val node = get(key) ?: throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        if (node.isNull) return null
        if (!node.isTextual) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        return node.textValue()
    }

    private fun JsonNode.requiredArray(key: String): JsonNode {
        val node = get(key)
        if (node == null || !node.isArray) throw unreadable(DiscordLayoutUnreadableReason.SHAPE)
        return node
    }

    private fun unreadable(reason: DiscordLayoutUnreadableReason) = DiscordLayoutUnreadableException(reason)
}
