import java.util.Properties

plugins {
    `java-gradle-plugin`
}

val rootProperties =
    Properties().apply {
        rootDir.resolve("../gradle.properties").inputStream().use(::load)
    }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${rootProperties.getProperty("kotlinVersion")}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${rootProperties.getProperty("kotlinVersion")}")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${rootProperties.getProperty("springBootVersion")}")
    implementation(
        "io.spring.gradle:dependency-management-plugin:${rootProperties.getProperty("springDependencyManagementVersion")}",
    )
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${rootProperties.getProperty("ktLintPluginVersion")}")
}

gradlePlugin {
    plugins {
        create("kotlinLibrary") {
            id = "premiumspread.kotlin-library"
            implementationClass = "io.premiumspread.buildlogic.KotlinLibraryConventionPlugin"
        }
        create("springLibrary") {
            id = "premiumspread.spring-library"
            implementationClass = "io.premiumspread.buildlogic.SpringLibraryConventionPlugin"
        }
        create("springBootApplication") {
            id = "premiumspread.spring-boot-application"
            implementationClass = "io.premiumspread.buildlogic.SpringBootApplicationConventionPlugin"
        }
    }
}
