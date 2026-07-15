package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ObservabilityCardinalityArchitectureTest {
    @Test
    fun `production metric tags never use pii or runtime identifiers`() {
        val forbidden = setOf(
            "cookie",
            "deliveryId",
            "email",
            "exceptionMessage",
            "memberId",
            "message",
            "owner",
            "password",
            "runId",
            "token",
        )
        val violations = productionSources().flatMap { source ->
            val text = Files.readString(source)
            forbidden.mapNotNull { key ->
                val tagBuilder = Regex("""\.tag\(\s*"$key"""").containsMatchIn(text)
                val registryCounter = Regex("""\.counter\([\s\S]{0,300}?"$key"\s*,""").containsMatchIn(text)
                if (tagBuilder || registryCounter) "${root().relativize(source)} -> $key" else null
            }
        }

        assertTrue(violations.isEmpty(), "Forbidden metric tags: $violations")
    }

    @Test
    fun `metric builders use literals or constant names instead of runtime metric names`() {
        val builders = listOf(
            Regex("""(?:Counter|Timer|Gauge)\.builder\(\s*([^,\n)]+)"""),
            Regex("""\.counter\(\s*([^,\n)]+)"""),
        )
        val violations = productionSources().flatMap { source ->
            val text = Files.readString(source)
            builders.flatMap { builder ->
                builder.findAll(text).mapNotNull { match ->
                    val argument = match.groupValues[1].trim()
                    val fixed = argument.startsWith('"') || argument.matches(Regex("[A-Z][A-Z0-9_.]*"))
                    if (fixed) null else "${root().relativize(source)} -> $argument"
                }.toList()
            }
        }

        assertTrue(violations.isEmpty(), "Dynamic metric names: $violations")
    }

    private fun productionSources(): List<Path> = Files.walk(root()).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) &&
                path.toString().endsWith(".kt") &&
                path.toString().replace('\\', '/').contains("/src/main/kotlin/")
        }.toList()
    }

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
