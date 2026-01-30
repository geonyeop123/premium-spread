dependencies {
    // Logstash encoder for structured logging
    api("net.logstash.logback:logstash-logback-encoder:${project.properties["logstashLogbackEncoderVersion"]}")

    // Spring Web (for RequestLoggingInterceptor)
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Spring Boot autoconfigure
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
}
