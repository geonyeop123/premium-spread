package io.premiumspread.testcontainers

import org.testcontainers.containers.MySQLContainer

/**
 * 테스트 전용 MySQL 컨테이너 내구성 완화. 테스트 DB 는 내구성이 필요 없으므로 DDL(TRUNCATE)·commit 의
 * fsync 비용을 줄인다. data directory 를 tmpfs 에 두고 redo log·binlog flush 와 doublewrite 를 끈다.
 * binlog 자체는 끄지 않는다(`--skip-log-bin` 미사용) — MySQL 8 기본값(binlog 활성)과 같은 서버 상태를 유지한다.
 * api·batch(`MySqlTestContainersConfig`)와 infrastructure:common(`SharedMySqlContainer`)이 이 목록 하나를 쓴다.
 */
object MySqlTestTuning {
    val TMPFS: Map<String, String> = mapOf("/var/lib/mysql" to "rw")

    val DURABILITY_FLAGS: List<String> = listOf(
        "--innodb-flush-log-at-trx-commit=0",
        "--sync-binlog=0",
        "--innodb-doublewrite=0",
    )
}

/**
 * [MySqlTestTuning] 을 적용한다. `withCommand` 는 기존 command 를 대체하므로 문자셋 같은 다른 서버 인자는
 * [serverArgs] 로 함께 넘긴다.
 */
fun MySQLContainer<*>.applyTestDurabilityTuning(vararg serverArgs: String) {
    withTmpFs(MySqlTestTuning.TMPFS)
    withCommand(*(serverArgs.toList() + MySqlTestTuning.DURABILITY_FLAGS).toTypedArray())
}
