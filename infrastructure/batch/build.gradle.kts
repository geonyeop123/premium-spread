plugins {
    id("premiumspread.spring-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure:common"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
