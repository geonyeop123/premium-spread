package io.premiumspread.infrastructure.common.support

import io.premiumspread.testcontainers.applyTestDurabilityTuning
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * common 통합 테스트가 JVM 하나에서 공유하는 MySQL 8.0 컨테이너.
 * 클래스마다 [database] 로 빈 database 를 받는다 — 격리 단위는 컨테이너가 아니라 database 다.
 * stop 하지 않는다. JVM 종료 시 Ryuk 가 정리한다(run 간 reuse 아님).
 */
object SharedMySqlContainer {
    const val USERNAME: String = "test"
    const val PASSWORD: String = "test"

    private const val BOOTSTRAP_DATABASE = "shared_bootstrap"
    private val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

    private val container: MySQLContainer<*> =
        MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName(BOOTSTRAP_DATABASE)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            // 테스트 전용: DDL/commit fsync 비용을 줄인다. 플래그 목록은 api·batch 와 같은 MySqlTestTuning 하나다.
            .apply { applyTestDurabilityTuning() }

    private val allocated: MutableSet<String> = mutableSetOf(BOOTSTRAP_DATABASE)

    init {
        container.start()
        check(!container.jdbcUrl.contains('?')) {
            "MySQLContainer.jdbcUrl 에 query string 이 있다: ${container.jdbcUrl}"
        }
    }

    /**
     * 공용 컨테이너에 [name] database 를 만들고 [USERNAME] 에게 그 database 의 권한만 준다.
     * FLUSH PRIVILEGES 는 쓰지 않는다 — caching_sha2 인증 캐시가 비면 `sslMode=DISABLED` 연결이 실패한다.
     * @throws IllegalArgumentException [name] 이 `^[a-z][a-z0-9_]{0,63}$` 가 아니면
     * @throws IllegalStateException 같은 [name] 이 이 JVM 에서 이미 할당됐거나 부트스트랩 database 이름이면
     */
    @Synchronized
    fun database(name: String): TestDatabase {
        require(NAME_PATTERN.matches(name)) { "허용되지 않는 database 이름: $name" }
        check(allocated.add(name)) { "이미 할당된 database 이름: $name" }

        val host = container.host
        val port = container.getMappedPort(3306)
        DriverManager.getConnection("jdbc:mysql://$host:$port/", "root", PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE `$name`")
                statement.execute("GRANT ALL PRIVILEGES ON `$name`.* TO '$USERNAME'@'%'")
            }
        }
        return TestDatabase(
            databaseName = name,
            jdbcUrl = "jdbc:mysql://$host:$port/$name",
            username = USERNAME,
            password = PASSWORD,
        )
    }
}

/** 공용 컨테이너 안의 database 하나. 기존 `MySQLContainer` 에서 테스트가 쓰던 네 멤버와 이름이 같다. */
class TestDatabase internal constructor(
    val databaseName: String,
    /** `jdbc:mysql://{host}:{mappedPort}/{databaseName}` — query string 없음 */
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    /** `"$jdbcUrl?$UTC_SESSION_PARAMS"` */
    val utcJdbcUrl: String = "$jdbcUrl?$UTC_SESSION_PARAMS"

    companion object {
        const val UTC_SESSION_PARAMS: String =
            "sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    }
}
