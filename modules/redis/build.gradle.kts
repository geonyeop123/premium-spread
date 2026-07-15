plugins {
    id("premiumspread.spring-library")
    `java-test-fixtures`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Redis
    api("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Redisson (분산 락)
    api("org.redisson:redisson:${project.properties["redissonVersion"]}")

    // Test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
    testImplementation("org.mockito.kotlin:mockito-kotlin:${project.properties["mockitoKotlinVersion"]}")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")

    // Test Fixtures (다른 모듈에서 사용 가능)
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.testcontainers:testcontainers")
}
