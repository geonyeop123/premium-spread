# 테스트 실행 시간 단축 (issue #104) — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (태스크 단위 실행).
> 체크박스(`- [ ]`)로 진행을 기록한다.

**Goal:** 검증 범위와 테스트 수를 줄이지 않고 로컬 ⑩ 검증 ≤ 7m, 로컬 common IT ≤ 4m, CI Quality Gate ≤ 6m 로 만든다.

**Architecture:** 테스트 인프라만 바꾼다 — common IT 는 JVM 공용 MySQL 컨테이너 + 클래스별 database + 지문 확인 TRUNCATE,
batch IT 는 Spring context 재사용 + 공용 MockWebServer 초기화, unit 은 지연 단축 + 모듈 병렬, CI 는 job 병렬화와 common job 분리,
⑩ 은 Gradle 한 번 호출.

**Tech Stack:** Kotlin 2.0, JUnit 5, Testcontainers 1.21.4(MySQL 8.0, Redis 7), Flyway, MockWebServer 4.12.0, Gradle 8.14.3, GitHub Actions.

> design: `docs/work/test-time-reduction/design.md` · dod: `docs/work/test-time-reduction/dod.md`
> 모든 명령은 worktree 루트(`/home/yeop/IdeaProjects/premium-spread/.worktrees/chore-issue-104-test-time-reduction`)에서
> 실행한다. Task 0 과 Task 7 만 측정용 별도 worktree 를 쓴다.
> **앱·도메인·infrastructure `src/main` 은 고치지 않는다**(design 범위). 고쳐야 할 것 같으면 멈추고 보고한다.
> `build-logic` 은 빌드 스크립트로 보며, `modules/jpa`·`modules/redis` 의 `testFixtures` 는 테스트 소스다.

## 공통 정의

측정 명령(design D9). 각 태스크의 "예상 결과" 는 이 함수로 잰다.

```bash
count_tests() {
  awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ skipped=$/)s+=$(i+1);if($i~/ failures=$/)f+=$(i+1);if($i~/ errors=$/)e+=$(i+1)}} END{printf "tests=%d skipped=%d failures=%d errors=%d\n",t,s,f,e}' "$@"
}
count_contexts() {
  grep -ho 'Started [^ ]* in [0-9.]* seconds' apps/batch/build/test-results/integrationTest/TEST-*.xml | wc -l
}
suite_attrs() {  # 사용: suite_attrs <모듈경로> <클래스 단순이름> — 클래스 tests·time
  grep -ho "<testsuite name=\"[^\"]*\.$2\" [^>]*" "$1"/build/test-results/test/TEST-*"$2".xml
}
case_times() {   # 사용: case_times <모듈경로> <클래스 단순이름> — 메서드별 name·time
  grep -ho '<testcase name="[^"]*" classname="[^"]*" time="[0-9.]*"' "$1"/build/test-results/test/TEST-*"$2".xml
}
```

AC10 대상 메서드(지연을 쓰는 4개):
- `BithumbPrivateAccountAdapterTest` — `네트워크 실패와 타임아웃 예외에도 secret 이 없다`, `응답 시간이 초과되면 NETWORK`
- `ExchangeRateClientTest` — `read timeout 은 설정한 횟수까지만 재시도한다`, `응답 헤더 지연으로 Netty responseTimeout 이 이기면 WebClientRequestException 으로 재시도한다`

기준선 기호(Task 0 이 dod 증거 로그 `### 기준선` 에 기록): `B_unit`, `B_infra_batch_unit`, `B_common`, `B_api`, `B_batch`,
`B_ctx`, `B_wall`, 위 4개 메서드의 testcase time, 그리고 `BithumbPrivateAccountAdapterTest`·`ExchangeRateClientTest` 의 `tests`·
`BinanceFuturesManualAccountAdapterTest` 의 `tests`·`time`.

태스크 순서와 의존:

```text
Task 0 (기준선, 커밋 없음)
Task 1 unit 지연 ── 독립
Task 2 공용 MySQL 컨테이너 ── Task 3 의 전제
Task 3 LatestSchemaFixture
Task 4 batch context 재사용 ── 독립
Task 5 CI workflow + 계약 테스트 ── 독립
Task 6 하네스·정본 문서 ── Task 5 의 unit 명령과 같은 플래그(`--parallel`)
Task 7 최종 측정 ── 1~6 전부 뒤
```

---

## Task 0 — 기준선 측정 (커밋 없음)

**Files:** 없음(dod 증거 로그에 기록만).

- [x] **Step 1: origin/dev 콜드 worktree 생성**

```bash
git -C /home/yeop/IdeaProjects/premium-spread fetch origin dev
git -C /home/yeop/IdeaProjects/premium-spread worktree add --detach /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-baseline origin/dev
cd /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-baseline
git rev-parse HEAD
```

- [x] **Step 2: 기존 ⑩ 직렬 5개 명령을 명령별로 시간 측정**

```bash
time ./gradlew test architectureTest --offline --no-daemon
time ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
time ./gradlew :apps:api:integrationTest --offline --no-daemon
time ./gradlew :apps:batch:integrationTest --offline --no-daemon
time ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
```

예상: 5개 모두 `BUILD SUCCESSFUL`. `real` 합 ≈ 17m(측정 근거). 하나라도 실패하면 기준선이 아니다 — 멈추고 보고한다.

- [x] **Step 3: 개수·context 수 집계**

```bash
count_tests $(find . -path '*/build/test-results/test/TEST-*.xml')               # B_unit
count_tests infrastructure/batch/build/test-results/test/TEST-*.xml               # B_infra_batch_unit
count_tests infrastructure/common/build/test-results/integrationTest/TEST-*.xml   # B_common
count_tests apps/api/build/test-results/integrationTest/TEST-*.xml                # B_api
count_tests apps/batch/build/test-results/integrationTest/TEST-*.xml              # B_batch
count_contexts                                                                    # B_ctx
```

예상: failures=0 errors=0. `B_common` ≥ 64, `B_api` ≥ 174, `B_batch` ≥ 109(issue 기준값, dev 에는 V22·OwnerMarketAlert 로 더 많을 수 있다).
`B_ctx` ≈ 23. `count_contexts` 가 0 이면 Spring Boot 기동 로그가 XML 에 없는 것이다 — 멈추고 보고한다(AC5 측정 수단 재결정 필요).

- [x] **Step 4: unit 클래스·메서드 시간 기준선**

```bash
./gradlew :infrastructure:batch:test --rerun --offline --no-daemon
suite_attrs infrastructure/batch BithumbPrivateAccountAdapterTest
suite_attrs infrastructure/batch ExchangeRateClientTest
suite_attrs infrastructure/batch BinanceFuturesManualAccountAdapterTest
case_times infrastructure/batch BithumbPrivateAccountAdapterTest
case_times infrastructure/batch ExchangeRateClientTest
```

`case_times` 출력에서 공통 정의의 AC10 대상 메서드 4개 행의 `time` 을 기준선으로 기록한다.

- [x] **Step 5: dod `### 기준선` 블록에 SHA·값 전부 기록, worktree 제거**

```bash
cd /home/yeop/IdeaProjects/premium-spread/.worktrees/chore-issue-104-test-time-reduction
git -C /home/yeop/IdeaProjects/premium-spread worktree remove /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-baseline
```

---

## Task 1 — unit MockWebServer 지연 1초 (design D4)

**Files:**
- Modify: `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbPrivateAccountAdapterTest.kt` (:101, :345)
- Modify: `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/ExchangeRateClientTest.kt` (:168, :180)

**코드 계약 (정확한 치환):**

| 위치 | 현재 | 변경 |
|---|---|---|
| Bithumb :101 | `json(accountsBody).setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)` | `json(accountsBody).setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)` |
| Bithumb :345 | 동일 | 동일 |
| ExchangeRate :168 | `.setBodyDelay(2, TimeUnit.SECONDS),` | `.setBodyDelay(1, TimeUnit.SECONDS),` |
| ExchangeRate :180 | `server.enqueue(success().setHeadersDelay(2, TimeUnit.SECONDS))` | `server.enqueue(success().setHeadersDelay(1, TimeUnit.SECONDS))` |

timeout 값(200ms)·assertion·테스트 이름은 바꾸지 않는다. `BinanceFuturesManualAccountAdapterTest`·`DiscordWebhookSenderAdapterTest`·
WebSocket 테스트는 건드리지 않는다.

- [x] **Step 1: 4곳 치환**
- [x] **Step 2: 모듈 테스트**

```bash
./gradlew :infrastructure:batch:test --rerun --offline --no-daemon
count_tests infrastructure/batch/build/test-results/test/TEST-*.xml
suite_attrs infrastructure/batch BithumbPrivateAccountAdapterTest
suite_attrs infrastructure/batch ExchangeRateClientTest
suite_attrs infrastructure/batch BinanceFuturesManualAccountAdapterTest
case_times infrastructure/batch BithumbPrivateAccountAdapterTest
case_times infrastructure/batch ExchangeRateClientTest
```

예상: `BUILD SUCCESSFUL`. tests = `B_infra_batch_unit`, failures=0 errors=0. 두 클래스의 `tests` 는 기준선과 같다. AC10 대상 메서드 4개
각각의 `time` ≤ 기준선 − 0.8s. ExchangeRate 두 메서드는 재시도가 새 연결에서 돌아 두 응답 지연이 겹치므로 메서드당 절감은 약 1s 다
(design D4). Binance 는 값만 기록.

- [x] **Step 3: 커밋** — `test: MockWebServer timeout 테스트 지연을 2초에서 1초로 단축`

---

## Task 2 — common IT 공용 MySQL 컨테이너 + 클래스별 database (design D6)

**Files:**
- Create: `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/support/SharedMySqlContainer.kt`
- Modify: design D6 표의 16개 클래스(`infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/` 아래
  `persistence/jdbc/accountpnl/` 3개, `persistence/jdbc/notification/` 1개, `persistence/jpa/notification/` 1개,
  `persistence/jdbc/ownermarket/` 1개, `cache/premium/` 1개, `migration/` 9개)

**시그니처 계약:**

```kotlin
package io.premiumspread.infrastructure.common.support

/**
 * common 통합 테스트가 JVM 하나에서 공유하는 MySQL 8.0 컨테이너.
 * 클래스마다 [database] 로 빈 database 를 받는다 — 격리 단위는 컨테이너가 아니라 database 다.
 * stop 하지 않는다. JVM 종료 시 Ryuk 가 정리한다(run 간 reuse 아님).
 */
object SharedMySqlContainer {
    const val USERNAME: String = "test"
    const val PASSWORD: String = "test"

    /**
     * 공용 컨테이너에 [name] database 를 만들고 [USERNAME] 에게 그 database 의 권한만 준다.
     * @throws IllegalArgumentException [name] 이 `^[a-z][a-z0-9_]{0,63}$` 가 아니면
     * @throws IllegalStateException 같은 [name] 이 이 JVM 에서 이미 할당됐거나 부트스트랩 database 이름이면
     */
    fun database(name: String): TestDatabase
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
    val utcJdbcUrl: String

    companion object {
        const val UTC_SESSION_PARAMS: String =
            "sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    }
}
```

`TestDatabase` 는 `data class` 가 아니다(자동 `toString()` 에 password 를 싣지 않는다).

**구현 요건 (`SharedMySqlContainer`):**
- 컨테이너: `MySQLContainer(DockerImageName.parse("mysql:8.0")).withDatabaseName("shared_bootstrap").withUsername(USERNAME).withPassword(PASSWORD)`,
  서버 기본 charset·collation 유지, `MySqlTestTuning`(`modules/jpa` testFixtures — tmpfs datadir, `--innodb-flush-log-at-trx-commit=0`,
  `--innodb-doublewrite=0`, `--sync-binlog=0`; `--skip-log-bin` 은 binlog 의존 migration 오류를 잡기 위해 쓰지 않는다)을 적용, object 초기화에서 `start()`.
- 기동 직후 `container.jdbcUrl` 에 `?` 가 있으면 `IllegalStateException` (design A3 전제 확인).
- 할당된 이름 집합은 `"shared_bootstrap"` 을 담고 시작한다. `database()` 는 `@Synchronized`.
- database 생성은 root 연결 하나에서: URL `jdbc:mysql://{host}:{mappedPort}/`(sslMode 지정 없음), user `root`, password `PASSWORD`.
  실행 SQL 은 정확히 두 개 — `CREATE DATABASE \`{name}\`` 와 `GRANT ALL PRIVILEGES ON \`{name}\`.* TO 'test'@'%'`.
  **`FLUSH PRIVILEGES` 금지**(이유를 KDoc 한 줄로: caching_sha2 인증 캐시가 비면 `sslMode=DISABLED` 연결이 실패한다). 연결은 `use` 로 닫는다.
- `jdbcUrl` = `"jdbc:mysql://${container.host}:${container.getMappedPort(3306)}/$name"`.

**16개 클래스 공통 변경 요건:**
- companion 의 `@Container @JvmStatic val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0").withDatabaseName("X").withUsername("test").withPassword("test")`
  를 `val mysql: TestDatabase = SharedMySqlContainer.database("X")` 로 바꾼다. **X 는 기존 `withDatabaseName` 값 그대로**(design D6 표).
- 클래스의 `@Testcontainers` 와 import `org.testcontainers.containers.MySQLContainer`·`org.testcontainers.junit.jupiter.Container`·
  `org.testcontainers.junit.jupiter.Testcontainers` 를 지운다. import `io.premiumspread.infrastructure.common.support.SharedMySqlContainer`·
  `io.premiumspread.infrastructure.common.support.TestDatabase` 를 더한다.
- **예외** `PremiumCacheTransactionIntegrationTest`: Redis 때문에 `@Testcontainers`, redis 의 `@Container @JvmStatic`, import
  `GenericContainer`·`Container`·`Testcontainers` 를 유지한다. mysql 만 바꾼다.
- 테스트 본문·`jdbcUrl` getter·`migrateLatest()`·migration 테스트의 clean/target 로직은 **이 태스크에서 바꾸지 않는다.**

- [x] **Step 1: `SharedMySqlContainer.kt` 작성**
- [x] **Step 2: 16개 클래스 전환**
- [x] **Step 3: 컴파일 확인** — `./gradlew :infrastructure:common:compileIntegrationTestKotlin --offline --no-daemon` → `BUILD SUCCESSFUL`(경고=에러 설정이라 경고 0)
- [x] **Step 4: common IT 실행**

```bash
time ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
count_tests infrastructure/common/build/test-results/integrationTest/TEST-*.xml
```

예상: `BUILD SUCCESSFUL`(leak 검사 포함). tests = `B_common`, failures=0 errors=0, skipped = 기준선. 실행 중 `docker ps` 에 이 JVM 의
`mysql:8.0` 은 1개. 시간은 기준선보다 짧다(컨테이너 기동 16회 → 1회) — 기록만.

- [x] **Step 5: 커밋** — `test: common 통합 테스트를 공용 MySQL 컨테이너와 클래스별 database 로 전환`

---

## Task 3 — adapter 테스트 migrate 1회 + 지문 확인 TRUNCATE (design D7)

**Files:**
- Create: `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/support/LatestSchemaFixture.kt`
- Create: `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/support/LatestSchemaFixtureIntegrationTest.kt`
- Modify: `JdbcAccountPnlAlertStoreIntegrationTest`, `JdbcOwnerAlertOutboxIntegrationTest`, `JdbcAccountPnlLatestStoreIntegrationTest`
  (`persistence/jdbc/accountpnl/`), `JdbcNotificationDeliveryRepositoryIntegrationTest`(`persistence/jdbc/notification/`),
  `NotificationSubscriptionOptimisticLockIntegrationTest`(`persistence/jpa/notification/`), `JdbcOwnerMarketAlertStoreIntegrationTest`
  (`persistence/jdbc/ownermarket/`)

**시그니처 계약:**

```kotlin
package io.premiumspread.infrastructure.common.support

/**
 * adapter 통합 테스트용 최신 schema. 상태(마이그레이션 여부·스키마 지문)를 인스턴스가 가지므로
 * 테스트 클래스 companion 에 하나만 둔다.
 */
class LatestSchemaFixture(private val database: TestDatabase) {
    /**
     * 첫 호출: Flyway clean + migrate 후 스키마 지문 저장.
     * 이후 호출: 지문이 같으면 flyway_schema_history 를 뺀 base table 중 dirty 테이블만 TRUNCATE,
     * 다르면 clean + migrate 후 지문 재저장.
     */
    fun reset()
}
```

**구현 요건 (`LatestSchemaFixture`):**
- 접속: `database.utcJdbcUrl`, `database.username`, `database.password`.
- Flyway: `Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").cleanDisabled(false).load()` 의 `clean()` → `migrate()`.
- TRUNCATE 경로는 **JDBC 연결 하나**에서: `SET FOREIGN_KEY_CHECKS = 0` → 대상 각각 `TRUNCATE TABLE \`{t}\`` → finally `SET FOREIGN_KEY_CHECKS = 1`.
  대상 = `SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'`(매 호출 조회, 하드코딩 금지) 중
  dirty 인 것만(행 존재 또는 `AUTO_INCREMENT > 1`; 세션 `information_schema_stats_expiry = 0`). 실측: 지문 12~29ms, clean+migrate 2.6~12s.
- 지문 = 아래 질의 결과를 행마다 문자열로 만든 뒤 정렬한 `List<String>`(모두 schema = `DATABASE()`): `TABLES`(TABLE_NAME, TABLE_TYPE) ·
  `COLUMNS`(TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA) · `STATISTICS`(TABLE_NAME, INDEX_NAME,
  SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE) · `TABLE_CONSTRAINTS`(TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE) · `CHECK_CONSTRAINTS`(CONSTRAINT_NAME,
  CHECK_CLAUSE) · `REFERENTIAL_CONSTRAINTS`(CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE) · `TRIGGERS`(TRIGGER_NAME) ·
  `VIEWS`(TABLE_NAME) · `flyway_schema_history`(installed_rank, version, checksum, success). 질의 종류마다 접두어를 붙여 서로 섞이지 않게 한다.
- 로그를 남긴다면 담을 값: 재구성 여부와 database 이름. 담지 않을 값: password.

**adapter 6개 클래스 변경 요건:**
- companion 에 `private val latestSchema = LatestSchemaFixture(mysql)` 를 **`mysql` 선언 아래에** 둔다(companion 초기화 순서).
- `@BeforeEach` 의 `migrateLatest()` 호출(Latest·OptimisticLock 은 인라인 Flyway 블록)을 `latestSchema.reset()` 으로 바꾼다.
  OptimisticLock 은 `dataSource` 생성 줄을 유지한다(뒤의 insert·EMF 가 쓴다).
- 미사용이 된 `private fun migrateLatest()` 와 `org.flywaydb.core.Flyway` import 를 지운다. 기존 `jdbcUrl` getter 와 테스트 본문은 그대로.

**`LatestSchemaFixtureIntegrationTest` 요건 (테스트 3건):**
- `@Tag("integration")`. companion: `val mysql = SharedMySqlContainer.database("premiumspread_latest_schema_fixture")`,
  `private val fixture = LatestSchemaFixture(mysql)`, `val firstMysql = SharedMySqlContainer.database("premiumspread_latest_schema_first")`,
  `private val firstFixture = LatestSchemaFixture(firstMysql)`. `@BeforeEach` 는 `fixture.reset()`.
- (a) `reset 은 history 를 뺀 모든 테이블을 비우고 AUTO_INCREMENT 를 되돌린다`: `member` 2행과 그 member 의 `notification_subscription` 1행
  (FK 자식, SQL 형태는 `JdbcNotificationDeliveryRepositoryIntegrationTest.insertSubscription` 과 같음)을 넣고, history 행 수를 기록 →
  `fixture.reset()` → information_schema 로 조회한 history 외 **모든** base table 의 `COUNT(*)` 가 0, history 행 수 불변, `member` 1행 insert 후
  `SELECT id FROM member` = 1.
- (b) `reset 은 지워진 CHECK 제약을 복구한다`: `ALTER TABLE owner_market_alert_state DROP CHECK ck_owner_market_alert_state_zone` 후
  `information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_owner_market_alert_state_zone'` 가 0건임을 확인
  → `fixture.reset()` → 1건.
- (c) `첫 reset 은 빈 database 를 최신 schema 로 만든다`: `firstFixture.reset()` 후 `firstMysql.utcJdbcUrl` 에 대한 Flyway `info()` 의 `pending()` 이
  비어 있고 `applied().size` 가 `all().size` 와 같다.

- [x] **Step 1: 테스트 + 지문 없는 1차 구현 → RED**

`LatestSchemaFixtureIntegrationTest` 를 쓰고, `LatestSchemaFixture.reset()` 은 **1차 구현**으로 둔다 — 첫 호출만 clean+migrate,
이후 호출은 항상 TRUNCATE(지문 비교 없음).

```bash
./gradlew :infrastructure:common:integrationTest --tests '*LatestSchemaFixtureIntegrationTest' --offline --no-daemon
```

예상: (a)·(c) 통과, (b) 가 CHECK 제약 건수 `expected 1 but was 0` 으로 실패한다(지문 없이는 스키마 변경을 되돌리지 못한다).
이 출력을 dod AC6 증거 로그에 RED 로 붙인다. 컴파일 실패는 RED 가 아니다.

- [x] **Step 2: 지문 비교를 넣어 완성 → 같은 명령 GREEN (3 tests, failures 0)**
- [x] **Step 3: adapter 6개 클래스 전환**
- [x] **Step 4: common IT 전체**

```bash
time ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
count_tests infrastructure/common/build/test-results/integrationTest/TEST-*.xml
grep -ho '<testsuite name="[^"]*JdbcAccountPnlAlertStoreIntegrationTest" [^>]*' infrastructure/common/build/test-results/integrationTest/TEST-*JdbcAccountPnlAlertStoreIntegrationTest.xml
```

예상: `BUILD SUCCESSFUL`. tests = `B_common` + 3, failures=0 errors=0, skipped = 기준선. `JdbcAccountPnlAlertStoreIntegrationTest` 의 `time`
이 기준선 163s 대비 크게 줄어든다(테스트당 clean+migrate 6.8s 제거) — 기록만. 전체 `real` ≤ 4m 이 AC1 의 예비 확인이다.

- [x] **Step 5: 커밋** — `test: common adapter 통합 테스트를 migrate 1회와 지문 확인 TRUNCATE 로 전환`

---

## Task 4 — batch 통합 테스트 context 재사용 (design D5)

**Files:**
- Modify: `apps/batch/src/integrationTest/kotlin/io/premiumspread/support/BatchIntegrationTestBase.kt`
- Create: `apps/batch/src/integrationTest/kotlin/io/premiumspread/support/FixedClockTestConfig.kt`
- Create: `apps/batch/src/integrationTest/kotlin/io/premiumspread/support/SharedContextIsolationIntegrationTest.kt`
- Modify: `apps/batch/src/integrationTest/kotlin/io/premiumspread/repository/ExchangeRateRepositoryTest.kt`
- Modify: `apps/batch/src/integrationTest/kotlin/io/premiumspread/cache/TickerCacheServiceScoreTest.kt`
- Modify: `apps/batch/build.gradle.kts` (`integrationTest` task)

**시그니처 계약:**

```kotlin
package io.premiumspread.support

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, BatchTestConfig::class)
// @DirtiesContext 없음 — 같은 설정의 클래스는 context 를 공유한다.
abstract class BatchIntegrationTestBase {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    /** BatchTestConfig.exchangeRateMockServer. 하위 클래스의 `exchangeRateMockServer` 와 이름이 겹치지 않게 private 다른 이름. */
    @Autowired
    private lateinit var sharedExchangeRateMockServer: MockWebServer

    /** 기존: BATCH_TABLES truncate + Redis flushAll. 추가: 마지막에 공용 MockWebServer 초기화. */
    @BeforeEach
    fun cleanUp()
}
```

```kotlin
package io.premiumspread.support

@TestConfiguration
class FixedClockTestConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

    companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-05-12T00:00:10Z")
    }
}
```

```kotlin
package io.premiumspread.support

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SharedContextIsolationIntegrationTest : BatchIntegrationTestBase() {
    @Autowired
    lateinit var exchangeRateMockServer: MockWebServer

    @Test @Order(1)
    fun `앞 테스트가 공용 서버에 응답과 요청 기록을 남긴다`()

    @Test @Order(2)
    fun `다음 테스트는 앞 테스트의 응답과 요청 기록을 보지 않는다`()
}
```

`apps/batch/build.gradle.kts` 의 `tasks.register<Test>("integrationTest") { ... }` 블록에 한 줄 추가:

```kotlin
    maxHeapSize = "1g"
```

**구현 요건:**
- base: `@DirtiesContext` 애너테이션과 `org.springframework.test.annotation.DirtiesContext` import 삭제. `cleanUp()` 의 기존 두 동작 뒤에
  공용 서버 초기화: `sharedExchangeRateMockServer.dispatcher = QueueDispatcher()` → `takeRequest(0, TimeUnit.MILLISECONDS)` 가 `null` 일 때까지 반복.
  KDoc 에 "공용 서버의 `requestCount` 는 누적값이라 단언하지 않는다" 를 적는다.
- `ExchangeRateRepositoryTest`: 중첩 `FixedClockConfig` 클래스 삭제, `@Import(FixedClockTestConfig::class)`. 미사용 import(`TestConfiguration`·`Bean`·
  `Primary`·`Clock`·`ZoneOffset` 중 본문이 안 쓰는 것) 삭제. `TickerCacheServiceScoreTest` 도 같은 방식.
- `SharedContextIsolationIntegrationTest`: 추가 설정·`@TestPropertySource`·`@Import` 없음(기본 context 를 공유해야 한다). HTTP 호출은 JDK
  `HttpURLConnection`(요청 헤더 `Connection: close`, 응답 본문을 읽은 뒤 `disconnect()`) — 스레드를 남기는 클라이언트를 쓰지 않는다.
  - (1): `enqueue(MockResponse().setBody("leftover-1"))`, `enqueue(MockResponse().setBody("leftover-2"))` → `GET {server.url("/first")}` 본문이
    `"leftover-1"`. `takeRequest` 는 하지 않는다(요청 기록 1건, 응답 1건을 남긴다).
  - (2): `enqueue(MockResponse().setBody("fresh"))` → `GET /second` 본문이 `"fresh"` → `takeRequest(0, MILLISECONDS)!!.path` = `"/second"` →
    다음 `takeRequest(0, MILLISECONDS)` = `null`.
- 커스텀 context 6개 클래스와 다른 테스트 본문은 바꾸지 않는다. 공유 때문에 실패하는 클래스가 나오면 design D5 마지막 문단을 따른다.

- [x] **Step 1: `SharedContextIsolationIntegrationTest` 와 `FixedClockTestConfig` 작성(아직 base 미수정) → RED**

```bash
./gradlew :apps:batch:integrationTest --tests '*SharedContextIsolationIntegrationTest' --offline --no-daemon
```

예상: (2) 가 `expected "fresh" but was "leftover-2"` 로 실패(공용 서버에 앞 응답이 남아 있다). 같은 클래스 안의 두 메서드는
`@DirtiesContext(AFTER_CLASS)` 와 무관하게 이미 context 를 공유하므로 base 수정 전에도 재현된다. dod AC7 증거 로그에 붙인다.
- [x] **Step 2: base 수정(`@DirtiesContext` 제거 + 초기화) → 같은 명령 GREEN (2 tests)**
- [x] **Step 3: FixedClock 두 클래스 전환, `maxHeapSize` 추가**
- [x] **Step 4: batch IT 전체**

```bash
time ./gradlew :apps:batch:integrationTest --offline --no-daemon
count_tests apps/batch/build/test-results/integrationTest/TEST-*.xml
count_contexts
```

예상: `BUILD SUCCESSFUL`(leak 검사 포함, OOM 없음). tests = `B_batch` + 2, failures=0 errors=0, skipped = 기준선. `count_contexts` ≤ 9(설계 예상 8).

- [x] **Step 5: 커밋** — `test: batch 통합 테스트 Spring context 재사용과 공용 MockWebServer 초기화`

---

## Task 5 — CI job 병렬화·common job 분리·cache read-only·unit 병렬 (design D1·D2·D3)

**Files:**
- Modify: `ci/quality-gate-contract-test.sh`
- Modify: `docker/deploy-contract-test.sh` (:49-54)
- Modify: `.github/workflows/quality-gate.yml`

### 5-A. 계약 테스트 갱신 (먼저)

`ci/quality-gate-contract-test.sh` 변경(전문 — 계약 테스트가 단언할 문자열이라 그대로 쓴다):

1. 197-204행:

```bash
expected_jobs=(compile-architecture unit-coverage api-integration batch-integration static-analysis dependency-security docker-build common-integration)
jobs_block="$(yaml_top_level_block jobs)"
mapfile -t actual_jobs < <(
  awk '/^  [^[:space:]#][^:]*:$/ { value = $0; sub(/^  /, "", value); sub(/:$/, "", value); print value }' \
    <<< "${jobs_block}"
)
[[ "${actual_jobs[*]}" == "${expected_jobs[*]}" ]] ||
  fail "Quality Gate job IDs must be exactly the eight required jobs in order"
```

2. 206-232행 — 두 배열 끝에 common 항목을 더하고 개수 7:

```bash
upload_step_names=(
  'Publish dependency bootstrap review bundle'
  'Publish unit and coverage evidence'
  'Publish API integration evidence'
  'Publish Batch integration evidence'
  'Publish static-analysis evidence'
  'Publish Docker image archives'
  'Publish common integration evidence'
)
upload_artifact_names=(
  'dependency-bootstrap-review-${{ github.sha }}'
  'unit-coverage-${{ github.sha }}'
  'api-integration-${{ github.sha }}'
  'batch-integration-${{ github.sha }}'
  'static-analysis-${{ github.sha }}'
  'docker-images-${{ github.sha }}'
  'common-integration-${{ github.sha }}'
)
```

```bash
[[ "$(grep -c 'uses: actions/upload-artifact@' "${quality_workflow}")" -eq 7 ]] ||
  fail "Quality Gate must publish exactly seven review/evidence artifacts"
```

3. 259-263행(`for dependent_job in ...` 루프)을 아래로 교체:

```bash
# Gradle job 은 각자 --dependency-verification strict 로 의존성을 검증하고(아래 루프), PR 은 Gradle cache 를
# 읽기만 하므로 compile job 을 기다리지 않아도 미검증 artifact 를 쓰거나 cache 를 오염시키지 않는다.
[[ "$(grep -Ec '^    needs:' "${quality_workflow}" || true)" -eq 0 ]] ||
  fail "Quality Gate jobs must start in parallel without needs"
setup_gradle_line='      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1'
cache_read_only_line="          cache-read-only: \${{ github.ref != 'refs/heads/dev' }}"
for gradle_job in compile-architecture unit-coverage api-integration batch-integration common-integration; do
  gradle_job_block="$(workflow_job_block "${gradle_job}")"
  [[ "$(grep -Fxc "${setup_gradle_line}" <<< "${gradle_job_block}")" -eq 1 ]] ||
    fail "${gradle_job} must set up Gradle exactly once"
  [[ "$(grep -Fxc "${cache_read_only_line}" <<< "${gradle_job_block}")" -eq 1 ]] ||
    fail "${gradle_job} must restore the Gradle cache read-only outside dev pushes"
done
[[ "$(grep -Fc 'uses: gradle/actions/setup-gradle@' "${quality_workflow}")" -eq 5 ]] ||
  fail "only the five Gradle jobs may set up Gradle"
```

4. 270-277행을 아래로 교체:

```bash
unit_job="$(workflow_job_block unit-coverage)"
api_job="$(workflow_job_block api-integration)"
batch_job="$(workflow_job_block batch-integration)"
common_job="$(workflow_job_block common-integration)"
unit_command='run: ./gradlew test jacocoTestReport jacocoTestCoverageVerification --parallel --dependency-verification strict --no-daemon'
api_integration_command='run: ./gradlew :apps:api:integrationTest --dependency-verification strict --no-daemon'
batch_integration_command='run: ./gradlew :apps:batch:integrationTest --dependency-verification strict --no-daemon'
common_integration_command='run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest --dependency-verification strict --no-daemon'
grep -Fq "${unit_command}" <<< "${unit_job}" ||
  fail "unit-coverage job must run unit tests and coverage across modules in parallel"
grep -Fq "${api_integration_command}" <<< "${api_job}" ||
  fail "api-integration job must execute the exact strict API integration task"
if grep -Fq ':infrastructure:common:' <<< "${api_job}"; then
  fail "api-integration job must not run common tasks; common-integration owns them"
fi
grep -Fq "${batch_integration_command}" <<< "${batch_job}" ||
  fail "batch-integration job must execute the exact strict Batch integration task"
grep -Fq "${common_integration_command}" <<< "${common_job}" ||
  fail "common-integration job must execute the exact strict migration and common integration tasks"
for integration_job in common-integration api-integration batch-integration; do
  grep -Fxq "          TESTCONTAINERS_REUSE_ENABLE: 'false'" <<< "$(workflow_job_block "${integration_job}")" ||
    fail "${integration_job} must disable Testcontainers reuse"
done
```

나머지 행은 그대로 둔다(strict 루프 264-268 은 새 job 의 `run: ./gradlew` 도 자동으로 검사한다).

`docker/deploy-contract-test.sh` 49-54행을 아래로 교체:

```bash
api_job="$(quality_job_block api-integration)"
batch_job="$(quality_job_block batch-integration)"
common_job="$(quality_job_block common-integration)"
grep -Fq 'run: ./gradlew :apps:api:integrationTest --dependency-verification strict --no-daemon' <<< "${api_job}" ||
  fail "Quality Gate API job must run exact strict API integration task"
grep -Fq 'run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest --dependency-verification strict --no-daemon' <<< "${common_job}" ||
  fail "Quality Gate common job must run exact strict migration and common integration tasks"
grep -Fq 'run: ./gradlew :apps:batch:integrationTest --dependency-verification strict --no-daemon' <<< "${batch_job}" ||
  fail "Quality Gate Batch job must run exact strict Batch integration task"
```

- [x] **Step 1: 두 스크립트 수정 → RED**

```bash
bash ci/quality-gate-contract-test.sh
bash docker/deploy-contract-test.sh
```

예상: 첫 명령 exit 1, `quality gate contract failed: Quality Gate job IDs must be exactly the eight required jobs in order`.
둘째 명령 exit 1, `deploy contract failed: Quality Gate API job must run exact strict API integration task`. dod AC8 증거 로그에 붙인다.

### 5-B. workflow 변경

`.github/workflows/quality-gate.yml` 변경(전문 — 계약 테스트가 문자열로 단언한다). `(변경 없음)` 으로 표시한 step 은 그대로 둔다.

compile-architecture 의 setup-gradle step(34-36행):

```yaml
      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1
        with:
          cache-disabled: ${{ steps.bootstrap-request.outputs.requested == 'true' }}
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
```

unit-coverage(62-85행):

```yaml
  unit-coverage:
    name: 2. Unit + coverage
    runs-on: ubuntu-latest
    timeout-minutes: 25
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
      - name: Run unit tests and enforce coverage thresholds
        run: ./gradlew test jacocoTestReport jacocoTestCoverageVerification --parallel --dependency-verification strict --no-daemon
      # Publish unit and coverage evidence step (변경 없음)
```

api-integration(87-112행):

```yaml
  api-integration:
    name: 3. API integration
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
      - name: Run API integration tests in an isolated runner
        env:
          TESTCONTAINERS_REUSE_ENABLE: 'false'
        run: ./gradlew :apps:api:integrationTest --dependency-verification strict --no-daemon
      - name: Publish API integration evidence
        if: always()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: api-integration-${{ github.sha }}
          path: apps/api/build/reports/tests/integrationTest/**
          if-no-files-found: warn
          retention-days: 14
```

batch-integration(114-137행): `    needs: compile-architecture` 줄 삭제, setup-gradle step 을 아래로. 나머지 그대로.

```yaml
      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
```

static-analysis(141행)·dependency-security(182행)·docker-build(203행): `    needs: compile-architecture` 줄만 삭제.

파일 끝(docker-build 의 마지막 step 뒤, 빈 줄 하나 다음)에 새 job:

```yaml
  common-integration:
    name: 8. Common integration
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@ac638b010cf58a27ee6c972d7336334ccaf61c96 # v4.4.1
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
      - name: Run common migration and integration tests in an isolated runner
        env:
          TESTCONTAINERS_REUSE_ENABLE: 'false'
        run: ./gradlew :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest --dependency-verification strict --no-daemon
      - name: Publish common integration evidence
        if: always()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: common-integration-${{ github.sha }}
          path: infrastructure/common/build/reports/tests/integrationTest/**
          if-no-files-found: warn
          retention-days: 14
```

- [x] **Step 2: workflow 수정 → GREEN**

```bash
bash ci/quality-gate-contract-test.sh
bash docker/deploy-contract-test.sh
bash ci/local-offline-contract-test.sh
grep -c '^    needs:' .github/workflows/quality-gate.yml
```

예상: 세 스크립트 exit 0(`quality gate and supply-chain contracts verified`, deploy 스크립트 성공 출력, `offline Gradle configuration is independent of CI-only tools`).
마지막 grep 출력 `0`.

- [x] **Step 3: 로컬 unit 병렬 확인**

```bash
./gradlew test --parallel --offline --no-daemon
count_tests $(find . -path '*/build/test-results/test/TEST-*.xml')
```

예상: `BUILD SUCCESSFUL`, tests = `B_unit`, failures=0 errors=0.

- [x] **Step 4: 커밋** — `chore: Quality Gate job 병렬화와 common 통합 테스트 job 분리`

---

## Task 6 — 하네스·정본 문서 갱신 (design D8)

**Files:**
- Modify: `.claude/skills/orchestrator/SKILL.md` (⑩ 블록 462-469)
- Modify: `.claude/agents/qa-agent.md` (실행 명령 20-28, 에러 핸들링 79)
- Modify: `.claude/skills/qa-verification/SKILL.md` (실행 순서 12-23)
- Modify: `.claude/skills/test-strategy/SKILL.md` (18-24)
- Modify: `.claude/agents/code-reviewer.md` (15)
- Modify: `.ai/rules/testing.md` (Test task와 명령 절, 격리 규칙 절)
- Modify: `AGENTS.md` (Quick Commands 52-56)
- Modify: `.ai/instructions.md` (테스트와 검증 69-73)
- Modify: `docs/harness-changelog.md` (표 맨 위 한 행)

**전체 검증 명령(모든 문서 공통, 문자 하나도 다르지 않게):**

```text
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
```

**재실행 문구(모든 문서 공통):** "실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌린다."

**변경 전문:**

`.claude/skills/orchestrator/SKILL.md` ⑩ 코드 블록과 바로 뒤 문단:

````markdown
```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
bash docs/check-documentation.sh
```

Gradle 은 **한 번만** 부른다. `--parallel` 로 모듈 task 가 동시에 돌고, `--continue` 로 한 task 가 실패해도 나머지를
끝까지 돌려 실패 전체를 한 번에 본다. 결과는 task 별(unit 합계 · common/api/batch integration · verifyMigrations)로
실행 수와 실패 수를 나눠 보고하고, 실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌린다.
````

(그 뒤 "**`./gradlew lint`은 이 저장소에 없다.**" 문단부터는 그대로.)

`.claude/agents/qa-agent.md` 실행 명령 블록:

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
./gradlew :{module}:test --offline --no-daemon            # 점진 검증
bash docs/check-documentation.sh
```

`.claude/agents/qa-agent.md` 79행(에러 핸들링) "- 전체 실행이 오래 걸리면 모듈별로 나눠 돌리고 소요 시간을 함께 보고한다." 를 아래로:

```markdown
- 전체 검증은 한 번에 돌린다. 실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌리고 소요 시간을 함께 보고한다.
```

`.claude/skills/qa-verification/SKILL.md` 실행 순서 블록과 첫 bullet 추가:

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
bash docs/check-documentation.sh
```

```markdown
- 한 번의 호출이라도 결과는 task 별(unit 합계, common/api/batch integration, verifyMigrations)로 실행 수·실패 수를 나눠
  적는다. 실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌린다.
```

(기존 bullet 3개는 그 아래 그대로.)

`.claude/skills/test-strategy/SKILL.md` 18-24행 블록과 뒤 한 문장:

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
```

```markdown
실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌린다.
```

`.claude/agents/code-reviewer.md` 15행: `./gradlew test architectureTest --parallel --offline --no-daemon`

`.ai/rules/testing.md` "## Test task와 명령" 절 전체:

````markdown
## Test task와 명령

로컬은 기존 cache만 사용한다. 전체 검증은 Gradle을 한 번만 부른다.

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
```

실패한 task만 다시 돌릴 때:

```bash
./gradlew test architectureTest --parallel --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
```

- 각 모듈 `test`: unit/context/contract test, `integration` tag 제외
- root `architectureTest`: 독립 architecture source set의 모듈 dependency, source/import, bytecode 경계
- `*:integrationTest`: `@Tag("integration")`, Docker/Testcontainers 필요
- `--parallel`이면 서로 다른 모듈의 unit·통합 task가 동시에 돈다. task끼리 컨테이너·DB·포트를 공유한다고 가정하지 않는다.
- `--continue`는 실패한 task가 있어도 나머지를 끝까지 실행해 실패 전체를 한 번에 보여 준다.
````

`.ai/rules/testing.md` "## 격리 규칙" 절 끝에 bullet 추가(최종 문구는 `.ai/rules/testing.md` 가 정본):

```markdown
- 같은 테스트 JVM 안의 Testcontainers singleton(모듈 공용 컨테이너)은 허용한다. 정리는 JVM 종료 시 Ryuk가 맡는다(명시적 stop 없음).
  Redis도 JVM당 컨테이너 1개이며, Spring에는 컨테이너를 bean으로 노출하지 않고 `spring.data.redis.host/port` property로만
  연결한다(context close 시 stop되지 않게).
- run 사이의 reuse는 금지다. 수단은 모든 Test task의 환경 변수 `TESTCONTAINERS_REUSE_ENABLE=false`이고
  `verifyTestIsolationPolicy`가 이 env를 검사한다. Testcontainers는 system property `testcontainers.reuse.enable`을
  읽지 않고(env → `~/.testcontainers.properties` 순), 개발자의 `~/.testcontainers.properties`가 reuse를 켜 두면 api·batch
  JVM이 한 컨테이너를 공유해 서로의 데이터를 지운다. fixture는 `withReuse`를 쓰지 않는다.
- 테스트 MySQL 컨테이너는 내구성 튜닝(`MySqlTestTuning`: tmpfs datadir, flush-log-at-trx-commit=0, doublewrite=0, sync-binlog=0)을
  허용한다. 트랜잭션 가시성·락·제약·charset·시간대 의미는 바꾸지 않고 crash 내구성만 포기한다. `--skip-log-bin`은 쓰지 않는다.
- common 통합 테스트 클래스는 공용 MySQL에서 `SharedMySqlContainer.database(...)`로 자기 database를 따로 받는다.
- 공용 Spring context를 쓰는 batch 통합 테스트는 매 테스트 전에 base가 공용 상태(DB·Redis·MockWebServer)를 초기화한다.
  상태를 남기는 공용 bean을 새로 추가하면 base 초기화에 넣는다.
```

`AGENTS.md` 52-56행 다섯 줄 → 전체 검증 명령 한 줄(51행 `compileKotlin` 과 58행 이후는 그대로).
`.ai/instructions.md` 69-73행 다섯 줄 → 전체 검증 명령 한 줄(68행 `compileKotlin`, 74행 `check-documentation.sh` 그대로).

`docs/harness-changelog.md` 표 헤더 바로 아래(최신 행이 위). 첫 칸 날짜는 커밋하는 날의 `date +%F` 값이다:

```markdown
| YYYY-MM-DD | 검증 명령을 Gradle 한 번 호출(`--parallel --continue`)로 통합 — orchestrator ⑩·qa-agent·qa-verification·test-strategy 의 직렬 5개 명령을 한 줄로 교체, qa-agent 의 "모듈별로 나눠 돌리기" 문구를 실패 task 개별 재실행으로 통일, code-reviewer unit 게이트에 `--parallel` 추가 | `.claude/skills/orchestrator/SKILL.md`, `.claude/agents/qa-agent.md`, `.claude/skills/qa-verification/SKILL.md`, `.claude/skills/test-strategy/SKILL.md`, `.claude/agents/code-reviewer.md` | issue #104 — 직렬 5회 호출 합 ~17m. 같은 검증 범위를 병렬 한 번 호출로 돌린다. `--continue` 로 실패 전체를 한 번에 본다. 정본(`.ai/rules/testing.md`·`AGENTS.md`·`.ai/instructions.md`)도 같은 명령으로 맞췄다. 전후 측정은 `docs/work/test-time-reduction/dod.md` 증거 로그 |
```

- [x] **Step 1: 9개 파일 수정** — `docs/harness-changelog.md`·`CLAUDE.md` 는 CRLF 다. 기존 줄바꿈을 유지한다(LF 로 바꾸면 행 끝 앵커 검사가 달라진다).
- [x] **Step 2: 게이트**

```bash
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
bash docs/check-documentation.sh
grep -rnF ':apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon' .claude AGENTS.md .ai/rules/testing.md .ai/instructions.md
grep -rnE '^\./gradlew :infrastructure:common:integrationTest --offline --no-daemon$' .claude/skills/orchestrator .claude/agents/qa-agent.md .claude/skills/qa-verification .claude/skills/test-strategy AGENTS.md .ai/instructions.md
```

예상: 첫 두 명령 exit 0(`documentation check passed (...)`). 셋째 grep 이 7개 파일(orchestrator SKILL, qa-agent, qa-verification, test-strategy,
testing.md, AGENTS.md, instructions.md)에서 각 1건 이상. 넷째 grep 출력 없음(exit 1) — 개별 명령은 `.ai/rules/testing.md` 에만 남는다. (AC9)

- [x] **Step 3: 커밋** — `docs: 검증 명령을 Gradle 한 번 호출과 --parallel 로 통합`

---

## Task 7 — 최종 측정과 증거 (커밋은 오케스트레이터)

**Files:** `docs/work/test-time-reduction/dod.md` 증거 로그만.

- [x] **Step 1: 회귀 방어선 (작업 worktree)**

```bash
./gradlew verifyTestIsolationPolicy --offline --no-daemon
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
bash ci/quality-gate-contract-test.sh
bash ci/local-offline-contract-test.sh
bash docker/deploy-contract-test.sh
bash docs/check-documentation.sh
```

예상: 전부 exit 0 (R1~R5, AC8 GREEN).

- [x] **Step 2: 최종 SHA 콜드 worktree 생성**

```bash
FINAL_SHA="$(git rev-parse HEAD)"
git -C /home/yeop/IdeaProjects/premium-spread worktree add --detach /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-measure "${FINAL_SHA}"
cd /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-measure
```

- [x] **Step 3: ⑩ 1회 (AC2·AC4·AC5, R6~R8)** — 측정 중 다른 무거운 작업을 돌리지 않는다.

```bash
time ./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
count_tests $(find . -path '*/build/test-results/test/TEST-*.xml')
count_tests infrastructure/common/build/test-results/integrationTest/TEST-*.xml
count_tests apps/api/build/test-results/integrationTest/TEST-*.xml
count_tests apps/batch/build/test-results/integrationTest/TEST-*.xml
count_contexts
```

예상: `BUILD SUCCESSFUL`, `real` ≤ 7m00s. tests: unit = `B_unit`, common = `B_common` + 3, api = `B_api`, batch = `B_batch` + 2, 모두 failures=0 errors=0,
skipped = 기준선. contexts ≤ 9.

- [x] **Step 4: common IT 단독 (AC1)**

```bash
time ./gradlew :infrastructure:common:integrationTest --rerun --offline --no-daemon
```

예상: `BUILD SUCCESSFUL`, `real` ≤ 4m00s.

- [x] **Step 5: unit 메서드 시간 (AC10)**

```bash
./gradlew :infrastructure:batch:test --rerun --offline --no-daemon
suite_attrs infrastructure/batch BithumbPrivateAccountAdapterTest
suite_attrs infrastructure/batch ExchangeRateClientTest
suite_attrs infrastructure/batch BinanceFuturesManualAccountAdapterTest
case_times infrastructure/batch BithumbPrivateAccountAdapterTest
case_times infrastructure/batch ExchangeRateClientTest
```

예상: Bithumb·ExchangeRate `tests` 기준선과 같고, AC10 대상 메서드 4개 각각의 `time` ≤ 기준선 − 0.8s. Binance 는 기록만.

- [x] **Step 6: 측정 worktree 제거, 증거 기록**

```bash
cd /home/yeop/IdeaProjects/premium-spread/.worktrees/chore-issue-104-test-time-reduction
git -C /home/yeop/IdeaProjects/premium-spread worktree remove /home/yeop/IdeaProjects/premium-spread/.worktrees/issue-104-measure
```

dod 증거 로그 AC1·AC2·AC4·AC5·AC10 에 명령·출력·SHA 를 붙인다. 기준선 대비 표(⑩ 합계, common IT, batch context 수)를 AC2 아래에 둔다.

- [ ] **Step 7: PR 생성 후 CI 측정 (AC3·R9·R10)** — PR 이 생기기 전까지 AC3 은 `AWAITING` 이다.

```bash
gh run list --workflow 'Quality Gate' --branch chore/issue-104-test-time-reduction --limit 1 --json databaseId,headSha,status,conclusion
gh run view <databaseId> --json jobs --jq '{start: ([.jobs[].startedAt] | min), end: ([.jobs[].completedAt] | max), jobs: [.jobs[] | {name, conclusion, startedAt, completedAt}]}'
```

`<databaseId>` 는 바로 위 명령 출력의 값이다. 예상: `headSha` = 최종 SHA, 8 job 전부 `success`, `end − start` ≤ 6m00s. run URL 을 AC3 증거
앵커로 기록한다. 초과하면 job 별 시간을 기록하고 FAILED 로 판정한다(재실행으로 좋은 값을 고르지 않는다).

---

> 개정: ⑥ spec-reviewer 1R 7건 반영 — AC10 메서드별 기준(`case_times` 추가, Task 0·1·7), Task 2·3·4 라벨 "시그니처 계약",
> Task 6 에 qa-agent 79행·재실행 문구 통일·CRLF 유지·Ryuk 정리 문장, AC9 둘째 grep 에 test-strategy 포함.
