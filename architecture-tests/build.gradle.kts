import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("premiumspread.kotlin-library")
}

dependencies {
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

val dependencyGraphSnapshot = layout.buildDirectory.file("architecture/project-dependency-graph.txt")
val writeProjectDependencyGraph =
    tasks.register("writeProjectDependencyGraph") {
        description = "Writes the stable production project-dependency graph used by architecture tests"
        outputs.file(dependencyGraphSnapshot)
        outputs.upToDateWhen { false }

        doLast {
            val productionConfigurations =
                listOf("api", "compileOnly", "compileOnlyApi", "implementation", "runtimeOnly")
            val graphEdges =
                rootProject.subprojects
                    .flatMap { candidate ->
                        productionConfigurations.flatMap { configurationName ->
                            candidate.configurations
                                .findByName(configurationName)
                                ?.dependencies
                                ?.withType(ProjectDependency::class.java)
                                ?.map { dependency ->
                                    "${candidate.path} [$configurationName] -> ${dependency.path}"
                                }.orEmpty()
                        }
                    }.sorted()
            val domainExternalEdges =
                productionConfigurations
                    .flatMap { configurationName ->
                        project(":domain")
                            .configurations
                            .findByName(configurationName)
                            ?.dependencies
                            ?.withType(ExternalModuleDependency::class.java)
                            ?.filterNot { dependency ->
                                dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
                            }
                            ?.map { dependency ->
                                val module = "${checkNotNull(dependency.group)}:${dependency.name}"
                                val coordinate = dependency.version?.let { version -> "$module:$version" } ?: module
                                ":domain [$configurationName] => $coordinate"
                            }.orEmpty()
                    }.sorted()
            val graph =
                buildList {
                    val apiRuntimeProjects = listOf(project(":apps:api"), project(":infrastructure:api"))
                    val apiCoordinates = apiRuntimeProjects.associate { candidate ->
                        candidate.path to "${candidate.group}:${candidate.name}"
                    }
                    require(apiCoordinates.values.toSet().size == apiCoordinates.size) {
                        "API runtime project component coordinates must be unique: $apiCoordinates"
                    }
                    add("# inspected API runtime project component coordinates")
                    apiCoordinates.toSortedMap().forEach { (path, coordinate) -> add("$path => $coordinate") }
                    add(
                        "# inspected project dependency configurations: " +
                            productionConfigurations.joinToString(),
                    )
                    addAll(graphEdges)
                    add(
                        "# inspected :domain direct external dependencies (excluding Kotlin plugin stdlib): " +
                            productionConfigurations.joinToString(),
                    )
                    addAll(domainExternalEdges)
                }.joinToString(separator = "\n", postfix = "\n")

            dependencyGraphSnapshot.get().asFile.apply {
                parentFile.mkdirs()
                writeText(graph)
            }
        }
    }

// api/batch are repeated leaf names under apps and infrastructure, so placing all six project
// artifacts on one test runtime classpath would trigger capability conflict substitution. Resolve
// each target in an isolated, non-transitive configuration and let ArchUnit import that exact jar.
val architectureTargetProjects =
    mapOf(
        "domain" to (":domain" to "runtimeElements"),
        "infrastructure.common" to (":infrastructure:common" to "runtimeElements"),
        "infrastructure.api" to (":infrastructure:api" to "runtimeElements"),
        "infrastructure.batch" to (":infrastructure:batch" to "runtimeElements"),
        "apps.api" to (":apps:api" to "architectureTestElements"),
        "apps.batch" to (":apps:batch" to "architectureTestElements"),
    )

val architectureTargets =
    architectureTargetProjects.mapValues { (propertySuffix, target) ->
        val (projectPath, targetConfiguration) = target
        val mainOutput = configurations.create("${propertySuffix.replace('.', '-')}MainOutput") {
            description = "Main output of $projectPath used only by architecture verification"
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val targetDependency = project.dependencies.project(projectPath, targetConfiguration)
        targetDependency.isTransitive = false
        project.dependencies.add(mainOutput.name, targetDependency)
        mainOutput
    }

val architectureSourceRoots =
    mapOf(
        "domain" to project(":domain").layout.projectDirectory.dir("src/main/kotlin"),
        "apps.api" to project(":apps:api").layout.projectDirectory.dir("src/main/kotlin"),
        "apps.batch" to project(":apps:batch").layout.projectDirectory.dir("src/main/kotlin"),
    )

tasks.test {
    dependsOn(writeProjectDependencyGraph)
    dependsOn(architectureTargets.values)
    systemProperty("architecture.dependency-graph", dependencyGraphSnapshot.get().asFile.absolutePath)
    architectureSourceRoots.forEach { (propertySuffix, sourceRoot) ->
        inputs.dir(sourceRoot)
        systemProperty("architecture.source.$propertySuffix", sourceRoot.asFile.absolutePath)
    }

    doFirst {
        architectureTargets.forEach { (propertySuffix, targetConfiguration) ->
            val artifacts = targetConfiguration.files
            require(artifacts.size == 1) {
                "Expected exactly one main artifact for $propertySuffix, but found: ${artifacts.sorted()}"
            }
            systemProperty("architecture.target.$propertySuffix", artifacts.single().absolutePath)
        }
    }
}
