package io.premiumspread.architecture

import com.tngtech.archunit.core.domain.Dependency
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaModifier
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
    fun `api application does not depend on technical implementations`() {
        val apiClasses = ArchitectureTarget.APPS_API.importClasses()
        assertExactDebt(
            classes = apiClasses,
            originPackage = API_APPLICATION_PACKAGE,
            forbiddenTargetPackages = API_APPLICATION_FORBIDDEN_PACKAGES,
            allowedEdges = emptySet(),
        )
    }

    @Test
    fun `batch application technical debt is explicit and cannot grow`() {
        val batchClasses = ArchitectureTarget.APPS_BATCH.importClasses()
        assertExactDebt(
            classes = batchClasses,
            originPackage = BATCH_APPLICATION_PACKAGE,
            forbiddenTargetPackages = BATCH_TECHNICAL_PACKAGES,
            allowedEdges = BATCH_APPLICATION_DEBT,
        )
    }

    @Test
    fun `batch app does not own technical adapter or legacy scheduler packages`() {
        val batchClasses = ArchitectureTarget.APPS_BATCH.importClasses()
        val compiledForbiddenClasses =
            batchClasses
                .filter { javaClass ->
                    BATCH_FORBIDDEN_OWNED_PACKAGES.any { forbiddenPackage ->
                        javaClass.packageName == forbiddenPackage ||
                            javaClass.packageName.startsWith("$forbiddenPackage.")
                    }
                }.map { javaClass -> javaClass.name }
                .sorted()
        assertTrue(
            compiledForbiddenClasses.isEmpty(),
            "apps-batch main output must not own technical adapters or legacy schedulers: $compiledForbiddenClasses",
        )

        val sourceRoot = requiredSourceRoot("architecture.source.apps.batch")
        val forbiddenSources =
            BATCH_FORBIDDEN_OWNED_PACKAGE_PATHS
                .flatMap { packagePath ->
                    val packageRoot = sourceRoot.resolve(packagePath)
                    if (Files.isDirectory(packageRoot)) {
                        Files.walk(packageRoot).use { paths ->
                            paths
                                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                                .map { path -> sourceRoot.relativize(path).toString().replace('\\', '/') }
                                .toList()
                        }
                    } else {
                        emptyList()
                    }
                }.sorted()
        assertTrue(
            forbiddenSources.isEmpty(),
            "apps-batch main source must not own technical adapters or legacy schedulers: $forbiddenSources",
        )
    }

    @Test
    fun `batch schedulers inject one application job and at most one scheduling config`() {
        val batchClasses = ArchitectureTarget.APPS_BATCH.importClasses()
        val schedulers =
            batchClasses
                .filter { javaClass ->
                    javaClass.packageName.startsWith(BATCH_SCHEDULING_PACKAGE) &&
                        javaClass.simpleName.endsWith("Scheduler") &&
                        !javaClass.isNestedClass
                }.sortedBy { javaClass -> javaClass.name }
        assertTrue(schedulers.isNotEmpty(), "Batch scheduler class-count guard must find at least one class")

        val violations =
            schedulers.mapNotNull { scheduler ->
                val constructors =
                    scheduler.constructors.filterNot { constructor ->
                        JavaModifier.SYNTHETIC in constructor.modifiers
                    }
                if (constructors.size != 1) {
                    return@mapNotNull "${scheduler.name} has ${constructors.size} non-synthetic constructors"
                }

                val parameters = constructors.single().rawParameterTypes
                val applicationJobs =
                    parameters.filter { parameter ->
                        parameter.packageName.startsWith("$BATCH_APPLICATION_PACKAGE.") &&
                            (parameter.simpleName.endsWith("Job") || parameter.simpleName.endsWith("JobFacade"))
                    }
                val supplementaryParameters = parameters - applicationJobs.toSet()
                val validSupplementaryParameters =
                    supplementaryParameters.size <= 1 &&
                        supplementaryParameters.all { parameter ->
                            parameter.simpleName in BATCH_SCHEDULER_CONFIG_TYPES
                        }
                if (applicationJobs.size != 1 || !validSupplementaryParameters) {
                    val actual = parameters.joinToString(prefix = "[", postfix = "]") { it.name }
                    "${scheduler.name} must inject one application Job and at most one scheduling config, but was $actual"
                } else {
                    null
                }
            }

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    @Test
    fun `batch scheduling interfaces do not depend on technical implementations`() {
        val batchClasses = ArchitectureTarget.APPS_BATCH.importClasses()
        assertExactDebt(
            classes = batchClasses,
            originPackage = BATCH_SCHEDULING_PACKAGE,
            forbiddenTargetPackages = BATCH_TECHNICAL_PACKAGES,
            allowedEdges = emptySet(),
        )
        assertExactSourceDebt(
            sourceRootProperty = "architecture.source.apps.batch",
            boundaryPackagePath = "io/premiumspread/interfaces/scheduling",
            forbiddenTargetPackages = BATCH_TECHNICAL_PACKAGES,
            allowedEdges = emptySet(),
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
    fun `api application source does not import technical implementations`() {
        assertExactSourceDebt(
            sourceRootProperty = "architecture.source.apps.api",
            boundaryPackagePath = "io/premiumspread/application",
            forbiddenTargetPackages = API_APPLICATION_FORBIDDEN_PACKAGES,
            allowedEdges = emptySet(),
        )
    }

    @Test
    fun `api app does not own domain or infrastructure implementation classes`() {
        val apiClasses = ArchitectureTarget.APPS_API.importClasses()
        val compiledForbiddenClasses =
            apiClasses
                .filter { javaClass ->
                    javaClass.packageName.startsWith(API_INFRASTRUCTURE_PACKAGE) ||
                        javaClass.packageName.startsWith(DOMAIN_PACKAGE)
                }
                .map { javaClass -> javaClass.name }
                .sorted()
        assertTrue(
            compiledForbiddenClasses.isEmpty(),
            "apps-api main output must not contain domain/infrastructure classes: $compiledForbiddenClasses",
        )

        val sourceRoot = requiredSourceRoot("architecture.source.apps.api")
        val forbiddenSources =
            listOf("io/premiumspread/domain", "io/premiumspread/infrastructure")
                .flatMap { packagePath ->
                    val packageRoot = sourceRoot.resolve(packagePath)
                    if (Files.isDirectory(packageRoot)) {
                        Files.walk(packageRoot).use { paths ->
                            paths
                                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                                .map { path -> sourceRoot.relativize(path).toString().replace('\\', '/') }
                                .toList()
                        }
                    } else {
                        emptyList()
                    }
                }.sorted()
        assertTrue(
            forbiddenSources.isEmpty(),
            "apps-api main source must not own domain/infrastructure files: $forbiddenSources",
        )
    }

    @Test
    fun `every REST controller injects exactly one application facade`() {
        val apiClasses = ArchitectureTarget.APPS_API.importClasses()
        val controllers =
            apiClasses
                .filter { javaClass -> javaClass.isAnnotatedWith(REST_CONTROLLER_ANNOTATION) }
                .sortedBy { javaClass -> javaClass.name }
        assertTrue(controllers.isNotEmpty(), "REST controller class-count guard must find at least one class")

        val violations =
            controllers.mapNotNull { controller ->
                val constructors =
                    controller.constructors.filterNot { constructor ->
                        JavaModifier.SYNTHETIC in constructor.modifiers
                    }
                if (constructors.size != 1) {
                    return@mapNotNull "${controller.name} has ${constructors.size} non-synthetic constructors"
                }

                val parameters = constructors.single().rawParameterTypes
                val facade = parameters.singleOrNull()
                if (
                    facade == null ||
                    !facade.packageName.startsWith("$API_APPLICATION_PACKAGE.") ||
                    !facade.simpleName.endsWith("Facade")
                ) {
                    val actual = parameters.joinToString(prefix = "[", postfix = "]") { it.name }
                    "${controller.name} constructor dependencies must be exactly one application Facade, but were $actual"
                } else {
                    null
                }
            }

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    @Test
    fun `facade public contracts do not expose domain or infrastructure types`() {
        val apiClasses = ArchitectureTarget.APPS_API.importClasses()
        val facades =
            apiClasses
                .filter { javaClass ->
                    javaClass.packageName.startsWith("$API_APPLICATION_PACKAGE.") &&
                        javaClass.simpleName.endsWith("Facade") &&
                        !javaClass.isNestedClass
                }.sortedBy { javaClass -> javaClass.name }
        assertTrue(facades.isNotEmpty(), "Facade class-count guard must find at least one class")

        val contractTypes = ArrayDeque<JavaClass>()
        val violations = sortedSetOf<String>()
        facades.forEach { facade ->
            facade.methods
                .filter(::isPublicContractCodeUnit)
                .forEach { method ->
                    method.allInvolvedRawTypes.forEach { involvedType ->
                        when {
                            involvedType.isFacadeContractForbidden() ->
                                violations += "${method.fullName} exposes ${involvedType.name}"
                            involvedType.isApplicationContractType() && involvedType.name != facade.name ->
                                contractTypes += involvedType
                        }
                    }
                }
        }

        val inspectedContractTypes = mutableSetOf<String>()
        while (contractTypes.isNotEmpty()) {
            val contractType = contractTypes.removeFirst()
            if (!inspectedContractTypes.add(contractType.name)) continue

            contractType.fields
                .filter { field -> JavaModifier.PUBLIC in field.modifiers }
                .forEach { field ->
                    val involvedType = field.rawType
                    when {
                        involvedType.isFacadeContractForbidden() ->
                            violations += "${field.fullName} exposes ${involvedType.name}"
                        involvedType.isApplicationContractType() -> contractTypes += involvedType
                    }
                }
            contractType.codeUnits
                .filter(::isPublicContractCodeUnit)
                .forEach { codeUnit ->
                    codeUnit.allInvolvedRawTypes.forEach { involvedType ->
                        when {
                            involvedType.isFacadeContractForbidden() ->
                                violations += "${codeUnit.fullName} exposes ${involvedType.name}"
                            involvedType.isApplicationContractType() -> contractTypes += involvedType
                        }
                    }
                }
        }

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
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
    fun `batch Phase 6 technical adapter files are an exact manifest`() {
        val sourceRootValue = System.getProperty("architecture.source.apps.batch")
        check(!sourceRootValue.isNullOrBlank()) { "Missing system property: architecture.source.apps.batch" }

        val sourceRoot = Path.of(sourceRootValue)
        val currentTechnicalAdapterFiles = sortedSetOf<String>()
        Files.walk(sourceRoot).use { sourcePaths ->
            sourcePaths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .sorted()
                .forEach { path ->
                    val importsTechnicalAdapter = Files.readString(path)
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.startsWith("import ") }
                        .map { line -> line.removePrefix("import ").substringBefore(" as ") }
                        .any { importedType ->
                            importedType in BATCH_PHASE6_EXACT_TECHNICAL_TYPES ||
                                BATCH_PHASE6_TECHNICAL_PREFIXES.any(importedType::startsWith)
                        }
                    if (importsTechnicalAdapter) {
                        currentTechnicalAdapterFiles += sourceRoot
                            .relativize(path)
                            .toString()
                            .replace('\\', '/')
                            .removePrefix("io/premiumspread/")
                    }
                }
        }

        assertEquals(
            expected = BATCH_PHASE6_TECHNICAL_ADAPTER_FILES,
            actual = currentTechnicalAdapterFiles,
            message =
                "Phase 6 temporary adapter manifest is exact: remove migrated files from the manifest and " +
                    "reject every new direct persistence/cache/modules-redis import",
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
            boundary = "api application",
            configuredPrefixes = API_APPLICATION_FORBIDDEN_PACKAGES.toSet(),
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
        val sourceRoot = requiredSourceRoot(sourceRootProperty)
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

    private fun requiredSourceRoot(propertyName: String): Path {
        val sourceRootValue = System.getProperty(propertyName)
        check(!sourceRootValue.isNullOrBlank()) { "Missing system property: $propertyName" }
        return Path.of(sourceRootValue)
    }

    private fun isPublicContractCodeUnit(codeUnit: JavaCodeUnit): Boolean =
        JavaModifier.PUBLIC in codeUnit.modifiers &&
            JavaModifier.SYNTHETIC !in codeUnit.modifiers &&
            JavaModifier.BRIDGE !in codeUnit.modifiers

    private fun JavaClass.isFacadeContractForbidden(): Boolean =
        FACADE_CONTRACT_FORBIDDEN_PACKAGES.any { forbiddenPackage -> packageName.startsWith(forbiddenPackage) }

    private fun JavaClass.isApplicationContractType(): Boolean =
        packageName.startsWith("$API_APPLICATION_PACKAGE.") && !simpleName.endsWith("Facade")

    private fun Dependency.targetsAny(packagePrefixes: Array<String>): Boolean =
        packagePrefixes.any { targetClass.packageName.startsWith(it) }

    private companion object {
        const val API_APPLICATION_PACKAGE = "io.premiumspread.application"
        const val API_INFRASTRUCTURE_PACKAGE = "io.premiumspread.infrastructure"
        const val DOMAIN_PACKAGE = "io.premiumspread.domain"
        const val REST_CONTROLLER_ANNOTATION = "org.springframework.web.bind.annotation.RestController"
        const val BATCH_APPLICATION_PACKAGE = "io.premiumspread.application"
        const val BATCH_SCHEDULING_PACKAGE = "io.premiumspread.interfaces.scheduling"

        val BATCH_FORBIDDEN_OWNED_PACKAGES =
            setOf(
                "io.premiumspread.cache",
                "io.premiumspread.client",
                "io.premiumspread.infrastructure",
                "io.premiumspread.repository",
                "io.premiumspread.scheduler",
            )
        val BATCH_FORBIDDEN_OWNED_PACKAGE_PATHS =
            BATCH_FORBIDDEN_OWNED_PACKAGES.map { packageName -> packageName.replace('.', '/') }
        val BATCH_SCHEDULER_CONFIG_TYPES =
            setOf(
                "BatchSchedulingProperties",
                "SchedulingProperties",
                "JobConfig",
            )

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

        val API_APPLICATION_FORBIDDEN_PACKAGES =
            (
                MANDATORY_TECHNICAL_PREFIXES +
                    setOf(
                        "io.premiumspread.cache",
                        "io.premiumspread.client",
                        "io.premiumspread.email",
                        API_INFRASTRUCTURE_PACKAGE,
                        "io.premiumspread.logging",
                        "io.premiumspread.monitoring",
                        "io.premiumspread.repository",
                        "jakarta.persistence",
                        "jakarta.servlet",
                        "org.hibernate",
                        "org.springframework.boot",
                        "org.springframework.data.jpa",
                        "org.springframework.data.repository",
                        "org.springframework.http",
                        "org.springframework.orm",
                        "org.springframework.web",
                    )
            ).sorted().toTypedArray()

        val FACADE_CONTRACT_FORBIDDEN_PACKAGES =
            setOf(
                "io.premiumspread.domain",
                API_INFRASTRUCTURE_PACKAGE,
            )

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
        val BATCH_PHASE6_TECHNICAL_ADAPTER_FILES =
            loadDebtAllowlist("batch-phase6-technical-adapter-files.allowlist")

        val BATCH_PHASE6_EXACT_TECHNICAL_TYPES =
            setOf(
                "jakarta.persistence.EntityManager",
                "javax.persistence.EntityManager",
                "org.springframework.data.redis.core.RedisTemplate",
                "org.springframework.data.redis.core.StringRedisTemplate",
                "org.springframework.jdbc.core.JdbcTemplate",
            )

        val BATCH_PHASE6_TECHNICAL_PREFIXES =
            setOf(
                "io.premiumspread.infrastructure.common.cache.",
                "io.premiumspread.infrastructure.common.persistence.",
                "io.premiumspread.redis.",
            )

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
