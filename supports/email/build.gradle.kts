plugins {
    id("premiumspread.spring-library")
}

dependencies {
    // Spring Mail
    api("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Boot autoconfigure (compileOnly: 런타임에는 앱이 제공)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
}
