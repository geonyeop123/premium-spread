package io.premiumspread.infrastructure.common.migration

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@ConfigurationProperties("migration.v12")
data class V12MigrationSafetyProperties(
    val allowEmptyPositionMigration: Boolean = false,
)

class V12MigrationSafetyPolicy {
    fun validate(version: String?, positionRows: Long, approvedForEmpty: Boolean) {
        if (version != V12_VERSION) return

        check(positionRows == 0L) {
            "V12 migration blocked: PENDING_WITH_DATA requires backup/backfill/cutover runbook"
        }
        check(approvedForEmpty) {
            "V12 migration blocked: PENDING_EMPTY requires one-time migration.v12.allow-empty-position-migration approval"
        }
    }

    private companion object {
        const val V12_VERSION = "12"
    }
}

class V12MigrationSafetyCallback(
    private val properties: V12MigrationSafetyProperties,
    private val policy: V12MigrationSafetyPolicy = V12MigrationSafetyPolicy(),
) : Callback {
    override fun supports(event: Event, context: Context?): Boolean =
        event == Event.BEFORE_EACH_MIGRATE

    override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true

    override fun handle(event: Event, context: Context) {
        val version = context.migrationInfo?.version?.version
        if (version != "12") return

        val positionRows = context.connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM `position`").use { result ->
                check(result.next()) { "V12 migration preflight did not return a position row count" }
                result.getLong(1)
            }
        }
        policy.validate(
            version = version,
            positionRows = positionRows,
            approvedForEmpty = properties.allowEmptyPositionMigration,
        )
    }

    override fun getCallbackName(): String = "v12-migration-safety"
}

@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnProperty(prefix = "spring.flyway", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(V12MigrationSafetyProperties::class)
class V12MigrationSafetyAutoConfiguration {
    @Bean
    fun v12MigrationSafetyCallback(properties: V12MigrationSafetyProperties) =
        V12MigrationSafetyCallback(properties)

    @Bean
    fun v12MigrationSafetyFlywayCustomizer(callback: V12MigrationSafetyCallback) =
        FlywayConfigurationCustomizer { configuration -> configuration.callbacks(callback) }
}
