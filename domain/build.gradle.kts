plugins {
    id("premiumspread.spring-library")
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    api("jakarta.persistence:jakarta.persistence-api")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.data:spring-data-commons")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
}
