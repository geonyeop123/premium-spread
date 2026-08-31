import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.ClasspathNormalizer

plugins {
    id("premiumspread.kotlin-library")
}

val architectureTestSourceSet = sourceSets.create("architectureTest") {
    kotlin.srcDir("src/architectureTest/kotlin")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[architectureTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(architectureTestSourceSet.implementationConfigurationName, "com.tngtech.archunit:archunit-junit5:1.3.0")
    add(architectureTestSourceSet.implementationConfigurationName, "org.slf4j:slf4j-api:2.0.16")
    add(architectureTestSourceSet.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.11.4")
    add(architectureTestSourceSet.implementationConfigurationName, kotlin("test-junit5"))
    add(architectureTestSourceSet.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher:1.11.4")
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
            val batchExternalEdges =
                productionConfigurations
                    .flatMap { configurationName ->
                        project(":apps:batch")
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
                                ":apps:batch [$configurationName] => $coordinate"
                            }.orEmpty()
                    }.sorted()
            val graph =
                buildList {
                    val runtimeProjects =
                        listOf(
                            project(":apps:api"),
                            project(":apps:batch"),
                            project(":infrastructure:api"),
                            project(":infrastructure:batch"),
                        )
                    val runtimeCoordinates = runtimeProjects.associate { candidate ->
                        candidate.path to "${candidate.group}:${candidate.name}"
                    }
                    require(runtimeCoordinates.values.toSet().size == runtimeCoordinates.size) {
                        "Application/infrastructure runtime project component coordinates must be unique: $runtimeCoordinates"
                    }
                    add("# inspected application/infrastructure runtime project component coordinates")
                    runtimeCoordinates.toSortedMap().forEach { (path, coordinate) -> add("$path => $coordinate") }
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
                    add(
                        "# inspected :apps:batch direct external dependencies (excluding Kotlin plugin stdlib): " +
                            productionConfigurations.joinToString(),
                    )
                    addAll(batchExternalEdges)
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

/** source 를 직접 훑는 architecture test 가 실제로 보는 범위 — 모든 subproject 의 production Kotlin. */
val scannedProductionSources =
    rootProject.subprojects.map { candidate ->
        candidate.layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    }

val architectureTest by tasks.registering(Test::class) {
    dependsOn(writeProjectDependencyGraph)
    dependsOn(architectureTargets.values)
    testClassesDirs = architectureTestSourceSet.output.classesDirs
    classpath = architectureTestSourceSet.runtimeClasspath
    systemProperty("architecture.dependency-graph", dependencyGraphSnapshot.get().asFile.absolutePath)
    architectureSourceRoots.forEach { (propertySuffix, sourceRoot) ->
        inputs.dir(sourceRoot)
        systemProperty("architecture.source.$propertySuffix", sourceRoot.asFile.absolutePath)
    }

    // ArchUnit 이 실제로 뜯어보는 것은 아래 여섯 jar 과 의존성 그래프 스냅샷이다. dependsOn 은 실행
    // 순서만 정하고, 경로를 system property 로 넘기는 것은 값 하나일 뿐이라 어느 쪽도 up-to-date
    // 판정의 입력이 아니다. 선언하지 않으면 infrastructure main 을 고쳐 jar 이 새로 빌드돼도 이
    // 게이트가 UP-TO-DATE 로 건너뛴다 — 검사하는 대상이 바뀌었는데 검사가 돌지 않는다.
    inputs.files(architectureTargets.values)
        .withPropertyName("architectureTargets")
        .withNormalizer(ClasspathNormalizer::class.java)
    inputs.file(dependencyGraphSnapshot).withPropertyName("dependencyGraphSnapshot")

    // 위 세 source root 는 system property 로 넘기는 대상일 뿐이다. Observability/TestIsolation/
    // VerifiedBalanceNoCache 는 그 셋이 아니라 **저장소 전체**의 src/main/kotlin 을 직접 훑는다.
    // 훑는 곳을 전부 입력으로 선언하지 않으면 선언 밖 모듈에 위반을 넣어도 게이트가 UP-TO-DATE 로
    // 건너뛴다. modules:jpa 가 실제 구멍이었다 — :domain 을 의존해 domain port 구현이 컴파일되는데
    // source root 로도, architectureTargets jar 로도 선언돼 있지 않았다.
    inputs.files(scannedProductionSources)
        .withPropertyName("scannedProductionSources")

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

tasks.test {
    description = "Disabled empty compatibility task; architecture sources live in src/architectureTest."
    enabled = false
}

tasks.named("check") {
    dependsOn(architectureTest)
}
