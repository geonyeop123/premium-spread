import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import java.util.Properties
import java.time.Duration

plugins {
    `java-gradle-plugin`
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val rootProperties =
    Properties().apply {
        rootDir.resolve("../gradle.properties").inputStream().use(::load)
    }

dependencies {
    compileOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.junit.platform:junit-platform-launcher:1.11.4")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${rootProperties.getProperty("kotlinVersion")}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${rootProperties.getProperty("kotlinVersion")}")
    implementation("org.jetbrains.kotlin:kotlin-noarg:${rootProperties.getProperty("kotlinVersion")}")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${rootProperties.getProperty("springBootVersion")}")
    implementation(
        "io.spring.gradle:dependency-management-plugin:${rootProperties.getProperty("springDependencyManagementVersion")}",
    )
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(15))
    systemProperty("premiumspread.test.leak-detection", "true")
    systemProperty("testcontainers.reuse.enable", "false")
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
    systemProperty("junit.jupiter.execution.timeout.lifecycle.method.default", "2m")
}

tasks.register("resolveVerificationArtifacts") {
    group = "build setup"
    description = "Materializes build-logic compile and test artifacts for verification metadata bootstrap."
    val manifestFile = layout.buildDirectory.file("reports/dependency-verification/resolved-artifacts.txt")
    outputs.file(manifestFile)
    outputs.upToDateWhen { false }

    doLast {
        val configurationNames =
            setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        val artifacts =
            configurations
                .filter { configuration ->
                    configuration.isCanBeResolved && configuration.name in configurationNames
                }.flatMap { configuration ->
                    configuration.incoming.artifactView {
                        componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                    }.artifacts.artifacts
                }.associateBy { artifact -> artifact.stableCoordinate() }
                .toSortedMap()
        check(artifacts.isNotEmpty()) { "No build-logic verification artifacts were resolved" }
        artifacts.values.forEach { artifact ->
            check(artifact.file.isFile) { "Artifact was not materialized: ${artifact.stableCoordinate()}" }
        }
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.keys.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

fun ResolvedArtifactResult.stableCoordinate(): String {
    val component = id.componentIdentifier as ModuleComponentIdentifier
    return "${component.group}:${component.module}:${component.version}:${file.name}"
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves build-logic dependency graphs for lock generation."
    notCompatibleWithConfigurationCache("Resolves dependency configurations for lock generation")
    doLast {
        configurations
            .filter { it.isCanBeResolved }
            .forEach { configuration -> configuration.incoming.resolutionResult.allComponents.size }
    }
}

gradlePlugin {
    plugins {
        create("kotlinLibrary") {
            id = "premiumspread.kotlin-library"
            implementationClass = "io.premiumspread.buildlogic.KotlinLibraryConventionPlugin"
        }
        create("springLibrary") {
            id = "premiumspread.spring-library"
            implementationClass = "io.premiumspread.buildlogic.SpringLibraryConventionPlugin"
        }
        create("jpaLibrary") {
            id = "premiumspread.jpa-library"
            implementationClass = "io.premiumspread.buildlogic.JpaLibraryConventionPlugin"
        }
        create("springBootApplication") {
            id = "premiumspread.spring-boot-application"
            implementationClass = "io.premiumspread.buildlogic.SpringBootApplicationConventionPlugin"
        }
    }
}
