package io.premiumspread.infrastructure.common.support

import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager

/**
 * adapter 통합 테스트용 최신 schema. 상태(마이그레이션 여부·스키마 지문)를 인스턴스가 가지므로
 * 테스트 클래스 companion 에 하나만 둔다.
 */
class LatestSchemaFixture(private val database: TestDatabase) {
    private var fingerprint: List<String>? = null

    /**
     * 첫 호출: Flyway clean + migrate 후 스키마 지문 저장.
     * 이후 호출: 지문이 같으면 flyway_schema_history 를 뺀 모든 base table 을 TRUNCATE,
     * 다르면 clean + migrate 후 지문 재저장.
     */
    @Synchronized
    fun reset() {
        val saved = fingerprint
        if (saved != null && saved == currentFingerprint()) {
            truncateAll()
            return
        }
        cleanAndMigrate()
        fingerprint = currentFingerprint()
    }

    private fun cleanAndMigrate() {
        val flyway = Flyway.configure()
            .dataSource(database.utcJdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
        flyway.clean()
        flyway.migrate()
    }

    private fun connect(): Connection =
        DriverManager.getConnection(database.utcJdbcUrl, database.username, database.password)

    private fun currentFingerprint(): List<String> =
        connect().use { connection ->
            FINGERPRINT_QUERIES.flatMap { (prefix, sql) -> rows(connection, prefix, sql) }.sorted()
        }

    private fun rows(connection: Connection, prefix: String, sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                val columns = rs.metaData.columnCount
                buildList {
                    while (rs.next()) {
                        add((1..columns).joinToString(separator = "|", prefix = "$prefix:") { (rs.getString(it) as String?).toString() })
                    }
                }
            }
        }

    private fun truncateAll() {
        connect().use { connection ->
            val tables = rows(connection, "T", TRUNCATE_TARGETS_SQL).map { it.removePrefix("T:") }
            connection.createStatement().use { statement ->
                statement.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    tables.forEach { statement.execute("TRUNCATE TABLE `$it`") }
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }
    }

    private companion object {
        const val TRUNCATE_TARGETS_SQL =
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
                "AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'"

        private const val IS = "information_schema"

        val FINGERPRINT_QUERIES: List<Pair<String, String>> = listOf(
            "TABLES" to "SELECT TABLE_NAME, TABLE_TYPE FROM $IS.TABLES WHERE TABLE_SCHEMA = DATABASE()",
            "COLUMNS" to "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, " +
                "COLUMN_DEFAULT, EXTRA FROM $IS.COLUMNS WHERE TABLE_SCHEMA = DATABASE()",
            "STATISTICS" to "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE " +
                "FROM $IS.STATISTICS WHERE TABLE_SCHEMA = DATABASE()",
            "TABLE_CONSTRAINTS" to "SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE " +
                "FROM $IS.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE()",
            "CHECK_CONSTRAINTS" to "SELECT CONSTRAINT_NAME, CHECK_CLAUSE " +
                "FROM $IS.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()",
            "REFERENTIAL_CONSTRAINTS" to "SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, " +
                "UPDATE_RULE, DELETE_RULE FROM $IS.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()",
            "TRIGGERS" to "SELECT TRIGGER_NAME FROM $IS.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()",
            "VIEWS" to "SELECT TABLE_NAME FROM $IS.VIEWS WHERE TABLE_SCHEMA = DATABASE()",
            "HISTORY" to "SELECT installed_rank, version, checksum, success FROM flyway_schema_history",
        )
    }
}
