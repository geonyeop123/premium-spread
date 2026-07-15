package io.premiumspread.architecture

internal class KotlinSourceDependencyScanner(forbiddenPackagePrefixes: Set<String>) {
    private val forbiddenPackagePrefixes = forbiddenPackagePrefixes.sortedByDescending(String::length)

    init {
        require(forbiddenPackagePrefixes.isNotEmpty()) { "At least one forbidden package prefix is required" }
        require(forbiddenPackagePrefixes.none(String::isBlank)) { "Forbidden package prefixes must not be blank" }
    }

    fun scan(
        relativePath: String,
        source: String,
    ): Set<String> {
        val normalizedPath = relativePath.replace('\\', '/')
        val sanitizedSource = source.removeCommentsAndLiterals()
        val importedTargets =
            sanitizedSource
                .lineSequence()
                .mapNotNull { line -> IMPORT_REGEX.matchEntire(line.trim())?.groupValues?.get(1) }
        val fullyQualifiedTargets =
            sanitizedSource
                .lineSequence()
                .filterNot { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") || trimmed.startsWith("package ")
                }.flatMap { line -> FULLY_QUALIFIED_REFERENCE_REGEX.findAll(line).map(MatchResult::value) }

        return (importedTargets + fullyQualifiedTargets)
            .filter(::isForbidden)
            .map { target -> "$normalizedPath -> $target" }
            .toSet()
    }

    private fun isForbidden(target: String): Boolean =
        forbiddenPackagePrefixes.any { prefix -> target == prefix || target.startsWith("$prefix.") }

    private fun String.removeCommentsAndLiterals(): String =
        replace(BLOCK_COMMENT_REGEX, " ")
            .replace(TRIPLE_QUOTED_STRING_REGEX, " ")
            .replace(QUOTED_STRING_REGEX, " ")
            .replace(CHAR_LITERAL_REGEX, " ")
            .replace(LINE_COMMENT_REGEX, "")

    private companion object {
        val IMPORT_REGEX = Regex("""import\s+([A-Za-z_][\w.]*(?:\.\*)?)(?:\s+as\s+[A-Za-z_][\w]*)?""")
        val FULLY_QUALIFIED_REFERENCE_REGEX = Regex("""\b[a-z_][\w]*(?:\.[A-Za-z_][\w*]*){1,}""")
        val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val TRIPLE_QUOTED_STRING_REGEX = Regex("""\"\"\".*?\"\"\"""", RegexOption.DOT_MATCHES_ALL)
        val QUOTED_STRING_REGEX = Regex("""\"(?:\\.|[^\"\\])*\"""")
        val CHAR_LITERAL_REGEX = Regex("""'(?:\\.|[^'\\])'""")
        val LINE_COMMENT_REGEX = Regex("""//.*$""", setOf(RegexOption.MULTILINE))
    }
}
