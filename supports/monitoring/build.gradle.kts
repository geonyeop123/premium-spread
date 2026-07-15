plugins {
    id("premiumspread.spring-library")
}

dependencies {
    // Actuator
    api("org.springframework.boot:spring-boot-starter-actuator")

    // Prometheus metrics
    api("io.micrometer:micrometer-registry-prometheus")

    // RestTemplate, ObjectMapper (Slack Webhook 호출용)
    // 런타임에는 앱의 web starter가 제공하므로 compileOnly
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework.data:spring-data-redis")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")

    // Test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework.data:spring-data-redis")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
