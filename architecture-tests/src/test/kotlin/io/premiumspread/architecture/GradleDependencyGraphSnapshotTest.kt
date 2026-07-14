package io.premiumspread.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

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

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")
}
