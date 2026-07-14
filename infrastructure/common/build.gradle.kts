plugins {
    id("premiumspread.spring-library")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs common persistence/cache integration tests (requires Docker)"
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("io.micrometer:micrometer-core")

    runtimeOnly("com.mysql:mysql-connector-j")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

val verifyMigrations by tasks.registering {
    group = "verification"
    description = "Verifies Flyway version uniqueness and rejects destructive SQL outside the immutable V12 exception."

    val migrationDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
    inputs.dir(migrationDirectory)

    doLast {
        val migrations = fileTree(migrationDirectory).matching { include("V*__*.sql") }.files.sortedBy { it.name }
        val versionPattern = Regex("^V([0-9]+(?:\\.[0-9]+)*)__.+\\.sql$")
        val versions = migrations.groupBy { migration ->
            versionPattern.matchEntire(migration.name)?.groupValues?.get(1)
                ?: error("Invalid Flyway migration name: ${migration.name}")
        }
        val duplicateVersions = versions.filterValues { it.size > 1 }
        check(duplicateVersions.isEmpty()) {
            "Duplicate Flyway migration versions: " +
                duplicateVersions.entries.joinToString { (version, files) ->
                    "$version=[${files.joinToString { it.name }}]"
                }
        }

        val destructiveAllowlist = setOf("V12__restructure_position_to_pair.sql")
        migrations.filterNot { it.name in destructiveAllowlist }.forEach { migration ->
            val statements = migration.readText()
                .lineSequence()
                .map { it.substringBefore("--") }
                .joinToString("\n")
                .split(';')
                .map { it.trim().replace(Regex("\\s+"), " ").uppercase() }
                .filter(String::isNotBlank)
            statements.forEach { statement ->
                check(!statement.startsWith("TRUNCATE TABLE ")) {
                    "Destructive TRUNCATE is forbidden in ${migration.name}"
                }
                check(!statement.startsWith("DROP TABLE ")) {
                    "Destructive DROP TABLE is forbidden in ${migration.name}"
                }
                check(!(statement.startsWith("DELETE FROM ") && " WHERE " !in statement)) {
                    "DELETE without WHERE is forbidden in ${migration.name}"
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyMigrations)
}
