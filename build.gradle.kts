import org.gradle.api.Project.DEFAULT_VERSION
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.math.BigDecimal
/** --- configuration functions --- */
fun getGitHash(): String {
    return runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrElse { "init" }
}

/** --- project configurations --- */
plugins {
    base
    jacoco
}

jacoco {
    toolVersion = providers.gradleProperty("jacocoVersion").get()
}

val coverageExclusions =
    layout.projectDirectory.file("config/coverage/exclusions.txt").asFile
        .readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
val approvedCoverageExclusions =
    setOf(
        "**/generated/**",
        "**/*Generated*.*",
        "**/PremiumSpreadApplication*.*",
        "**/PremiumSpreadBatchApplication*.*",
        "**/config/**",
        "**/*Configuration*.*",
        "**/*AutoConfiguration*.*",
        "**/*Properties*.*",
        "**/*Dto*.*",
        "**/*Dtos*.*",
        "**/interfaces/api/**/*Request*.*",
        "**/interfaces/api/**/*Response*.*",
    )

allprojects {
    val projectGroup: String by project
    group = projectGroup
    version = if (version == DEFAULT_VERSION) getGitHash() else version

    repositories {
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }
}

val coverageProjects =
    subprojects.filter { candidate ->
        candidate.layout.projectDirectory.dir("src/main/kotlin").asFile.isDirectory
    }
val domainCoverageProjects = listOf(project(":domain"))
val applicationCoverageProjects = listOf(project(":apps:api"), project(":apps:batch"))

fun classDirectoriesOf(
    projects: Iterable<Project>,
    includes: List<String> = emptyList(),
) =
    files(
        projects.map { candidate ->
            candidate.fileTree(candidate.layout.buildDirectory.dir("classes/kotlin/main")) {
                exclude(coverageExclusions)
                if (includes.isNotEmpty()) {
                    include(includes)
                }
            }
        },
    )

fun sourceDirectoriesOf(projects: Iterable<Project>) =
    files(projects.map { it.layout.projectDirectory.dir("src/main/kotlin") })

fun externalArtifactsOf(
    projects: Iterable<Project>,
    configurationNames: Set<String>,
): List<ResolvedArtifactResult> =
    projects.flatMap { candidate ->
        candidate.configurations
            .filter { configuration ->
                configuration.isCanBeResolved && configuration.name in configurationNames
            }.flatMap { configuration ->
                configuration.incoming.artifactView {
                    componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                }.artifacts.artifacts
            }
    }

fun externalBuildscriptArtifactsOf(projects: Iterable<Project>): List<ResolvedArtifactResult> =
    projects.flatMap { candidate ->
        candidate.buildscript.configurations
            .filter { configuration -> configuration.isCanBeResolved }
            .flatMap { configuration ->
                configuration.incoming.artifactView {
                    componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                }.artifacts.artifacts
            }
    }

fun ResolvedArtifactResult.stableCoordinate(): String {
    val component = id.componentIdentifier as ModuleComponentIdentifier
    return "${component.group}:${component.module}:${component.version}:${file.name}"
}

val aggregateExecutionData =
    files(
        coverageProjects.map { candidate ->
            candidate.fileTree(candidate.layout.buildDirectory.dir("jacoco")) {
                // Unit coverage must not change when a developer ran integrationTest earlier in the same worktree.
                include("test.exec")
            }
        },
    )

val unitTest by tasks.registering {
    group = "verification"
    description = "Runs unit tests without architecture or Docker-backed integration tests."
    dependsOn(coverageProjects.map { "${it.path}:test" })
}

val architectureTest by tasks.registering {
    group = "verification"
    description = "Runs the independent architecture verification suite."
    dependsOn(":architecture-tests:architectureTest")
}

val verifyTestIsolationPolicy by tasks.registering {
    group = "verification"
    description = "Verifies timeout, thread-leak detection, Testcontainers isolation, and no hidden retry policy."
    doLast {
        val testTasks = allprojects.flatMap { it.tasks.withType(Test::class.java).toList() }
        check(testTasks.isNotEmpty()) { "No Test tasks found" }
        testTasks.forEach { testTask ->
            check(testTask.timeout.orNull != null) { "${testTask.path} must define a task timeout" }
            check(testTask.systemProperties["premiumspread.test.leak-detection"] == "true") {
                "${testTask.path} must enable non-daemon thread leak detection"
            }
            check(testTask.systemProperties["testcontainers.reuse.enable"] == "false") {
                "${testTask.path} must disable Testcontainers reuse for isolated CI execution"
            }
        }
        check(allprojects.none { it.plugins.hasPlugin("org.gradle.test-retry") }) {
            "Test retry is forbidden because it can hide flaky or leaked-resource failures"
        }
    }
}

val verifyCoverageExclusions by tasks.registering {
    group = "verification"
    description = "Rejects coverage exclusion expansion beyond generated, wiring, and DTO-only code."
    inputs.file(layout.projectDirectory.file("config/coverage/exclusions.txt"))
    doLast {
        check(coverageExclusions.toSet() == approvedCoverageExclusions) {
            "Coverage exclusions changed without an explicit gate review. " +
                "Expected $approvedCoverageExclusions but found ${coverageExclusions.toSet()}"
        }
    }
}

val verifySecurityDependencyVersions by tasks.registering {
    group = "verification"
    description = "Verifies security-reviewed versions on API and Batch production runtime classpaths."
    val expectedByProject =
        mapOf(
            ":apps:api" to
                mapOf(
                    "org.springframework.boot:spring-boot" to providers.gradleProperty("springBootVersion").get(),
                    "com.fasterxml.jackson.core:jackson-databind" to "2.21.4",
                    "io.netty:netty-common" to providers.gradleProperty("nettyVersion").get(),
                    "org.apache.logging.log4j:log4j-api" to providers.gradleProperty("log4j2Version").get(),
                    "org.apache.tomcat.embed:tomcat-embed-core" to providers.gradleProperty("tomcatVersion").get(),
                    "org.redisson:redisson" to providers.gradleProperty("redissonVersion").get(),
                    "org.springframework:spring-context" to "6.2.19",
                    "org.springframework.security:spring-security-core" to "6.5.11",
                    "org.springdoc:springdoc-openapi-starter-webmvc-ui" to
                        providers.gradleProperty("springDocOpenApiVersion").get(),
                ),
            ":apps:batch" to
                mapOf(
                    "org.springframework.boot:spring-boot" to providers.gradleProperty("springBootVersion").get(),
                    "com.fasterxml.jackson.core:jackson-databind" to "2.21.4",
                    "io.netty:netty-common" to providers.gradleProperty("nettyVersion").get(),
                    "org.apache.logging.log4j:log4j-api" to providers.gradleProperty("log4j2Version").get(),
                    "org.apache.tomcat.embed:tomcat-embed-el" to providers.gradleProperty("tomcatVersion").get(),
                    "org.eclipse.angus:jakarta.mail" to "2.0.5",
                    "org.redisson:redisson" to providers.gradleProperty("redissonVersion").get(),
                    "org.springframework:spring-context" to "6.2.19",
                ),
        )
    doLast {
        expectedByProject.forEach { (projectPath, expectedVersions) ->
            val runtime = project(projectPath).configurations.getByName("productionRuntimeClasspath")
            val resolvedVersions =
                runtime.incoming.resolutionResult.allComponents
                    .mapNotNull { component ->
                        (component.id as? ModuleComponentIdentifier)?.let { identifier ->
                            "${identifier.group}:${identifier.module}" to identifier.version
                        }
                    }.toMap()
            expectedVersions.forEach { (coordinate, expectedVersion) ->
                check(resolvedVersions[coordinate] == expectedVersion) {
                    "$projectPath production runtime requires $coordinate:$expectedVersion, " +
                        "but resolved ${resolvedVersions[coordinate] ?: "nothing"}"
                }
            }
        }
    }
}

val jacocoTestReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generates aggregate unit-test coverage for all production modules."
    dependsOn(unitTest)
    executionData.setFrom(aggregateExecutionData)
    classDirectories.setFrom(classDirectoriesOf(coverageProjects))
    sourceDirectories.setFrom(sourceDirectoriesOf(coverageProjects))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

fun registerCoverageGate(
    name: String,
    description: String,
    projects: Iterable<Project>,
    minimumRatio: String,
    includes: List<String> = emptyList(),
) =
    tasks.register<JacocoCoverageVerification>(name) {
        group = "verification"
        this.description = description
        dependsOn(unitTest)
        executionData.setFrom(aggregateExecutionData)
        classDirectories.setFrom(classDirectoriesOf(projects, includes))
        sourceDirectories.setFrom(sourceDirectoriesOf(projects))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal(minimumRatio)
                }
            }
        }
    }

val jacocoOverallCoverageVerification =
    registerCoverageGate(
        name = "jacocoOverallCoverageVerification",
        description = "Enforces 70% aggregate line coverage.",
        projects = coverageProjects,
        minimumRatio = "0.70",
    )
val jacocoDomainCoverageVerification =
    registerCoverageGate(
        name = "jacocoDomainCoverageVerification",
        description = "Enforces 85% Domain line coverage.",
        projects = domainCoverageProjects,
        minimumRatio = "0.85",
    )
val jacocoApplicationCoverageVerification =
    registerCoverageGate(
        name = "jacocoApplicationCoverageVerification",
        description = "Enforces 80% Application line coverage.",
        projects = applicationCoverageProjects,
        minimumRatio = "0.80",
        includes = listOf("io/premiumspread/application/**"),
    )

val jacocoTestCoverageVerification by tasks.registering {
    group = "verification"
    description = "Enforces overall, Domain, and Application aggregate coverage gates."
    dependsOn(
        jacocoOverallCoverageVerification,
        jacocoDomainCoverageVerification,
        jacocoApplicationCoverageVerification,
    )
}

tasks.named("check") {
    dependsOn(
        architectureTest,
        jacocoTestCoverageVerification,
        verifyTestIsolationPolicy,
        verifyCoverageExclusions,
    )
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every resolvable configuration; run with --write-locks after dependency review."
    notCompatibleWithConfigurationCache("Resolves dependency configurations for lock generation")
    doLast {
        allprojects.forEach { candidate ->
            candidate.configurations
                .filter { it.isCanBeResolved }
                // Kotlin's IDE metadata views request classifier artifacts that are not
                // build/test inputs and are intentionally absent from the offline cache.
                .filterNot { it.name.endsWith("DependenciesMetadata") }
                .filterNot {
                    it.name == "kotlinKlibCommonizerClasspath" ||
                        it.name == "kotlinNativeBundleConfiguration"
                }
                .forEach { configuration ->
                    // Resolve the dependency graph rather than every artifact. This writes
                    // locks without downloading optional classifiers/native bundles.
                    configuration.incoming.resolutionResult.allComponents.size
                }
        }
    }
}

tasks.register("resolveVerificationArtifacts") {
    group = "build setup"
    description = "Materializes external compile/test/integration/runtime artifacts for verification metadata bootstrap."
    val manifestFile = layout.buildDirectory.file("reports/dependency-verification/resolved-artifacts.txt")
    outputs.file(manifestFile)
    outputs.upToDateWhen { false }

    doLast {
        val configurationNames =
            setOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
                "integrationTestCompileClasspath",
                "integrationTestRuntimeClasspath",
                "architectureTestCompileClasspath",
                "architectureTestRuntimeClasspath",
                "productionRuntimeClasspath",
                "kotlinCompilerClasspath",
                "kotlinCompilerPluginClasspath",
                "kotlinCompilerPluginClasspathMain",
                "kotlinBuildToolsApiClasspath",
                "jacocoAgent",
                "jacocoAnt",
            )
        val artifacts =
            (externalArtifactsOf(allprojects, configurationNames) + externalBuildscriptArtifactsOf(allprojects))
                .associateBy { artifact -> artifact.stableCoordinate() }
                .toSortedMap()
        check(artifacts.isNotEmpty()) { "No external verification artifacts were resolved" }
        artifacts.values.forEach { artifact ->
            check(artifact.file.isFile) { "Artifact was not materialized: ${artifact.stableCoordinate()}" }
        }
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.keys.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

tasks.register("materializeProductionBuildArtifacts") {
    group = "build setup"
    description = "Materializes external artifacts required to build the API and Batch production modules."
    val manifestFile = layout.buildDirectory.file("reports/container-runtime/production-build-artifacts.txt")
    outputs.file(manifestFile)
    outputs.upToDateWhen { false }

    doLast {
        val productionProjects =
            listOf(
                project(":apps:api"),
                project(":apps:batch"),
                project(":domain"),
                project(":infrastructure:common"),
                project(":infrastructure:api"),
                project(":infrastructure:batch"),
                project(":modules:jpa"),
                project(":modules:redis"),
                project(":supports:logging"),
                project(":supports:email"),
                project(":supports:monitoring"),
            )
        val configurationNames =
            setOf(
                "compileClasspath",
                "runtimeClasspath",
                "productionRuntimeClasspath",
                "kotlinCompilerClasspath",
                "kotlinCompilerPluginClasspathMain",
                "kotlinBuildToolsApiClasspath",
            )
        val artifacts =
            externalArtifactsOf(productionProjects, configurationNames)
                .associateBy { artifact -> artifact.stableCoordinate() }
                .toSortedMap()
        check(artifacts.isNotEmpty()) { "No external production build artifacts were resolved" }
        artifacts.values.forEach { artifact ->
            check(artifact.file.isFile) { "Production build artifact was not materialized: ${artifact.stableCoordinate()}" }
        }
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.keys.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}
