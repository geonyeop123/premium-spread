package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * `V17` — 조건 평가·reconcile 조회를 받쳐 줄 인덱스가 두 경로(빈 DB→latest, `V16`→`V17`)에서
 * 모두 붙는지 본다.
 *
 * 인덱스는 정확성이 아니라 실행 계획을 바꾸므로 단위 테스트가 잡을 성질이 아니다. 대신
 * **존재와 컬럼 순서**를 고정한다 — 순서가 바뀌면 `status` 1컬럼 prefix 로 타던 reconcile 조회가
 * 조용히 인덱스를 잃는다.
 */
@Tag("integration")
@Testcontainers
class V17TradePreparationIndexMigrationIntegrationTest {

    @Test
    fun `빈 DB는 V17까지 순서대로 migration 되고 평가 인덱스가 붙는다`() {
        val flyway = flyway()
        flyway.clean()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(appliedVersions()).containsAll((1..17).map(Int::toString))
        assertEvaluationIndex()
    }

    @Test
    fun `V16 스키마에 V17을 적용하면 기존 인덱스를 유지한 채 평가 인덱스가 추가된다`() {
        val v16Flyway = flyway(target = MigrationVersion.fromVersion("16"))
        v16Flyway.clean()
        v16Flyway.migrate()
        assertThat(indexColumns(EVALUATION_INDEX)).isEmpty()

        assertThat(flyway().migrate().success).isTrue()

        assertEvaluationIndex()
        // V16이 만든 인덱스는 그대로다 — V17은 append-only 로 추가만 한다.
        assertThat(indexColumns("idx_trade_preparation_owner_id")).containsExactly("owner_id")
        assertThat(indexColumns("uk_trade_preparation_owner_active")).containsExactly("active_key")
    }

    /**
     * 컬럼 **순서**까지 단언한다. `getIndexInfo` 는 `ORDINAL_POSITION` 순으로 돌려주므로
     * `containsExactly` 가 곧 인덱스의 컬럼 순서다.
     */
    private fun assertEvaluationIndex() {
        assertThat(indexColumns(EVALUATION_INDEX))
            .containsExactly("status", "symbol", "korea_exchange", "foreign_exchange")
    }

    private fun indexColumns(indexName: String): List<String> =
        connection().use { connection ->
            connection.metaData.getIndexInfo(mysql.databaseName, null, "trade_preparation", false, false).use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("INDEX_NAME") == indexName) add(result.getString("COLUMN_NAME"))
                    }
                }
            }
        }

    private fun flyway(target: MigrationVersion? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun appliedVersions(): List<String> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                ).use { result ->
                    buildList {
                        while (result.next()) add(result.getString("version"))
                    }
                }
            }
        }

    private fun connection() = DriverManager.getConnection(jdbcUrl, mysql.username, mysql.password)

    private val jdbcUrl: String
        get() = mysql.jdbcUrl + if (mysql.jdbcUrl.contains('?')) "&sslMode=DISABLED" else "?sslMode=DISABLED"

    companion object {
        private const val EVALUATION_INDEX = "idx_trade_preparation_status_pair"

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("premiumspread_trade_preparation_index_migration")
            .withUsername("test")
            .withPassword("test")
    }
}
