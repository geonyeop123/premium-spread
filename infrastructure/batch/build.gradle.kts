plugins {
    id("premiumspread.spring-library")
}

group = "${rootProject.group}.infrastructure"

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure:common"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:email"))
    implementation(project(":supports:monitoring"))

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework:spring-tx")
    implementation("io.micrometer:micrometer-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.properties["kotlinCoroutinesVersion"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${project.properties["kotlinCoroutinesVersion"]}")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
    testImplementation("org.mockito.kotlin:mockito-kotlin:${project.properties["mockitoKotlinVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.properties["kotlinCoroutinesVersion"]}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.0")
    testImplementation(testFixtures(project(":modules:redis")))
}
