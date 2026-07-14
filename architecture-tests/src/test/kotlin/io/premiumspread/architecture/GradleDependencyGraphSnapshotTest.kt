package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleDependencyGraphSnapshotTest {
    @Test
    fun `production project dependency graph matches the reviewed snapshot`() {
        val actualPath = System.getProperty("architecture.dependency-graph")
        check(!actualPath.isNullOrBlank()) { "Missing system property: architecture.dependency-graph" }

        val actual = Files.readString(Path.of(actualPath)).normalizeLineEndings()
        val expected =
            checkNotNull(javaClass.getResource("/gradle-dependency-graph.snapshot")) {
                "Missing gradle-dependency-graph.snapshot"
            }.readText().normalizeLineEndings()

        assertEquals(expected, actual)
    }

    @Test
    fun `batch app uses domain at compile time and adapters only at runtime`() {
        val actualLines = actualGraph().lineSequence().filter(String::isNotBlank).toSet()
        val batchProjectEdges = actualLines.filter { line -> line.startsWith(":apps:batch [") && " -> " in line }.toSet()

        assertEquals(
            expected =
                setOf(
                    ":apps:batch [implementation] -> :domain",
                    ":apps:batch [runtimeOnly] -> :infrastructure:batch",
                    ":apps:batch [runtimeOnly] -> :infrastructure:common",
                    ":apps:batch [runtimeOnly] -> :supports:logging",
                    ":apps:batch [runtimeOnly] -> :supports:monitoring",
                ),
            actual = batchProjectEdges,
        )

        val forbiddenExternalEdges =
            actualLines
                .filter { line -> line.startsWith(":apps:batch [") && " => " in line }
                .filter { line -> BATCH_FORBIDDEN_EXTERNAL_MODULES.any(line::contains) }
                .sorted()
        assertTrue(
            forbiddenExternalEdges.isEmpty(),
            "Batch app must not compile against adapter implementation libraries: $forbiddenExternalEdges",
        )
    }

    private fun actualGraph(): String {
        val actualPath = System.getProperty("architecture.dependency-graph")
        check(!actualPath.isNullOrBlank()) { "Missing system property: architecture.dependency-graph" }
        return Files.readString(Path.of(actualPath)).normalizeLineEndings()
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

    private companion object {
        val BATCH_FORBIDDEN_EXTERNAL_MODULES =
            setOf(
                "com.fasterxml.jackson",
                "io.micrometer",
                "org.jetbrains.kotlinx:kotlinx-coroutines",
                "org.springframework.boot:spring-boot-starter-data-redis",
                "org.springframework.boot:spring-boot-starter-mail",
                "org.springframework.boot:spring-boot-starter-webflux",
                "org.springframework.data",
                "org.springframework.jdbc",
            )
    }
}
