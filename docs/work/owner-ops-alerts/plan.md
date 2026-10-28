# owner 운영 알림 — 환율 수집 지연·시세 수집 끊김·Job 연속 실패 (issue #91) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** batch 가 환율 수집 지연·시세 수집 끊김·Job 연속 실패를 지속 상태로 감지해 owner Discord ops 채널(·메일)로 알리고, 6시간마다 재알림·해소 시 무음 복구를 보낸다. PnL 모니터가 꺼져 있어도 동작한다.

**Architecture:** Domain `ownerops` 가 신호 모델·3상태 관측 함수·장애 정책·렌더러를 소유하고, Domain `accountpnl` 의 범용 port `OwnerAlertOutbox` 가 신호 상태(V21 `owner_alert_signal_state`)와
outbox 행(`account_pnl_mail`)을 한 트랜잭션에 쓴다(infra:common `JdbcOwnerAlertOutbox`, `CommonJdbcConfiguration` `@Import` 등록). Job 연속 실패는 infra:batch `RedisJobRunRecorderAdapter` 의
in-memory 카운터를 `JobFailureStreakReadPort` 로 읽는다. 새 `OwnerOpsSignalJob`(30s)이 판정·enqueue 하고 새 `OwnerAlertDeliveryJob`(15s)이 기존 `OwnerAlertDispatcher` 로 보낸다.
채널 bean 3개는 PnL 모니터 구성에서 `OwnerAlertChannelConfiguration` 으로 옮긴다.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.5 (`@ConfigurationProperties`, `Condition`, `ObjectProvider`, `@Scheduled`), JDBC `TransactionTemplate`, Flyway·MySQL 8 (Testcontainers), JUnit5 + AssertJ + MockK, `ApplicationContextRunner` + `OutputCaptureExtension`.

**Spec:** `docs/work/owner-ops-alerts/design.md` · **DoD:** `docs/work/owner-ops-alerts/dod.md` · ⑥ R1 반영 내역은 design §15·dod `## 스펙 리뷰 라운드 기록`

## Global Constraints

- base `20a7484`(Task 0 확인). 직전 단위 산출물(`docs/work/owner-alert-channel/**`·`docs/work/owner-account-alerts/**` 등)은 수정하지 않는다.
- **동결 테스트 무수정** (dod R3). 이 테스트들이 컴파일·통과하도록:
  - `AccountPnlAlertStore` interface 무변경. `AccountPnlMonitorJob`·`OwnerAlertDispatcher` main 소스 무변경.
  - `AccountPnlMail`·`ClaimedAccountPnlMail` 생성자·프로퍼티 무변경.
  - `JdbcAccountPnlAlertStore` 의 SQL 문자열·검증 메시지·상수 값 무변경(위치만 옮긴다, dod R6).
- 허용 예외는 dod R7(`OwnerAlertRoutingTest` 기대 목록 끝 4개)·R8·R9 뿐. **R8 의 diff 는 `AccountPnlMonitorWiringTest.kt` 한 파일에서 정확히 둘** — (1) runner 목록(`:72-79`)에
  `OwnerAlertChannelConfiguration::class.java` 추가(+import), (2) `assertNoBeans()`(`:81-91`)에서 옮겨진 채널 bean 부재 단언(`:89` `AccountPnlEmailSenderAdapter` 및 같은 함수 안의 다른 채널 bean
  부재 단언) 삭제(+미사용 import 삭제). PnL 전용 부재 단언(`:83-88`)·다른 메서드·fixture 무변경. 그 밖의 기존 테스트를 고쳐야 하면 **멈추고 보고**한다.
- `OwnerAlertKind`·`JobId` 새 값은 enum **끝에** 추가. `OwnerAlertKind` 전체 `when` 금지(`OwnerAlertRouting.kt:18`).
- Domain 에 Spring/JDBC/Redis 의존 추가 금지(`ownerops` 는 `java.time` + 기존 domain 타입만). 앱 main 은 infrastructure 타입을 import 하지 않는다. 앱에 `infrastructure`·`cache`·`repository` 패키지 금지.
- webhook URL·token·수신 주소·API key·예외 메시지를 알림 본문·로그·예외 메시지·`toString`·DB 에 남기지 않는다. 새 로그는 design §7.2·§8.2·§9.2 의 문구·키만.
- 테스트: 고정 `Clock`(기준 `2026-10-28T00:00:00Z`)·`ZoneId.of("Asia/Seoul")`, URL 은 `https://discord.example.test/...` 또는 MockWebServer, 주소는 `owner@example.test`. 실 webhook·실 key 금지(`TestIsolationArchitectureTest`).
  시간을 전진시키는 테스트의 fake port(마지막 성공·tick·streak)는 **`MutableClock` 기준 상대 시각**(예 `clock.instant() − 1분`)을 돌려준다 — 고정 절대 시각을 쓰면 시계를 6시간 전진할 때 다른 신호가 장애로 오염된다.
- migration 은 V21 **추가만**. Redis key·TTL·payload, `PublicEndpointPolicy`, Slack `OperatorAlert` 경로 무변경.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 변이에서의 **assertion 실패**다. RED·GREEN 로그를 dod `## 증거 로그` 에 append 한다.
- 각 태스크 커밋 시점에 `./gradlew test architectureTest --offline --no-daemon` 이 초록이어야 한다. integrationTest 소스도 컴파일이 깨진 채로 두지 않는다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령은 `--offline --no-daemon`. ktlint·detekt 는 로컬 실행 불가 — CI 확인(dod R14), 우회 금지.

## Review Focus

1. **3상태 관측과 재시작** — 재시작 직후 `Unknown` 이 DB 의 장애를 가짜로 닫지 않는가, `firstRunAt` 대기 후에만 `Down` 이 되는가(Task 4·12·14).
2. **경계값** — FX `>` 30분(정확히 30분 미발동), 시세·Job·재알림 `≥`(정확히 5분·6시간 발동). 모든 시각이 주입 `Clock`(Task 4·5).
3. **원자성** — 신호 상태 + outbox 행이 한 트랜잭션이고 두 번째 신호 충돌이 첫 신호까지 되돌리는가(Task 8). bean 이 실제 auto-configuration 으로 해석되는가(Task 8 AC7-i).
4. **이중 발송 없음** — 전달 Job 과 PnL 회차 디스패처가 동시에 돌 때 행당 발송 1회(Task 14). `OwnerAlertDispatcher` 무변경.
5. **채널 bean 이동** — PnL 꺼짐에도 ready 로만 뜨는가(AC15), R8 diff 가 위 두 종류뿐인가, app 조건 = infra 조건인가(Task 10·13).
6. **카운터 내성** — Redis 쓰기 예외에도 카운트가 남고 Skipped 가 무변경인가(Task 9).
7. **취소·비노출** — 관측 중 취소가 삼켜지지 않는가(Task 12), 본문·로그에 예외 메시지·URL·주소 없음(Task 6·12·13).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt` | enum 4개 추가 | 1 |
| `apps/batch/src/main/resources/application.yml` | routes 4블록(1), jobs·scheduling(2), `owner-alert.ops`(13) | 1·2·13 |
| `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt` | `JobId` 2개, `JobFailureStreak`, `JobFailureStreakReadPort` | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/common/JobConfig.kt` | `DefaultJobConfigProvider` 2행 | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/BatchJobProperties.kt` | 2필드·2행 | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/BatchSchedulingProperties.kt` | 2필드 | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertOutbox.kt` (신규) | `OwnerAlertIncident`·`OwnerAlertSignalState`·`OwnerAlertOutbox` | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OpsSignal.kt` (신규) | `OpsSignal`·`OpsObservation`·`OpsAlertSettings`·`OpsSignals` | 4 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OpsIncidentPolicy.kt` (신규) | `OpsNotice`·`OpsIncidentDecision`·`OpsIncidentPolicy` | 5 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContent.kt` (신규) | eventKey·메일·Discord 렌더링 | 6 |
| `infrastructure/common/src/main/resources/db/migration/V21__create_owner_alert_signal_state.sql` (신규) | DDL | 7 |
| `apps/batch/src/integrationTest/resources/batch-schema.sql` | V21 테이블(`IF NOT EXISTS`) | 7 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/OwnerAlertOutboxRows.kt` (신규) | outbox INSERT·검증 단일 소유자 | 8 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt` | `insertMail`·`validate` 를 `OwnerAlertOutboxRows` 위임 | 8 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcOwnerAlertOutbox.kt` (신규) | port 구현 | 8 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/CommonInfrastructureAutoConfiguration.kt` | `CommonJdbcConfiguration` `@Import` 에 `JdbcOwnerAlertOutbox::class` 추가 | 8 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/job/RedisJobRunRecorderAdapter.kt` | 연속 실패 카운터 | 9 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelConfiguration.kt` (신규) | 채널 bean 3개·`OwnerAlertChannelReadyCondition` | 10 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorAdapterConfiguration.kt` | bean 3개·`describeRoutes`·`OwnerAlertProperties` 바인딩 제거 | 10 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/BatchInfrastructureAutoConfiguration.kt` | `@Import` 1개 추가 | 10 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDeliveryJob.kt` (신규) | 전달 Job | 11 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJob.kt` (신규) | 운영 신호 Job | 12 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerAlertDeliveryConfiguration.kt` (신규) | app 조건 2개·구성 2개 | 13 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerOpsAlertProperties.kt` (신규) | `owner-alert.ops.*` 바인딩 | 13 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/OwnerAlertDeliveryScheduler.kt`·`OwnerOpsSignalScheduler.kt` (신규) | trigger | 13 |
| `docs/runbooks/owner-ops-alert.md` (신규), `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`, (Task 0 이 찾은 env 예시 파일) | 문서 | 15 |
| tests — domain: `OwnerOpsAlertKindTest`(owneralert), `OwnerAlertSignalStateTest`(accountpnl), `OpsSignalsTest`·`OpsIncidentPolicyTest`·`OwnerOpsAlertContentTest`(ownerops), 허용 예외 `OwnerAlertRoutingTest`; infra:common: `V21OwnerAlertSignalStateMigrationIntegrationTest`·`JdbcOwnerAlertOutboxIntegrationTest`; infra:batch: `RedisJobRunRecorderFailureStreakTest`·`OwnerAlertChannelConfigurationTest`; apps:batch: `OwnerAlertJobConfigTest`·`OwnerAlertDeliveryJobTest`·`OwnerOpsSignalJobTest`(+ `OwnerOpsTestFakes.kt`)·`OwnerAlertChannelIndependenceWiringTest`·`OwnerAlertDeliveryWiringTest`·허용 예외 `AccountPnlMonitorWiringTest`(R8)·`OwnerOpsAlertIntegrationTest` | | 1~14 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  git rev-parse --short HEAD && git merge-base HEAD origin/dev | cut -c1-7                    # base 20a7484 확인
  ./gradlew test architectureTest --offline --no-daemon                                     # R1·R2 — 테스트 수 기록(build/test-results XML 집계)
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon                   # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                    # R10 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                               # R11 (Docker)
  ./gradlew :apps:api:integrationTest --offline --no-daemon                                 # R13 (Docker)
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1              # (g1) V21 번호 근거
  grep -rn "findLatest(" --include=*.kt apps/batch/src/main                                 # (g3) TickerFlushJob 의 (exchange, quote) — 확인된 사실 A3 재확인
  grep -rln "AccountPnlMonitorAdapterConfiguration" --include=*.kt apps/batch/src/test apps/batch/src/integrationTest infrastructure/batch/src/test  # (g4) R8 대상 목록
  grep -n "containsExactly" domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt   # (g5) R7 줄 번호
  grep -rn "JobId.entries\|JobId.values()" --include=*.kt apps domain infrastructure architecture-tests          # (g6) JobId 개수 단언
  grep -rn "information_schema.tables\|TABLE_NAME" apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt  # (g7) R9 필요 여부
  grep -rn "OwnerAlertProperties" --include=*.kt apps/batch/src/test infrastructure/batch/src/test               # (g8) A5 — PnL 꺼짐 미바인딩 단언 여부
  grep -rln "OWNER_ALERT_DISCORD_ENABLED" .env.example deploy docker docs/runbooks          # (g10) env 예시 갱신 대상
  grep -rn "account_pnl_monitor" docs/runbooks .ai                                          # (g11) job tag 목록 갱신 대상
  grep -rn "fixedRateString" --include=*.kt apps/batch/src/main                             # (g12) Scheduler trigger 패턴
  grep -rn "JobExecutor(" --include=*.kt apps/batch/src/test/kotlin/io/premiumspread/application  # (g13) 단위 테스트의 executor 구성
  grep -rn "doesNotHaveBean(" --include=*.kt apps/batch/src/test infrastructure/batch/src/test | grep -E "EmailSenderAdapter|OwnerAlertRouting|OwnerAlertDiscordSender|DiscordWebhookSenderAdapter"  # (g14) R8 — 채널 bean 부재 단언 위치
  grep -rln "CommonInfrastructureAutoConfiguration" --include=*.kt infrastructure/common/src/integrationTest   # (g15) AC7-i — auto-configuration 을 띄우는 기존 통합 테스트 패턴
  ```
- [ ] **Step 2: 예상 결과.** merge-base `20a7484`. gradle 6건 exit 0(Docker 부재면 integration 3건 "미실행", green 금지). (g1) `V20__add_owner_account_alert_state.sql`.
  (g3) `BatchMarket` 의 korea/foreign 두 쌍으로 조회. (g4) `AccountPnlMonitorWiringTest.kt` 하나. (g5) `containsExactly` 2곳. (g6) `when` 외 개수 단언 없음.
  (g8) PnL 꺼짐에서 `OwnerAlertProperties` 미바인딩을 단언하는 테스트 없음. (g14) `AccountPnlMonitorWiringTest.kt:89` 한 줄(같은 파일의 `assertNoBeans()` 안)뿐.
  **다르면 멈추고 보고한다** — (g1) 이 V20 이 아니면 V21 번호·dod AC6, (g4)/(g14) 가 다른 파일·줄을 보이면 dod R8 범위를 Gate 1 에서 다시 정한다, (g7) 결과로 R9 필요 여부 확정,
  (g8) 이 다르면 design §9.3·A5 를 다시 정한다. (g5)·(g7)·(g10)·(g11)·(g12)·(g13)·(g14)·(g15) 결과와 R3 의 정확한 경로 목록을 증거 로그에 남긴다.
- [ ] **Step 3: Commit** — `docs: owner 운영 알림 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: `OwnerAlertKind` 4종과 yml routes (AC1)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt`, `apps/batch/src/main/resources/application.yml`(routes 4블록만);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerOpsAlertKindTest.kt`;
허용 예외(R7) Modify `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt`(Task 0 (g5) 줄만)

**Interfaces — Produces (전문, `UNRECOVERED_REMINDER` 뒤에 추가):**

```kotlin
    FX_INGESTION_DELAYED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    MARKET_STREAM_DOWN(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    JOB_CONSECUTIVE_FAILURES(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    OPS_RECOVERED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT))),
```

yml (전문, `owner-alert.routes.unrecovered-reminder` 블록 뒤):

```yaml
    fx-ingestion-delayed:
      email: ${OWNER_ALERT_FX_INGESTION_DELAYED_EMAIL:}
      discord: ${OWNER_ALERT_FX_INGESTION_DELAYED_DISCORD:}
      push: ${OWNER_ALERT_FX_INGESTION_DELAYED_PUSH:}
    market-stream-down:
      email: ${OWNER_ALERT_MARKET_STREAM_DOWN_EMAIL:}
      discord: ${OWNER_ALERT_MARKET_STREAM_DOWN_DISCORD:}
      push: ${OWNER_ALERT_MARKET_STREAM_DOWN_PUSH:}
    job-consecutive-failures:
      email: ${OWNER_ALERT_JOB_CONSECUTIVE_FAILURES_EMAIL:}
      discord: ${OWNER_ALERT_JOB_CONSECUTIVE_FAILURES_DISCORD:}
      push: ${OWNER_ALERT_JOB_CONSECUTIVE_FAILURES_PUSH:}
    ops-recovered:
      email: ${OWNER_ALERT_OPS_RECOVERED_EMAIL:}
      discord: ${OWNER_ALERT_OPS_RECOVERED_DISCORD:}
      push: ${OWNER_ALERT_OPS_RECOVERED_PUSH:}
```

허용 예외(R7): `OwnerAlertRoutingTest` 의 종류 이름 기대 목록 끝에 `"FX_INGESTION_DELAYED", "MARKET_STREAM_DOWN", "JOB_CONSECUTIVE_FAILURES", "OPS_RECOVERED"`,
key 기대 목록 끝에 `"fx-ingestion-delayed", "market-stream-down", "job-consecutive-failures", "ops-recovered"`. 다른 줄 무변경.

**테스트가 재는 것** (`OwnerOpsAlertKindTest`):
- `운영 알림 네 종류의 기본 경로는 설계 표와 같다` — 4종 `defaultRoute` `isEqualTo` (AC1-b)
- `운영 알림 key 는 kebab 이고 fromKey 로 되돌아온다` — 4 key, `fromKey(" Ops-Recovered ")` (AC1-a)
- `메일과 Discord 를 모두 쓰면 기본 경로` / `Discord 가 꺼져 있으면 네 종류 모두 메일만` — `OwnerAlertRouting(OwnerAlertChannels(true, true))`·`EMAIL_ONLY` → `(true, null)` (AC1-c)
- `복구는 무음이고 메일이 없다` — `OPS_RECOVERED` both 채널 해석 `(false, OPS/SILENT)`

- [ ] **Step 1:** enum 값 4개를 **stub 경로** `OwnerAlertRoute(email = true, discord = null)` 로 추가, 허용 예외 수정, yml routes 4블록, 새 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` → RED(기본 경로·both 해석·복구 단언 실패. key·`EMAIL_ONLY` 는 stub 에서도 초록일 수 있다). 증거 로그 AC1.
- [ ] **Step 3:** 경로를 위 전문으로. dod AC1 명령 → PASS(`OwnerAlertPropertiesTest`·`OwnerAlertYamlContractTest` 무수정 통과 포함). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 운영 알림 종류 네 개와 기본 경로`

### Task 2: `JobId` 2종·연속 실패 port·JobConfig·주기 (AC10)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/common/JobConfig.kt`,
`apps/batch/src/main/kotlin/io/premiumspread/config/BatchJobProperties.kt`, `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/BatchSchedulingProperties.kt`,
`apps/batch/src/main/resources/application.yml`(`batch.jobs`·`batch.scheduling`); Test Create `apps/batch/src/test/kotlin/io/premiumspread/config/OwnerAlertJobConfigTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// JobPorts.kt — JobId 끝에
    OWNER_ALERT_DELIVERY("owner_alert_delivery"),
    OWNER_OPS_SIGNAL("owner_ops_signal"),

/** 한 Job 의 현재 연속 실패. Success 에서 사라지고 Skipped 는 바꾸지 않는다. */
data class JobFailureStreak(
    val jobId: JobId,
    val consecutiveFailures: Int,
    val firstFailureAt: Instant,
    val lastFailureAt: Instant,
) {
    init {
        require(consecutiveFailures >= 1) { "consecutiveFailures must be at least 1." }
        require(!lastFailureAt.isBefore(firstFailureAt)) { "lastFailureAt must not be before firstFailureAt." }
    }
}

/** 이 프로세스가 기록한 연속 실패. 없으면 null(실패 없음 또는 재시작 후 아직 실패 없음). */
fun interface JobFailureStreakReadPort {
    fun findFailureStreak(jobId: JobId): JobFailureStreak?
}
```

```kotlin
// DefaultJobConfigProvider.get — when 끝에
        JobId.OWNER_ALERT_DELIVERY -> JobConfig(jobId, "lock:owner-alert:delivery", Duration.ofSeconds(50), Duration.ofSeconds(40))

        JobId.OWNER_OPS_SIGNAL -> JobConfig(jobId, "lock:owner-ops:signal", Duration.ofSeconds(20), Duration.ofSeconds(10))

// BatchJobProperties — 생성자 끝 필드 2개, when 끝 2행
    @field:Valid val ownerAlertDelivery: LockSpec =
    LockSpec("lock:owner-alert:delivery", Duration.ofSeconds(50), Duration.ofSeconds(40)),
    @field:Valid val ownerOpsSignal: LockSpec =
    LockSpec("lock:owner-ops:signal", Duration.ofSeconds(20), Duration.ofSeconds(10)),
        JobId.OWNER_ALERT_DELIVERY -> ownerAlertDelivery
        JobId.OWNER_OPS_SIGNAL -> ownerOpsSignal

// BatchSchedulingProperties — zone 앞에 필드 2개
    @field:Valid val ownerAlertDelivery: FixedRate = FixedRate(Duration.ofSeconds(15)),
    @field:Valid val ownerOpsSignal: FixedRate = FixedRate(Duration.ofSeconds(30)),
```

yml (전문): `batch.scheduling` 의 `account-pnl-monitor` 블록 뒤

```yaml
    owner-alert-delivery:
      fixed-rate: ${OWNER_ALERT_DELIVERY_FIXED_RATE:15s}
    owner-ops-signal:
      fixed-rate: ${OWNER_OPS_SIGNAL_FIXED_RATE:30s}
```

`batch.jobs` 의 `account-pnl-monitor` 행 뒤

```yaml
    owner-alert-delivery: { lock-key: "${OWNER_ALERT_DELIVERY_LOCK_KEY:lock:owner-alert:delivery}", lease: "${OWNER_ALERT_DELIVERY_LEASE:50s}", execution-timeout: "${OWNER_ALERT_DELIVERY_TIMEOUT:40s}" }
    owner-ops-signal: { lock-key: "${OWNER_OPS_SIGNAL_LOCK_KEY:lock:owner-ops:signal}", lease: "${OWNER_OPS_SIGNAL_LEASE:20s}", execution-timeout: "${OWNER_OPS_SIGNAL_TIMEOUT:10s}" }
```

**테스트가 재는 것** (`OwnerAlertJobConfigTest`):
- `새 Job 두 개의 기본 lock 설정` — `DefaultJobConfigProvider.get` 두 값 `isEqualTo` 전문 (AC10-a)
- `두 provider 는 모든 JobId 에서 같은 값` — `JobId.entries.forEach { BatchJobProperties().get(it) == DefaultJobConfigProvider.get(it) }` (AC10-a·c)
- `yml 의 jobs 와 scheduling 값` — `application.yml` 을 `Binder` 로 읽어 두 LockSpec·주기 `15s`/`30s` (AC10-b)

- [ ] **Step 1:** 전문대로 추가하되 **stub** 으로 두 `JobConfig`(Default·Properties 모두)의 lease/timeout 을 `5s`/`3s` 로 둔다. 테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertJobConfigTest' --offline --no-daemon` → RED(기본값 단언 실패). 증거 로그 AC10.
- [ ] **Step 3:** 값을 전문으로. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 운영 알림 Job 식별자와 lock·주기 설정`

### Task 3: 범용 enqueue port 와 신호 상태 타입 (AC3-g)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertOutbox.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertSignalStateTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.owneralert.OwnerAlertKind
import java.time.Instant

/** 진행 중인 장애 한 건. [notifyCount] = 지금까지 보낸 장애 알림 수(첫 알림 포함). */
data class OwnerAlertIncident(val startedAt: Instant, val lastNotifiedAt: Instant, val notifyCount: Int) {
    init {
        require(notifyCount >= 1) { "notifyCount must be at least 1." }
        require(!lastNotifiedAt.isBefore(startedAt)) { "lastNotifiedAt must not be before startedAt." }
    }
}

/**
 * owner 알림 신호 하나의 지속 상태 (owner_alert_signal_state 한 행). [incident] null = 정상.
 * [version] 0 = 아직 행이 없다. 저장은 [OwnerAlertOutbox.enqueue] 가 version 조건부로 한다.
 */
data class OwnerAlertSignalState(
    val signalKey: String,
    val alertKind: OwnerAlertKind,
    val incident: OwnerAlertIncident?,
    val version: Long,
) {
    init {
        require(SIGNAL_KEY_PATTERN.matches(signalKey)) { "signalKey must match [a-z0-9._-]{1,64}." }
        require(version >= 0) { "version must not be negative." }
    }

    val active: Boolean get() = incident != null

    companion object {
        val SIGNAL_KEY_PATTERN: Regex = Regex("[a-z0-9._-]{1,64}")

        fun initial(signalKey: String, alertKind: OwnerAlertKind): OwnerAlertSignalState =
            OwnerAlertSignalState(signalKey, alertKind, null, 0)
    }
}

/**
 * owner 알림 공용 enqueue port — PnL 모니터 밖의 생산자(#91 운영, #90 시장)가 outbox(account_pnl_mail)에 행을 넣는다.
 * 신호 상태 갱신과 행 삽입은 한 트랜잭션이다(DB-first). 외부 발송은 전달 Job 의 claim commit 뒤다.
 */
interface OwnerAlertOutbox {
    /** [signalKeys] 중 저장된 행만 돌려준다. 없는 key 는 map 에 없다. */
    fun loadSignals(signalKeys: Collection<String>): Map<String, OwnerAlertSignalState>

    /**
     * 한 트랜잭션: [signals] 각각을 version 조건부로 쓰고(version 0 = INSERT, 그 밖 = `version = ?` 인 UPDATE, 저장 후 version+1)
     * 모두 성공하면 [rows] 를 넣는다. 같은 (eventKey, channel) 행은 무시한다.
     * 하나라도 version 이 맞지 않으면 아무것도 쓰지 않고 false. [signals] 의 signalKey 는 서로 달라야 한다.
     */
    fun enqueue(signals: List<OwnerAlertSignalState>, rows: List<AccountPnlMail>): Boolean
}
```

자동 `toString` 노출: `OwnerAlertIncident(startedAt, lastNotifiedAt, notifyCount)`, `OwnerAlertSignalState(signalKey, alertKind, incident, version)` — 비밀 없음.

**테스트가 재는 것** (`OwnerAlertSignalStateTest`, AC3-g):
- `signalKey 정규식` — `fx-ingestion.usd-krw`·`job-failures.fx_ingestion`·64자 허용, `Bad Key`·`a:b`·65자·빈 문자열 거부(메시지 전문)
- `version 음수 거부`, `initial 은 incident null·version 0·active false`
- `incident 불변식` — `notifyCount = 0`, `lastNotifiedAt = startedAt − 1ms` 거부, `lastNotifiedAt = startedAt` 허용

- [ ] **Step 1:** 타입을 **stub**(init 블록 비움)으로 작성, 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.OwnerAlertSignalStateTest' --offline --no-daemon` → RED(거부 단언 실패). 증거 로그 AC3.
- [ ] **Step 3:** init 을 전문으로. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 범용 enqueue port 와 신호 상태 타입`

### Task 4: 운영 신호와 3상태 관측 (AC2)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OpsSignal.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OpsSignalsTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.ownerops

import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.domain.job.JobFailureStreak
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketTick
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import java.time.Duration
import java.time.Instant

/** 운영 신호 하나. [key] 는 owner_alert_signal_state.signal_key 이자 eventKey 의 일부다 (design §5.1). */
sealed interface OpsSignal {
    val key: String
    val alertKind: OwnerAlertKind

    /** key = "fx-ingestion.{base}-{quote}" (소문자). alertKind = FX_INGESTION_DELAYED */
    data class FxIngestion(val base: Currency, val quote: Currency) : OpsSignal

    /** key = "market-stream.{exchange}.{quote.baseCode}-{quote.currency.code}" (소문자). alertKind = MARKET_STREAM_DOWN */
    data class MarketStream(val exchange: Exchange, val quote: Quote) : OpsSignal

    /** key = "job-failures.{jobId.tag}". alertKind = JOB_CONSECUTIVE_FAILURES */
    data class JobFailures(val jobId: JobId) : OpsSignal
}

sealed interface OpsObservation {
    data class Down(val since: Instant?, val failureCount: Int? = null) : OpsObservation {
        init { require(failureCount == null || failureCount >= 1) { "failureCount must be at least 1." } }
    }

    data object Up : OpsObservation

    data object Unknown : OpsObservation
}

data class OpsAlertSettings(
    val marketStaleAfter: Duration = Duration.ofMinutes(5),
    val jobFailureCount: Int = 3,
    val jobFailureDuration: Duration = Duration.ofMinutes(5),
    val realertInterval: Duration = Duration.ofHours(6),
) {
    init {
        require(marketStaleAfter > Duration.ZERO) { "marketStaleAfter must be positive." }
        require(jobFailureCount >= 1) { "jobFailureCount must be at least 1." }
        require(!jobFailureDuration.isNegative) { "jobFailureDuration must not be negative." }
        require(realertInterval > Duration.ZERO) { "realertInterval must be positive." }
    }
}

object OpsSignals {
    fun fx(lastSuccessAt: Instant?, firstRunAt: Instant, now: Instant): OpsObservation
    fun market(tick: MarketTick?, firstRunAt: Instant, now: Instant, settings: OpsAlertSettings): OpsObservation
    fun job(streak: JobFailureStreak?, lastSuccessAt: Instant?, now: Instant, settings: OpsAlertSettings): OpsObservation
}
```

판정 규칙은 design §5.2 표 전문(위에서부터 먼저 맞는 행). `Currency.code`·`Exchange.name`·`JobId.tag` 를 소문자로 key 에 넣는다. `MarketStream` 의 자동 `toString` 은 `Quote.toString`(`BTC/KRW`)을 담는다 — 비밀 없음.

**테스트가 재는 것** (`OpsSignalsTest`, `now = 2026-10-28T00:00:00Z`):
- `FX 정확히 30분은 정상이고 1ns 넘으면 장애` — (AC2-a 1·2행)
- `FX 성공 기록이 없으면 첫 실행 기준 30분 동안 Unknown, 넘으면 since 없는 장애` — (AC2-a 3·4행)
- `시세 5분 미만 Up, 정확히 5분 Down(observedAt), 미래 observedAt Up` — (AC2-b)
- `시세 tick 없음은 첫 실행 기준 5분 Unknown 뒤 Down(null)` — (AC2-b)
- `Job 3회·5분 경계와 2회·1시간` — `(3, now−5m)` Down, `(3, now−5m+1ms)` 와 `(2, now−1h)` 는 성공 있음 Up/없음 Unknown (AC2-c)
- `Job streak 없음 + 성공 있음 Up, 둘 다 없음 Unknown` — (AC2-c)
- `신호 key 와 alertKind` — 4개 key 문자열 `isEqualTo`, 모두 `OwnerAlertSignalState.SIGNAL_KEY_PATTERN` 일치 (AC2-d). 빗썸 quote = `Quote.coin(Symbol("BTC"), Currency.KRW)`, 바이낸스 = `Quote.coin(Symbol("BTC"), Currency.USD)`
- `설정 기본값과 거부` — 기본값 4개, 네 거부 입력의 메시지 전문 (AC2-e)
- `JobFailureStreak 불변식` — `consecutiveFailures = 0`, `lastFailureAt < firstFailureAt` 거부 (AC2-f)

- [ ] **Step 1:** 타입은 전문, `OpsSignals` 세 함수는 **stub**(항상 `Unknown`), key 는 실제 식. 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownerops.OpsSignalsTest' --offline --no-daemon` → RED(Up/Down 단언 실패. key·설정·불변식은 초록). 증거 로그 AC2.
- [ ] **Step 3:** design §5.2 표대로 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 운영 신호 모델과 3상태 관측 함수`

### Task 5: 장애 정책 (AC3)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OpsIncidentPolicy.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OpsIncidentPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.ownerops

sealed interface OpsNotice {
    val signal: OpsSignal
    val incidentStartedAt: Instant
    val at: Instant
    val kind: OwnerAlertKind

    /** kind = signal.alertKind */
    data class Alert(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val sequence: Int,
        val observation: OpsObservation.Down,
    ) : OpsNotice {
        init { require(sequence >= 1) { "sequence must be at least 1." } }
        override val kind: OwnerAlertKind get() = signal.alertKind
    }

    /** kind = OPS_RECOVERED */
    data class Recovered(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val notifyCount: Int,
    ) : OpsNotice {
        init { require(notifyCount >= 1) { "notifyCount must be at least 1." } }
        override val kind: OwnerAlertKind get() = OwnerAlertKind.OPS_RECOVERED
    }
}

/** [next] null = 상태 변화 없음(쓰지 않는다). */
data class OpsIncidentDecision(val next: OwnerAlertSignalState?, val notice: OpsNotice?)

object OpsIncidentPolicy {
    /** [current] 는 저장된 상태 또는 OwnerAlertSignalState.initial. signalKey 가 signal.key 와 다르면 IllegalArgumentException. */
    fun decide(
        signal: OpsSignal,
        current: OwnerAlertSignalState,
        observation: OpsObservation,
        now: Instant,
        settings: OpsAlertSettings,
    ): OpsIncidentDecision
}
```

상태 전이표 = design §5.3 T1~T6 전문. `next.version = current.version`, `next.alertKind = signal.alertKind`. signalKey 불일치 메시지 `signal key does not match the state.`.

**테스트가 재는 것** (`OpsIncidentPolicyTest`, `settings = OpsAlertSettings()`):
- `T1 정상에서 장애 — 첫 알림` — `incident(now, now, 1)`·`Alert(seq 1, observation 그대로)`·version 유지 (AC3-a)
- `T2 정상에서 Up·Unknown 은 아무것도 쓰지 않는다` (AC3-b)
- `T3 마지막 알림 6시간 뒤 재알림, T4 6시간 − 1ms 는 없음` — 상태 `(started, now−6h, 2)` → `(started, now, 3)`·`Alert(seq 3)` (AC3-c)
- `T5 장애에서 Up 은 복구 1회, T6 Unknown 은 유지` (AC3-d)
- `복구 다음 Down 은 새 장애` — T5 결과를 current 로 넣고 `now+30s` Down → `Alert(seq 1, incidentStartedAt = now+30s)` (AC3-e)
- `signalKey 불일치 거부` (AC3-f)
- `notice kind` — Alert 는 신호 종류, Recovered 는 `OPS_RECOVERED`

- [ ] **Step 1:** **stub** `decide` = `OpsIncidentDecision(null, null)`. 테스트 작성.
- [ ] **Step 2:** dod AC3 명령 → RED(T1·T3·T5·재발생 단언 실패. T2·T4·T6·불일치는 초록일 수 있다). 증거 로그 AC3.
- [ ] **Step 3:** 전이표대로 구현. dod AC3 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 운영 장애 정책 — 첫 알림·6시간 재알림·복구`

### Task 6: 알림 본문 (AC4)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContent.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownerops/OwnerOpsAlertContentTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.ownerops

object OwnerOpsAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-ops-alert.md"
    const val ALERT_COLOR = 0xE67E22
    const val RECOVERED_COLOR = 0x95A5A6

    /** design §10.1. */
    fun eventKey(notice: OpsNotice): String

    /** EMAIL 행(channel 기본값). 제목·본문 = design §10.2. */
    fun mail(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): AccountPnlMail

    /** DiscordMessage.of 로 절단. 제목·설명·색 = design §10.2. */
    fun discord(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): DiscordMessage

    /** 임계값 표기 {d}. */
    internal fun threshold(duration: Duration): String

    /** 경과 표기 {e}. 음수는 0 으로 본다. */
    internal fun elapsed(duration: Duration): String
}
```

시각 서식은 `AccountPnlFormat.time`(메일)·`AccountPnlFormat.shortTime`(Discord)을 쓴다. 템플릿은 design §10.2 전문 그대로 복사한다. 담지 않는 값은 design §10.3 표.

**테스트가 재는 것** (`OwnerOpsAlertContentTest`, `zone = Asia/Seoul`, `at = 2026-10-28T00:00:00Z`):
- 메일 golden 5건 — 제목·본문·eventKey 전체 `isEqualTo` (AC4-a): FX 첫 알림(마지막 성공 `2026-10-27T23:29:00Z`, 감지 `at`), FX 첫 알림(`since = null`), 시세 빗썸 첫 알림(마지막 tick `2026-10-27T23:54:30Z`), Job 재알림(`seq 2`, `PREMIUM_REALTIME`, `failureCount 4`, 첫 실패 `2026-10-27T17:55:00Z`, 감지 `2026-10-27T18:00:00Z`), 복구(바이낸스 시세, 감지 `2026-10-27T12:00:00Z`, `notifyCount 2`)
- Discord golden 같은 5건 — 제목·설명·색, 색 상수 2개 정수값 `15105570`·`9807270` (AC4-b)
- eventKey 형식 — 5건이 `owner-ops.{key}.{ms}.alert-{n}`/`.recovered` 이고 정규식 일치 (AC4-c)
- `threshold`·`elapsed` 표기 — dod AC4-d 5개 값 (AC4-d)
- 금지 문자열 — 10개 결과 문자열 모두에 `example.test`·`/api/webhooks/`·`@`·`http` 없음 (AC4-e)
- mail 결과의 `channel == EMAIL`, `discordRoute == null`

- [ ] **Step 1:** **stub** — `eventKey`·`threshold`·`elapsed` 는 실제 식, `mail = AccountPnlMail(eventKey, "x", "")`, `discord = DiscordMessage("x", "", 0)`. 테스트 작성(golden 은 design §10.2 로 손으로 작성).
- [ ] **Step 2:** dod AC4 명령 → RED(golden 10건 실패. eventKey·표기·금지 문자열은 초록). 증거 로그 AC4.
- [ ] **Step 3:** 템플릿 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 운영 알림 메일·Discord 본문`

### Task 7: V21 migration (AC6)

**Files:** Create `infrastructure/common/src/main/resources/db/migration/V21__create_owner_alert_signal_state.sql`;
Modify `apps/batch/src/integrationTest/resources/batch-schema.sql`(아래 fixture DDL 추가);
Test Create `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V21OwnerAlertSignalStateMigrationIntegrationTest.kt`

**Interfaces — Produces (DDL 전문):**

```sql
-- owner 알림 신호별 지속 상태(#91 운영 알림: 환율 수집 지연·시세 수집 끊김·Job 연속 실패. #90 이 재사용한다). 한 신호 = 한 행.
-- 장애 중이면 active = TRUE 이고 incident 3열이 채워진다. 복구되면 active = FALSE 로 비운다. 행은 지우지 않는다.
-- 상태 갱신과 outbox(account_pnl_mail) 행 삽입은 같은 트랜잭션에서 행별 version 조건부로 한다.
-- 비밀값(webhook URL·수신 주소·API key)과 알림 본문은 저장하지 않는다.
CREATE TABLE owner_alert_signal_state (
    signal_key          VARCHAR(64) NOT NULL,
    alert_kind          VARCHAR(32) NOT NULL,
    active              BOOLEAN     NOT NULL,
    incident_started_at DATETIME(6) NULL,
    last_notified_at    DATETIME(6) NULL,
    notify_count        INT         NOT NULL DEFAULT 0,
    version             BIGINT      NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT pk_owner_alert_signal_state PRIMARY KEY (signal_key),
    CONSTRAINT ck_owner_alert_signal_state_incident CHECK (
        (active = FALSE AND incident_started_at IS NULL AND last_notified_at IS NULL AND notify_count = 0)
        OR (active = TRUE AND incident_started_at IS NOT NULL AND last_notified_at IS NOT NULL
            AND notify_count >= 1 AND last_notified_at >= incident_started_at)
    ),
    CONSTRAINT ck_owner_alert_signal_state_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

batch fixture(`batch-schema.sql`)는 위 DDL 과 열·제약·테이블 옵션이 같고 첫 줄만 `CREATE TABLE IF NOT EXISTS owner_alert_signal_state (` 다(주석 4줄은 생략 가능).
테이블 옵션은 V18(`V18__create_account_pnl_alert.sql:13`, `:30`)과 같다 — 확인된 사실.

**테스트가 재는 것** (`V21OwnerAlertSignalStateMigrationIntegrationTest`, V19/V20 migration 테스트와 같은 패턴):
- `빈 DB 에서 latest 는 V21 까지 성공하고 열이 설계와 같다` — `flyway_schema_history` 버전 1~21, `information_schema.columns` 의 이름·타입·nullable·순서, PK, `information_schema.tables.TABLE_COLLATION = utf8mb4_unicode_ci` (AC6-a)
- `V20 에서 V21 로 올려도 기존 행이 그대로다` — V20 까지 적용 후 `account_pnl_monitor_state`·`account_pnl_mail` 행 INSERT → latest → 값 동일, 새 테이블 0행 (AC6-b)
- `CHECK 가 어긋난 행을 거부한다` — dod AC6-c 4건 각각 예외 (AC6-c)

- [ ] **Step 1:** 테스트 작성(파일 없음 상태).
- [ ] **Step 2:** dod AC6 명령 → RED(버전·열 부재 assertion 실패 — Flyway 는 V20 까지 정상 적용되므로 테스트는 실행된다). 증거 로그 AC6.
- [ ] **Step 3:** V21·fixture 작성. dod AC6 명령 → PASS. `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` → PASS, `./gradlew :apps:batch:integrationTest --offline --no-daemon` → PASS(fixture 스키마 테스트 포함, R9 필요 시 허용 예외만). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 신호 상태 테이블 V21`

### Task 8: `JdbcOwnerAlertOutbox` 와 outbox INSERT 단일 소유자 (AC7)

**Files:** Create `.../persistence/jdbc/accountpnl/OwnerAlertOutboxRows.kt`, `.../persistence/jdbc/accountpnl/JdbcOwnerAlertOutbox.kt`;
Modify `.../persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt`(`insertMail`·`validate` 위임만), `.../CommonInfrastructureAutoConfiguration.kt`(`CommonJdbcConfiguration` `@Import` 한 줄);
Test Create `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcOwnerAlertOutboxIntegrationTest.kt`
(main 경로 접두사 `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common`)

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

/** owner 알림 outbox(account_pnl_mail) 행 삽입·검증의 단일 소유자. JdbcAccountPnlAlertStore 의 기존 SQL·메시지를 그대로 옮긴 것이다. */
internal object OwnerAlertOutboxRows {
    const val MAX_EVENT_KEY_LENGTH = 128
    const val MAX_SUBJECT_LENGTH = 255
    const val MAX_BODY_BYTES = 65_535 // MySQL TEXT

    /** 값은 메시지에 넣지 않는다. */
    fun validate(mail: AccountPnlMail)

    /** 같은 (event_key, channel) 은 무시한다. 다른 제약 위반·오류는 그대로 던진다. 호출자 트랜잭션에 참여한다. */
    fun insert(jdbcTemplate: JdbcTemplate, mail: AccountPnlMail, createdAt: Instant)
}

/** OwnerAlertOutbox 구현 — 신호 상태(owner_alert_signal_state) + outbox 행 한 트랜잭션 (owner-ops-alerts design §4.3). */
@Repository
class JdbcOwnerAlertOutbox(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : OwnerAlertOutbox {
    override fun loadSignals(signalKeys: Collection<String>): Map<String, OwnerAlertSignalState>
    override fun enqueue(signals: List<OwnerAlertSignalState>, rows: List<AccountPnlMail>): Boolean
}
```

```kotlin
// CommonInfrastructureAutoConfiguration.kt — CommonJdbcConfiguration 의 @Import 목록, JdbcAccountPnlAlertStore::class(:74) 바로 뒤
    JdbcOwnerAlertOutbox::class,
```

SQL 요건 (컬럼 물리명 = design §4.1):
- SELECT: `SELECT signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version FROM owner_alert_signal_state WHERE signal_key IN (…)`.
- INSERT: `INSERT INTO owner_alert_signal_state (signal_key, alert_kind, active, incident_started_at, last_notified_at, notify_count, version, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?)` — `DuplicateKeyException` = 충돌.
- UPDATE: `UPDATE owner_alert_signal_state SET alert_kind = ?, active = ?, incident_started_at = ?, last_notified_at = ?, notify_count = ?, version = version + 1, updated_at = ? WHERE signal_key = ? AND version = ?` — 영향 0행 = 충돌.
- 충돌이면 `TransactionStatus.setRollbackOnly()` 후 false. 검증 예외는 트랜잭션을 열기 전에 던진다. 신호·행 모두 비면 DB 호출 없이 true.
- `alert_kind` 를 읽을 때 모르는 값 → `IllegalStateException("unknown owner alert kind in owner_alert_signal_state")`.
- `JdbcAccountPnlAlertStore` 는 `OwnerAlertOutboxRows.validate`·`insert` 를 부르고 자기 사본(`MAX_EVENT_KEY_LENGTH`·`MAX_SUBJECT_LENGTH`·`MAX_BODY_BYTES` 상수, `insertMail`, `validate`)을 지운다. 다른 줄 무변경.

**테스트가 재는 것** (`JdbcOwnerAlertOutboxIntegrationTest`, 실제 MySQL, 고정 Clock): dod AC7 (a)~(g)·(i) 각각 한 메서드. (b) 는 `[신규 A(version 0), 기존 B(틀린 version)]` 입력 후 A 행 부재·`account_pnl_mail` 0행.
(g) 는 `JdbcAccountPnlAlertStore(...).claim("owner-alert-delivery:test", 1, now)` 가 enqueue 한 행을 돌려주고 `markSent` true.
(i) 는 Task 0 (g15) 의 기존 패턴대로 `ApplicationContextRunner` 에 Testcontainers DataSource + JDBC·트랜잭션 auto-configuration + `CommonInfrastructureAutoConfiguration` 을 올려
`getBeansOfType(OwnerAlertOutbox::class.java)` 가 1개이고 `JdbcOwnerAlertOutbox` 임을 단언.

- [ ] **Step 1:** `JdbcOwnerAlertOutbox` 를 **stub**(`loadSignals = emptyMap()`, `enqueue = true` 무기록)으로 만들되 `@Import` 는 아직 추가하지 않는다. `OwnerAlertOutboxRows` 추출은 실제로. 테스트 작성.
- [ ] **Step 2:** dod AC7 명령 → RED(새 테스트의 기록·읽기 단언 실패, (i) bean 0개 단언 실패. 기존 `JdbcAccountPnlAlertStoreIntegrationTest` 는 추출 뒤에도 초록이어야 한다 — 빨간색이면 추출이 동작을 바꾼 것이므로 멈춘다). 증거 로그 AC7.
- [ ] **Step 3:** 구현과 `@Import` 추가. dod AC7 명령 → PASS. `./gradlew :infrastructure:common:integrationTest --offline --no-daemon`(R10) → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 범용 enqueue JDBC 구현`

### Task 9: Job 연속 실패 카운터 (AC5)

**Files:** Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/job/RedisJobRunRecorderAdapter.kt`;
Test Create `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/job/RedisJobRunRecorderFailureStreakTest.kt`

**Interfaces — Produces (전문):**

```kotlin
class RedisJobRunRecorderAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : JobRunRecorder,
    JobLastSuccessReadPort,
    JobFailureStreakReadPort {

    /** 이 프로세스의 연속 실패. 재시작하면 비어 있다 (owner-ops-alerts design §6.2 K5). */
    override fun findFailureStreak(jobId: JobId): JobFailureStreak?
}
```

요건: `private val failureStreaks = ConcurrentHashMap<JobId, JobFailureStreak>()`, `completed` 의 **첫 문장**에서 design §6.2 표대로 `compute`/`remove`. 기존 메트릭·Redis·로그 줄 무변경.
bean 등록은 기존 `@Import(RedisJobRunRecorderAdapter::class)`(`BatchJobSupportConfiguration`) 그대로 — 타입으로 `JobFailureStreakReadPort` 주입이 된다.

**테스트가 재는 것** (`RedisJobRunRecorderFailureStreakTest`, `SimpleMeterRegistry` + MockK `StringRedisTemplate`): dod AC5 (a)~(f).
(e) 는 FAILED 1회 뒤 `opsForValue().set` 이 `RuntimeException` 을 던지게 하고 `completed(SUCCEEDED)` 가 던진 뒤에도 streak 이 null. (f) 는 가상 스레드 100개가 같은 Job `completed(FAILED)` → `consecutiveFailures == 100`.

- [ ] **Step 1:** **stub** `findFailureStreak = null`, 카운터 미갱신. 테스트 작성.
- [ ] **Step 2:** dod AC5 명령 → RED. 증거 로그 AC5.
- [ ] **Step 3:** 구현. 같은 명령 → PASS(기존 `infrastructure/batch/.../job` 테스트 무수정 통과). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: batch Job 연속 실패 카운터`

### Task 10: 채널 bean 을 PnL 모니터 구성에서 분리 (AC11-a·b·c·d·f·h·j, AC15)

**Files:** Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelConfiguration.kt`;
Modify `.../accountpnl/AccountPnlMonitorAdapterConfiguration.kt`, `.../BatchInfrastructureAutoConfiguration.kt`;
Test Create `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelConfigurationTest.kt`,
`apps/batch/src/test/kotlin/io/premiumspread/config/OwnerAlertChannelIndependenceWiringTest.kt`;
허용 예외(R8) Modify `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt`(아래 두 종류 diff 만)

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.infrastructure.batch.owneralert

/** OwnerAlertChannelRequirements.ready(env). 로그 없음. */
class OwnerAlertChannelReadyCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean
}

/** owner 알림 채널(메일·Discord) sender 와 라우팅. PnL 모니터와 독립이다 (owner-ops-alerts design §9.3). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${batch.scheduling.enabled:true} and \${scheduling.enabled:true}")
@Conditional(OwnerAlertChannelReadyCondition::class, OwnerAlertChannelAvailableCondition::class)
@EnableConfigurationProperties(OwnerAlertProperties::class)
class OwnerAlertChannelConfiguration {
    @Bean
    @ConditionalOnBean(EmailSender::class)
    @Conditional(OwnerAlertEmailRequestedCondition::class)
    fun accountPnlMailSender(emailSender: EmailSender, environment: Environment): AccountPnlMailSender

    @Bean
    @Conditional(DiscordWebhookConfiguredCondition::class)
    fun ownerAlertDiscordSender(ownerAlert: OwnerAlertProperties): DiscordWebhookSenderAdapter

    @Bean
    fun ownerAlertRouting(
        ownerAlert: OwnerAlertProperties,
        mailSender: ObjectProvider<AccountPnlMailSender>,
        discordSender: ObjectProvider<OwnerAlertDiscordSender>,
    ): OwnerAlertRouting
}
```

요건: 세 bean 본문·로그 문구(`owner-alert discord webhooks: …`, `owner-alert routes: …`)·`describeRoutes` 를 `AccountPnlMonitorAdapterConfiguration.kt:170-214` 에서 **그대로** 옮긴다(log 는 새 클래스의 logger).
`AccountPnlMonitorAdapterConfiguration` 의 `@EnableConfigurationProperties` 는 `AccountPnlMonitorProperties` 하나로 줄인다. `BatchInfrastructureAutoConfiguration` 의 `@Import` 첫 줄에 `OwnerAlertChannelConfiguration::class` 를 더한다.

허용 예외(R8) diff (전문 — 이 둘 외 금지):
1. `AccountPnlMonitorWiringTest` runner `withUserConfiguration(...)`(`:72-79`)에 `OwnerAlertChannelConfiguration::class.java,` 한 줄 추가와 그 import.
2. `assertNoBeans()`(`:81-91`)에서 `assertThat(ctx).doesNotHaveBean(AccountPnlEmailSenderAdapter::class.java)`(`:89`)와, Task 0 (g14) 가 같은 함수 안에서 찾은 다른 채널 bean
   (`OwnerAlertRouting`·`OwnerAlertDiscordSender`·`DiscordWebhookSenderAdapter`) 부재 단언을 지우고, 그 결과 쓰이지 않는 import 를 지운다. `:83-88` 은 그대로.

**테스트가 재는 것:**
- `OwnerAlertChannelConfigurationTest`(infra:batch, `ApplicationContextRunner` + 기존 owneralert 테스트가 쓰는 `EmailSender` fake 구성):
  - `PnL 모니터가 꺼져 있어도 Discord 채널이면 라우팅과 Discord sender 가 뜬다` — `account-pnl-monitor.enabled=false` + Discord + `webhook.ops=https://discord.example.test/api/webhooks/1/x` → `OwnerAlertRouting.channels == (false, true)` (AC11-a)
  - `메일 채널만이면 메일 sender 와 라우팅` — SMTP·`EmailSender`·`owner@example.test` (AC11-b)
  - `채널이 준비되지 않으면 bean 이 없다` — 아무것도 없음 / Discord + webhook 없음 / 메일 + 수신 주소 없음 (AC11-c·d)
  - `scheduling 이 꺼지면 bean 이 없다` (AC11-f)
  - `채널 ready 면 Discord 켜짐과 무관하게 webhook·override 를 검증한다` — PnL 꺼짐 + Discord 켜짐 + `webhook.ops=http://discord.example.test/x` → 실패, PnL 꺼짐 + **Discord 꺼짐** + 메일 ready + 같은 http webhook → 실패, 메일 ready + `owner-alert.routes.ops-recovered.push=loud` → 실패. 원인 메시지에 값 없음 (AC11-h)
- `OwnerAlertChannelIndependenceWiringTest`(apps:batch, runner = `AccountPnlMonitorWiringTest` 와 같은 5개 구성 + `OwnerAlertChannelConfiguration`, 같은 `Stubs`·`enabledProps` 값을 이 클래스 안에 복제): dod AC15 —
  R8 로 부재 단언을 뺀 시나리오(`:98-101` 플래그 false, `:103-108` 의 계정 key 4개 각각 누락, `:337-398` 의 PnL 꺼짐 시나리오들과 같은 property)에서 `AccountPnlMonitorJob`·계정 adapter 3개는 없고
  `AccountPnlEmailSenderAdapter`·`OwnerAlertRouting` 은 있다. `account-pnl-monitor.recipient=` 이면 채널 bean 도 없다. `batch.scheduling.enabled=false` 면 채널 bean 도 없다.

- [ ] **Step 1:** `OwnerAlertChannelConfiguration` 을 위 클래스 조건·`@EnableConfigurationProperties` 만 있고 **bean 이 없는 stub** 으로, `OwnerAlertChannelReadyCondition` 은 전문으로 만든다(아직 import 에 넣지 않는다). 두 새 테스트를 작성.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelConfigurationTest' :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertChannelIndependenceWiringTest' --offline --no-daemon` → RED(채널 bean 존재 assertion 실패). 증거 로그 AC11·AC15.
- [ ] **Step 3:** bean 3개 이동, `@Import` 추가, 허용 예외(R8) 적용. 같은 명령 → PASS. `./gradlew :apps:batch:test --tests 'io.premiumspread.config.*' --offline --no-daemon` → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `refactor: owner 알림 채널 bean 을 PnL 모니터 구성에서 분리`

### Task 11: 독립 전달 Job (AC9)

**Files:** Create `apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDeliveryJob.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDeliveryJobTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.application.notification.owneralert

/** owner 알림 outbox 독립 전달 1회분. PnL 모니터와 무관하게 돈다 (owner-ops-alerts design §7). */
class OwnerAlertDeliveryJob(
    private val store: AccountPnlAlertStore,
    mailSender: AccountPnlMailSender,
    discordSender: OwnerAlertDiscordSender?,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    fun run(): JobResult

    companion object {
        const val OWNER_PREFIX = "owner-alert-delivery:"
        val STALE_CLAIM_AFTER: Duration = Duration.ofMinutes(5)
    }
}
```

`JobResult` 분기 = design §7.2 표 전문. 로그 = 그 표의 두 문구. 내부 디스패처 = `OwnerAlertDispatcher(store, mailSender, discordSender, clock, OWNER_PREFIX + UUID.randomUUID())`(인스턴스당 1개).

**테스트가 재는 것** (`OwnerAlertDeliveryJobTest`, #88 `FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender`·`MutableClock` **무수정 재사용**, executor 는 Task 0 (g13) 의 기존 구성): dod AC9 (a)~(e) 각각.
(b) 는 PENDING 12행 → 1회차 claim 10·발송 10, 2회차 2. (a) 는 `recoverStale` 호출 인자 `(now, 5m)` 와 호출 순서(claim 보다 먼저).

- [ ] **Step 1:** **stub** `run = executor.execute(jobConfigs.get(JobId.OWNER_ALERT_DELIVERY)) { JobResult.Success }`. 테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJobTest' --offline --no-daemon` → RED. 증거 로그 AC9.
- [ ] **Step 3:** 구현. dod AC9 명령 → PASS(디스패처·PnL Job 테스트 무수정). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 독립 전달 Job`

### Task 12: 운영 신호 Job (AC8)

**Files:** Create `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJob.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownerops/OwnerOpsSignalJobTest.kt`, `.../job/ownerops/OwnerOpsTestFakes.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.application.job.ownerops

/** owner 운영 신호 1회차 판정과 enqueue. 발송은 OwnerAlertDeliveryJob 이 한다 (owner-ops-alerts design §8). */
@Suppress("LongParameterList")
class OwnerOpsSignalJob(
    private val lastSuccess: JobLastSuccessReadPort,
    private val failureStreaks: JobFailureStreakReadPort,
    private val ticks: LatestMarketTickReadPort,
    private val market: BatchMarketProvider,
    private val outbox: OwnerAlertOutbox,
    private val routing: OwnerAlertRouting,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: OpsAlertSettings = OpsAlertSettings(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    fun run(): JobResult

    companion object {
        val MONITORED_JOBS: List<JobId> =
            JobId.entries.filterNot { it == JobId.OWNER_OPS_SIGNAL || it == JobId.OWNER_ALERT_DELIVERY }
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}
```

회차 순서 = design §8.2 1~9 전문 — 2단계 FX 신호는 `OpsSignal.FxIngestion(market.fxBase, market.fxQuote)`(`defaultMarket()` 결과, `FxIngestionJob.kt:29-30` 과 같은 출처),
4단계 관측 예외는 원인 체인(자기 자신 포함, 깊이 16)에 `InterruptedException`·`CancellationException` 이 있으면 `Thread.currentThread().interrupt()` 후 **그대로 다시 던지고**, 그 밖이면
그 신호를 `Unknown` 으로 본다(`AccountPnlMonitorJob.kt:176-180`·`:369-374` 와 같은 규칙). `JobResult` 분기·로그 문구 = 같은 절 표 전문.
행 생성은 `OwnerAlertRows.expand(OwnerOpsAlertContent.mail(n, settings, zone), routing.resolve(n.kind)) { OwnerOpsAlertContent.discord(n, settings, zone) }`.
`firstRunAt` 는 인스턴스 필드 `AtomicReference<Instant?>`. 관측 예외 WARN 의 `error` 는 예외 단순 클래스 이름(메시지 금지).

테스트 fake 요건(`OwnerOpsTestFakes.kt`):
- `FakeOwnerAlertOutbox`(저장 map·누적 행·`enqueue` 호출 수·다음 호출 예외/false 주입, `enqueue` 가 version 을 +1 해 저장).
- 가변 `JobLastSuccessReadPort`·`JobFailureStreakReadPort`·`LatestMarketTickReadPort` fake(조회 인자 기록·호출 시 예외 주입). **시각은 `MutableClock` 을 받아 호출 시점의
  `clock.instant()` 기준 상대값**으로 돌려준다(예 정상 = 마지막 성공 `clock.instant() − 1분`, tick `clock.instant() − 1초`). 장애로 만들 신호만 고정 절대 시각 또는 null 로 덮어쓴다 —
  시계를 6시간 전진해도 나머지 신호가 장애로 오염되지 않게.

**테스트가 재는 것** (`OwnerOpsSignalJobTest`, `MutableClock(2026-10-28T00:00:00Z)`, routing `OwnerAlertRouting(OwnerAlertChannels(true, true))`, BatchMarket = BTC·BITHUMB/KRW·BINANCE/USD·USD/KRW): dod AC8 (a)~(k) 각각.
(b) 는 FX 신호 key 가 `fx-ingestion.usd-krw` 이고 BatchMarket 의 fxBase/fxQuote 를 다른 값(예 `EUR`/`KRW`)으로 바꾸면 key 도 따라 바뀜. (c) 는 tick fake 의 조회 인자 집합 = `{(BITHUMB, BTC/KRW), (BINANCE, BTC/USD)}`.
(e) 는 시계를 30초씩 6시간 전진시키며 enqueue 호출 수 = 첫 회 1 + 6시간 시점 1. (g) 는 FakeOutbox 에 FX 장애 상태(`lastNotifiedAt = now`)를 넣은 채 FX 성공 기록 null 인 새 Job 인스턴스로 0~30분을 30초 간격으로 돌려 enqueue 0회.
(i) 저장 전 interrupt 는 마지막 관측 fake 가 호출될 때 `Thread.currentThread().interrupt()` 를 건다. (j) 는 `OutputCaptureExtension` 으로 로그에 `example.test`·`owner@`·주입한 관측 예외 메시지 문자열이 없음.
(k) 는 tick fake 가 `RuntimeException(InterruptedException())` 을 던지게 하고 action 을 executor 없이 직접 부르는 경로(또는 executor 결과가 `Failure` 이고 원인이 그 예외)로 재전파·interrupt 플래그 true·enqueue 0회를 확인한다 — 테스트 끝에 `Thread.interrupted()` 로 플래그를 지운다.

- [ ] **Step 1:** **stub** `run = executor.execute(jobConfigs.get(JobId.OWNER_OPS_SIGNAL)) { JobResult.Success }`. fake·테스트 작성.
- [ ] **Step 2:** dod AC8 명령 → RED(행·상태·분기·재전파 단언 실패. (a) 는 초록일 수 있다). 증거 로그 AC8.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 운영 신호 Job`

### Task 13: 앱 배선·기동 조건·Scheduler (AC11)

**Files:** Create `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerAlertDeliveryConfiguration.kt`, `.../config/OwnerOpsAlertProperties.kt`,
`.../interfaces/scheduling/OwnerAlertDeliveryScheduler.kt`, `.../interfaces/scheduling/OwnerOpsSignalScheduler.kt`;
Modify `apps/batch/src/main/resources/application.yml`(`owner-alert.ops` 블록);
Test Create `apps/batch/src/test/kotlin/io/premiumspread/config/OwnerAlertDeliveryWiringTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.config

/** design §9.2 ready 규칙의 app 사본 + WARN/INFO(className = OwnerAlertDeliveryConfiguration 일 때만). infra OwnerAlertChannelRequirements.ready 와 같은 답을 낸다. */
class OwnerAlertDeliveryEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean
}

/** OwnerAlertDeliveryEnabledCondition ∧ owner-alert.ops.enabled(빈 값 = true). 꺼져 있으면 INFO(className = OwnerOpsAlertConfiguration 일 때만). */
class OwnerOpsAlertEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean
}

@ConfigurationProperties(prefix = "owner-alert.ops")
data class OwnerOpsAlertProperties(
    val marketStaleAfter: Duration = Duration.ofMinutes(5),
    val jobFailureCount: Int = 3,
    val jobFailureDuration: Duration = Duration.ofMinutes(5),
    val realertInterval: Duration = Duration.ofHours(6),
) {
    fun toSettings(): OpsAlertSettings = OpsAlertSettings(marketStaleAfter, jobFailureCount, jobFailureDuration, realertInterval)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerAlertDeliveryEnabledCondition::class)
class OwnerAlertDeliveryConfiguration {
    /** 메일 sender bean 이 없으면 OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER 를 넘긴다. */
    @Bean
    fun ownerAlertDeliveryJob(
        store: AccountPnlAlertStore,
        mailSender: ObjectProvider<AccountPnlMailSender>,
        discordSender: ObjectProvider<OwnerAlertDiscordSender>,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
    ): OwnerAlertDeliveryJob
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerOpsAlertEnabledCondition::class)
@EnableConfigurationProperties(OwnerOpsAlertProperties::class)
class OwnerOpsAlertConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun ownerOpsSignalJob(
        lastSuccess: JobLastSuccessReadPort,
        failureStreaks: JobFailureStreakReadPort,
        ticks: LatestMarketTickReadPort,
        market: BatchMarketProvider,
        outbox: OwnerAlertOutbox,
        routing: OwnerAlertRouting,
        executor: JobExecutor,
        clock: Clock,
        properties: OwnerOpsAlertProperties,
        @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
        jobConfigs: JobConfigProvider,
    ): OwnerOpsSignalJob
}
```

```kotlin
package io.premiumspread.interfaces.scheduling

/** 채널이 준비되고 scheduling 이 켜졌을 때만. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerAlertDeliveryEnabledCondition::class)
class OwnerAlertDeliveryScheduler(
    private val job: OwnerAlertDeliveryJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-alert-delivery.fixed-rate:15000}")
    fun deliver() { job.run() }
}

@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerOpsAlertEnabledCondition::class)
class OwnerOpsSignalScheduler(
    private val job: OwnerOpsSignalJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-ops-signal.fixed-rate:30000}")
    fun evaluate() { job.run() }
}
```

trigger 패턴은 `ExchangeRateScheduler`(yml 값 `5m` 형식 + ms fallback, `BatchSchedulingProperties` 주입으로 검증)와 같다 — Task 0 (g12) 와 다르면 그 패턴을 따른다.

yml (전문, `owner-alert` 블록 안 `routes` 앞):

```yaml
  # owner 운영 알림 (환율 수집 지연·시세 수집 끊김·Job 연속 실패). 쓸 수 있는 owner 채널이 없으면 기동하지 않는다.
  ops:
    enabled: ${OWNER_ALERT_OPS_ENABLED:true}
    market-stale-after: ${OWNER_ALERT_OPS_MARKET_STALE_AFTER:5m}
    job-failure-count: ${OWNER_ALERT_OPS_JOB_FAILURE_COUNT:3}
    job-failure-duration: ${OWNER_ALERT_OPS_JOB_FAILURE_DURATION:5m}
    realert-interval: ${OWNER_ALERT_OPS_REALERT_INTERVAL:6h}
```

조건·로그 문구·등급 = design §9.2 전문(위에서부터 먼저 맞는 행 하나): Discord 켜짐 + webhook 없음 → WARN, SMTP 켜짐 + 수신 주소 없음 → **INFO**, 그 밖 미준비 → 로그 없음, ops 꺼짐 → INFO.
로그에는 env 이름 목록과 boolean 만 — 값을 싣지 않는다.

**테스트가 재는 것** (`OwnerAlertDeliveryWiringTest`, `ApplicationContextRunner` + `OutputCaptureExtension`, runner 구성 = 기존 `AccountPnlMonitorWiringTest` 의 구성 + `OwnerAlertChannelConfiguration` + 새 구성 2개 + Scheduler 2개, 필요한 port 는 fake bean):
dod AC11 (a)~(g)·(i) 각각((h) 는 Task 10 의 infra 테스트가 잰다).
- (d) 는 두 경우 각각 로그 등급(WARN / INFO)과 문구 전문, 그리고 수신 주소 누락 경우 출력에 `WARN` 행이 없음, 출력에 `discord.example.test`·`owner@example.test` 가 없음.
- (i) 는 email 요청(빈 값/true/false) × SMTP(true/false) × 수신 주소(있음/없음) × Discord 요청(true/false) × webhook(있음/없음) 48조합에서 `OwnerAlertDeliveryJob` bean 존재 여부 == 기대표(design §9.2 `ready` 규칙을 테스트 안 표로 적는다 — infra 함수는 `internal`). SMTP true 조합은 runner 에 `EmailSender` fake 를 둔다.
- (g) 는 `owner-alert.ops.market-stale-after=7m`·`job-failure-count=4`·`job-failure-duration=10m`·`realert-interval=3h` 에서 bean `OwnerOpsAlertProperties.toSettings() == OpsAlertSettings(7m, 4, 10m, 3h)`, 그리고 `realert-interval=0s` 는 기동 실패이고 원인 체인에 `realertInterval must be positive.`.

- [ ] **Step 1:** 조건 2개를 **stub**(항상 false)으로, 나머지 전문. 테스트 작성.
- [ ] **Step 2:** dod AC11 명령 → RED((a)(b)(e)(g) bean 존재·로그 단언 실패). 증거 로그 AC11.
- [ ] **Step 3:** 조건 구현. dod AC11 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS(앱 main 이 infra 를 import 하지 않음 포함).
- [ ] **Step 4: Commit** — `feat: owner 운영 알림 앱 배선과 Scheduler`

### Task 14: E2E 통합 (AC12)

**Files:** Test Create `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerOpsAlertIntegrationTest.kt`

**테스트가 재는 것** (구성은 기존 `OwnerAlertDeliveryIntegrationTest` 의 MySQL fixture·executor·기록용 sender 를 따른다. 실제 `JdbcOwnerAlertOutbox`·`JdbcAccountPnlAlertStore`, `MutableClock(2026-10-28T00:00:00Z)`,
fake `JobLastSuccessReadPort`·`JobFailureStreakReadPort`·`LatestMarketTickReadPort` — Task 12 와 같은 규칙으로 **`MutableClock` 기준 상대 시각**(정상 = 마지막 성공 `now − 1분`, tick `now − 1초`)을 돌려준다 —, routing `(email=true, discord=true)`): dod AC12 (a)~(e).
- (a)(b)(c) 는 한 시나리오 메서드: FX 마지막 성공만 고정 절대 시각 `2026-10-27T23:29:00Z`(31분 전)로 덮어씀 → 신호 회차 → 전달 회차 → `account_pnl_mail` 에서 eventKey·channel·`SENT`, Discord sender 호출 route `(OPS, SOUND)` 1회 → 시계 +6h·두 회차 → `alert-2` 와 그 밖 신호의 행 없음 → FX 마지막 성공을 상대 시각으로 되돌림 → 두 회차 → `.recovered` `(OPS, SILENT)`, `owner_alert_signal_state` 의 `active = 0`.
- (d) 장애 상태에서 새 `OwnerOpsSignalJob` 인스턴스 + FX 성공 기록 null → 29분 동안 30초 간격 회차 → `account_pnl_mail` 행 수 불변, 상태 행 `active = 1`.
- (e) PENDING EMAIL 6행을 `JdbcOwnerAlertOutbox.enqueue(emptyList(), rows)` 로 넣고 → 전달 Job 의 `run()` 과 owner `account-pnl-monitor:e2e` 의 `OwnerAlertDispatcher.dispatch()` 를 두 스레드에서 동시에 → 기록용 sender 의 eventKey 별 호출 수가 모두 1, 6행 모두 `SENT`.

- [ ] **Step 1:** 테스트 작성.
- [ ] **Step 2:** RED — 변이: `OwnerOpsSignalJob` 의 `enqueue` 호출에서 `rows` 대신 `emptyList()` 를 넘긴다. dod AC12 명령 → (a) 행 부재 assertion 실패. 증거 로그 AC12. 변이를 되돌리고 `git diff --stat -- apps/batch/src/main` 빈 출력 확인.
- [ ] **Step 3:** dod AC12 명령 → PASS. `./gradlew :apps:batch:integrationTest --offline --no-daemon`(R11) → PASS.
- [ ] **Step 4: Commit** — `test: owner 운영 알림 E2E — 재알림·복구·재시작·동시 전달`

### Task 15: 문서 (AC13)

**Files:** Create `docs/runbooks/owner-ops-alert.md`; Modify `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`,
Task 0 (g10)·(g11) 이 찾은 파일

요건 (design §13 전문을 따른다):
- `owner-ops-alert.md` 절 순서: `## 무엇을 알리나`(신호 3종·경계 표) → `## 재알림과 복구` → `## 라우팅`(4종 표, **Discord 가 꺼져 있으면 4종 모두 메일로 온다**, override env 12개) → `## 환경변수`(design §9.1 표 전문) →
  `## 기동 판정과 로그`(§9.2 규칙·로그 표 전문 — Discord webhook 누락은 WARN, **SMTP 는 켜졌지만 owner 수신 주소가 없으면 INFO 이고 그 이유는 SMTP 가 회원 김프 구독 알림과 공유되기 때문**,
  그리고 **채널 구성이 ready 면 Discord 켜짐 여부와 무관하게 webhook 형식과 `OWNER_ALERT_*_EMAIL/_DISCORD/_PUSH` override 값을 검증해 틀리면 기동 실패**) →
  `## 한계`(재시작·카운터·`Unknown` 대기·다중 인스턴스) → `## 이 경로로 알릴 수 없는 장애`(§6.3 표, Slack `BATCH_JOB_FAILED` 와의 관계) →
  `## 상태 확인과 정리`(§13 SQL 2개 전문) → `## 배포`(V21 additive, 롤백 SQL 없음, API 먼저) → `## Discord 수신 체크리스트 (owner-ops-alerts AC14)`. 알림 메시지 예 3종(design §10.2 golden 과 같은 값).
- `account-pnl-alert.md`: 채널 bean 이 PnL 모니터와 독립(PnL 을 꺼도 채널은 운영 알림용으로 뜬다), 위 검증 시점 변화(채널 ready 면 Discord 켜짐과 무관하게 webhook·override 검증), 라우팅 표·override `{KIND}` 목록에 4종, 전달 Job 이 같은 outbox 를 비운다.
- `ARCHITECTURE_DESIGN.md`: `### 실계정 순손익 알림` 절 뒤에 운영 알림 흐름 한 단락.
- `PROJECT_STATUS.md`: `:13` `Flyway V1~V21`, migration 표 V21 행, 기능 상태 한 항목(AC14 owner 확인 대기 명시).

- [ ] **Step 1:** 작성.
- [ ] **Step 2:** `bash docs/check-documentation.sh` → exit 0. 증거 로그 AC13.
- [ ] **Step 3: Commit** — `docs: owner 운영 알림 runbook·아키텍처·상태 문서`

### Task 16: 최종 검증 (⑩)

**Files:** 없음 (dod 증거 로그)

- [ ] **Step 1:** dod 회귀 방어선 R1·R2·R4·R5·R10·R11·R12·R13·R15 명령과 AC1~AC13·AC15 검증 명령을 각각 실행해 exit code·테스트 수를 기록한다. Task 0 대비 테스트 수 증가를 적는다.
- [ ] **Step 2:** 경계면 대조 — V21 SQL ↔ `batch-schema.sql` ↔ `JdbcOwnerAlertOutbox` SQL 컬럼, yml env ↔ runbook env 표, routes 기본값 ↔ runbook 라우팅 표, JobConfig ↔ yml jobs ↔ runbook.
- [ ] **Step 3:** R3·R5·R6·R7·R8·R9 diff 를 실행해 사람 판정용 출력으로 남긴다(R8 은 dod 의 두 종류 diff 외 줄이 없는지). ktlint·detekt 는 CI(R14).
- [ ] **Step 4:** dod 판정 블록을 표에서 세어 출력한다(AC13 T3 기록 제출, AC14 T4 → `AWAITING_HUMAN`).
