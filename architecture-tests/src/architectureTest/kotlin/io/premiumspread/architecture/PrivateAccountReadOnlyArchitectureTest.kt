package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertTrue

/**
 * `AC10` — private 계정 adapter(`*PrivateAccountAdapter.kt`)는 조회 전용이다 (`LIVE-3`·`LIVE-4`).
 *
 * GET 외 HTTP method 호출과 주문·취소·출금·이체 경로 문자열이 소스에 있으면 실패한다. 주석까지
 * 포함해 원문을 그대로 본다 — adapter 는 주석에서도 이 문자열을 피하도록 쓰였고, 산문에 금지
 * 경로가 남는 것도 복붙 사고의 씨앗이다.
 *
 * 조회 경로 `/v1/orders`·`/v1/order`·`/v1/orders/chance` 는 금지 대상이 아니다. 주문 경로는 따옴표로
 * 닫은 정확 일치(`/v2/orders"`)나 뚜렷한 접미(`/orders/cancel`)로만 잡는다.
 *
 * 범용 `.method(x)` 호출은 인자가 `HttpMethod.GET` 일 때만 허용한다.
 */
class PrivateAccountReadOnlyArchitectureTest {
    @Test
    fun `private 계정 adapter 는 GET 외 HTTP 호출과 주문 출금 이체 경로를 갖지 않는다`() {
        val targets = targetSources()
        assertTrue(
            targets.size >= MINIMUM_TARGETS,
            "*PrivateAccountAdapter.kt 를 ${targets.size}개만 찾았다(최소 $MINIMUM_TARGETS) — " +
                "스캐너가 대상을 못 보거나 adapter 가 이름을 바꿨다. root=${root()}",
        )

        val violations = targets.flatMap { path ->
            val text = Files.readString(path)
            FORBIDDEN.flatMap { rule ->
                rule.regex.findAll(text).map { match ->
                    val line = text.take(match.range.first).count { it == '\n' } + 1
                    "${relative(path)}:L$line  [${rule.name}] ${match.value}"
                }.toList()
            }
        }

        assertTrue(
            violations.isEmpty(),
            "private 계정 adapter 는 조회 전용이다 — AC10/LIVE-3·LIVE-4.\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `금지 패턴의 각 대안은 위반 표본에 개별로 매치한다`() {
        assertTrue(FORBIDDEN.size >= MINIMUM_RULES, "금지 규칙이 ${FORBIDDEN.size}개뿐이다 — 규칙이 지워졌다.")
        val misses = FORBIDDEN.filterNot { rule -> rule.regex.containsMatchIn(rule.violatingSample) }
        assertTrue(misses.isEmpty(), "위반 표본에 매치하지 않는 규칙: ${misses.map { it.name }}")
        val names = FORBIDDEN.map { it.name }
        assertTrue(names.size == names.toSet().size, "규칙 이름이 중복된다: $names")
    }

    @Test
    fun `금지 패턴은 조회 전용 표본에는 매치하지 않는다`() {
        val hits = ALLOWED_SAMPLES.flatMap { sample ->
            FORBIDDEN.filter { it.regex.containsMatchIn(sample) }.map { "'$sample' <- ${it.name}" }
        }
        assertTrue(hits.isEmpty(), "조회 전용 표본을 위반으로 잡았다: $hits")
    }

    private fun targetSources(): List<Path> = Files.walk(root()).use { paths ->
        paths.filter { path ->
            val normalized = path.toString().replace('\\', '/')
            Files.isRegularFile(path) &&
                path.fileName.toString().endsWith("PrivateAccountAdapter.kt") &&
                normalized.contains("/src/main/kotlin/") &&
                !normalized.contains("/build/")
        }.toList()
    }

    private fun relative(path: Path): String = root().relativize(path).invariantSeparatorsPathString

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private class Rule(val name: String, pattern: String, val violatingSample: String) {
        val regex = Regex(pattern)
    }

    private companion object {
        const val MINIMUM_TARGETS = 2
        const val MINIMUM_RULES = 19

        val FORBIDDEN: List<Rule> = listOf(
            Rule("call .post(", """\.post\(""", "client.post(url)"),
            Rule("call .put(", """\.put\(""", "client.put(url)"),
            Rule("call .delete(", """\.delete\(""", "client.delete(url)"),
            Rule("call .patch(", """\.patch\(""", "client.patch(url)"),
            Rule("HttpMethod.POST", """HttpMethod\.POST\b""", "HttpMethod.POST"),
            Rule("HttpMethod.PUT", """HttpMethod\.PUT\b""", "HttpMethod.PUT"),
            Rule("HttpMethod.DELETE", """HttpMethod\.DELETE\b""", "HttpMethod.DELETE"),
            Rule("HttpMethod.PATCH", """HttpMethod\.PATCH\b""", "HttpMethod.PATCH"),
            Rule("generic .method(non-GET)", """\.method\((?!\s*HttpMethod\.GET\s*\))""", ".method(verb)"),
            Rule("path /v2/orders\"", """/v2/orders"""", "\"/v2/orders\""),
            Rule("path /v2/order\"", """/v2/order"""", "\"/v2/order\""),
            Rule("path /orders/batch", """/orders/batch""", "/orders/batch"),
            Rule("path /orders/cancel", """/orders/cancel""", "/orders/cancel"),
            Rule("path /orders/search", """/orders/search""", "/orders/search"),
            Rule("path /twap", """/twap""", "/twap"),
            Rule("path /fapi/v1/order\"", """/fapi/v1/order"""", "\"/fapi/v1/order\""),
            Rule("batchOrders", """batchOrders""", "batchOrders"),
            Rule("withdraw", """(?i)withdraw""", "/withdraw"),
            Rule("deposit", """(?i)deposit""", "/deposit"),
            Rule("transfer", """(?i)transfer""", "/transfer"),
        )

        val ALLOWED_SAMPLES = listOf(
            "\"/v1/orders?market=KRW-BTC\"",
            "\"/v1/orders/chance\"",
            "\"/v1/order\"",
            "client.get()",
            ".method(HttpMethod.GET)",
        )
    }
}
