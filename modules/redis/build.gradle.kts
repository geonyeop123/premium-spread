plugins {
    `java-test-fixtures`
}

dependencies {
    // Redis
    api("org.springframework.boot:spring-boot-starter-data-redis")

    // Redisson (분산 락)
    api("org.redisson:redisson-spring-boot-starter:${project.properties["redissonVersion"]}")

    // Test
    testImplementation("org.testcontainers:testcontainers")

    // Test Fixtures (다른 모듈에서 사용 가능)
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.testcontainers:testcontainers")
}
