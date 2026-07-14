plugins {
    id("premiumspread.spring-boot-application")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (requires Docker)"
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
}

dependencies {
    implementation(project(":domain"))
    runtimeOnly(project(":infrastructure:common"))
    runtimeOnly(project(":infrastructure:batch"))

    // modules
    // TODO(Phase 6): Remove after Batch JDBC/JPA adapters move behind ports in infrastructure:common.
    implementation(project(":modules:jpa"))
    // TODO(Phase 6): Remove after Batch cache/lock adapters move behind ports in infrastructure modules.
    implementation(project(":modules:redis"))

    // supports
    runtimeOnly(project(":supports:logging"))
    // TODO(Phase 7): Remove after durable notification delivery owns the SMTP adapter outside the Batch app.
    implementation(project(":supports:email"))
    // TODO(Phase 6): Remove after OperatorAlert and metric adapters move to infrastructure:batch.
    implementation(project(":supports:monitoring"))

    // Kotlin / serialization
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // WebFlux (External API 호출용)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Coroutines (비동기 처리)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.properties["kotlinCoroutinesVersion"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${project.properties["kotlinCoroutinesVersion"]}")

    // Test
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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.properties["kotlinCoroutinesVersion"]}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.0")

    // test-fixtures (TestContainers, DatabaseCleanUp)
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
