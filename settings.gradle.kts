rootProject.name = "premium-spread"

include(
    // apps
    ":apps:api",
    ":apps:batch",
    // domain
    ":domain",
    // infrastructure
    ":infrastructure:common",
    ":infrastructure:api",
    ":infrastructure:batch",
    // architecture verification
    ":architecture-tests",
    // modules
    ":modules:jpa",
    ":modules:redis",
    // supports
    ":supports:logging",
    ":supports:email",
    ":supports:monitoring",
)

// configurations
pluginManagement {
    includeBuild("build-logic")

    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings

    repositories {
        maven { url = uri("https://repo.spring.io/milestone") }
        maven { url = uri("https://repo.spring.io/snapshot") }
        gradlePluginPortal()
        mavenCentral()
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.springframework.boot" -> useVersion(springBootVersion)
                "io.spring.dependency-management" -> useVersion(springDependencyManagementVersion)
            }
        }
    }
}
