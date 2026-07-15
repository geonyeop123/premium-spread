package io.premiumspread.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal enum class ArchitectureTarget(private val propertySuffix: String) {
    DOMAIN("domain"),
    INFRASTRUCTURE_COMMON("infrastructure.common"),
    INFRASTRUCTURE_API("infrastructure.api"),
    INFRASTRUCTURE_BATCH("infrastructure.batch"),
    APPS_API("apps.api"),
    APPS_BATCH("apps.batch"),
    ;

    fun importClasses(): JavaClasses {
        val propertyName = "architecture.target.$propertySuffix"
        val targetPath = System.getProperty(propertyName)
        check(!targetPath.isNullOrBlank()) { "Missing system property: $propertyName" }

        val path = Path.of(targetPath)
        check(Files.isRegularFile(path)) { "Architecture target does not exist: $path" }
        return JarFile(path.toFile()).use { jar -> ClassFileImporter().importJar(jar) }
    }
}
