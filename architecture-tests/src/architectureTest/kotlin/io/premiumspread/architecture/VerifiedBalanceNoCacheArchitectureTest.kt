package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `AC5`의 첫 문장 — **"판정용 잔고를 캐시에서 읽을 수 없다"** — 를 강제한다 (design.md `D2`).
 *
 * `AC5`의 둘째 문장(스냅샷 id 결속과 무효화)은 `TradePreparationSnapshotBindingTest`가 검증한다.
 * 첫 문장은 이 파일이 오기 전까지 `VerifiedBalanceReadPort`의 KDoc 산문에만 있었다. 산문은
 * 게이트가 아니다.
 *
 * ## 왜 캐시가 금지인가
 *
 * 판정용 잔고는 노출을 **늘리는** 결정의 입력이다. 캐시된(=이미 소진되었을 수 있는) 잔고가 진입을
 * 승인하면 한쪽 다리만 체결된 헤지가 남는다 — `SAFE-9`가 금지하는 상태다. 그래서 표시용
 * ([io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort], 캐시 허용)과 판정용
 * ([io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort], 캐시 불가)을 **반환 타입이 다른 두
 * 계약**으로 갈라 두었다.
 *
 * ## 지금은 대상이 0개다 — 그래도 이 test는 비어 있지 않다
 *
 * production에는 `VerifiedBalanceReadPort` 구현이 **하나도 없다**(`D22`, `AC20`). 규칙이 무는 순간은
 * `ACT-2`에서 `ExchangeBalanceAdapter`가 들어올 때다. 즉 이 게이트는 **미래를 향해** 설치된다.
 *
 * 대상이 0개인 규칙은 "아무것도 검사하지 않아서 통과하는 test"가 되기 쉽다. 그래서 규칙 test
 * ([production_VerifiedBalanceReadPort_구현은_캐시에_손을_뻗지_않는다]) 하나만 두지 않고, **스캐너
 * 자체가 살아 있는지**를 이 저장소의 실제 코드로 되짚는 self-check 세 개를 함께 둔다.
 *
 * - [스캔_대상_production_트리에_이_규칙이_지키는_port가_그대로_있다] — port 선언이 production에
 *   정확히 하나 있는지. port가 사라지거나 이름이 바뀌면 규칙은 조용히 아무 데도 적용되지 않는다.
 * - [구현_탐지기는_이_저장소의_실제_구현들을_찾아낸다] — 탐지기를 test source의 실제 구현(클래스
 *   상속, SAM 람다)에 돌려 실제로 잡히는지. 소비자(`ObjectProvider<Port>`)는 잡지 않는지.
 * - [캐시_탐지기는_실제_캐시_사용을_잡고_산문의_캐시_단어는_넘긴다] — 탐지기를 실제 캐시 어댑터에
 *   돌려 잡히는지, 주석에만 cache가 나오는 파일은 넘기는지.
 *
 * 세 self-check는 이 파일의 스캔 로직을 그대로 통과한다. 스캔 로직을 통째로 지우면 규칙 test는
 * 여전히 통과하지만 self-check 세 개가 모두 실패한다. 그것이 이 배치의 목적이다.
 *
 * ## 무엇을 캐시로 보는가
 *
 * 고정 목록은 늙는다. 그래서 식별자 안의 `cache`/`redis`(대소문자 무시)와 `opsFor` 접두를 잡는
 * **패턴**을 1차로 쓴다. 이 저장소의 캐시 진입점 — `RedisTemplate`, `RedissonClient`,
 * `opsForValue`, `CacheManager`, `PremiumCacheReader`, `FxCacheReader`,
 * `PremiumAggregationCacheReader`, `PremiumCacheService`, `FxCacheService`, `TickerCacheReader`,
 * `TickerCacheService`, `CacheWarmupService`, `AfterCommitCacheExecutor`, `TimeSeriesCacheSupport`
 * — 은 전부 이 패턴에 걸리며, [캐시_진입점_이름들은_모두_패턴에_걸린다]가 그 사실을 못 박는다.
 * 앞으로 생길 `BalanceCacheReader`나 `RedisBalanceAdapter` 같은 이름도 목록을 고치지 않고 걸린다.
 *
 * 판정은 **파일 단위**로 넓게 한다. 한 production 파일이 이 port를 구현하면서 동시에 캐시 심볼을
 * 가지면 위반이다. 구현체 본문만 좁게 보면 상위 프로퍼티나 import로 새는 경로를 놓친다. 넓게 잡아
 * 생기는 오탐은 review에서 걷어내면 되지만, 놓친 캐시는 체결된 다리로 나타난다.
 *
 * 주석과 문자열 리터럴은 [redact]로 지운 뒤 본다. `AggregationPorts.kt`처럼 KDoc에만 cache가
 * 나오는 파일을 위반으로 세지 않기 위해서다.
 */
class VerifiedBalanceNoCacheArchitectureTest {
    @Test
    fun `production VerifiedBalanceReadPort 구현은 캐시에 손을 뻗지 않는다`() {
        val sources = productionSources().map(::scan)
        val portNames = portTypeNames(sources)
        val violations = sources.mapNotNull { source ->
            val implemented = implementations(source, portNames)
            if (implemented.isEmpty()) return@mapNotNull null
            val cacheUses = cacheSymbols(source.code)
            if (cacheUses.isEmpty()) return@mapNotNull null
            buildString {
                append(relative(source.path))
                append("\n    구현: ")
                append(implemented.joinToString(", ") { source.describe(it) })
                append("\n    캐시: ")
                append(cacheUses.joinToString(", ") { (index, symbol) -> "$symbol@${source.lineOf(index)}" })
            }
        }

        assertTrue(
            violations.isEmpty(),
            "판정용 잔고(VerifiedBalanceReadPort)는 캐시에서 읽을 수 없다 — AC5/D2. " +
                "실조회로 바꾸거나, 표시용이라면 BalanceSnapshotReadPort 를 구현하라.\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `스캔 대상 production 트리에 이 규칙이 지키는 port가 그대로 있다`() {
        val sources = productionSources().map(::scan)
        assertTrue(
            sources.size >= MINIMUM_PRODUCTION_SOURCES,
            "production source 스캔이 ${sources.size}개만 찾았다 — 스캐너가 트리를 못 보고 있다. " +
                "root=${root()}",
        )

        val declaringFiles = sources
            .filter { source -> Regex("""\binterface\s+$PORT\b""").containsMatchIn(source.code) }
            .map { relative(it.path) }

        assertEquals(
            listOf(PORT_DECLARATION),
            declaringFiles,
            "$PORT 선언이 예상 위치에 정확히 하나 있어야 한다. 이름을 바꾸거나 옮겼다면 이 규칙이 " +
                "지키는 대상도 함께 옮겨야 한다 — 그러지 않으면 규칙은 아무 데도 적용되지 않는다.",
        )
    }

    @Test
    fun `구현 탐지기는 이 저장소의 실제 구현들을 찾아낸다`() {
        val sources = (productionSources() + testSources()).map(::scan)
        val portNames = portTypeNames(sources)
        val byPath = sources.associateBy { relative(it.path) }

        val missing = KNOWN_IMPLEMENTATION_ANCHORS.filter { anchor ->
            val source = byPath[anchor] ?: return@filter true
            implementations(source, portNames).isEmpty()
        }
        assertTrue(
            missing.isEmpty(),
            "구현 탐지기가 알려진 실제 구현을 못 찾았다 — 탐지기가 망가졌거나 anchor 가 이동/삭제됐다. " +
                "anchor 를 갱신하기 전에 탐지기가 여전히 도는지 먼저 확인하라: $missing",
        )

        val falsePositives = KNOWN_CONSUMER_ANCHORS.filter { anchor ->
            val source = byPath[anchor] ?: return@filter true
            implementations(source, portNames).isNotEmpty()
        }
        assertTrue(
            falsePositives.isEmpty(),
            "구현 탐지기가 소비자(ObjectProvider<$PORT>)나 port 선언 자체를 구현으로 잘못 잡았다: $falsePositives",
        )
    }

    @Test
    fun `캐시 탐지기는 실제 캐시 사용을 잡고 산문의 캐시 단어는 넘긴다`() {
        val byPath = productionSources().map(::scan).associateBy { relative(it.path) }

        val reader = assertNotNull(byPath[CACHE_READER_ANCHOR], "캐시 탐지기 anchor 가 사라졌다: $CACHE_READER_ANCHOR")
        assertTrue(
            cacheSymbols(reader.code).isNotEmpty(),
            "캐시 탐지기가 실제 캐시 어댑터($CACHE_READER_ANCHOR)를 못 잡았다 — 탐지기가 망가졌다.",
        )

        val prose = assertNotNull(byPath[PROSE_ONLY_ANCHOR], "산문 anchor 가 사라졌다: $PROSE_ONLY_ANCHOR")
        assertTrue(
            Regex("""(?i)cache""").containsMatchIn(prose.text),
            "$PROSE_ONLY_ANCHOR 에 더 이상 cache 산문이 없다 — redact anchor 를 옮겨야 한다.",
        )
        assertEquals(
            emptyList<Pair<Int, String>>(),
            cacheSymbols(prose.code),
            "주석에만 나오는 cache 를 캐시 사용으로 셌다 — redact 가 망가졌다.",
        )
    }

    @Test
    fun `캐시 진입점 이름들은 모두 패턴에 걸린다`() {
        val uncovered = CACHE_ENTRY_POINTS.filterNot { symbol -> CACHE_SYMBOL.matches(symbol) }

        assertTrue(
            uncovered.isEmpty(),
            "이 저장소의 캐시 진입점이 캐시 탐지 패턴에 걸리지 않는다 — 패턴을 좁히면 규칙이 샌다: $uncovered",
        )
    }

    // --- 스캔 ---------------------------------------------------------------------------------

    private data class ScannedSource(val path: Path, val text: String, val code: String) {
        fun lineOf(index: Int): String = "L${text.take(index).count { it == '\n' } + 1}"

        fun describe(index: Int): String {
            val start = text.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            return "${lineOf(index)} ${text.substring(start, end).trim()}"
        }
    }

    private fun scan(path: Path): ScannedSource {
        val text = Files.readString(path)
        return ScannedSource(path, text, redact(text))
    }

    private fun productionSources(): List<Path> = kotlinSources("/src/main/kotlin/")

    private fun testSources(): List<Path> =
        kotlinSources("/src/test/kotlin/", "/src/integrationTest/kotlin/", "/src/testFixtures/kotlin/")

    private fun kotlinSources(vararg segments: String): List<Path> = Files.walk(root()).use { paths ->
        paths.filter { path ->
            val normalized = path.toString().replace('\\', '/')
            Files.isRegularFile(path) &&
                normalized.endsWith(".kt") &&
                !normalized.contains("/build/") &&
                segments.any(normalized::contains)
        }.toList()
    }

    // --- 규칙 판정 ----------------------------------------------------------------------------

    /**
     * `VerifiedBalanceReadPort` 와, production/test 트리에서 그것을 (전이적으로) 확장하는 interface
     * 이름 전부. 하위 port 를 하나 끼워 두고 그 하위 이름으로만 구현하는 우회를 막는다.
     */
    private fun portTypeNames(sources: List<ScannedSource>): Set<String> {
        var names = setOf(PORT)
        while (true) {
            val grown = names + sources.flatMap { source ->
                typeDeclarations(source.code)
                    .filter { declaration -> declaration.keyword == "interface" && declaration.name != null }
                    .filter { declaration -> declaration.supertypes.any { (_, base) -> base in names } }
                    .map { declaration -> checkNotNull(declaration.name) }
            }
            if (grown == names) return names
            names = grown
        }
    }

    /** 이 파일이 port 를 구현하는 지점들의 (redact 된 코드 기준) 인덱스. */
    private fun implementations(source: ScannedSource, portNames: Set<String>): List<Int> {
        val bySupertype = typeDeclarations(source.code).flatMap { declaration ->
            declaration.supertypes.filter { (_, base) -> base in portNames }.map { (index, _) -> index }
        }
        val bySamConstruction = portNames.flatMap { name ->
            Regex("""\b${Regex.escape(name)}\b\s*\{""")
                .findAll(source.code)
                .filterNot { match -> isDeclarationName(source.code, match.range.first) }
                .map { match -> match.range.first }
                .toList()
        }
        return (bySupertype + bySamConstruction).distinct().sorted()
    }

    private fun cacheSymbols(code: String): List<Pair<Int, String>> =
        CACHE_SYMBOL.findAll(code).map { match -> match.range.first to match.value }.toList()

    // --- Kotlin 선언 훑기 ---------------------------------------------------------------------

    private data class TypeDeclaration(
        val keyword: String,
        val name: String?,
        /** 상위 타입 목록의 각 항목: (base 이름의 절대 인덱스, base 이름). */
        val supertypes: List<Pair<Int, String>>,
    )

    private fun typeDeclarations(code: String): List<TypeDeclaration> =
        Regex("""(?<![\w.:])(class|object|interface)\s+""").findAll(code).map { match ->
            val afterKeyword = match.range.last + 1
            val name = Regex("""^\w+""").find(code.substring(afterKeyword, minOf(code.length, afterKeyword + 200)))
            val supertypes = supertypeList(code, afterKeyword)?.let { (start, text) ->
                splitTopLevel(text).mapNotNull { (offset, entry) ->
                    baseTypeName(entry)?.let { base -> (start + offset + entry.indexOf(base)) to base }
                }
            }.orEmpty()
            TypeDeclaration(match.groupValues[1], name?.value, supertypes)
        }.toList()

    /**
     * 선언 헤더에서 상위 타입 목록을 떼어낸다. 생성자 파라미터의 `:` 와 제네릭 경계의 `:` 는 괄호/
     * 꺾쇠 안에 있으므로, depth 0 에서 처음 만나는 `:` 가 상위 타입 목록의 시작이다.
     */
    private fun supertypeList(code: String, from: Int): Pair<Int, String>? {
        var depth = 0
        var colon = -1
        var index = from
        while (index < code.length) {
            when (code[index]) {
                '(', '<', '[' -> depth++

                ')', '>', ']' -> if (depth > 0) depth--

                ':' -> if (depth == 0 && colon < 0) colon = index

                '{', '}', ';' -> if (depth == 0) return finishSupertypeList(code, colon, index)

                '\n' -> if (depth == 0 && !continuesHeader(code, colon, index)) {
                    return finishSupertypeList(code, colon, index)
                }
            }
            index++
        }
        return finishSupertypeList(code, colon, code.length)
    }

    private fun finishSupertypeList(code: String, colon: Int, end: Int): Pair<Int, String>? =
        if (colon < 0) null else colon + 1 to code.substring(colon + 1, end)

    /** 줄바꿈으로 끊긴 선언 헤더가 다음 줄로 이어지는지. */
    private fun continuesHeader(code: String, colon: Int, newline: Int): Boolean {
        if (colon >= 0 && code.substring(colon + 1, newline).trimEnd().endsWith(',')) return true
        if (colon >= 0 && code.substring(colon + 1, newline).isBlank()) return true
        var index = newline + 1
        while (index < code.length && code[index].isWhitespace()) index++
        if (index >= code.length) return false
        val ahead = code.substring(index, minOf(code.length, index + 8))
        return ahead.startsWith(',') ||
            ahead.startsWith('(') ||
            ahead.startsWith('{') ||
            (colon < 0 && ahead.startsWith(':')) ||
            Regex("""^(?:by|where)\b""").containsMatchIn(ahead)
    }

    private fun splitTopLevel(text: String): List<Pair<Int, String>> {
        val entries = mutableListOf<Pair<Int, String>>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, character ->
            when (character) {
                '(', '<', '[' -> depth++

                ')', '>', ']' -> if (depth > 0) depth--

                ',' -> if (depth == 0) {
                    entries += start to text.substring(start, index)
                    start = index + 1
                }
            }
        }
        entries += start to text.substring(start)
        return entries
    }

    /** `io.x.Foo<Bar>() by delegate` -> `Foo`. */
    private fun baseTypeName(entry: String): String? =
        Regex("""^\s*([\w.]+)""").find(entry)?.groupValues?.get(1)?.substringAfterLast('.')?.ifBlank { null }

    /** `fun interface VerifiedBalanceReadPort {` 의 선언 이름을 SAM 생성으로 오인하지 않게 한다. */
    private fun isDeclarationName(code: String, index: Int): Boolean =
        Regex("""(?:interface|class|object)\s+$""").containsMatchIn(code.substring(maxOf(0, index - 32), index))

    // --- 주석/문자열 지우기 --------------------------------------------------------------------

    /**
     * 주석과 문자열 리터럴을 같은 길이의 공백으로 덮는다. 길이와 줄바꿈 위치를 보존하므로 인덱스가
     * 원문과 1:1 로 맞고, 위반 보고에 원문 줄 번호와 원문 줄 내용을 그대로 쓸 수 있다.
     */
    private fun redact(text: String): String {
        val redacted = StringBuilder(text)
        fun blank(from: Int, to: Int) {
            for (position in from until minOf(to, redacted.length)) {
                if (redacted[position] != '\n') redacted[position] = ' '
            }
        }

        var index = 0
        while (index < text.length) {
            val end = when {
                text.startsWith("/*", index) -> blockCommentEnd(text, index)

                text.startsWith("//", index) -> text.indexOf('\n', index).let { if (it < 0) text.length else it }

                text.startsWith("\"\"\"", index) ->
                    text.indexOf("\"\"\"", index + 3).let { if (it < 0) text.length else it + 3 }

                text[index] == '"' -> quotedEnd(text, index, '"')

                text[index] == '\'' -> quotedEnd(text, index, '\'')

                else -> {
                    index++
                    continue
                }
            }
            blank(index, end)
            index = maxOf(end, index + 1)
        }
        return redacted.toString()
    }

    private fun blockCommentEnd(text: String, start: Int): Int {
        var depth = 1
        var index = start + 2
        while (index < text.length && depth > 0) {
            when {
                text.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }

                text.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }

                else -> index++
            }
        }
        return index
    }

    private fun quotedEnd(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length && text[index] != quote && text[index] != '\n') {
            index += if (text[index] == '\\') 2 else 1
        }
        return minOf(index + 1, text.length)
    }

    // --- 경로 ---------------------------------------------------------------------------------

    private fun relative(path: Path): String = root().relativize(path).invariantSeparatorsPathString

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private companion object {
        const val PORT = "VerifiedBalanceReadPort"

        const val PORT_DECLARATION = "domain/src/main/kotlin/io/premiumspread/domain/tradeprep/TradePrepPorts.kt"

        /** production 트리를 실제로 훑었다는 하한. 스캐너가 빈 목록을 돌려주면 규칙이 무의미해진다. */
        const val MINIMUM_PRODUCTION_SOURCES = 200

        val CACHE_SYMBOL = Regex("""\w*(?:(?i:cache|redis|caffeine)|opsFor)\w*""")

        /** 이 저장소에 실재하는 캐시 진입점. 패턴을 좁히면 이 목록이 먼저 실패한다. */
        val CACHE_ENTRY_POINTS = listOf(
            "AfterCommitCacheExecutor",
            "CacheManager",
            "CachePayloadSupport",
            "CacheWarmupService",
            "FxCacheReader",
            "FxCacheService",
            "PremiumAggregationCacheReader",
            "PremiumCacheReader",
            "PremiumCacheService",
            "PremiumCacheWriter",
            "RedisKeyGenerator",
            "RedisTemplate",
            "RedissonClient",
            "StringRedisTemplate",
            "TickerCacheReader",
            "TickerCacheService",
            "TimeSeriesCacheSupport",
            "opsForHash",
            "opsForValue",
            "opsForZSet",
            "redisTemplate",
        )

        /** 탐지기를 되짚는 실제 구현들. 클래스 상속(여러 줄/한 줄)과 SAM 람다를 모두 포함한다. */
        val KNOWN_IMPLEMENTATION_ANCHORS = listOf(
            "apps/api/src/integrationTest/kotlin/io/premiumspread/interfaces/api/tradeprep/" +
                "TradePreparationArmingContractTest.kt",
            "apps/api/src/integrationTest/kotlin/io/premiumspread/interfaces/api/tradeprep/" +
                "TradePreparationRegisterTargetStaleContractTest.kt",
            "domain/src/test/kotlin/io/premiumspread/domain/tradeprep/RecordedBalanceAdapter.kt",
        )

        /** port 를 언급만 하는 production 소비자와 port 선언 자체. 구현으로 잡히면 안 된다. */
        val KNOWN_CONSUMER_ANCHORS = listOf(
            "apps/api/src/main/kotlin/io/premiumspread/application/tradeprep/TradePreparationFacade.kt",
            "apps/batch/src/main/kotlin/io/premiumspread/application/job/tradeprep/TradePreparationReconcileJob.kt",
            PORT_DECLARATION,
        )

        /** 실제로 캐시를 읽는 production 어댑터 — `PremiumReadAdapter` 가 산다. 이것은 의도된 캐시다. */
        const val CACHE_READER_ANCHOR =
            "infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/cache/" +
                "MarketSnapshotReadAdapters.kt"

        /** cache 가 KDoc 산문에만 나오는 production 파일. redact 가 살아 있는지 되짚는다. */
        const val PROSE_ONLY_ANCHOR =
            "domain/src/main/kotlin/io/premiumspread/domain/aggregation/AggregationPorts.kt"
    }
}
