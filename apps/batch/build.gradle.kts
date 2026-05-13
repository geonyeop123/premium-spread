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
    // modules
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))

    // supports
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // WebFlux (External API 호출용)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Coroutines (비동기 처리)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.properties["kotlinCoroutinesVersion"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${project.properties["kotlinCoroutinesVersion"]}")

    // Test
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.properties["kotlinCoroutinesVersion"]}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.0")

    // test-fixtures (TestContainers, DatabaseCleanUp)
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
