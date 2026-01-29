plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
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
