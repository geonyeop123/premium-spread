dependencies {
    // Spring Mail
    api("org.springframework.boot:spring-boot-starter-mail")

    // Spring Boot autoconfigure (compileOnly: 런타임에는 앱이 제공)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
}
