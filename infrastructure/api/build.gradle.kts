plugins {
    id("premiumspread.spring-library")
}

// apps/api와 동일한 project.name("api")을 사용하므로 런타임 의존성 충돌을 피한다.
group = "${rootProject.group}.infrastructure"

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure:common"))
    implementation(project(":modules:redis"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation(testFixtures(project(":modules:redis")))
}
