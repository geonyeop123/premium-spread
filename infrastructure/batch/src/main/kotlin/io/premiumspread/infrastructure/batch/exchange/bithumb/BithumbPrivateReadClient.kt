package io.premiumspread.infrastructure.batch.exchange.bithumb

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.accountpnl.AccountReadException
import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException

/** 빗썸 REST GET 과 JWT 서명, 실패 정규화. 모든 실패는 [AccountReadException] 이다. */
internal class BithumbPrivateReadClient(
    private val webClient: WebClient,
    private val accessKey: String,
    private val secretKey: String,
    private val clock: Clock,
    private val timeout: Duration,
) {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    // adapter 경계: 모든 실패를 AccountReadException 으로 정규화한다.
    @Suppress("TooGenericExceptionCaught")
    fun get(path: String, query: String?, signed: Boolean): JsonNode {
        val body = try {
            var wireQuery: String? = null
            webClient.get()
                .uri { builder ->
                    val uri = builder.path(path).also { if (query != null) it.query(query) }.build()
                    wireQuery = uri.rawQuery
                    uri
                }
                .headers { headers ->
                    if (signed) {
                        val jwt = BithumbJwtSigner.sign(
                            accessKey,
                            secretKey,
                            // query_hash 는 wire 의 인코딩 query 가 아니라 디코딩된 query(states[]=done...) 기준이다.
                            wireQuery?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) },
                            UUID.randomUUID().toString(),
                            clock.millis(),
                        )
                        headers.setBearerAuth(jwt)
                    }
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(timeout)
                .block()
        } catch (e: WebClientResponseException) {
            throw AccountReadException(statusCategory(e.statusCode.value()), "Bithumb $path responded ${e.statusCode.value()}", e)
        } catch (e: WebClientRequestException) {
            throw AccountReadException(AccountReadFailureCategory.NETWORK, "Bithumb $path request failed", e)
        } catch (e: AccountReadException) {
            throw e
        } catch (e: RuntimeException) {
            val timedOut = generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutException }
            val category = if (timedOut) AccountReadFailureCategory.NETWORK else AccountReadFailureCategory.UNEXPECTED
            throw AccountReadException(category, "Bithumb $path call failed", e)
        } ?: bithumbUnexpected("Bithumb $path returned an empty body")
        return try {
            mapper.readTree(body) ?: bithumbUnexpected("Bithumb $path returned an empty body")
        } catch (e: JsonProcessingException) {
            throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "Bithumb $path returned malformed JSON", e)
        }
    }

    private fun statusCategory(status: Int) = when {
        status == 401 -> AccountReadFailureCategory.BITHUMB_AUTH
        status == 429 || status >= 500 -> AccountReadFailureCategory.NETWORK
        else -> AccountReadFailureCategory.UNEXPECTED
    }
}

internal fun JsonNode.requireArray(name: String): JsonNode =
    if (isArray) this else bithumbUnexpected("$name response is not an array")

internal fun JsonNode.dec(field: String): BigDecimal {
    val node = get(field) ?: bithumbUnexpected("field $field is missing")
    return try {
        when {
            node.isNumber -> node.decimalValue()
            node.isTextual -> BigDecimal(node.asText())
            else -> bithumbUnexpected("field $field is not a number")
        }
    } catch (e: NumberFormatException) {
        throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, "field $field is not a number", e)
    }
}

internal fun bithumbUnexpected(message: String): Nothing =
    throw AccountReadException(AccountReadFailureCategory.UNEXPECTED, message)
