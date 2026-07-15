plugins {
    id("premiumspread.spring-library")
}

dependencies {
    // Logstash encoder for structured logging
    api("net.logstash.logback:logstash-logback-encoder:${project.properties["logstashLogbackEncoderVersion"]}")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.slf4j:slf4j-api")

    // Spring Web (for RequestLoggingInterceptor)
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Spring Boot autoconfigure
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
