dependencies {
    // modules
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
}
