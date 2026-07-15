plugins {
    id("premiumspread.spring-boot-application")
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

kotlin {
    target.compilations.named("integrationTest") {
        associateWith(target.compilations.getByName("main"))
    }
}

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.named<Test>("test") {
    useJUnitPlatform {
        // 기본 테스트 실행 시 integration 태그 제외
        excludeTags("integration")
    }
}

// 통합 테스트 태스크 별도 정의
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (requires Docker)"
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

dependencies {
    implementation(project(":domain"))
    runtimeOnly(project(":infrastructure:common"))
    runtimeOnly(project(":infrastructure:api"))

    // supports
    runtimeOnly(project(":supports:logging"))
    runtimeOnly(project(":supports:monitoring"))

    // Kotlin / Spring
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework:spring-tx")

    // serialization
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // security-test
    testImplementation("org.springframework.security:spring-security-test")

    // test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
    testImplementation("org.mockito:mockito-core:${project.properties["mockitoVersion"]}")
    testImplementation("org.mockito.kotlin:mockito-kotlin:${project.properties["mockitoKotlinVersion"]}")
    testImplementation("org.instancio:instancio-junit:${project.properties["instancioJUnitVersion"]}")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation(project(":infrastructure:common"))
    testImplementation(project(":modules:jpa"))
    testImplementation(project(":modules:redis"))

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
