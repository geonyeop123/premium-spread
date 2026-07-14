package io.premiumspread.architecture

import com.tngtech.archunit.core.domain.Dependency
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureBoundaryTest {
    @Test
    fun `domain does not depend on forbidden technical frameworks`() {
        val domainClasses = ArchitectureTarget.DOMAIN.importClasses()
        assertTrue(domainClasses.isNotEmpty(), "domain class-count guard must find at least one class")

        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*DOMAIN_FORBIDDEN_PACKAGES)
            .check(domainClasses)
    }

    @Test
    fun `infrastructure modules do not depend on app packages`() {
        INFRASTRUCTURE_TARGETS.forEach { target ->
            val infrastructureClasses = target.importClasses()
            assertTrue(
                infrastructureClasses.isNotEmpty(),
                "$target class-count guard must find at least one class",
            )

            noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*APP_PACKAGES)
                .check(infrastructureClasses)
        }
    }

    @Test
    fun `api interfaces technical debt is explicit and cannot grow`() {
        val apiClasses = ArchitectureTarget.APPS_API.importClasses()
        assertExactDebt(
            classes = apiClasses,
            originPackage = "io.premiumspread.interfaces",
            forbiddenTargetPackages = API_INTERFACES_FORBIDDEN_PACKAGES,
            allowedEdges = API_INTERFACES_DEBT,
        )
    }

    @Test
    fun `batch application technical debt is explicit and cannot grow`() {
        val batchClasses = ArchitectureTarget.APPS_BATCH.importClasses()
        assertExactDebt(
            classes = batchClasses,
            originPackage = "io.premiumspread.application",
            forbiddenTargetPackages = BATCH_TECHNICAL_PACKAGES,
            allowedEdges = BATCH_APPLICATION_DEBT,
        )
    }

    @Test
    fun `api interfaces source technical debt is explicit and cannot grow`() {
        assertExactSourceDebt(
            sourceRootProperty = "architecture.source.apps.api",
            boundaryPackagePath = "io/premiumspread/interfaces",
            forbiddenTargetPackages = API_INTERFACES_FORBIDDEN_PACKAGES,
            allowedEdges = API_INTERFACES_SOURCE_DEBT,
        )
    }

    @Test
    fun `batch application source technical debt is explicit and cannot grow`() {
        assertExactSourceDebt(
            sourceRootProperty = "architecture.source.apps.batch",
            boundaryPackagePath = "io/premiumspread/application",
            forbiddenTargetPackages = BATCH_TECHNICAL_PACKAGES,
            allowedEdges = BATCH_APPLICATION_SOURCE_DEBT,
        )
    }

    @Test
    fun `domain source has no forbidden technical dependency including inlined constants`() {
        assertExactSourceDebt(
            sourceRootProperty = "architecture.source.domain",
            boundaryPackagePath = "io/premiumspread/domain",
            forbiddenTargetPackages = DOMAIN_FORBIDDEN_PACKAGES.map { it.removeSuffix("..") }.toTypedArray(),
            allowedEdges = DOMAIN_SOURCE_DEBT,
        )
    }

    @Test
    fun `forbidden package prefixes cover every mandatory technical family`() {
        assertContainsMandatoryTechnicalPrefixes(
            boundary = "domain",
            configuredPrefixes = DOMAIN_FORBIDDEN_PACKAGES.map { it.removeSuffix("..") }.toSet(),
        )
        assertContainsMandatoryTechnicalPrefixes(
            boundary = "api interfaces",
            configuredPrefixes = API_INTERFACES_FORBIDDEN_PACKAGES.toSet(),
        )
        assertContainsMandatoryTechnicalPrefixes(
            boundary = "batch application",
            configuredPrefixes = BATCH_TECHNICAL_PACKAGES.toSet(),
        )
        assertTrue(
            "io.premiumspread.infrastructure" in API_INTERFACES_FORBIDDEN_PACKAGES,
            "API source inspection must retain infrastructure imports that bytecode const inlining can erase",
        )
        assertTrue(
            "org.hibernate" in DOMAIN_FORBIDDEN_PACKAGES.map { it.removeSuffix("..") },
            "Domain may use JPA annotations but must not depend directly on Hibernate implementations",
        )
        assertTrue(
            setOf("jakarta.servlet", "org.springframework.http")
                .all { it in BATCH_TECHNICAL_PACKAGES },
            "Batch application must not depend on servlet or HTTP transport implementations",
        )
        assertTrue(
            setOf("jakarta.servlet", "org.springframework.http")
                .none { it in API_INTERFACES_FORBIDDEN_PACKAGES },
            "API interfaces are the transport adapter and may use servlet and Spring HTTP types",
        )
    }

    private fun assertContainsMandatoryTechnicalPrefixes(
        boundary: String,
        configuredPrefixes: Set<String>,
    ) {
        val missingPrefixes = MANDATORY_TECHNICAL_PREFIXES - configuredPrefixes
        assertTrue(
            missingPrefixes.isEmpty(),
            "$boundary forbidden packages are missing mandatory technical prefixes: ${missingPrefixes.sorted()}",
        )
    }

    private fun assertExactDebt(
        classes: JavaClasses,
        originPackage: String,
        forbiddenTargetPackages: Array<String>,
        allowedEdges: Set<String>,
    ) {
        val inspectedClassCount = classes.count { it.packageName.startsWith(originPackage) }
        assertTrue(
            inspectedClassCount > 0,
            "class-count guard for $originPackage must find at least one class",
        )

        val currentDebtEdges =
            classes
                .asSequence()
                .filter { it.packageName.startsWith(originPackage) }
                .flatMap { it.directDependenciesFromSelf.asSequence() }
                .filter { dependency -> dependency.targetsAny(forbiddenTargetPackages) }
                .map { dependency -> "${dependency.originClass.name} -> ${dependency.targetClass.name}" }
                .toSortedSet()

        assertEquals(
            expected = allowedEdges,
            actual = currentDebtEdges,
            message =
                "Debt allowlist is an exact ceiling: remove migrated classes from the allowlist; " +
                    "new dependencies must never be added implicitly",
        )
    }

    private fun assertExactSourceDebt(
        sourceRootProperty: String,
        boundaryPackagePath: String,
        forbiddenTargetPackages: Array<String>,
        allowedEdges: Set<String>,
    ) {
        val sourceRootValue = System.getProperty(sourceRootProperty)
        check(!sourceRootValue.isNullOrBlank()) { "Missing system property: $sourceRootProperty" }

        val sourceRoot = Path.of(sourceRootValue)
        val boundaryRoot = sourceRoot.resolve(boundaryPackagePath)
        check(Files.isDirectory(boundaryRoot)) { "Architecture source boundary does not exist: $boundaryRoot" }
        val scanner = KotlinSourceDependencyScanner(forbiddenTargetPackages.toSet())
        val currentDebtEdges = sortedSetOf<String>()

        Files.walk(boundaryRoot).use { sourcePaths ->
            sourcePaths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .sorted()
                .forEach { path ->
                    val relativePath =
                        sourceRoot
                            .relativize(path)
                            .toString()
                            .replace('\\', '/')
                            .removePrefix("io/premiumspread/")
                    currentDebtEdges += scanner.scan(relativePath, Files.readString(path))
                }
        }

        assertEquals(
            expected = allowedEdges,
            actual = currentDebtEdges,
            message =
                "Source debt allowlist is exact and catches imports/FQ references erased or altered in bytecode; " +
                    "remove migrated edges and reject new technical edges",
        )
    }

    private fun Dependency.targetsAny(packagePrefixes: Array<String>): Boolean =
        packagePrefixes.any { targetClass.packageName.startsWith(it) }

    private companion object {
        val INFRASTRUCTURE_TARGETS =
            listOf(
                ArchitectureTarget.INFRASTRUCTURE_COMMON,
                ArchitectureTarget.INFRASTRUCTURE_API,
                ArchitectureTarget.INFRASTRUCTURE_BATCH,
            )

        val MANDATORY_TECHNICAL_PREFIXES =
            setOf(
                // JWT/security implementations
                "com.auth0.jwt",
                "com.nimbusds.jwt",
                "io.jsonwebtoken",
                "org.springframework.security",
                // Serialization
                "com.fasterxml.jackson",
                // Redis clients
                "io.lettuce",
                "io.premiumspread.redis",
                "org.redisson",
                "org.springframework.data.redis",
                "redis.clients.jedis",
                // JDBC/R2DBC and database drivers
                "com.mysql.cj",
                "io.r2dbc",
                "java.sql",
                "javax.sql",
                "org.mariadb.jdbc",
                "org.postgresql",
                "org.springframework.jdbc",
                "org.springframework.r2dbc",
                // HTTP clients and WebSocket implementations
                "feign",
                "io.netty",
                "jakarta.websocket",
                "java.net.http",
                "javax.websocket",
                "okhttp3",
                "org.springframework.cloud.openfeign",
                "org.springframework.web.client",
                "org.springframework.web.reactive",
                "org.springframework.web.socket",
                "reactor",
                "retrofit2",
                // SMTP implementations
                "com.sun.mail",
                "jakarta.mail",
                "javax.mail",
                "org.eclipse.angus.mail",
                "org.springframework.mail",
                // Metrics
                "io.micrometer",
            )

        val DOMAIN_FORBIDDEN_PACKAGES =
            (
                MANDATORY_TECHNICAL_PREFIXES +
                    setOf(
                        "io.premiumspread.infrastructure",
                        "jakarta.servlet",
                        "org.springframework.boot",
                        "org.springframework.data.jpa",
                        "org.springframework.data.repository",
                        "org.springframework.http",
                        "org.hibernate",
                        "org.springframework.orm",
                        "org.springframework.web",
                    )
            ).sorted().map { "$it.." }.toTypedArray()

        val APP_PACKAGES =
            arrayOf(
                "io.premiumspread.application..",
                "io.premiumspread.interfaces..",
                "io.premiumspread.scheduler..",
            )

        val API_INTERFACES_FORBIDDEN_PACKAGES =
            (
                MANDATORY_TECHNICAL_PREFIXES +
                    setOf(
                        "io.premiumspread.domain",
                        "io.premiumspread.email",
                        "io.premiumspread.infrastructure",
                        "io.premiumspread.logging",
                        "io.premiumspread.monitoring",
                        "jakarta.persistence",
                        "org.springframework.data.jpa",
                        "org.springframework.data.repository",
                        "org.hibernate",
                        "org.springframework.orm",
                    )
            ).sorted().toTypedArray()

        val API_INTERFACES_DEBT = loadDebtAllowlist("api-interfaces-debt.allowlist")
        val API_INTERFACES_SOURCE_DEBT = loadDebtAllowlist("api-interfaces-source-debt.allowlist")
        val DOMAIN_SOURCE_DEBT = loadDebtAllowlist("domain-source-debt.allowlist")

        val BATCH_TECHNICAL_PACKAGES =
            (
                MANDATORY_TECHNICAL_PREFIXES +
                    setOf(
                        "io.premiumspread.cache",
                        "io.premiumspread.calculator",
                        "io.premiumspread.client",
                        "io.premiumspread.email",
                        "io.premiumspread.infrastructure",
                        "io.premiumspread.logging",
                        "io.premiumspread.monitoring",
                        "io.premiumspread.repository",
                        "jakarta.persistence",
                        "jakarta.servlet",
                        "org.hibernate",
                        "org.springframework.data.jpa",
                        "org.springframework.data.repository",
                        "org.springframework.http",
                        "org.springframework.orm",
                    )
            ).sorted().toTypedArray()

        val BATCH_APPLICATION_DEBT = loadDebtAllowlist("batch-application-debt.allowlist")
        val BATCH_APPLICATION_SOURCE_DEBT = loadDebtAllowlist("batch-application-source-debt.allowlist")

        fun loadDebtAllowlist(resourceName: String): Set<String> {
            val resource =
                checkNotNull(ArchitectureBoundaryTest::class.java.getResource("/$resourceName")) {
                    "Missing architecture debt allowlist: $resourceName"
                }
            val edges = resource.readText().lineSequence().filter(String::isNotBlank).toList()
            check(edges.size == edges.toSet().size) { "Duplicate edge in architecture debt allowlist: $resourceName" }
            return edges.toSet()
        }
    }
}
