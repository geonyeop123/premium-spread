package io.premiumspread.infrastructure.batch.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant

/** layout 이 있는 메시지의 embed payload (dod AC13). layout 없는 payload 는 `DiscordWebhookSenderAdapterTest` 가 지킨다. */
class DiscordWebhookSenderAdapterLayoutTest {

    private val mapper = ObjectMapper()
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        Thread.interrupted()
        runCatching { server.shutdown() }
    }

    private val full = DiscordMessage(
        title = "수익 알림 \"q\"",
        description = "대체 평문",
        color = 0x2ECC71,
        layout = DiscordLayout(
            author = "실계정 · BTC",
            fields = listOf(
                DiscordField("현김프", "1.78%", true),
                DiscordField("매수김프", "0.53%", true),
                DiscordField("메모", "줄1\n줄2", false),
            ),
            footer = "환율 1,400.00원/USD",
            timestamp = Instant.parse("2026-10-20T00:00:00Z"),
        ),
    )

    private val embed =
        """{"author":{"name":"실계정 · BTC"},"title":"수익 알림 \"q\"","color":3066993,""" +
            """"fields":[{"name":"현김프","value":"1.78%","inline":true},""" +
            """{"name":"매수김프","value":"0.53%","inline":true},{"name":"메모","value":"줄1\n줄2","inline":false}],""" +
            """"footer":{"text":"환율 1,400.00원/USD"},"timestamp":"2026-10-20T00:00:00Z"}"""

    private fun send(push: DiscordPush, message: DiscordMessage): String {
        server.enqueue(MockResponse().setResponseCode(204))
        val uri: URI = server.url("/api/webhooks/1/TOKENlayoutQ7").toUri()
        DiscordWebhookSenderAdapter(DiscordWebhooks(uri, uri, uri), Duration.ofSeconds(3), Duration.ofSeconds(2)) {}
            .send(DiscordRoute(DiscordTarget.PNL, push), message)
        return server.takeRequest().body.readUtf8()
    }

    private fun json(text: String): JsonNode = mapper.readTree(text)

    @Test
    fun `layout 이 있으면 embed 는 author title color fields footer timestamp 이고 description 이 없다`() {
        val body = send(DiscordPush.SOUND, full)

        assertThat(body).isEqualTo("""{"embeds":[$embed],"allowed_mentions":{"parse":[]}}""")
        assertThat(json(body).get("embeds").get(0).has("description")).isFalse()
    }

    @Test
    fun `push 3종의 요청 본문 전문 - 무음 소리 here`() {
        assertThat(json(send(DiscordPush.SILENT, full))).isEqualTo(
            json("""{"embeds":[$embed],"allowed_mentions":{"parse":[]},"flags":4096}"""),
        )
        assertThat(json(send(DiscordPush.SOUND, full))).isEqualTo(
            json("""{"embeds":[$embed],"allowed_mentions":{"parse":[]}}"""),
        )
        assertThat(json(send(DiscordPush.HERE, full))).isEqualTo(
            json("""{"content":"@here","embeds":[$embed],"allowed_mentions":{"parse":["everyone"]}}"""),
        )
    }

    @Test
    fun `author footer timestamp 가 null 이고 fields 가 비면 그 키만 빠지고 fields 는 빈 배열로 남는다`() {
        val message = DiscordMessage("제목", "평문", 0x123456, DiscordLayout(null, emptyList(), null, null))

        val body = send(DiscordPush.SOUND, message)

        assertThat(body).isEqualTo("""{"embeds":[{"title":"제목","color":1193046,"fields":[]}],"allowed_mentions":{"parse":[]}}""")
    }

    @Test
    fun `칸 순서와 inline 을 보존한다`() {
        val fields = json(send(DiscordPush.SOUND, full)).get("embeds").get(0).get("fields")

        assertThat(fields.map { it.get("name").asText() }).containsExactly("현김프", "매수김프", "메모")
        assertThat(fields.map { it.get("inline").asBoolean() }).containsExactly(true, true, false)
    }
}
