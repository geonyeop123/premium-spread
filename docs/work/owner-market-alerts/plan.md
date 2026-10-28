# owner 시장 알림 — 김프 급변·기준선 돌파·신고가/신저가·일일 요약 (issue #90) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** batch 가 기존 김프 데이터(Redis current·분/시/일 집계)만으로 김프 급변·기준선 돌파·1h/24h 신고가·신저가·09:00 KST 일일 요약을 판정해 owner Discord market 채널(·메일)로 보낸다.
실계정 순수익률은 PnL 모니터가 남긴 최신값을 읽는다. 시장 알림은 PnL 모니터와 독립이다.

**Architecture:** Domain `ownermarket` 이 pair key·설정·상태·창·근거 충분성·정책 4종·렌더러를 소유한다. 새 port `OwnerMarketAlertStore` 가 상태(V22 `owner_market_alert_state`)와
outbox 행(`account_pnl_mail`)을 한 트랜잭션에 쓴다(infra:common `JdbcOwnerMarketAlertStore`). 범위 데이터는 기존 Domain port `PremiumAggregationReadPort` 를
infra:common `JpaPremiumRepositoryAdapter` 가 기존 cache+DB 병합 경로로 구현한다. 실계정 최신값은 port `AccountPnlLatestStore`(V22 `account_pnl_latest`)이고 PnL 모니터가 상태 저장 뒤 기록한다.
새 `OwnerMarketSignalJob`(30s)이 판정·enqueue 하고 #91 `OwnerAlertDeliveryJob` 이 보낸다.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x (`@ConfigurationProperties`, `Condition`, `ObjectProvider`, `@Scheduled`), JDBC `TransactionTemplate`, Flyway·MySQL 8 (Testcontainers), JUnit5 + AssertJ + MockK, `ApplicationContextRunner` + `OutputCaptureExtension`.

**Spec:** `docs/work/owner-market-alerts/design.md` · **DoD:** `docs/work/owner-market-alerts/dod.md`

## Global Constraints

- base `4f8727c`(Task 0 확인). 직전 단위 산출물(`docs/work/owner-alert-channel/**`·`owner-account-alerts/**`·`owner-ops-alerts/**`)은 수정하지 않는다.
- **동결 테스트 무수정** (dod R3). 이 테스트들이 컴파일·통과하도록:
  - `AccountPnlAlertStore`·`AccountPnlMonitorState`·`OwnerAlertOutbox` interface/타입 무변경. `JdbcAccountPnlAlertStore`·`JdbcOwnerAlertOutbox`·`OwnerAlertOutboxRows` 무변경(dod R6).
  - `AccountPnlMonitorJob` 생성자 새 인자는 **끝에 기본값 null**. 배선은 `ObjectProvider`. `OwnerAlertDispatcher`·`OwnerAlertDeliveryJob`·`OwnerOpsSignalJob` main 무변경.
- 허용 예외는 dod R7(`OwnerAlertRoutingTest` 기대 목록 끝 4개)·R8(main 두 파일의 한정 diff)·R9 뿐. 그 밖 기존 테스트를 고쳐야 하면 **멈추고 보고**한다.
- `OwnerAlertKind`·`JobId` 새 값은 enum **끝에**. `OwnerAlertKind` 전체 `when` 금지(`OwnerAlertRouting.kt:18`) — 정책·렌더러는 `MarketNotice` sealed `when` 만 쓴다.
- Domain 에 Spring/JDBC/Redis 의존 추가 금지(`ownermarket` 은 `java.time`·`java.math` + 기존 domain 타입만). 앱 main 은 infrastructure 타입을 import 하지 않는다. 앱에 `infrastructure`·`cache`·`repository` 패키지 금지.
- webhook URL·token·수신 주소·API key·예외 메시지를 본문·로그·예외 메시지·`toString`·DB 에 남기지 않는다. 실계정 금액은 일일 요약 본문에만(로그·`toString` 금지). 새 로그는 design §6.3·§7.4·§9.2 의 문구·키만.
- 테스트: 고정 `Clock`(기준 `2026-10-29T00:00:00Z` = 09:00 KST)·`ZoneId.of("Asia/Seoul")`, URL 은 `https://discord.example.test/...`, 주소 `owner@example.test`, 가짜 금액(`12.34`·`123456`). 실 webhook·key 금지(`TestIsolationArchitectureTest`).
  시간을 전진시키는 테스트의 fake port(현재값·bucket·최신 PnL)는 **`MutableClock` 기준 상대 시각**을 돌려준다.
- migration 은 V22 **추가만**. Redis key·TTL·payload, `PublicEndpointPolicy`, Slack `OperatorAlert` 무변경.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 변이에서의 **assertion 실패**다. RED·GREEN 로그를 dod `## 증거 로그` 에 append 한다.
- 각 태스크 커밋 시점에 `./gradlew test architectureTest --offline --no-daemon` 이 초록. integrationTest 소스도 컴파일이 깨진 채로 두지 않는다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령 `--offline --no-daemon`. ktlint·detekt 는 로컬 실행 불가 — CI 확인(dod R14), 우회 금지.

## Review Focus

1. **가짜 알림 없음** — 현재값 없음·10초 초과·bucket 80% 미만·읽기 예외에서 판정과 상태가 멈추는가, 재시작 뒤 쿨다운·구역·요약 일자가 이어지는가(Task 3·4·5·6·13·15).
2. **경계값** — 급변·신고가 `≥`(정확히 0.5/0.1%p 발동), 쿨다운 `≥`(정확히 30분/2시간 재발동), 기준선 `rate == U` ABOVE·`U − h` 이탈, 09:00:00 vs 08:59:59, PnL maxAge 정확히 유효(Task 3~7).
3. **24h 우선** — 24h 조건이 참이면 1h 를 보지 않고, 24h 발동 때 1h 쿨다운도 찍는가(Task 6).
4. **MarketPair·시간 계약** — 다른 pair 현재값 = 없음, 집계 pair 불일치 = 예외, 분·시 UTC·전일 KST `[from,to)`(Task 3·11·15).
5. **원자성** — 상태 + outbox 행 한 트랜잭션, 두 번째 상태 충돌이 첫 상태까지 되돌리는가(Task 10).
6. **PnL 모니터 무회귀** — 최신값 기록이 save true 뒤에만, 실패해도 결과·발송 무변화, 동결 테스트 무수정(Task 12).
7. **비노출** — 본문·로그에 URL·주소·예외 메시지 없음, 금액은 요약 본문에만(Task 8·13).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt` | enum 4개 추가 | 1 |
| `apps/batch/src/main/resources/application.yml` | routes 4블록(1), jobs·scheduling(2), `owner-alert.market`(14) | 1·2·14 |
| `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt` | `JobId.OWNER_MARKET_SIGNAL` | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/common/JobConfig.kt` | `DefaultJobConfigProvider` 1행 | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/BatchJobProperties.kt` | 필드·`when` 1행 | 2 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/BatchSchedulingProperties.kt` | 필드 1개 | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketAlertModel.kt` (신규) | `MarketKeys`·`PremiumZone`·`ThresholdBaseline`·`MarketAlertState`·`OwnerMarketAlertStore`·`MarketAlertSettings` | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketObservation.kt` (신규) | `MarketWindows`·`MarketObservation`·`MarketEvidence` | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketNotice.kt` (신규) | `MarketDirection`·`ExtremeWindow`·`ThresholdLine`·`MarketNotice`·`SummaryPnl`·`MarketDecision` | 4 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumSwingPolicy.kt` (신규) | 급변 | 4 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumThresholdPolicy.kt` (신규) | 기준선 | 5 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumExtremePolicy.kt` (신규) | 신고가·신저가, `ExtremeStates` | 6 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlLatest.kt` (신규) | `AccountPnlLatest`·`AccountPnlLatestStore` | 7 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/DailySummaryPolicy.kt` (신규) | `DailySummaryPolicy`·`DailySummaryInput` | 7 |
| `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContent.kt` (신규) | eventKey·메일·Discord | 8 |
| `infrastructure/common/src/main/resources/db/migration/V22__create_owner_market_alert_state.sql` (신규) | DDL 2테이블 | 9 |
| `apps/batch/src/integrationTest/resources/batch-schema.sql` | V22 두 테이블(`IF NOT EXISTS`) | 9 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/ownermarket/JdbcOwnerMarketAlertStore.kt` (신규) | port 구현 | 10 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlLatestStore.kt` (신규) | port 구현 | 10 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/CommonInfrastructureAutoConfiguration.kt` | `@Import` 2개 추가 | 10 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jpa/premium/JpaPremiumRepositoryAdapter.kt` | `PremiumAggregationReadPort` 구현 | 11 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt` | 최신값 기록 (R8) | 12 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt` | `ObjectProvider<AccountPnlLatestStore>` 인자 | 12 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJob.kt` (신규) | 시장 신호 Job | 13 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerMarketAlertConfiguration.kt` (신규) | 조건·구성 | 14 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerMarketAlertProperties.kt` (신규) | `owner-alert.market.*` 바인딩 | 14 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerAlertDeliveryConfiguration.kt` | `private object OwnerAlertReadiness` → `internal object` (R8) | 14 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/OwnerMarketSignalScheduler.kt` (신규) | trigger | 14 |
| `docs/runbooks/owner-market-alert.md` (신규), `docs/runbooks/account-pnl-alert.md`, `docs/runbooks/owner-ops-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md` | 문서 | 16 |
| tests — domain: `owneralert/OwnerMarketAlertKindTest`, `ownermarket/{MarketAlertModelTest,PremiumSwingPolicyTest,PremiumThresholdPolicyTest,PremiumExtremePolicyTest,DailySummaryPolicyTest,OwnerMarketAlertContentTest}`, `accountpnl/AccountPnlLatestTest`, 허용 예외 `OwnerAlertRoutingTest`; infra:common: `V22OwnerMarketAlertStateMigrationIntegrationTest`·`JdbcOwnerMarketAlertStoreIntegrationTest`·`JdbcAccountPnlLatestStoreIntegrationTest`(integrationTest)·`JpaPremiumRepositoryAdapterAggregationReadTest`(test); apps:batch: `OwnerMarketJobConfigTest`·`AccountPnlMonitorLatestRecordTest`·`OwnerMarketSignalJobTest`(+ `OwnerMarketTestFakes.kt`)·`OwnerMarketAlertWiringTest`·`OwnerMarketAlertIntegrationTest` | | 1~15 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  git rev-parse --short HEAD && git merge-base HEAD origin/dev | cut -c1-7                    # base 4f8727c 확인
  ./gradlew test architectureTest --offline --no-daemon                                     # R1·R2 — 테스트 수 기록(build/test-results XML 집계)
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon                   # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                    # R10 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                               # R11 (Docker)
  ./gradlew :apps:api:integrationTest --offline --no-daemon                                 # R13 (Docker)
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1              # (g1) V22 번호 근거
  grep -n "containsExactly" domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt   # (g2) R7 줄
  grep -rn "JobId.entries\|JobId.values()\|MONITORED_JOBS" --include=*.kt apps domain infrastructure architecture-tests   # (g4) 새 JobId 영향
  grep -rn "PremiumRepository\|EntityManagerFactory\|PremiumAggregationReadPort" --include=*.kt apps/batch/src infrastructure/batch/src/main   # (g5) A6 batch 의 JPA adapter 존재·기존 소비자
  grep -n "aggregation" infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/config/AggregationInfrastructureProperties.kt   # (g6) DAY 변환 zone 키
  grep -rn "21\|latestVersion\|currentVersion" apps/api/src/integrationTest/kotlin --include=*Flyway*   # (g7) 최신 버전 숫자 단언 여부
  grep -n "11개\|market\|#90\|owner-alert routes" docs/runbooks/account-pnl-alert.md         # (g8) 문서 갱신 줄
  grep -rn "class MutableClock\|class FakeOwnerAlertStore\|class FakeMailSender\|class FakeDiscordSender" --include=*.kt apps/batch/src/test   # (g9) 재사용 fake 위치
  grep -n "mockk" infrastructure/common/build.gradle.kts                                    # (g10) AC10 MockK 가용
  grep -rn "owner_ops_signal\|owner_alert_delivery" docs/runbooks .ai                       # (g11) job tag 목록 문서
  grep -rn "class PremiumReadAdapter" -A15 --include=*.kt infrastructure/batch/src/main     # (g12) current pair 처리
  grep -n "TABLE_NAME\|information_schema" apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt   # (g13) R9 필요 여부
  grep -rn "OwnerAlertReadiness" --include=*.kt apps/batch/src                              # (g14) internal 전환 영향
  grep -rn "class AccountRound" -A8 --include=*.kt domain/src/main                          # (g15) NoPosition completedAt 출처
  grep -rln "BatchIntegrationTestBase" apps/batch/src/integrationTest                       # (g16) E2E 기반
  ```
- [ ] **Step 2: 예상 결과.** merge-base `4f8727c`. gradle 6건 exit 0(Docker 부재면 integration 3건 "미실행", green 금지). (g1) `V21__create_owner_alert_signal_state.sql`.
  (g2) 2곳. (g4) `MONITORED_JOBS` 는 `OwnerOpsSignalJob` 정의와 테스트의 **참조**뿐(고정 목록 단언 없음). (g5) batch 가 `PremiumRepository`/JPA bean 을 가진 근거가 있고 `PremiumAggregationReadPort` 소비자 없음.
  (g6) `aggregation.zone`. (g7) 최신 버전 숫자 단언 없음. (g10) MockK 있음. (g12) pair 를 요청 pair 로 채우거나 검사함. (g13) 목록 단언 아님. (g14) 이 파일 하나.
  **다르면 멈추고 보고한다** — (g1) 이 V21 이 아니면 V22 번호·AC8, (g4) 가 고정 목록 단언이면 dod 허용 예외를 Gate 1 에서 다시 정한다, (g5) 가 batch JPA 부재를 보이면 design K1 을 다시 정한다,
  (g7) 가 숫자 단언을 보이면 R13 허용 예외 결정, (g10) 이 없으면 AC10 을 integrationTest 로 옮기는 변경 요청. (g2)·(g8)·(g9)·(g11)·(g13)·(g15)·(g16) 결과와 R3 경로 목록을 증거 로그에 남긴다.
- [ ] **Step 3: Commit** — `docs: owner 시장 알림 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: `OwnerAlertKind` 4종과 yml routes (AC1)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt`, `apps/batch/src/main/resources/application.yml`(routes 4블록만);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerMarketAlertKindTest.kt`; 허용 예외(R7) Modify `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt`(Task 0 (g2) 줄만)

**Interfaces — Produces (전문, `OPS_RECOVERED` 뒤):**

```kotlin
    PREMIUM_SWING(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))),
    PREMIUM_THRESHOLD_CROSSED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))),
    PREMIUM_NEW_EXTREME(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))),
    DAILY_SUMMARY(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))),
```

yml (전문, `owner-alert.routes.ops-recovered` 블록 뒤):

```yaml
    premium-swing:
      email: ${OWNER_ALERT_PREMIUM_SWING_EMAIL:}
      discord: ${OWNER_ALERT_PREMIUM_SWING_DISCORD:}
      push: ${OWNER_ALERT_PREMIUM_SWING_PUSH:}
    premium-threshold-crossed:
      email: ${OWNER_ALERT_PREMIUM_THRESHOLD_CROSSED_EMAIL:}
      discord: ${OWNER_ALERT_PREMIUM_THRESHOLD_CROSSED_DISCORD:}
      push: ${OWNER_ALERT_PREMIUM_THRESHOLD_CROSSED_PUSH:}
    premium-new-extreme:
      email: ${OWNER_ALERT_PREMIUM_NEW_EXTREME_EMAIL:}
      discord: ${OWNER_ALERT_PREMIUM_NEW_EXTREME_DISCORD:}
      push: ${OWNER_ALERT_PREMIUM_NEW_EXTREME_PUSH:}
    daily-summary:
      email: ${OWNER_ALERT_DAILY_SUMMARY_EMAIL:}
      discord: ${OWNER_ALERT_DAILY_SUMMARY_DISCORD:}
      push: ${OWNER_ALERT_DAILY_SUMMARY_PUSH:}
```

허용 예외(R7): 종류 이름 기대 목록 끝에 `"PREMIUM_SWING", "PREMIUM_THRESHOLD_CROSSED", "PREMIUM_NEW_EXTREME", "DAILY_SUMMARY"`, key 기대 목록 끝에
`"premium-swing", "premium-threshold-crossed", "premium-new-extreme", "daily-summary"`. 다른 줄 무변경.

**테스트가 재는 것** (`OwnerMarketAlertKindTest`):
- `시장 알림 네 종류의 기본 경로는 설계 표와 같다` — 4종 `defaultRoute` `isEqualTo` (AC1-b)
- `시장 알림 key 는 kebab 이고 fromKey 로 되돌아온다` — 4 key, `fromKey(" Daily-Summary ")` (AC1-a)
- `메일과 Discord 를 모두 쓰면 기본 경로` / `Discord 가 꺼져 있으면 네 종류 모두 메일만` — `OwnerAlertChannels(true, true)`·`EMAIL_ONLY` → `(true, null)` (AC1-c)
- `일일 요약만 메일을 쓴다` — both 채널 해석에서 `DAILY_SUMMARY.email` true, 나머지 false

- [ ] **Step 1:** enum 값 4개를 **stub 경로** `OwnerAlertRoute(email = true, discord = null)` 로 추가, 허용 예외 수정, yml routes 4블록, 새 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` → RED(기본 경로·both 해석 단언 실패). 증거 로그 AC1.
- [ ] **Step 3:** 경로를 위 전문으로. dod AC1 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 종류 네 개와 기본 경로`

### Task 2: `JobId.OWNER_MARKET_SIGNAL`·JobConfig·주기 (AC13)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/job/JobPorts.kt`, `apps/batch/src/main/kotlin/io/premiumspread/application/common/JobConfig.kt`,
`apps/batch/src/main/kotlin/io/premiumspread/config/BatchJobProperties.kt`, `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/BatchSchedulingProperties.kt`,
`apps/batch/src/main/resources/application.yml`(`batch.jobs`·`batch.scheduling`); Test Create `apps/batch/src/test/kotlin/io/premiumspread/config/OwnerMarketJobConfigTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// JobPorts.kt — JobId 끝에
    OWNER_MARKET_SIGNAL("owner_market_signal"),

// DefaultJobConfigProvider.get — when 끝에
        JobId.OWNER_MARKET_SIGNAL -> JobConfig(jobId, "lock:owner-market:signal", Duration.ofSeconds(20), Duration.ofSeconds(10))

// BatchJobProperties — 생성자 끝 필드, when 끝 행
    @field:Valid val ownerMarketSignal: LockSpec =
    LockSpec("lock:owner-market:signal", Duration.ofSeconds(20), Duration.ofSeconds(10)),
        JobId.OWNER_MARKET_SIGNAL -> ownerMarketSignal

// BatchSchedulingProperties — ownerOpsSignal 뒤
    @field:Valid val ownerMarketSignal: FixedRate = FixedRate(Duration.ofSeconds(30)),
```

yml (전문):

```yaml
# batch.scheduling — owner-ops-signal 뒤
    owner-market-signal:
      fixed-rate: ${OWNER_MARKET_SIGNAL_FIXED_RATE:30s}
# batch.jobs — owner-ops-signal 뒤
    owner-market-signal: { lock-key: "${OWNER_MARKET_SIGNAL_LOCK_KEY:lock:owner-market:signal}", lease: "${OWNER_MARKET_SIGNAL_LEASE:20s}", execution-timeout: "${OWNER_MARKET_SIGNAL_TIMEOUT:10s}" }
```

**테스트가 재는 것** (`OwnerMarketJobConfigTest`):
- `시장 신호 Job 의 lock 은 설계 값이다` — `DefaultJobConfigProvider.get(OWNER_MARKET_SIGNAL)` 과 `BatchJobProperties().get(...)` 가 `(lock:owner-market:signal, 20s, 10s)` (AC13-a)
- `yml 바인딩도 같은 값과 30초 주기` — #91 `OwnerAlertJobConfigTest` 와 같은 방식(`PropertySourcesPlaceholdersResolver` 로 `${ENV:default}` 해석)으로 `BatchJobProperties`·`BatchSchedulingProperties` (AC13-b)
- `운영 신호 Job 이 시장 신호 Job 을 감시한다` — `OwnerOpsSignalJob.MONITORED_JOBS` 에 `OWNER_MARKET_SIGNAL` 포함 (AC13-c)

- [ ] **Step 1:** 값을 stub(`Duration.ofSeconds(5)`/`3`, 주기 15s, yml 같은 stub)으로 추가하고 테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerMarketJobConfigTest' --offline --no-daemon` → RED(lease·timeout·주기 단언 실패; (c) 는 stub 에서도 초록). 증거 로그 AC13.
- [ ] **Step 3:** 전문 값으로. dod AC13 명령 → PASS(기존 `OwnerAlertJobConfigTest`·`application.job.ownerops.*` 무수정). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 신호 Job 식별자와 lock·주기`

### Task 3: 시장 알림 모델 — key·설정·상태·port·창·근거 (AC2)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketAlertModel.kt`, `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketObservation.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/MarketAlertModelTest.kt`

**Interfaces — Produces (전문, package `io.premiumspread.domain.ownermarket`):**

```kotlin
// MarketAlertModel.kt
object MarketKeys {
    const val MAX_PAIR_KEY_LENGTH = 40

    /** "{symbol}-{korea}-{foreign}".lowercase(). 부분이 [a-z0-9]+ 가 아니거나 40자 초과면
     *  IllegalArgumentException("market pair key must be [a-z0-9]+ parts within 40 chars."). */
    fun pairKey(pair: MarketPair): String

    fun swing(pairKey: String, direction: MarketDirection): String        // market.{pk}.swing-up / swing-down
    fun threshold(pairKey: String): String                                // market.{pk}.threshold
    fun extreme(pairKey: String, window: ExtremeWindow, direction: MarketDirection): String  // market.{pk}.extreme-{1h|24h}-{high|low}
    fun dailySummary(pairKey: String): String                             // market.{pk}.daily-summary
}

enum class PremiumZone { ABOVE, BETWEEN, BELOW }

data class ThresholdBaseline(val upper: BigDecimal?, val lower: BigDecimal?, val hysteresis: BigDecimal) {
    init {
        require(upper != null || lower != null) { "threshold baseline needs upper or lower." }
        require(hysteresis.signum() >= 0) { "hysteresis must not be negative." }
    }
    fun sameAs(other: ThresholdBaseline): Boolean
}

data class MarketAlertState(
    val signalKey: String,
    val alertKind: OwnerAlertKind,
    val lastNotifiedAt: Instant?,
    val zone: PremiumZone?,
    val baseline: ThresholdBaseline?,
    val summaryDate: LocalDate?,
    val version: Long,
) {
    init {
        require(OwnerAlertSignalState.SIGNAL_KEY_PATTERN.matches(signalKey)) { "signalKey must match [a-z0-9._-]{1,64}." }
        require(version >= 0) { "version must not be negative." }
        require((zone == null) == (baseline == null)) { "zone and baseline must be set together." }
        require(zone == null || alertKind == OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED) { "zone is only for threshold signals." }
        require(summaryDate == null || alertKind == OwnerAlertKind.DAILY_SUMMARY) { "summaryDate is only for daily summary signals." }
    }

    companion object {
        fun initial(signalKey: String, alertKind: OwnerAlertKind): MarketAlertState =
            MarketAlertState(signalKey, alertKind, null, null, null, null, 0)
    }
}

interface OwnerMarketAlertStore {
    fun load(signalKeys: Collection<String>): Map<String, MarketAlertState>
    fun enqueue(states: List<MarketAlertState>, rows: List<AccountPnlMail>): Boolean
}

data class MarketAlertSettings(
    val swingWindow: Duration = Duration.ofMinutes(10),
    val swingThreshold: BigDecimal = BigDecimal("0.5"),
    val swingCooldown: Duration = Duration.ofMinutes(30),
    val upperThreshold: BigDecimal? = null,
    val lowerThreshold: BigDecimal? = null,
    val thresholdHysteresis: BigDecimal = BigDecimal("0.1"),
    val extremeMinDelta: BigDecimal = BigDecimal("0.1"),
    val extremeHourCooldown: Duration = Duration.ofMinutes(30),
    val extremeDayCooldown: Duration = Duration.ofHours(2),
    val summaryHour: Int = 9,
    val summaryPnlMaxAge: Duration = Duration.ofMinutes(10),
) {
    // init = design §7.1 require 전문(메시지 포함) 그대로
    val thresholdBaseline: ThresholdBaseline?
}

// MarketObservation.kt
data class MarketWindows(val minutes: AggregationWindow, val hours: AggregationWindow, val previousDay: AggregationWindow) {
    companion object { fun of(now: Instant, zone: ZoneId): MarketWindows }
}

data class MarketObservation(
    val pair: MarketPair,
    val current: PremiumSnapshot?,
    val minutes: List<PremiumAggregationSnapshot>?,
    val hours: List<PremiumAggregationSnapshot>?,
)

object MarketEvidence {
    val CURRENT_MAX_AGE: Duration = Duration.ofSeconds(10)
    fun current(snapshot: PremiumSnapshot?, pair: MarketPair, now: Instant): PremiumSnapshot?
    fun sufficient(actual: Int, expected: Int): Boolean   // actual * 5 >= expected * 4; expected < 1 → IllegalArgumentException("expected must be at least 1.")
}
```

`MarketDirection`·`ExtremeWindow` 는 Task 4 의 `MarketNotice.kt` 에 둔다 — 이 태스크가 먼저 쓰므로 **이 태스크에서** `MarketNotice.kt` 를 만들고 두 enum 만 넣는다(Task 4 가 나머지를 추가).

```kotlin
// MarketNotice.kt (이 태스크 분량)
enum class MarketDirection { UP, DOWN }
enum class ExtremeWindow(val duration: Duration) { ONE_HOUR(Duration.ofHours(1)), ONE_DAY(Duration.ofHours(24)) }
```

`toString`: 전부 자동 data class `toString` — key·kind·시각·구역·기준값·일자·version·임계값만, 비밀·계정 값 없음.

**테스트가 재는 것** (`MarketAlertModelTest`, 고정 `Instant`, `ZoneId.of("Asia/Seoul")`): dod AC2 (a)~(f) 전 항목 — pair key·41자·`BTC_X` 거부, 상태 key 8개 문자열,
설정 기본값·각 검증 경계(2h 정확히 거부·2.21 통과), 상태 불변식 5종, `sameAs`(scale 무관·null), 창 3개(`2026-10-29T00:30:15Z` 입력의 정확한 `Instant`), current 나이 10s/10s+1ms/미래/다른 pair,
`sufficient` 경계 5개.

- [ ] **Step 1:** 타입 선언·시그니처와 테스트 작성. 함수 본문은 stub(`pairKey` = `"x"`, `sufficient` = true, `current` = snapshot, `of` 는 고정 창, settings init 비움).
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.MarketAlertModelTest' --offline --no-daemon` → RED(값·예외 단언 실패). 증거 로그 AC2.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 상태·설정·관측 창 모델`

### Task 4: 알림 타입과 급변 정책 (AC3)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/MarketNotice.kt`; Create `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumSwingPolicy.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/PremiumSwingPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// MarketNotice.kt 에 추가
enum class ThresholdLine { UPPER, LOWER }

sealed interface MarketNotice {
    val pair: MarketPair
    val at: Instant
    val kind: OwnerAlertKind

    data class Swing(
        override val pair: MarketPair, override val at: Instant,
        val direction: MarketDirection, val window: Duration,
        val reference: BigDecimal, val current: BigDecimal, val currentObservedAt: Instant, val delta: BigDecimal,
    ) : MarketNotice { override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_SWING }

    data class ThresholdCrossed(
        override val pair: MarketPair, override val at: Instant,
        val line: ThresholdLine, val threshold: BigDecimal, val hysteresis: BigDecimal,
        val current: BigDecimal, val currentObservedAt: Instant,
    ) : MarketNotice { override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_THRESHOLD_CROSSED }

    data class NewExtreme(
        override val pair: MarketPair, override val at: Instant,
        val window: ExtremeWindow, val direction: MarketDirection,
        val previous: BigDecimal, val current: BigDecimal, val currentObservedAt: Instant,
    ) : MarketNotice { override val kind: OwnerAlertKind get() = OwnerAlertKind.PREMIUM_NEW_EXTREME }

    data class DailySummary(
        override val pair: MarketPair, override val at: Instant,
        val date: LocalDate, val day: PremiumAggregationSnapshot?, val fx: ExchangeRateSnapshot?,
        val pnl: SummaryPnl,
    ) : MarketNotice { override val kind: OwnerAlertKind get() = OwnerAlertKind.DAILY_SUMMARY }
}

sealed interface SummaryPnl {
    data class Value(val netPercent: BigDecimal, val netKrw: BigDecimal, val calculatedAt: Instant) : SummaryPnl {
        override fun toString(): String = "Value(calculatedAt=$calculatedAt)"
    }
    data class NoPosition(val calculatedAt: Instant) : SummaryPnl
    data object Unavailable : SummaryPnl
}

data class MarketDecision(val next: List<MarketAlertState>, val notices: List<MarketNotice>) {
    companion object { val NONE: MarketDecision = MarketDecision(emptyList(), emptyList()) }
}

// PremiumSwingPolicy.kt
object PremiumSwingPolicy {
    /** design §7.2(A) W1~W4. [up]·[down] 의 signalKey 는 MarketKeys.swing(...) 이어야 한다(아니면 IllegalArgumentException("swing state key mismatch.")). */
    fun decide(
        observation: MarketObservation, up: MarketAlertState, down: MarketAlertState,
        settings: MarketAlertSettings, now: Instant,
    ): MarketDecision
}
```

전이표는 design §7.2(A) 그대로(코드에 옮길 정본). `delta` 는 `current − reference` 그대로(scale 은 입력 그대로, 반올림 없음).

**테스트가 재는 것** (`PremiumSwingPolicyTest`, now = `2026-10-29T00:00:30Z`, bucket observedAt = `floorMinute(now) − k분`): dod AC3 (a)~(g) — 저점 2.00 + 2.50 발동·2.49 미발동, 고점 3.00 DOWN,
양방향 동시, 쿨다운 `now−30m` 발동·`now−30m+1ms` 미발동(next 없음), 11분 전 bucket 무시·bucket 7개 W1, high 9.99 무시(종가만), current/minutes null → `NONE`. next 의 version 이 입력 version 과 같음.

- [ ] **Step 1:** 타입·시그니처·테스트 작성, `decide` 는 `MarketDecision.NONE` stub.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.PremiumSwingPolicyTest' --offline --no-daemon` → RED(발동 단언 실패; 미발동·null 은 stub 에서도 초록). 증거 로그 AC3.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 김프 급변 판정 정책`

### Task 5: 기준선 돌파 정책 (AC4)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumThresholdPolicy.kt`; Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/PremiumThresholdPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
object PremiumThresholdPolicy {
    /** design §7.2(B) classify 1~5. prev null = 히스테리시스 없이 분류. */
    fun classify(rate: BigDecimal, prev: PremiumZone?, baseline: ThresholdBaseline): PremiumZone

    /**
     * design §7.2(B) H1~H6. settings.thresholdBaseline 이 null 이면 IllegalArgumentException("threshold is disabled.")
     * — 호출자(Job)가 꺼짐이면 부르지 않는다. state.signalKey 불일치 검사는 하지 않는다(호출자가 MarketKeys.threshold 로 만든다).
     */
    fun decide(observation: MarketObservation, state: MarketAlertState, settings: MarketAlertSettings, now: Instant): MarketDecision
}
```

**테스트가 재는 것** (`PremiumThresholdPolicyTest`, U=3.0·L=1.0·h=0.1): dod AC4 (a)~(h) — H2 재설정 무알림, `3.0` ABOVE 발동·`2.9999` 미발동, ABOVE 유지 2.91·이탈 2.90·재돌파, 하단 대칭(1.09 유지·1.10 이탈),
ABOVE→BELOW 직행 LOWER, baseline 변경(U 3.0→3.2) 재분류 무알림, U 만 설정, current null. `classify` 단독 경계(`rate == U`, `rate == U − h` with prev ABOVE).

- [ ] **Step 1:** 시그니처·테스트, 본문 stub(`classify` = `BETWEEN`, `decide` = `NONE`).
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.PremiumThresholdPolicyTest' --offline --no-daemon` → RED. 증거 로그 AC4.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 김프 기준선 돌파 판정 정책(히스테리시스)`

### Task 6: 신고가·신저가 정책 (AC5)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/PremiumExtremePolicy.kt`; Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/PremiumExtremePolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
data class ExtremeStates(
    val hourHigh: MarketAlertState,
    val hourLow: MarketAlertState,
    val dayHigh: MarketAlertState,
    val dayLow: MarketAlertState,
)

object PremiumExtremePolicy {
    /** design §7.2(C) E1~E5. 방향(UP=HIGH, DOWN=LOW)마다 독립. */
    fun decide(observation: MarketObservation, states: ExtremeStates, settings: MarketAlertSettings, now: Instant): MarketDecision
}
```

**테스트가 재는 것** (`PremiumExtremePolicyTest`, now = `2026-10-29T00:20:30Z` → 분 기대 80·시 기대 23): dod AC5 (a)~(g) — 24h 발동 + 1h 쿨다운 동시 기록·`2.5999` 미발동, 1h 단독 발동,
24h 쿨다운 중 1h 억제·`now−2h` 재발동, 1h 쿨다운 경계, 시 bucket 18개 → 24h 무판정·1h 판정, 분 1h 구간 47개 → 1h 무판정, 신저가 대칭, 고가·저가 사용(종가와 다르게 둔 fixture).

- [ ] **Step 1:** 시그니처·테스트, `decide` = `NONE` stub.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.PremiumExtremePolicyTest' --offline --no-daemon` → RED. 증거 로그 AC5.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 김프 1시간·24시간 신고가 신저가 판정 정책`

### Task 7: 실계정 최신값 타입과 일일 요약 정책 (AC6)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlLatest.kt`, `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/DailySummaryPolicy.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlLatestTest.kt`, `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/DailySummaryPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// AccountPnlLatest.kt (package io.premiumspread.domain.accountpnl)
data class AccountPnlLatest(
    val position: Boolean,
    val netPercent: BigDecimal?,
    val netKrw: BigDecimal?,
    val calculatedAt: Instant,
) {
    init {
        require(position == (netPercent != null) && position == (netKrw != null)) {
            "net values must be present exactly when a position exists."
        }
    }

    override fun toString(): String = "AccountPnlLatest(position=$position, calculatedAt=$calculatedAt)"

    companion object {
        fun computed(pnl: AccountNetPnl): AccountPnlLatest   // netPercent 그대로, netKrw.setScale(0, HALF_UP), pnl.calculatedAt
        fun noPosition(calculatedAt: Instant): AccountPnlLatest = AccountPnlLatest(false, null, null, calculatedAt)
    }
}

interface AccountPnlLatestStore {
    fun record(latest: AccountPnlLatest)
    fun find(): AccountPnlLatest?
}

// DailySummaryPolicy.kt (package io.premiumspread.domain.ownermarket)
data class DailySummaryInput(val day: PremiumAggregationSnapshot?, val fx: ExchangeRateSnapshot?, val pnl: AccountPnlLatest?)

object DailySummaryPolicy {
    fun due(state: MarketAlertState, settings: MarketAlertSettings, now: Instant, zone: ZoneId): Boolean
    fun decide(state: MarketAlertState, pair: MarketPair, input: DailySummaryInput, settings: MarketAlertSettings, now: Instant, zone: ZoneId): MarketDecision
    fun summaryPnl(pnl: AccountPnlLatest?, settings: MarketAlertSettings, now: Instant): SummaryPnl
}
```

**테스트가 재는 것**: dod AC6 (a)~(d) — `due` 6 시나리오(08:59:59 false, 09:00:00 true, 같은 날·미래 날짜 false, 전날 true, hour 0), `decide` 의 next(`summaryDate`·`lastNotifiedAt`)와
notice `date = 2026-10-28`, `summaryPnl` 4분기(null·10m·10m+1ms·position false), `AccountPnlLatest` 불변식·`computed` 반올림(`123456.5` → `123457`)·`toString` 에 `123457`·`12.34` 문자열 없음.
`computed` 테스트의 `AccountNetPnl` 은 `AccountNetPnl` 생성자에 가짜 값으로 만든다(실계정 값 금지).

- [ ] **Step 1:** 시그니처·테스트, 본문 stub(`due` = false, `decide` = `NONE`, `summaryPnl` = `Unavailable`, `computed` 는 반올림 없이).
- [ ] **Step 2:** dod AC6 명령 → RED. 증거 로그 AC6.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 실계정 최신 순수익률 타입과 일일 요약 판정`

### Task 8: 시장 알림 본문 (AC7)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContent.kt`; Test Create `domain/src/test/kotlin/io/premiumspread/domain/ownermarket/OwnerMarketAlertContentTest.kt`

**Interfaces — Produces (전문):**

```kotlin
object OwnerMarketAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-market-alert.md"
    const val UP_COLOR = 0xE74C3C
    const val DOWN_COLOR = 0x3498DB
    const val SUMMARY_COLOR = 0x95A5A6

    fun eventKey(notice: MarketNotice): String
    fun mail(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): AccountPnlMail
    fun discord(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): DiscordMessage
}
```

eventKey·제목·이모지·색·본문·대체 줄은 design §8.1~§8.4 표·블록이 정본(exact copy). 이모지는 유니코드 escape 상수로 둔다(`OwnerOpsAlertContent.WARNING` 과 같은 방식).
메일 subject = `"[시장] " + 핵심`, Discord title = `"{이모지} " + 핵심`. `{d(x)}` 는 이 파일의 private 함수(시간/분/초 규칙 — `OwnerOpsAlertContent` 와 같은 규칙, 공유하지 않음).

**테스트가 재는 것** (`OwnerMarketAlertContentTest`): dod AC7 (a)~(e) — 메일 golden 9건·Discord golden 9건(전체 문자열 `isEqualTo`), 색 상수 3개 정수값, eventKey 4형식·정규식,
금지 문자열 5종, 급변·기준선·신고가 결과에 `원` 없음. 시각 `2026-10-29T00:00:00Z`, zone `Asia/Seoul`.

- [ ] **Step 1:** 시그니처·테스트, 본문 stub(`mail`·`discord` = `"x"` 고정, `eventKey` 는 전문 구현 — eventKey 테스트는 stub 에서 초록).
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.ownermarket.OwnerMarketAlertContentTest' --offline --no-daemon` → RED(golden 실패). 증거 로그 AC7.
- [ ] **Step 3:** 템플릿 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 메일·Discord 본문`

### Task 9: V22 migration 과 batch fixture (AC8)

**Files:** Create `infrastructure/common/src/main/resources/db/migration/V22__create_owner_market_alert_state.sql`; Modify `apps/batch/src/integrationTest/resources/batch-schema.sql`(끝에 추가);
Test Create `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V22OwnerMarketAlertStateMigrationIntegrationTest.kt`

**Interfaces — Produces (DDL 전문):**

```sql
-- owner 시장 알림(#90) 신호별 지속 상태. 한 신호 = 한 행(쿨다운 시각·기준선 구역·일일 요약 일자).
-- 상태 갱신과 outbox(account_pnl_mail) 행 삽입은 같은 트랜잭션에서 행별 version 조건부로 한다.
-- 비밀값(webhook URL·수신 주소·API key)과 알림 본문은 저장하지 않는다.
CREATE TABLE owner_market_alert_state (
    signal_key         VARCHAR(64)   NOT NULL,
    alert_kind         VARCHAR(32)   NOT NULL,
    last_notified_at   DATETIME(6)   NULL,
    premium_zone       VARCHAR(16)   NULL,
    upper_percent      DECIMAL(10,4) NULL,
    lower_percent      DECIMAL(10,4) NULL,
    hysteresis_percent DECIMAL(10,4) NULL,
    summary_date       DATE          NULL,
    version            BIGINT        NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_owner_market_alert_state PRIMARY KEY (signal_key),
    CONSTRAINT ck_owner_market_alert_state_zone CHECK (
        (premium_zone IS NULL AND upper_percent IS NULL AND lower_percent IS NULL AND hysteresis_percent IS NULL)
        OR (premium_zone IN ('ABOVE', 'BETWEEN', 'BELOW') AND hysteresis_percent IS NOT NULL
            AND hysteresis_percent >= 0 AND (upper_percent IS NOT NULL OR lower_percent IS NOT NULL))
    ),
    CONSTRAINT ck_owner_market_alert_state_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 실계정 순손익 모니터가 회차마다 남기는 최신 순수익률(단일 행). 일일 요약(#90)이 읽는다.
-- has_position = FALSE 면 포지션 없음 관측이고 금액 열은 NULL 이다. 수량·가격·수수료·credential 은 저장하지 않는다.
CREATE TABLE account_pnl_latest (
    id            TINYINT       NOT NULL PRIMARY KEY,
    has_position  BOOLEAN       NOT NULL,
    net_percent   DECIMAL(12,2) NULL,
    net_krw       DECIMAL(20,0) NULL,
    calculated_at DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    CONSTRAINT ck_account_pnl_latest_singleton CHECK (id = 1),
    CONSTRAINT ck_account_pnl_latest_values CHECK (
        (has_position = TRUE AND net_percent IS NOT NULL AND net_krw IS NOT NULL)
        OR (has_position = FALSE AND net_percent IS NULL AND net_krw IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

fixture: 같은 두 DDL 을 `CREATE TABLE IF NOT EXISTS` 로, 앞에 주석 `-- V22 와 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.`

**테스트가 재는 것** (`V22…MigrationIntegrationTest`, `V21OwnerAlertSignalStateMigrationIntegrationTest` 와 같은 패턴): dod AC8 (a)~(c) — 빈 DB 1~22 성공·열 순서/타입/nullable·PK·collation,
V21 target 에 기존 행 넣고 latest → 기존 행 무변경·새 테이블 0행, CHECK 거부 8종.

- [ ] **Step 1:** 테스트 작성(V22 파일 없음 상태).
- [ ] **Step 2:** AC8 명령 → RED(버전 22 부재·테이블 부재 assertion — 테스트가 테이블 부재를 `assertThat(...).isEmpty()` 류로 먼저 확인하도록 써서 SQL 예외가 아닌 assertion 실패로 둔다). 증거 로그 AC8.
- [ ] **Step 3:** V22·fixture 추가. AC8 명령 → PASS. `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` → PASS. `./gradlew :apps:batch:integrationTest --offline --no-daemon` → PASS(R9 확인). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 상태·실계정 최신값 테이블 V22`

### Task 10: `JdbcOwnerMarketAlertStore`·`JdbcAccountPnlLatestStore` (AC9)

**Files:** Create `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/ownermarket/JdbcOwnerMarketAlertStore.kt`,
`infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlLatestStore.kt`;
Modify `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/CommonInfrastructureAutoConfiguration.kt`(`@Import` 에 `JdbcOwnerAlertOutbox::class` 뒤 두 줄);
Test Create `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/ownermarket/JdbcOwnerMarketAlertStoreIntegrationTest.kt`,
`infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlLatestStoreIntegrationTest.kt`

**Interfaces — Produces (전문):**

```kotlin
@Repository
class JdbcOwnerMarketAlertStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : OwnerMarketAlertStore

@Repository
class JdbcAccountPnlLatestStore(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) : AccountPnlLatestStore
```

요건:
- 동작·검증 메시지·트랜잭션 규칙은 design §5.5·§6.2 전문(`JdbcOwnerAlertOutbox` 와 같은 순서). 행 검증·삽입은 `OwnerAlertOutboxRows.validate`/`insert` 호출(그 파일 무변경).
- 열 매핑은 design §5.4 표. `BigDecimal` 은 그대로 바인딩, `LocalDate` ↔ `java.sql.Date.valueOf`/`toLocalDate`, `Instant` ↔ `Timestamp.from`/`toInstant`.
- 모르는 enum 값 예외 메시지는 design §5.4 문구(값 없음).
- `record` SQL 은 design §6.2 전문(`ON DUPLICATE KEY UPDATE` 값 재바인딩, `VALUES()` 미사용). `updated_at` = `clock.instant()`.

**테스트가 재는 것**: dod AC9 (a)~(j) — 실제 MySQL(기존 `JdbcOwnerAlertOutboxIntegrationTest` 의 컨테이너·fixture 패턴), 상태 3종 round-trip(`BigDecimal` compareTo, `LocalDate`),
두 번째 충돌 전체 롤백, version 0 중복 INSERT 충돌, 행만·상태만, 중복 key·256자 subject 거부 + DB 무변경, `JdbcAccountPnlAlertStore.claim` 호환, latest upsert 3단계,
`ApplicationContextRunner`(DataSource/JdbcTemplate/TransactionManager auto-configuration + `CommonInfrastructureAutoConfiguration` + 고정 `Clock`)로 bean 2개 해석.

- [ ] **Step 1:** 클래스를 stub(`load` = 빈 map, `enqueue` = true 무기록, `record` 무동작, `find` = null)으로 만들고 `@Import` 는 **아직 추가하지 않는다**. 테스트 작성.
- [ ] **Step 2:** AC9 명령 → RED(기록·읽기·예외·bean 해석 단언 실패). 증거 로그 AC9.
- [ ] **Step 3:** 구현 + `@Import` 2줄. AC9 명령 → PASS. `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` (R10) → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 상태 저장소와 실계정 최신값 저장소`

### Task 11: `PremiumAggregationReadPort` 구현 (AC10)

**Files:** Modify `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jpa/premium/JpaPremiumRepositoryAdapter.kt`;
Test Create `infrastructure/common/src/test/kotlin/io/premiumspread/infrastructure/common/persistence/jpa/premium/JpaPremiumRepositoryAdapterAggregationReadTest.kt`

**Interfaces — Produces (전문):**

```kotlin
class JpaPremiumRepositoryAdapter(
    private val premiumRepository: SpringDataPremiumRepository,
    private val premiumCacheReader: PremiumCacheReader? = null,
    private val premiumAggregationCacheReader: PremiumAggregationCacheReader? = null,
    private val premiumAggregationQueryRepository: PremiumAggregationQueryRepository,
) : PremiumRepository, PremiumAggregationReadPort {
    override fun read(pair: MarketPair, window: AggregationWindow, unit: AggregationUnit): List<PremiumAggregationSnapshot>
}
```

요건: `MINUTE → "1m"`, `HOUR → "1h"`, `DAY → "1d"` 매핑 후 `findAggregationByPair(pair, interval, window.from, window.to)` 위임(기존 메서드 무변경). 결과 중 `it.pair != pair` 가 있으면
`IllegalStateException("premium aggregation pair mismatch")`. `AggregationUnit` 매핑은 `when (unit)`(이 enum 은 `OwnerAlertKind` 가 아니므로 전체 `when` 허용).

**테스트가 재는 것** (MockK, `SpringDataPremiumRepository`·cache reader·query repository mock): dod AC10 (a)~(d) — interval 3종·from/to 전달(`verify`), 같은 `observedAt` 이면 DB 값,
pair 다른 snapshot → 예외 메시지 정확히, cache reader null → DB 결과.

- [ ] **Step 1:** `read` 를 `emptyList()` stub 으로 추가, 테스트 작성.
- [ ] **Step 2:** AC10 명령 → RED(위임·예외 단언 실패). 증거 로그 AC10.
- [ ] **Step 3:** 구현. AC10 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 김프 집계 범위 읽기 port 구현`

### Task 12: PnL 모니터의 최신값 기록 (AC11)

**Files:** Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`(R8 한정), `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorLatestRecordTest.kt`

**Interfaces — Produces (전문):**

```kotlin
class AccountPnlMonitorJob(
    // … 기존 인자 13개 그대로 …
    private val accountAlerts: AccountAlertSettings? = null,
    /** 실계정 최신 순수익률 기록(#90 일일 요약용). null 이면 기록하지 않는다. */
    private val latestPnl: AccountPnlLatestStore? = null,
)

// AccountPnlMonitorConfiguration.accountPnlMonitorJob — 인자 끝에
        latestPnl: ObjectProvider<AccountPnlLatestStore>,
// 호출 끝에
        latestPnl = latestPnl.ifAvailable,
```

요건: design §6.3 표 그대로 — save true 뒤·`deliver()` 전 기록, Failed·save false/예외·저장 전 취소는 기록 없음, `record` 예외는 WARN `Account PnL latest record failed: error={}`(단순 클래스 이름)
후 계속, 취소면 interrupt 복구 후 기록 건너뛰고 `deliver()`. private `Observed` 에 `completedAt: Instant?`(성공 관측의 `completedAt`, 실패 null) 추가. 그 밖 줄 무변경(R8).

**테스트가 재는 것** (`AccountPnlMonitorLatestRecordTest`, 기존 `AccountPnlMonitorJob*Test` 의 fake 를 **import 해 재사용**하고 수정하지 않는다; 파일 안 private `FakeAccountPnlLatestStore(records, nextFailure)`):
dod AC11 (a)~(e) — Computed → `computed(pnl)` 1회(값 비교), NoPosition → `noPosition(completedAt)`, Failed·save false·save 예외·사전 interrupt → 0회, record 예외 → `JobResult`·발송 수가
예외 없는 회차와 같고 WARN 줄이 `error=IllegalStateException` 로 끝남(메시지 없음), `latestPnl` 미지정 → 기존 결과.

- [ ] **Step 1:** 생성자 인자와 배선만 추가(기록 블록 없음 = stub). 테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorLatestRecordTest' --offline --no-daemon` → RED(record 횟수 단언 실패; 0회 시나리오는 초록). 증거 로그 AC11.
- [ ] **Step 3:** 기록 블록 구현. dod AC11 명령 → PASS(기존 `job.accountpnl.*`·`AccountPnlMonitorWiringTest` 무수정). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 실계정 모니터가 최신 순수익률을 기록`

### Task 13: 시장 신호 Job (AC12)

**Files:** Create `apps/batch/src/main/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJob.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketTestFakes.kt`, `apps/batch/src/test/kotlin/io/premiumspread/application/job/ownermarket/OwnerMarketSignalJobTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.application.job.ownermarket

@Suppress("LongParameterList")
class OwnerMarketSignalJob(
    private val premiums: PremiumReadPort,
    private val aggregations: PremiumAggregationReadPort,
    private val fx: FxRateReadPort,
    private val pnlLatest: AccountPnlLatestStore,
    private val market: BatchMarketProvider,
    private val store: OwnerMarketAlertStore,
    private val routing: OwnerAlertRouting,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val settings: MarketAlertSettings = MarketAlertSettings(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    fun run(): JobResult   // = executor.execute(jobConfigs.get(JobId.OWNER_MARKET_SIGNAL)) { evaluate() }

    companion object {
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}
```

`JobResult` 분기·로그 키·담지 않는 값 = design §7.4 표 전문. 회차 순서 = design §7.4 1~9. 취소 판정 = 원인 체인 깊이 16(`OwnerOpsSignalJob.kt:170-175` 와 같은 private 함수를 이 파일에 둔다).

테스트 fake (`OwnerMarketTestFakes.kt`, 전문 시그니처):

```kotlin
class FakePremiumRead(var next: (MarketPair) -> PremiumSnapshot?) : PremiumReadPort
class FakeAggregationRead(var minutes: () -> List<PremiumAggregationSnapshot>, var hours: () -> List<PremiumAggregationSnapshot>,
                          var day: () -> List<PremiumAggregationSnapshot>) : PremiumAggregationReadPort { val calls: MutableList<AggregationUnit> }
class FakeFxRead(var next: () -> ExchangeRateSnapshot?) : FxRateReadPort { var calls: Int }
class FakeAccountPnlLatest(var latest: () -> AccountPnlLatest?) : AccountPnlLatestStore { var finds: Int }
class FakeOwnerMarketAlertStore : OwnerMarketAlertStore {
    val states: MutableMap<String, MarketAlertState>; val rows: MutableList<AccountPnlMail>; val loadedKeys: MutableList<Collection<String>>
    var enqueueCalls: Int; var loadFailure: Exception?; var nextEnqueueFailure: Exception?; var nextEnqueueConflict: Boolean
}
```

(`next`/`minutes` 등 람다는 테스트가 `MutableClock` 상대 시각으로 값을 만든다. `JobExecutor` 구성·`MutableClock` 은 Task 0 (g9) 위치의 기존 것을 import 해 쓴다.)

**테스트가 재는 것** (`OwnerMarketSignalJobTest`): dod AC12 (a)~(m) 전부 — 행 채널·target·push, 상태 key, 쿨다운 경계 회차, 기준선 꺼짐 시 `loadedKeys` 에 threshold 없음,
요약 09:00/08:59:59(`FakeAggregationRead.calls` 에 DAY 없음·`FakeFxRead.calls == 0`), 요약 입력 예외 후 다음 회차 1통, 원천별 모름 조합, 새 인스턴스 쿨다운 유지, JobResult 4분기,
취소 재전파(`assertThrows` + `Thread.interrupted()` true + `enqueueCalls == 0`), `EMAIL_ONLY`, `OutputCaptureExtension` 로그 금지 문자열(`example.test`·`@`·fake 예외 메시지 `secret-detail`·`2.50`·`원`).

- [ ] **Step 1:** Job 을 `run() = executor.execute(...) { JobResult.Success }` stub 으로, fake·테스트 작성.
- [ ] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.ownermarket.*' --offline --no-daemon` → RED(행·상태·Failure/Skipped·취소 단언 실패; (a)·(h)·(i) 무기록 시나리오는 stub 에서도 초록). 증거 로그 AC12.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 신호 Job`

### Task 14: 앱 배선·기동 조건·Scheduler·`owner-alert.market` (AC14)

**Files:** Create `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerMarketAlertConfiguration.kt`, `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerMarketAlertProperties.kt`,
`apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/OwnerMarketSignalScheduler.kt`; Modify `apps/batch/src/main/kotlin/io/premiumspread/config/OwnerAlertDeliveryConfiguration.kt`(R8: `private object` → `internal object`),
`apps/batch/src/main/resources/application.yml`(`owner-alert.market` 블록, `owner-alert.ops` 뒤); Test Create `apps/batch/src/test/kotlin/io/premiumspread/config/OwnerMarketAlertWiringTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// OwnerMarketAlertProperties.kt
@ConfigurationProperties("owner-alert.market")
data class OwnerMarketAlertProperties(
    val swingWindow: Duration = Duration.ofMinutes(10),
    val swingThreshold: BigDecimal = BigDecimal("0.5"),
    val swingCooldown: Duration = Duration.ofMinutes(30),
    val upperThreshold: BigDecimal? = null,
    val lowerThreshold: BigDecimal? = null,
    val thresholdHysteresis: BigDecimal = BigDecimal("0.1"),
    val extremeMinDelta: BigDecimal = BigDecimal("0.1"),
    val extremeHourCooldown: Duration = Duration.ofMinutes(30),
    val extremeDayCooldown: Duration = Duration.ofHours(2),
    val summaryHour: Int = 9,
    val summaryPnlMaxAge: Duration = Duration.ofMinutes(10),
) {
    fun toSettings(): MarketAlertSettings   // 필드 1:1
}

// OwnerMarketAlertConfiguration.kt
/** OwnerAlertReadiness.ready(env, logging = false) ∧ owner-alert.market.enabled(빈 값·없음 = true, trim 후 equalsIgnoreCase "true").
 *  꺼짐이면 OwnerMarketAlertConfiguration 평가 때만 INFO "owner-alert market alerts disabled by OWNER_ALERT_MARKET_ENABLED". */
class OwnerMarketAlertEnabledCondition : Condition

@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerMarketAlertEnabledCondition::class)
@EnableConfigurationProperties(OwnerMarketAlertProperties::class)
class OwnerMarketAlertConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun ownerMarketSignalJob(
        premiums: PremiumReadPort,
        aggregations: PremiumAggregationReadPort,
        fx: FxRateReadPort,
        pnlLatest: AccountPnlLatestStore,
        market: BatchMarketProvider,
        store: OwnerMarketAlertStore,
        routing: OwnerAlertRouting,
        executor: JobExecutor,
        clock: Clock,
        properties: OwnerMarketAlertProperties,
        @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
        jobConfigs: JobConfigProvider,
    ): OwnerMarketSignalJob

// OwnerMarketSignalScheduler.kt
@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerMarketAlertEnabledCondition::class)
class OwnerMarketSignalScheduler(
    private val job: OwnerMarketSignalJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-market-signal.fixed-rate:30000}")
    fun evaluate() { job.run() }
}
```

yml (전문, `owner-alert.ops` 블록 뒤):

```yaml
  # owner 시장 알림 (김프 급변·기준선 돌파·신고가/신저가·일일 요약). 쓸 수 있는 owner 채널이 없으면 기동하지 않는다.
  # 단위 %p = 김프 퍼센트 포인트. 기준선은 upper-threshold·lower-threshold 중 하나 이상 설정해야 동작한다(기본 꺼짐).
  market:
    enabled: ${OWNER_ALERT_MARKET_ENABLED:true}
    swing-window: ${OWNER_ALERT_MARKET_SWING_WINDOW:10m}
    swing-threshold: ${OWNER_ALERT_MARKET_SWING_THRESHOLD:0.5}
    swing-cooldown: ${OWNER_ALERT_MARKET_SWING_COOLDOWN:30m}
    upper-threshold: ${OWNER_ALERT_MARKET_UPPER_THRESHOLD:}
    lower-threshold: ${OWNER_ALERT_MARKET_LOWER_THRESHOLD:}
    threshold-hysteresis: ${OWNER_ALERT_MARKET_THRESHOLD_HYSTERESIS:0.1}
    extreme-min-delta: ${OWNER_ALERT_MARKET_EXTREME_MIN_DELTA:0.1}
    extreme-hour-cooldown: ${OWNER_ALERT_MARKET_EXTREME_HOUR_COOLDOWN:30m}
    extreme-day-cooldown: ${OWNER_ALERT_MARKET_EXTREME_DAY_COOLDOWN:2h}
    summary-hour: ${OWNER_ALERT_MARKET_SUMMARY_HOUR:9}
    summary-pnl-max-age: ${OWNER_ALERT_MARKET_SUMMARY_PNL_MAX_AGE:10m}
```

**테스트가 재는 것** (`OwnerMarketAlertWiringTest`, `ApplicationContextRunner` + mock port bean, `OutputCaptureExtension`, 기존 `OwnerAlertDeliveryWiringTest` 의 property 조합을 참고만 하고 수정하지 않는다):
dod AC14 (a)~(h) — ready + 기본 → Job·Scheduler 존재(`account-pnl-monitor.enabled=false`), `enabled=false` → 없음 + INFO 1줄, `enabled=`(빈 값) → 존재, 채널 없음 → 없음·INFO 없음,
scheduling off → 없음, 11개 값 바인딩(`upper-threshold=3.0` 등)과 빈 기준선 null, `swing-threshold=0` 기동 실패 원인 메시지, infra `OwnerAlertProperties` 를 같은 environment 로 바인딩해도 예외 없음,
yml 원문을 읽어 `owner-alert.market` 12키 기본값 문자열 비교, 출력에 `discord.example.test`·`owner@example.test` 없음.

- [ ] **Step 1:** 조건의 `matches` 를 `false` stub 으로, 나머지 전문 작성 + 테스트.
- [ ] **Step 2:** AC14 명령 → RED(bean 존재·INFO 단언 실패). 증거 로그 AC14.
- [ ] **Step 3:** 조건 구현. AC14 명령 → PASS. `./gradlew :apps:batch:test --tests 'io.premiumspread.config.*' --offline --no-daemon` → PASS(기존 wiring 무수정). `./gradlew test architectureTest --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: owner 시장 알림 기동 조건과 배선`

### Task 15: E2E 통합 (AC15)

**Files:** Test Create `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerMarketAlertIntegrationTest.kt`

요건(구성): `BatchIntegrationTestBase` 상속(Task 0 (g16)), `@Autowired` 실제 `OwnerMarketAlertStore`·`AccountPnlAlertStore`·`AccountPnlLatestStore`·`PremiumAggregationReadPort`,
`premium_minute`·`premium_hour`·`premium_day` 행은 `JdbcTemplate` INSERT(UTC `minute_at`·`hour_at`, `day_at` = KST 날짜), fake `PremiumReadPort`·`FxRateReadPort`(clock 상대),
`MutableClock`(시작 `2026-10-29T00:00:00Z`), `JobExecutor(AlwaysLock, NoopRecorder, NoopAlert, clock)`(#91 `OwnerOpsAlertIntegrationTest` 와 같은 구성), 기록용 메일·Discord sender +
`OwnerAlertDeliveryJob`. 각 테스트 전 `owner_market_alert_state`·`account_pnl_latest`·`account_pnl_mail`·`premium_*` 를 비운다. Redis premium 집계 key 는 쓰지 않는다(DB 경로 검증).

**테스트가 재는 것**: dod AC15 (a)~(f) — 급변 행 SENT·sender 1회(MARKET/SOUND), +10분 쿨다운 무행, 24h 신고가 SILENT, 09:00 요약 메일+Discord SENT·본문 줄·`summary_date`,
새 인스턴스 재회차 무행, `day_at` 2026-10-29 행이 있어도 요약 값은 10-28 행.

- [ ] **Step 1:** 테스트 작성.
- [ ] **Step 2:** RED — `OwnerMarketSignalJob` 의 `store.enqueue(next, rows)` 를 `enqueue(next, emptyList())` 로 **임시 변이**하고 AC15 명령 → assertion 실패(행 수). 증거 로그 AC15. 변이 되돌림 후 `git diff --stat -- apps/batch/src/main` 빈 출력 확인.
- [ ] **Step 3:** AC15 명령 → PASS. `./gradlew :apps:batch:integrationTest --offline --no-daemon` (R11) → PASS.
- [ ] **Step 4: Commit** — `test: owner 시장 알림 E2E 통합`

### Task 16: 문서 (AC16)

**Files:** Create `docs/runbooks/owner-market-alert.md`; Modify `docs/runbooks/account-pnl-alert.md`(Task 0 (g8) 줄), `docs/runbooks/owner-ops-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`,
(Task 0 (g11) 이 job tag 목록을 찾은 문서)

요건: design §13 항목 전부. runbook 의 env 표는 design §9.1 env 전부(이름·기본값·의미), 라우팅 표 4종, 정리 SQL 두 문장 전문, V22 배포(additive·롤백 SQL 없음), AC17 체크리스트
((a) 기동 INFO 4종 (b) 09:00 무음 요약 + 메일 (c) 상단 기준선 소리 (d) 급변·신고가 관찰 여부). 문서에 실제 webhook·주소·key 를 쓰지 않는다(`https://discord.example.test/...` 예시만).

- [ ] **Step 1:** 문서 작성.
- [ ] **Step 2:** `bash docs/check-documentation.sh` → exit 0. `./gradlew harnessCheck harnessCheckTest --offline --no-daemon` (R16) → PASS.
- [ ] **Step 3: Commit** — `docs: owner 시장 알림 runbook 과 상태 문서`

### Task 17: 최종 게이트

**Files:** 없음 (dod 증거 로그 append)

- [ ] **Step 1:** 실행·기록.
  ```bash
  ./gradlew test architectureTest --offline --no-daemon                               # R1·R2
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon             # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon              # R10 · AC8 · AC9
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                         # R11 · AC15
  ./gradlew :apps:api:integrationTest --offline --no-daemon                           # R13
  ./gradlew jacocoTestReport jacocoTestCoverageVerification --offline --no-daemon     # R15
  ./gradlew harnessCheck harnessCheckTest --offline --no-daemon                       # R16
  bash docs/check-documentation.sh                                                    # R12
  git diff --exit-code --diff-filter=MDR 4f8727c -- <Task 0 이 확정한 R3 경로>          # R3 (⑨ 리뷰가 재실행)
  ```
- [ ] **Step 2: 예상 결과.** 전부 exit 0. Docker 부재 항목은 "미실행"(green 금지). R6·R7·R8 diff 는 ⑨ 리뷰가 사람 판정. R14 는 CI.
- [ ] **Step 3: Commit** — `docs: owner 시장 알림 최종 게이트 기록`
