plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

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
    shouldRunAfter(tasks.named("test"))
}

dependencies {
    // modules
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))

    // supports
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
