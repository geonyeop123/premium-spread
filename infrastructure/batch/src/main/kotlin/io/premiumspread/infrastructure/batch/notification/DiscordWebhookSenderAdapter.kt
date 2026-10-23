package io.premiumspread.infrastructure.batch.notification

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.owneralert.DiscordMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertSendException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.Exceptions
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/**
 * 대상별 webhook URI. 설정 안 된 대상은 [PNL, MARKET, OPS] 순서의 첫 설정 대상으로 간다 (design §5.3, 판정 A5).
 * data class 가 아니다 — 자동 toString 이 URI 를 찍지 않게 한다.
 */
class DiscordWebhooks(pnl: URI?, market: URI?, ops: URI?) {
    private val configured: Map<DiscordTarget, URI> = buildMap {
        pnl?.let { put(DiscordTarget.PNL, it) }
        market?.let { put(DiscordTarget.MARKET, it) }
        ops?.let { put(DiscordTarget.OPS, it) }
    }

    init {
        require(configured.isNotEmpty()) { "at least one Discord webhook is required." }
    }

    fun resolve(target: DiscordTarget): URI = configured[target] ?: configured.getValue(fallbackTarget())

    /** "set" 또는 "fallback(PNL)" 형식 — 기동 INFO 와 toString 이 쓴다. */
    fun describe(target: DiscordTarget): String =
        if (target in configured) "set" else "fallback(${fallbackTarget().name})"

    override fun toString(): String =
        "DiscordWebhooks(pnl=${describe(DiscordTarget.PNL)}, market=${describe(DiscordTarget.MARKET)}, " +
            "ops=${describe(DiscordTarget.OPS)})"

    private fun fallbackTarget(): DiscordTarget = FALLBACK_ORDER.first { it in configured }

    private companion object {
        val FALLBACK_ORDER = listOf(DiscordTarget.PNL, DiscordTarget.MARKET, DiscordTarget.OPS)
    }
}

/**
 * Discord webhook 발송 (design §5.3). 예외는 WebClient 원본을 연결하지 않는다 — 원본 메시지가 요청 URI(= token)를 담는다.
 * 일반 class — 필드(URI)를 출력하는 toString 이 없다.
 */
class DiscordWebhookSenderAdapter(
    private val webhooks: DiscordWebhooks,
    private val timeout: Duration = Duration.ofSeconds(3),
    private val maxRetryAfter: Duration = Duration.ofSeconds(2),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : OwnerAlertDiscordSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient: WebClient = WebClient.builder().build()

    override fun send(route: DiscordRoute, message: DiscordMessage) {
        val target = route.target.name
        val uri = webhooks.resolve(route.target)
        val body = payload(route.push, message)

        var response = post(target, uri, body)
        if (response.status == TOO_MANY_REQUESTS) {
            val wait = retryAfter(response)
            if (wait == null || wait > maxRetryAfter) throw rateLimited(target)
            log.warn("Discord webhook rate limited; retrying once: target={}, retryAfterMs={}", target, wait.toMillis())
            try {
                sleeper(wait)
            } catch (_: InterruptedException) {
                throw interrupted(target)
            }
            response = post(target, uri, body)
            if (response.status == TOO_MANY_REQUESTS) throw rateLimited(target)
        }

        val status = response.status
        when {
            status in 200..299 -> return
            status in 400..499 -> throw OwnerAlertSendException(true, "Discord webhook $target responded $status")
            else -> throw OwnerAlertSendException(false, "Discord webhook $target responded $status")
        }
    }

    private fun post(target: String, uri: URI, body: String): Response =
        try {
            webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono { res ->
                    val status = res.statusCode().value()
                    if (status == TOO_MANY_REQUESTS) {
                        val header = res.headers().asHttpHeaders().getFirst("Retry-After")
                        res.bodyToMono(String::class.java).defaultIfEmpty("").map { Response(status, header, it) }
                    } else {
                        res.releaseBody().thenReturn(Response(status, null, ""))
                    }
                }
                .timeout(timeout)
                .block() ?: throw OwnerAlertSendException(false, "Discord webhook $target request failed: NoResponse")
        } catch (e: OwnerAlertSendException) {
            throw e
        } catch (e: Exception) {
            // 원본(메시지·cause)을 쓰지 않는다 — URI 가 들어 있다.
            if (isCancellation(e)) throw interrupted(target)
            val name = Exceptions.unwrap(e).javaClass.simpleName
            throw OwnerAlertSendException(false, "Discord webhook $target request failed: $name")
        }

    private fun interrupted(target: String): OwnerAlertSendException {
        Thread.currentThread().interrupt()
        return OwnerAlertSendException(false, "Discord webhook $target interrupted")
    }

    private fun rateLimited(target: String) = OwnerAlertSendException(false, "Discord webhook $target rate limited")

    /** `Retry-After` 헤더(초) → JSON 본문 `retry_after`(초) → null. 음수·파싱 불가는 null, 상한 초과는 상한보다 1ms 큰 값(호출자가 거절). */
    private fun retryAfter(response: Response): Duration? {
        val seconds = response.retryAfterHeader?.trim()?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: bodyRetryAfter(response.body)
        if (seconds == null || seconds.signum() < 0) return null
        val limitSeconds = BigDecimal(maxRetryAfter.toMillis()).movePointLeft(3)
        if (seconds > limitSeconds) return Duration.ofMillis(maxRetryAfter.toMillis() + 1)
        return Duration.ofMillis(seconds.movePointRight(3).setScale(0, java.math.RoundingMode.CEILING).longValueExact())
    }

    private fun bodyRetryAfter(body: String): BigDecimal? =
        runCatching {
            val node = MAPPER.readTree(body)?.get("retry_after")
            if (node != null && node.isNumber) node.decimalValue() else null
        }.getOrNull()

    private fun payload(push: DiscordPush, message: DiscordMessage): String {
        val embed = linkedMapOf("title" to message.title, "description" to message.description, "color" to message.color)
        val root = linkedMapOf<String, Any>()
        if (push == DiscordPush.HERE) root["content"] = "@here"
        root["embeds"] = listOf(embed)
        root["allowed_mentions"] = mapOf("parse" to if (push == DiscordPush.HERE) listOf("everyone") else emptyList())
        if (push == DiscordPush.SILENT) root["flags"] = SUPPRESS_NOTIFICATIONS
        return MAPPER.writeValueAsString(root)
    }

    private class Response(val status: Int, val retryAfterHeader: String?, val body: String)

    internal companion object {
        private const val TOO_MANY_REQUESTS = 429
        private const val SUPPRESS_NOTIFICATIONS = 4096
        private const val MAX_CAUSE_DEPTH = 16
        private val MAPPER = ObjectMapper()

        /**
         * 원인 체인(자기 자신 포함, 같은 객체 순환 차단, 깊이 16)에 InterruptedException·CancellationException 이 있으면 true.
         * AccountPnlMonitorJob.isCancellation 과 같은 규칙 (app 함수는 private 이고 infra 는 app 을 참조하지 않으므로 따로 둔다).
         */
        fun isCancellation(error: Throwable): Boolean {
            val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
                if (current is InterruptedException || current is CancellationException) return true
                current = current.cause
                depth++
            }
            return false
        }
    }
}
