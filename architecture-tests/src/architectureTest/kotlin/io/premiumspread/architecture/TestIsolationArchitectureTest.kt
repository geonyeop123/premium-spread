package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertTrue

class TestIsolationArchitectureTest {
    @Test
    fun `tests do not connect to real exchange SMTP or Slack endpoints`() {
        val urlPattern = Regex("""(?:https?|wss?)://[^\s\"')]+""")
        val smtpHostPattern =
            Regex(
                """(?im)(?:spring\.mail\.host|mail\.host|smtp[-_.]?(?:host|server))\s*[:=]\s*[\"']?([a-z0-9.-]+)""",
            )
        val violations = testSources().flatMap { source ->
            val content = Files.readString(source)
            val urlViolations = urlPattern.findAll(content).mapNotNull { match ->
                val uri = runCatching { URI.create(match.value) }.getOrNull() ?: return@mapNotNull null
                val host = uri.host?.lowercase() ?: return@mapNotNull null
                if (host.isIsolatedTestHost()) null else "${relative(source)} -> ${match.value}"
            }.toList()
            val smtpViolations = smtpHostPattern.findAll(content).mapNotNull { match ->
                val host = match.groupValues[1].lowercase()
                if (host.isIsolatedTestHost()) null else "${relative(source)} -> smtp://$host"
            }.toList()
            urlViolations + smtpViolations
        }

        assertTrue(
            violations.isEmpty(),
            "Tests must use localhost, reserved example/test hosts, MockWebServer, or fakes: $violations",
        )
    }

    @Test
    fun `disabled tests require an explicit reviewed allowlist`() {
        val disabledMarkers = listOf("@Disabled", "org.junit.Ignore", "org.junit.jupiter.api.Disabled")
        val violations =
            testSources()
                .filterNot { source -> source.fileName.toString() == javaClass.simpleName + ".kt" }
                .filter { source ->
                    val text = Files.readString(source)
                    disabledMarkers.any(text::contains)
                }.map(::relative)

        assertTrue(violations.isEmpty(), "Disabled tests are not allowed without a reviewed allowlist: $violations")
    }

    private fun testSources(): List<Path> = projectPaths.flatMap { projectPath ->
        testSourceSegments.flatMap { sourceSegment ->
            val sourceRoot = root().resolve(projectPath).resolve(sourceSegment)
            if (!Files.isDirectory(sourceRoot)) {
                emptyList()
            } else {
                Files.walk(sourceRoot).use { paths ->
                    paths.filter(Files::isRegularFile).toList()
                }
            }
        }
    }

    private fun relative(path: Path): String = root().relativize(path).invariantSeparatorsPathString

    private fun String.isIsolatedTestHost(): Boolean =
        this == "localhost" ||
        this == "0.0.0.0" ||
        this == "::1" ||
        startsWith("127.") ||
        endsWith(".localhost") ||
            endsWith(".example") ||
            endsWith(".example.com") ||
            endsWith(".example.test") ||
            endsWith(".invalid") ||
            endsWith(".test")

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private companion object {
        val projectPaths =
            listOf(
                "apps/api",
                "apps/batch",
                "architecture-tests",
                "domain",
                "infrastructure/api",
                "infrastructure/batch",
                "infrastructure/common",
                "modules/jpa",
                "modules/redis",
                "supports/email",
                "supports/logging",
                "supports/monitoring",
            )
        val testSourceSegments =
            listOf(
                "src/test/kotlin",
                "src/test/java",
                "src/test/resources",
                "src/testFixtures/kotlin",
                "src/testFixtures/java",
                "src/testFixtures/resources",
                "src/integrationTest/kotlin",
                "src/integrationTest/java",
                "src/integrationTest/resources",
                "src/architectureTest/kotlin",
                "src/architectureTest/java",
                "src/architectureTest/resources",
            )
    }
}
