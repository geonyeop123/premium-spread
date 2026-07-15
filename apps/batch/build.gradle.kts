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
        excludeTags("integration")
    }
}

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
    runtimeOnly(project(":infrastructure:batch"))

    // supports
    runtimeOnly(project(":supports:logging"))
    runtimeOnly(project(":supports:monitoring"))

    // Application runtime foundation and scheduling annotations
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework:spring-tx")

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
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.0")

    // Runtime adapter contracts referenced only by application integration tests.
    testImplementation(project(":infrastructure:common"))
    testImplementation(project(":infrastructure:batch"))
    testImplementation(project(":modules:redis"))
    testImplementation(project(":supports:email"))
    testImplementation(project(":supports:monitoring"))
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.properties["kotlinCoroutinesVersion"]}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // test-fixtures (TestContainers, DatabaseCleanUp)
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
