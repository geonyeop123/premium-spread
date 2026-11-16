# Plan — 실계정 포지션 관측 이력 보존 기간 정책 (#148)

Spec: `docs/work/issue-148-observation-retention/design.md` · 계약: `docs/work/issue-148-observation-retention/dod.md` (FROZEN)

## Global Constraints

- 대상 테이블: `account_pnl_observation`, `account_foreign_leg_observation`, `account_korea_leg_observation`. 건·사건 테이블은 절대 지우지 않는다.
- 삭제 조건 `observed_at < cutoff`, cutoff = `clock.instant() − retention`. 오래된 순(`ORDER BY observed_at, id`).
- 배치 1회 = 트랜잭션 1개: 관측 SELECT(LIMIT) → 두 레그 테이블 DELETE(관측 키) → 관측 DELETE(id). 반환 = 지운 관측 수.
- JobId: `ACCOUNT_OBSERVATION_RETENTION("account_observation_retention")`. lock spec `batch.jobs.account-observation-retention`:
  lock-key `lock:account-pnl:observation-retention`, lease 150s, execution-timeout 120s
  (env `ACCOUNT_OBSERVATION_RETENTION_LOCK_KEY`·`_LEASE`·`_TIMEOUT`, 다른 jobs 행과 같은 형식).
- 설정 prefix `account-pnl-monitor.position-history.retention`:
  `enabled`(true, env `ACCOUNT_PNL_OBSERVATION_RETENTION_ENABLED`), `retention`(365d, `ACCOUNT_PNL_OBSERVATION_RETENTION`),
  `batch-size`(1000, 1..10000, `ACCOUNT_PNL_OBSERVATION_RETENTION_BATCH_SIZE`), `max-batches-per-run`(50, 1..10000, `ACCOUNT_PNL_OBSERVATION_RETENTION_MAX_BATCHES_PER_RUN`),
  `interval`(1h, `ACCOUNT_PNL_OBSERVATION_RETENTION_INTERVAL`, Scheduler `fixedDelayString`).
- 시작 검증: `retention <= maxAge + 1d` 이면 `IllegalStateException`. `maxAge` 는 `AccountRealizedPnlProperties` bean 이 아니라
  `Binder.get(environment).bind("account-pnl-monitor.realized-pnl.max-age", Duration::class.java).orElse(Duration.ofDays(90))`.
  메시지에는 설정 키 이름만(값은 넣어도 무방 — 실계정 값 아님).
- 배선은 `account-pnl-monitor.enabled` 와 무관하다. `@ConditionalOnBatchScheduling` + `retention.enabled`(없으면 true).
- 로그: 지운 관측 수·배치 수·상한만. 가격·금액 없음. 예외는 클래스명만.
- 배치 Job 규칙 `.ai/rules/batch.md`: Scheduler 는 Job 한 번 호출, Job 은 Domain port 만 주입.

## Task 1 — domain port + store (AC1, AC2)

- `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt`: `JobId.ACCOUNT_OBSERVATION_RETENTION` 추가.
  `JobId` 를 `when` 으로 다 쓰는 곳(`BatchJobProperties.get` 등)은 Task 2 에서 맞춘다 — 이 태스크에서 컴파일이 깨지면 `BatchJobProperties` 에 lock spec 까지 같이 추가한다.
- 신규 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountObservationRetentionStore.kt`: `fun interface AccountObservationRetentionStore { fun deleteObservedBefore(cutoff: Instant, limit: Int): Int }` (KDoc: Global Constraints 의 삭제 계약).
- 신규 `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountObservationRetentionStore.kt`: `@Repository`, `JdbcTemplate` + 자체 `TransactionTemplate`. `limit` 은 1 이상 require.
- 신규 통합 테스트 `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountObservationRetentionStoreIntegrationTest.kt` (기존 `JdbcAccountPositionHistoryStoreIntegrationTest` 의 fixture·DB 준비 방식 재사용):
  - `cutoff 이전 관측과 그 레그만 지우고 cutoff 이후와 건 사건은 남는다` (경계: `observed_at = cutoff` 행은 남음)
  - `limit 만큼 오래된 순으로 지우고 반환값은 지운 관측 수다`
  - `레그가 없는 FLAT 관측도 지운다`
  - `지울 것이 없으면 0 이다`
  - `관측 DELETE 가 실패하면 같은 배치의 레그 DELETE 도 롤백된다` (관측 DELETE 문에서 던지는 JdbcTemplate 하위 클래스)
- 검증: `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountObservationRetentionStoreIntegrationTest --offline --no-daemon`

## Task 2 — Job + 설정 + 배선 (AC3~AC6)

- 신규 `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountObservationRetentionJob.kt`:
  생성자 `(store, executor: JobExecutor, clock, settings: Settings, jobConfigs: JobConfigProvider = DefaultJobConfigProvider)`,
  `data class Settings(retention, batchSize, maxBatchesPerRun)`, `fun run(): JobResult`. 동작은 design D4. `AccountRealizedPnlJob` 의 executor·취소 처리 관용을 따른다.
- `apps/batch/src/main/kotlin/io/premiumspread/config/BatchJobProperties.kt`: `accountObservationRetention` LockSpec 과 `get` 분기. `DefaultJobConfigProvider` 도 JobId 를 다 다루면 같이 추가.
- 신규 `apps/batch/src/main/kotlin/io/premiumspread/config/AccountObservationRetentionConfiguration.kt`: Properties(`@ConfigurationProperties`, 검증 annotation), 조건 클래스(`retention.enabled` 없으면 true), Job bean, 시작 검증(Global Constraints).
- 신규 `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/AccountObservationRetentionScheduler.kt`.
- `apps/batch/src/main/resources/application.yml`: `batch.jobs.account-observation-retention` 행, `account-pnl-monitor.position-history.retention.*` 블록(env 기본값 Global Constraints 그대로).
- 테스트:
  - `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountObservationRetentionJobTest.kt` (AC3):
    `cutoff 는 현재 시각에서 보존 기간을 뺀 값이다`, `배치가 batch-size 보다 적게 지우면 멈춘다`, `max-batches-per-run 에 닿으면 더 부르지 않고 경고한다`,
    `JobId ACCOUNT_OBSERVATION_RETENTION 설정으로 executor 를 거친다`, `store 예외면 Failure 다`, `interrupt 가 서 있으면 다음 배치를 부르지 않는다`.
  - `apps/batch/src/test/kotlin/io/premiumspread/config/AccountObservationRetentionConfigurationTest.kt` (AC4~AC6, `ApplicationContextRunner` — `AccountRealizedPnlWiringTest` 패턴):
    `보존 기간이 max-age 더하기 1일 이하면 시작하지 않는다`(91d 실패·92d 성공), `max-age 를 바꾸면 경계도 따라간다`,
    `모니터가 꺼져 있어도 사용자 max-age 로 검증한다`, `정산이 꺼져 있어도 사용자 max-age 로 검증한다`,
    `enabled false 면 scheduler 가 없다`, `모니터가 꺼져 있어도 기본값이면 scheduler 가 있다`, `설정이 없으면 기본값 365d 1000 50 으로 시작한다`.
- 검증: `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountObservationRetentionJobTest --tests io.premiumspread.config.AccountObservationRetentionConfigurationTest --offline --no-daemon`, 그리고 `./gradlew :apps:batch:test architectureTest --offline --no-daemon`.
- batch 통합 테스트 context 가 새 bean 때문에 깨지지 않는지 `./gradlew :apps:batch:integrationTest --offline --no-daemon` 1회.

## 최종 게이트

- `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon`
- `bash docs/check-documentation.sh`
- lint 는 CI(`5. ktlint + detekt`) — 함수 20개 이하·인자 줄바꿈 규칙(#139 에서 걸림)을 미리 지킨다.
