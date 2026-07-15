plugins {
    id("premiumspread.jpa-library")
    `java-test-fixtures`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(project(":domain"))

    // jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // jdbc-mysql
    runtimeOnly("com.mysql:mysql-connector-j")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:mysql")

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.testcontainers:mysql")
}
