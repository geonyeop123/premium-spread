package io.premiumspread.infrastructure.common.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

class MigrationResourcePolicyTest {

    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `migration version은 중복되지 않고 V14 durable notification migration을 포함한다`() {
        val migrations = migrations()
        val versions = migrations.map { migration -> migration.version }

        assertThat(versions).doesNotHaveDuplicates()
        assertThat(versions).contains(13, 14)
    }

    @Test
    fun `이미 적용된 V1부터 V13 migration은 checksum을 변경하지 않는다`() {
        val actual = migrations()
            .filter { migration -> migration.version <= 13 }
            .associate { migration -> migration.fileName to sha256(migration.content) }

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(IMMUTABLE_MIGRATION_SHA256)
    }

    @Test
    fun `destructive SQL은 immutable V12만 예외로 허용한다`() {
        val destructiveMigrations = migrations()
            .filter { migration -> containsDestructiveSql(migration.content) }
            .map { migration -> migration.fileName }

        assertThat(destructiveMigrations).containsExactly(V12_FILE_NAME)
        assertThat(sha256(migrations().single { it.fileName == V12_FILE_NAME }.content))
            .isEqualTo(IMMUTABLE_MIGRATION_SHA256.getValue(V12_FILE_NAME))
    }

    @Test
    fun `destructive SQL gate는 truncate table drop no-WHERE delete를 검출한다`() {
        assertThat(containsDestructiveSql("TRUNCATE TABLE premium_snapshot;")).isTrue()
        assertThat(containsDestructiveSql("DROP TABLE premium_snapshot;")).isTrue()
        assertThat(containsDestructiveSql("DELETE FROM premium_snapshot;")).isTrue()
        assertThat(containsDestructiveSql("DELETE FROM premium_snapshot WHERE id = 1;")).isFalse()
    }

    private fun migrations(): List<MigrationResource> =
        resolver.getResources("classpath*:db/migration/V*__*.sql")
            .map { resource ->
                val fileName = requireNotNull(resource.filename)
                val version = VERSION_PATTERN.matchEntire(fileName)?.groupValues?.get(1)?.toInt()
                    ?: error("Invalid Flyway migration name: $fileName")
                MigrationResource(
                    version = version,
                    fileName = fileName,
                    content = resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
            }
            .sortedWith(compareBy(MigrationResource::version, MigrationResource::fileName))

    private fun containsDestructiveSql(sql: String): Boolean {
        val statements = sql
            .lineSequence()
            .map { line -> line.substringBefore("--") }
            .joinToString("\n")
            .split(';')
            .map { statement -> statement.trim() }
            .filter { statement -> statement.isNotEmpty() }

        return statements.any { statement ->
            TRUNCATE_PATTERN.containsMatchIn(statement) ||
                DROP_TABLE_PATTERN.containsMatchIn(statement) ||
                (DELETE_PATTERN.containsMatchIn(statement) && !WHERE_PATTERN.containsMatchIn(statement))
        }
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class MigrationResource(
        val version: Int,
        val fileName: String,
        val content: String,
    )

    companion object {
        private const val V12_FILE_NAME = "V12__restructure_position_to_pair.sql"
        private val VERSION_PATTERN = Regex("V(\\d+)__.+\\.sql")
        private val TRUNCATE_PATTERN = Regex("(?i)^TRUNCATE\\s+(TABLE\\s+)?")
        private val DROP_TABLE_PATTERN = Regex("(?i)^DROP\\s+TABLE\\s+")
        private val DELETE_PATTERN = Regex("(?i)^DELETE\\s+FROM\\s+")
        private val WHERE_PATTERN = Regex("(?i)\\bWHERE\\b")

        private val IMMUTABLE_MIGRATION_SHA256 = mapOf(
            "V1__create_ticker_table.sql" to "d8032925de9596052f388c92733df9c7bd46363d50fbedef0f85ea56440e49ad",
            "V2__create_premium_table.sql" to "c2660c30b4e347a259164803f3a8718877b851acdcf4345f2285a10ba22d00cb",
            "V3__create_position_table.sql" to "a237dfe5cddd0547ffd1b7046234b5817e88e18de5ecec02000d208f7709dc90",
            "V4__create_premium_snapshot_table.sql" to "02319fb05e4d84f7a579496f2d750df7e5f692a5a0ebe9b129b7491eea053439",
            "V5__create_premium_aggregation_tables.sql" to "bd085a2010545e92ee42f63748aa187b31fbac8526e6d350e666e3f2d4c21039",
            "V6__create_ticker_and_exchange_rate_tables.sql" to "b8c01e51eef84f6e603cc7ccfadacbfc9eb4bf352337fb5704583ac106010720",
            "V7__create_member_table.sql" to "edd255cec7263b982cb50aeafcee5ff0b0b564119169a7c5a8290ac51a5ae146",
            "V8__add_member_id_to_position.sql" to "2b8a125736ae414832f66ff4bb9207376f71c02884ddf5739de7fc2def2f2553",
            "V9__add_indexes_and_currency_column.sql" to "dfb51474112f6e8c55fe68c7338e54ee034e4fc8afb3d968bf19cca6e65d73d3",
            "V10__add_fx_rate_to_premium_aggregation_tables.sql" to "3ddb42a5a20574ca3d0c82539be7521f3b049dd7c71c959db5fe436a12db54fd",
            "V11__create_notification_subscription.sql" to "a91a508bdabf94c5689ba4b47b28aa00f8c34cf6da3f51bfe2ea35c184ce05ca",
            V12_FILE_NAME to "197a5b1b082bdfb5855cd32dfdf3eaefb88e23e8b0aeb60e97db63b0fe766922",
            "V13__add_market_pair_to_premium_tables.sql" to
                "abfd68893f5584220b2b9698ff97a0a99a580e2f25522732c0b80a54a6433400",
        )
    }
}
