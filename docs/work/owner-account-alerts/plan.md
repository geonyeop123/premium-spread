# owner 계정 알림 — 포지션 변경·순수익률 급변·미반영 리마인더 (issue #89) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 실계정 모니터 회차가 포지션 변경·순수익률 급변·미반영 리마인더 3종을 판정해 #88 라우팅·outbox 로 보낸다.

**Architecture:** Domain `accountpnl` 에 leg·상태·설정 모델과 순수 정책 3개(+ 조합기 `AccountAlertPolicy`)·sealed `AccountAlert`·렌더러 `AccountAlertContent` 를 둔다.
상태는 `AccountPnlMonitorState` 의 기본값 있는 4번째 프로퍼티 `account` 로 붙어 V20 이 더한 `account_pnl_monitor_state` 열에 기존 `save` 한 트랜잭션·한 version 으로 저장된다.
`AccountPnlMonitorJob` 은 생성자 끝 `accountAlerts: AccountAlertSettings? = null` 이 주어질 때만 판정하고, 알림마다 `OwnerAlertRows.expand(mail, routing.resolve(kind))` 로 행을 만든다.
새 Job·port·테이블·Scheduler 없음.

**Tech Stack:** Kotlin 2.0, Spring Boot 3.5 (`@Value`, `ApplicationContextRunner`), Flyway·MySQL 8 (Testcontainers), JUnit5 + AssertJ.

**Spec:** `docs/work/owner-account-alerts/design.md` · **DoD:** `docs/work/owner-account-alerts/dod.md` · 사용자 확정 D1~D3·판정 O1 은 design §12

## Global Constraints

- base `033d513`(Task 0 확인). `docs/work/owner-alert-channel/**`·`live-account-pnl-*` 등 직전 단위 산출물은 수정하지 않는다.
- **동결 테스트 무수정** (dod R3). 이 테스트들이 컴파일·통과하도록:
  - `AccountPnlAlertStore` interface 무변경(메서드 추가 없음).
  - `AccountPnlMonitorJob` 생성자의 기존 13개 인자 순서·타입·기본값을 바꾸지 않고 **끝에** `accountAlerts: AccountAlertSettings? = null` 하나만 붙인다.
  - `AccountPnlMonitorState(alert, outage, version)` 3인자 생성과 `INITIAL` 을 유지한다 — 4번째 프로퍼티는 기본값.
  - `observe()` 의 관측 로그 문구·취소 재전파·실패 범주 정규화를 바꾸지 않는다.
- 허용 예외는 dod R7(`OwnerAlertRoutingTest` 한 파일)·R8(fixture 스키마 테스트) 범위뿐. `OwnerAlertPropertiesTest` 는 **무수정**.
  확장 테스트(`JdbcAccountPnlAlertStoreIntegrationTest`·`AccountPnlMonitorWiringTest`)는 **추가만**(R6).
- `OwnerAlertKind` 전체에 대한 `when` 금지. 새 enum 값은 기존 4개 **뒤에** `POSITION_CHANGED, NET_PERCENT_SWING, UNRECOVERED_REMINDER` 순서.
- 기존 렌더러(`AccountPnlMailContent`·`AccountPnlDiscordContent`)·`AccountPnlFormat` 기존 함수의 출력 바이트 불변(기존 golden).
- Domain 에 Spring/JDBC 의존 금지. 앱 main 은 infrastructure 타입을 import 하지 않는다.
- 로그·예외·`toString`·DB 에 수신 주소·webhook URL·credential 금지. 새 로그는 design §5.3 키만. 상태·알림 객체를 통째로 로그에 넣지 않는다.
- 테스트: 고정 `Clock`·`ZoneId.of("Asia/Seoul")`, 가짜 수치(빗썸 `0.5` BTC·평단 `100000000`, 바이낸스 `-0.5`·`70000`, 펀딩 `3.2` 등). 실제 포지션 값 금지. 외부 URL 금지.
- migration 은 V20 **추가만**. Redis·`PublicEndpointPolicy`·`JobId`·`JobConfig`·Scheduler 무변경.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 변이에서의 assertion 실패다.
- 각 태스크 커밋 시점에 `./gradlew test --offline --no-daemon` 이 초록이어야 한다(Task 1 이 yml routes 를 함께 넣는 이유).
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령은 `--offline --no-daemon`.

## Review Focus

1. **동결 호환** — `accountAlerts = null` 경로가 기존 회차 결과(행 수·상태 `alert`/`outage`)를 바꾸지 않는가. 저장되는 `account` 가 로드한 값 그대로인가(Task 10).
2. **정밀도 오탐** — leg·펀딩 비교가 `compareTo`/정규화로 scale 차이를 무시하는가, DB 왕복 후에도 같은가(Task 2·9). 오탐이면 매 회차 `@here` 가 울린다.
3. **기준선 초기화 순서** — 포지션 변경 회차에서 급변이 판정되지 않고 그 버킷 기준선이 null 이 되는가(Task 6).
4. **리마인더 소비 규칙(D1)** — 항목 0개 회차·실패·포지션 없음 회차가 그날을 소비하지 않는가, 보낸 뒤에는 같은 날 다시 보내지 않는가, 시각 경계가 zone 기준인가(Task 5).
5. **V20 호환** — 구 binary 상태 INSERT/UPDATE 가 V20 에서 성공하고 새 열을 보존하는가(Task 8).
6. **허용 예외 범위** — R7·R8 diff 가 기대 목록 끝 추가·이름 2개 rename 뿐인가.

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt` | enum 값 3개 추가 | 1 |
| `apps/batch/src/main/resources/application.yml` | routes 3블록(1), `account-alerts` 블록(11) | 1·11 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertState.kt` (신규) | leg·상태·설정·`AccountRound` | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlert.kt` (신규) | sealed `AccountAlert`, `SwingDirection`, `ReminderItem`, `ManualFundingStaleness` | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertStore.kt` | `AccountPnlMonitorState.account` 기본값 프로퍼티 | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/PositionChangePolicy.kt` (신규) | P1~P8 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/NetPercentSwingPolicy.kt` (신규) | S1~S6 | 4 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/UnrecoveredReminderPolicy.kt` (신규) | 펀딩 추적·R1~R5 | 5 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertPolicy.kt` (신규) | 조합기 | 6 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt` | `percentPoint`·`date` 추가 | 7 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContent.kt` (신규) | 메일·Discord 렌더링, eventKey | 7 |
| `infrastructure/common/src/main/resources/db/migration/V20__add_owner_account_alert_state.sql` (신규) | DDL | 8 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt` | 상태 열 읽기·쓰기·검증 | 9 |
| `apps/batch/src/integrationTest/resources/batch-schema.sql` | `account_pnl_monitor_state` V18+V20 | 9 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt` | `round`, 판정, 행 생성, 상태 저장, 클래스 KDoc 시각 규칙 | 10 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt` | `accountAlertSettings` bean, Job 인자 | 11 |
| `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md` | 문서 | 13 |
| tests — domain: `OwnerAccountAlertKindTest`(owneralert), `AccountAlertStateTest`·`PositionChangePolicyTest`·`NetPercentSwingPolicyTest`·`UnrecoveredReminderPolicyTest`·`AccountAlertPolicyTest`·`AccountAlertContentTest`(accountpnl), 허용 예외 `OwnerAlertRoutingTest`; infra:common: `V20OwnerAccountAlertStateMigrationIntegrationTest`·`JdbcAccountPnlAlertStoreIntegrationTest`(확장); apps:batch: `AccountPnlMonitorJobAccountAlertTest`·`AccountPnlMonitorWiringTest`(확장)·`OwnerAccountAlertIntegrationTest`·허용 예외 `AccountPnlFixtureSchemaIntegrationTest` | | 1~12 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [x] **Step 1:** 기준선 실행·기록.
  ```bash
  git rev-parse --short HEAD && git merge-base HEAD origin/dev | cut -c1-7                   # base 033d513 확인
  ./gradlew architectureTest --offline --no-daemon                                          # R1
  ./gradlew test --offline --no-daemon                                                      # R2·R4
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon                   # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                    # R9 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                               # R10 (Docker)
  ./gradlew :apps:api:integrationTest --offline --no-daemon                                 # R13 (Docker)
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1              # V20 번호 근거
  grep -rn "AccountPnlMonitorJob(" --include=*.kt apps                                      # 생성자 호출처
  grep -rn "AccountPnlMonitorState(" --include=*.kt .                                       # 상태 생성처 (3인자 유지 확인)
  grep -rn "OwnerAlertKind.entries\|known: profit" --include=*.kt .                         # enum 개수 고정 단언 (R7)
  ```
- [x] **Step 2: 예상 결과.** merge-base `033d513`. gradle 6건 exit 0. `ls` 마지막 줄 `V19__add_owner_alert_channel.sql`. 생성자 호출처는 `AccountPnlMonitorConfiguration` +
  테스트(`AccountPnlMonitorJobTest`·`...HourlyReportTest`·`...ObservationLogTest`·`AccountPnlMonitorJobOwnerAlertTest`) + 통합(`AccountPnlAlertDurabilityIntegrationTest`·
  `OwnerAlertDeliveryIntegrationTest`)이고 모두 13개 인자 이내. 상태 생성처는 모두 3인자. enum 개수를 `containsExactly` 로 고정한 곳은 `OwnerAlertRoutingTest`(:24-25, :34-35)뿐이고
  `OwnerAlertPropertiesTest`(:111-113)는 `contains` 부분 문자열이다(그 밖 `entries` 사용처는 `forEach`/집합 비교). **다르면 멈추고 보고한다** — V19 가 아니면 V20 번호,
  base 가 다르면 dod R3·R5 의 base 를 Gate 1 에서 다시 정한다. Docker 부재면 R9·R10·R13 "미실행"(green 금지).
- [x] **Step 3: Commit** — `docs: owner 계정 알림 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: `OwnerAlertKind` 3종과 yml routes (AC1, AC11-f)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt`, `apps/batch/src/main/resources/application.yml`(routes 3블록만);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAccountAlertKindTest.kt`;
허용 예외(R7) Modify `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt`(:23, :24-25, :34-35, :42)

**Interfaces — Produces (전문, 기존 4개 뒤에 추가):**

```kotlin
    POSITION_CHANGED(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE))),
    NET_PERCENT_SWING(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SOUND))),
    UNRECOVERED_REMINDER(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))),
```

yml: design §6.2 의 routes 3블록(`position-changed`·`net-percent-swing`·`unrecovered-reminder`)을 `owner-alert.routes.monitoring-recovered` 뒤에 **그대로**.

허용 예외 수정 (전문, `OwnerAlertRoutingTest` 한 파일):
- `:23` 이름 `종류는 네 개이고 기본 경로는 설계 표와 같다` → `종류 목록과 기본 경로는 설계 표와 같다`
- `:25` 기대 목록 → `"PROFIT", "HOURLY_REPORT", "MONITORING_OUTAGE", "MONITORING_RECOVERED", "POSITION_CHANGED", "NET_PERCENT_SWING", "UNRECOVERED_REMINDER"`
- `:35` → `"profit", "hourly-report", "monitoring-outage", "monitoring-recovered", "position-changed", "net-percent-swing", "unrecovered-reminder"`
- `:42` 이름 `EMAIL_ONLY 는 네 종류 모두 메일만` → `EMAIL_ONLY 는 모든 종류가 메일만`
- 그 밖 줄 무변경. `OwnerAlertPropertiesTest` 는 수정하지 않는다(부분 문자열 `contains` 라 enum 끝 추가에도 통과).

**테스트가 재는 것** (`OwnerAccountAlertKindTest`):
- `계정 알림 세 종류의 기본 경로는 설계 표와 같다` — 3종 `defaultRoute` `isEqualTo` (AC1-b)
- `계정 알림 key 는 kebab 이고 fromKey 로 되돌아온다` — 3 key, `fromKey(" Net-Percent-Swing ")` (AC1-a)
- `메일과 Discord 를 모두 쓰면 기본 경로` / `Discord 가 꺼져 있으면 세 종류 모두 메일만` — `OwnerAlertRouting(both)`·`EMAIL_ONLY` (AC1-c, 사용자 확정 D2)

- [x] **Step 1:** enum 값 3개를 **stub 경로** `OwnerAlertRoute(email = true, discord = null)` 로 추가, 허용 예외 수정, yml routes 3블록, 새 테스트 작성.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.*' --offline --no-daemon` → RED(기본 경로·both 해석 단언 실패, `EMAIL_ONLY`·key 는 초록일 수 있다). 증거 로그 AC1.
- [x] **Step 3:** 경로를 위 전문으로. dod AC1 명령 → PASS(`OwnerAlertPropertiesTest` 무수정 통과 포함). `./gradlew :apps:batch:test --tests 'io.premiumspread.config.OwnerAlertYamlContractTest' --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` → PASS(#88 테스트 무수정, routes INFO 는 `contains` 라 새 값이 뒤에 붙어도 성립).
- [x] **Step 4: Commit** — `feat: owner 계정 알림 종류 세 개와 기본 경로`

### Task 2: leg·상태·설정·알림 타입 (AC2)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertState.kt`, `.../accountpnl/AccountAlert.kt`; Modify `.../accountpnl/AccountPnlAlertStore.kt`(상태 data class 만);
Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAlertStateTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.accountpnl

/** 빗썸 현물 leg (design §3.1). [of] 로 만든다 — dust 는 [EMPTY], 값은 stripTrailingZeros. 비교는 [samePosition]. */
data class KoreaLeg(val quantity: BigDecimal, val averageBuyPrice: BigDecimal) {
    val isEmpty: Boolean get() = quantity.signum() == 0
    /** 두 필드 모두 compareTo == 0 (scale 무관). */
    fun samePosition(other: KoreaLeg): Boolean

    companion object {
        val EMPTY: KoreaLeg = KoreaLeg(BigDecimal.ZERO, BigDecimal.ZERO)
        fun of(snapshot: KoreaSpotAccountSnapshot): KoreaLeg
        /** quantity < AccountHedgePosition.KOREA_DUST → EMPTY. 저장소 복원도 이 함수를 쓴다. */
        fun of(quantity: BigDecimal, averageBuyPrice: BigDecimal): KoreaLeg
    }
}

/** 바이낸스 선물 leg. signedQuantity == 0 이면 [FLAT](진입가 무시). */
data class ForeignLeg(val signedQuantity: BigDecimal, val entryPrice: BigDecimal) {
    val isFlat: Boolean get() = signedQuantity.signum() == 0
    fun samePosition(other: ForeignLeg): Boolean

    companion object {
        val FLAT: ForeignLeg = ForeignLeg(BigDecimal.ZERO, BigDecimal.ZERO)
        fun of(snapshot: ForeignPerpAccountSnapshot): ForeignLeg
        fun of(signedQuantity: BigDecimal, entryPrice: BigDecimal): ForeignLeg
    }
}

data class PositionLegs(val korea: KoreaLeg, val foreign: ForeignLeg) {
    companion object {
        fun of(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): PositionLegs
    }
}

/** 포지션 변경 감지 상태 (design §3.2). legs == null = 아직 기록 없음. */
data class PositionWatchState(val legs: PositionLegs?, val hedgeMismatchWarned: Boolean) {
    companion object { val INITIAL = PositionWatchState(null, false) }
}

/** 순수익률 급변 상태 (design §3.3). bucketStart == null 이면 나머지는 null/false/false. */
data class SwingState(
    val bucketStart: Instant?,
    val baselinePercent: BigDecimal?,
    val upSent: Boolean,
    val downSent: Boolean,
) {
    init {
        require(bucketStart != null || (baselinePercent == null && !upSent && !downSent)) {
            "swing state without a bucket must be empty."
        }
    }
    companion object { val INITIAL = SwingState(null, null, false, false) }
}

/**
 * 미반영 리마인더 상태 (design §3.4). [lastDayStart] = 리마인더를 마지막으로 **보낸** zone 날짜의 자정.
 * manualFundingChangedAt == null 이면 manualFundingUsdt == null.
 */
data class ReminderState(
    val lastDayStart: Instant?,
    val manualFundingUsdt: BigDecimal?,
    val manualFundingChangedAt: Instant?,
) {
    init {
        require(manualFundingChangedAt != null || manualFundingUsdt == null) {
            "manual funding value requires its change time."
        }
    }
    companion object { val INITIAL = ReminderState(null, null, null) }
}

data class AccountAlertState(val position: PositionWatchState, val swing: SwingState, val reminder: ReminderState) {
    companion object {
        val INITIAL = AccountAlertState(PositionWatchState.INITIAL, SwingState.INITIAL, ReminderState.INITIAL)
    }
}

data class AccountAlertSettings(
    val swingThreshold: BigDecimal = BigDecimal("0.50"),
    val reminderHour: Int = 9,
    val manualFundingStaleAfter: Duration = Duration.ofDays(3),
) {
    init {
        require(swingThreshold.signum() > 0) { "swingThreshold must be positive." }
        require(reminderHour in 0..23) { "reminderHour must be between 0 and 23." }
        require(!manualFundingStaleAfter.isNegative && !manualFundingStaleAfter.isZero) {
            "manualFundingStaleAfter must be positive."
        }
    }
}

/**
 * 성공 회차 1개의 관측 (design §3.5). 실패 회차는 만들지 않는다(null 로 넘긴다).
 * [pnl] == null 은 포지션 없음. [observedAt] 은 두 계정 조회를 마친 시각이며 pnl 이 있으면 pnl.calculatedAt 과 같다.
 */
data class AccountRound(
    val legs: PositionLegs,
    val source: ForeignPerpPositionSource,
    val fundingUsdt: BigDecimal?,
    val pnl: AccountNetPnl?,
    val observedAt: Instant,
) {
    init { require(pnl == null || pnl.calculatedAt == observedAt) { "pnl must be calculated at observedAt." } }
}
```

```kotlin
package io.premiumspread.domain.accountpnl

enum class SwingDirection { UP, DOWN }

/** 리마인더 항목 — 이 순서로 표시한다. */
enum class ReminderItem { KOREA_ENTRY_FEE, FOREIGN_ENTRY_FEE, FUNDING, MANUAL_FUNDING_STALE }

data class ManualFundingStaleness(val fundingUsdt: BigDecimal, val changedAt: Instant, val days: Long)

/** 계정 알림 판정 결과. Job·렌더러는 이 sealed 타입으로만 분기한다(OwnerAlertKind 전체 when 금지). */
sealed interface AccountAlert {
    val kind: OwnerAlertKind

    data class PositionChanged(
        val before: PositionLegs,
        val after: PositionLegs,
        val hedgeMismatchWarning: Boolean,
        val source: ForeignPerpPositionSource,
        val detectedAt: Instant,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.POSITION_CHANGED
        val koreaChanged: Boolean get() = !before.korea.samePosition(after.korea)
        val foreignChanged: Boolean get() = !before.foreign.samePosition(after.foreign)

        init {
            require(koreaChanged || foreignChanged) { "position change requires a changed leg." }
            require(!hedgeMismatchWarning || (koreaChanged && !foreignChanged && source == ForeignPerpPositionSource.MANUAL_ENV)) {
                "hedge mismatch warning applies only to a Bithumb-only change with a manual Binance leg."
            }
        }
    }

    data class NetPercentSwing(
        val direction: SwingDirection,
        val bucketStart: Instant,
        val baselinePercent: BigDecimal,
        val threshold: BigDecimal,
        val pnl: AccountNetPnl,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.NET_PERCENT_SWING
        val delta: BigDecimal get() = pnl.netPercent.subtract(baselinePercent)
    }

    data class UnrecoveredReminder(
        val dayStart: Instant,
        val items: List<ReminderItem>,
        val source: ForeignPerpPositionSource,
        val manualFunding: ManualFundingStaleness?,
        val reminderHour: Int,
        val detectedAt: Instant,
    ) : AccountAlert {
        override val kind: OwnerAlertKind get() = OwnerAlertKind.UNRECOVERED_REMINDER

        init {
            require(items.isNotEmpty()) { "reminder requires at least one item." }
            require((ReminderItem.MANUAL_FUNDING_STALE in items) == (manualFunding != null)) {
                "manual funding staleness must match its item."
            }
        }
    }
}
```

```kotlin
// AccountPnlAlertStore.kt — 기존 3개 프로퍼티·INITIAL 무변경, 4번째만 추가
data class AccountPnlMonitorState(
    val alert: PnlAlertState,
    val outage: OutageState,
    val version: Long,
    /** owner 계정 알림 상태(#89). 3인자 생성(동결 테스트)과 V20 이전 행은 INITIAL. */
    val account: AccountAlertState = AccountAlertState.INITIAL,
)
```

자동 `toString()` 노출(전문): `KoreaLeg(quantity, averageBuyPrice)`, `ForeignLeg(signedQuantity, entryPrice)`, `PositionLegs(korea, foreign)`, `PositionWatchState(legs, hedgeMismatchWarned)`,
`SwingState(bucketStart, baselinePercent, upSent, downSent)`, `ReminderState(lastDayStart, manualFundingUsdt, manualFundingChangedAt)`, `AccountAlertState(position, swing, reminder)`,
`AccountAlertSettings(swingThreshold, reminderHour, manualFundingStaleAfter)`, `AccountRound(legs, source, fundingUsdt, pnl, observedAt)`(pnl 은 스냅샷 호가까지 포함),
`ManualFundingStaleness(fundingUsdt, changedAt, days)`, `AccountAlert.*` 생성자 프로퍼티 전부, `AccountPnlMonitorState(alert, outage, version, account)`. 비밀 없음 — 로그 금지는 Global Constraints.

**요건 (구현부):** `KoreaLeg.of(q, p)` = `if (q < KOREA_DUST) EMPTY else KoreaLeg(q.stripTrailingZeros(), p.stripTrailingZeros())`. `ForeignLeg.of(q, p)` = `if (q.signum() == 0) FLAT else …strip`.
`samePosition` = `compareTo == 0` 두 번.

**테스트가 재는 것** (`AccountAlertStateTest`): dod AC2 (a)~(e) 그대로 — dust 경계 `0.00009999`/`0.0001`, `0.50`↔`0.5` 의 `==`·`samePosition`, 평단 `100000000.00`↔`1E+8`, 바이낸스 `0` 수량 + 진입가 `70000` → `FLAT`,
상태 불변식 3건, 설정 기본값·거부 5건(메시지 일치), 3인자 상태의 `account == INITIAL`.

- [x] **Step 1:** 타입 전부 + **stub** `KoreaLeg.of`/`ForeignLeg.of` = 정규화 없이 그대로 생성, `samePosition` = `==`. 테스트 작성.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertStateTest' --offline --no-daemon` → RED(dust·scale·FLAT 케이스). 동결 컴파일 확인:
  `./gradlew :domain:test :apps:batch:compileTestKotlin :infrastructure:common:compileIntegrationTestKotlin :apps:batch:compileIntegrationTestKotlin --offline --no-daemon` 의 컴파일 성공(domain 기존 테스트 초록). 증거 로그 AC2.
- [x] **Step 3:** 구현. Step 2 첫 명령 → PASS.
- [x] **Step 4: Commit** — `feat: owner 계정 알림 상태·leg·설정 Domain 모델`

### Task 3: `PositionChangePolicy` (AC3)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/PositionChangePolicy.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PositionChangePolicyTest.kt`

**Interfaces — Consumes:** Task 2. **Produces (전문):**

```kotlin
data class PositionChangeDecision(val next: PositionWatchState, val alert: AccountAlert.PositionChanged?)

object PositionChangePolicy {
    /** design §3.2 전이표 P1~P8. [now] 는 감지 시각(회차 observedAt). */
    fun decide(
        state: PositionWatchState,
        legs: PositionLegs,
        source: ForeignPerpPositionSource,
        now: Instant,
    ): PositionChangeDecision
}
```

상태 전이표: design §3.2 P1~P8 **그대로**(구현·테스트 모두 그 표가 정본, 해소 조건은 사용자 확정 D3). `next.legs` 는 항상 이번 `legs`.

**테스트가 재는 것** (`now = 2026-10-20T03:00:00Z`, before = 빗썸 `0.5`/`100000000`, 바이낸스 `-0.5`/`70000`):
- P1 — INITIAL → 알림 null, `next == (legs, false)`
- P2·P3 — 같은 legs(scale 만 다른 `0.50`) API/manual, `warned` true 유지(P3)·false 로(P2)
- P4 — 빗썸 `0.6` manual, warned false → 경고 true, next warned true, 알림 필드 전부
- P5 — 같은 조건 warned true → 경고 false, next warned true
- P3 해소 아님 — warned true 상태에서 빗썸이 원래 `0.5` 로 돌아옴(manual) → 이 회차는 P5(빗썸 변경, 경고 없음)·warned true 유지
- P6 — 빗썸만 API → 경고 false, next warned false
- P7 — 바이낸스 `-0.6` 만, warned true → 경고 false, next warned false
- P8 — 둘 다 → `koreaChanged && foreignChanged`, 경고 false
- 청산 — 빗썸 `EMPTY` → 변경, 바이낸스 `FLAT` → 변경
- 알림 불변식 — `PositionChanged(before, before, …)`·API 출처 경고 true 거부 (AC3)

- [x] **Step 1:** 시그니처 + **stub** `decide = PositionChangeDecision(state.copy(legs = legs), null)` + 테스트.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PositionChangePolicyTest' --offline --no-daemon` → RED. 증거 로그 AC3.
- [x] **Step 3:** 구현 → PASS.
- [x] **Step 4: Commit** — `feat: 실계정 포지션 leg 변경 감지 정책`

### Task 4: `NetPercentSwingPolicy` (AC4)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/NetPercentSwingPolicy.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/NetPercentSwingPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
data class SwingDecision(val next: SwingState, val alert: AccountAlert.NetPercentSwing?)

object NetPercentSwingPolicy {
    /** zone 정시 버킷 시작 — AccountPnlMailContent.hourly 와 같은 식. 범위 [bucketStart, +1h). */
    fun bucketStart(at: Instant, zone: ZoneId): Instant

    /** design §3.3 전이표 S1~S6. [pnl] == null 은 포지션 없음. 비교는 compareTo, 경계 포함. */
    fun decide(
        state: SwingState,
        bucket: Instant,
        pnl: AccountNetPnl?,
        positionChanged: Boolean,
        threshold: BigDecimal,
    ): SwingDecision
}
```

상태 전이표: design §3.3 S1~S6 그대로.

**테스트가 재는 것** (zone `Asia/Seoul`, threshold `0.50`, `AccountNetPnl` 은 테스트 안 헬퍼로 `netPercent` 만 바꿔 생성자로 만든다):
- `bucketStart` 경계 — `2026-10-20T01:59:59.999Z` → `01:00Z`, `02:00:00Z` → `02:00Z`, `2026-10-20T00:00:05Z` → `00:00Z` (AC4-a)
- S2 새 버킷 — INITIAL + `0.40` → `(01:00Z, 0.40, F, F)`, 알림 null; `pnl == null` → 기준선 null (AC4-b)
- S4·S6 경계 — 기준선 `0.40`, `0.89` → 없음, `0.90` → UP·`delta == 0.50`·`upSent`, 같은 버킷 `1.20` → 없음 (AC4-c)
- S5 — 이어서 `-0.10` → DOWN·`delta == -0.50`, 다시 `-0.50` → 없음 (AC4-c)
- S2 리셋 — 다음 버킷 첫 회차 `1.20` → `(02:00Z, 1.20, F, F)` (AC4-d)
- S1 — `positionChanged = true`, 같은 버킷·기준선 있음 → `(bucket, null, F, F)`; 이어 `+3.00` → 없음(S3); 다음 버킷 → 기준선 재설정 (AC4-e)
- 알림 필드 `direction`·`bucketStart`·`baselinePercent`·`threshold`·`pnl` (AC4-f)

- [x] **Step 1:** 시그니처 + **stub** `decide = SwingDecision(state, null)`, `bucketStart` 는 실제 구현(단순 식) + 테스트.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.NetPercentSwingPolicyTest' --offline --no-daemon` → RED. 증거 로그 AC4.
- [x] **Step 3:** 구현 → PASS.
- [x] **Step 4: Commit** — `feat: 순수익률 정시 기준선 급변 정책`

### Task 5: `UnrecoveredReminderPolicy` (AC5)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/UnrecoveredReminderPolicy.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/UnrecoveredReminderPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
data class ReminderDecision(val next: ReminderState, val alert: AccountAlert.UnrecoveredReminder?)

object UnrecoveredReminderPolicy {
    /** zone 날짜의 자정. 범위 [dayStart, 다음 dayStart). */
    fun dayStart(at: Instant, zone: ZoneId): Instant

    /**
     * design §3.4 — 1단계 수동 펀딩 추적(모든 성공 회차) → 2단계 R1~R5(사용자 확정 D1).
     * 설정 시각 이후 포지션 있는 회차마다 판정하고, 항목이 있는 첫 회차에 보낸다. lastDayStart 는 **보냈을 때만** 갱신한다.
     */
    fun decide(state: ReminderState, round: AccountRound, settings: AccountAlertSettings, zone: ZoneId): ReminderDecision
}
```

상태 전이표: design §3.4 R1~R5 그대로 — R4(항목 0개)는 `lastDayStart` 를 **바꾸지 않는다**.

**요건 (구현부):** 펀딩 값 비교는 `compareTo`(null ↔ 값은 다름), 저장 값은 `stripTrailingZeros()`. `MANUAL_FUNDING_STALE` 판정은 1단계 **이후** 상태로. `days = Duration.between(changedAt, observedAt).toDays()`.
항목 순서는 `ReminderItem` 선언 순서. 알림의 `dayStart` = 이번 회차의 `dayStart(observedAt, zone)`, `detectedAt = observedAt`.

**테스트가 재는 것** (zone `Asia/Seoul`, 기본 설정, `AccountRound` 헬퍼 — 미반영 플래그는 스냅샷 `entryFeeKrw`/`entryCommissionUsdt`/`fundingUsdt` 를 null 로 해 `AccountNetPnl.calculate` 로 만든다):
- 펀딩 추적 4행 — API → `(null, null)`; manual 첫 관측 `3.2` → `(3.2, t)`; `3.20` → 그대로; `4.0`·null → `(새 값, t)` (AC5-a)
- 시각 경계(항목 있음) — `2026-10-19T23:59:59Z` 없음, `2026-10-20T00:00:00Z` 리마인더·`lastDayStart == 2026-10-19T15:00:00Z`, `05:00Z` 없음, `2026-10-20T15:00:00Z` 없음, `2026-10-21T00:00:00Z` 리마인더 (AC5-b)
- 항목 순서·부분 집합 — 3플래그 모두 → 3항목 순서, 빗썸만 → 1항목 (AC5-c)
- 펀딩 경과 — changedAt `2026-10-17T00:00:00.001Z`, 판정 `2026-10-20T00:00:00Z` → 없음; changedAt `2026-10-17T00:00:00Z` → 있음, `days == 3`, `fundingUsdt == 3.2` (AC5-d)
- `항목이 처음 생긴 회차에 보내고 같은 날 두 번째 항목은 보내지 않는다` — `00:00Z`·`03:00Z` 항목 0 → 알림 null·`lastDayStart` null 유지, `06:00Z`(15:00 KST) 빗썸 진입 수수료 null → 리마인더 `[KOREA_ENTRY_FEE]`·`lastDayStart == 2026-10-19T15:00:00Z`, `08:00Z` 펀딩까지 null → 알림 null (AC5-e)
- `종일 항목이 없으면 아무것도 보내지 않는다` — `00:00Z`~`14:59Z` 사이 여러 회차 항목 0 → 알림 0, `lastDayStart` null (AC5-e)
- `포지션 없음 회차는 판정하지도 소비하지도 않는다` — `01:00Z` NoPosition(항목 조건과 무관) → 펀딩 추적만·`lastDayStart` 그대로·알림 null, 이어 `01:01Z` Computed 항목 있음 → 리마인더 (AC5-f)
- `reminderHour = 0` — `2026-10-20T15:00:00Z` 판정 (AC5-g)

- [x] **Step 1:** 시그니처 + **stub** `decide = ReminderDecision(state, null)`, `dayStart` 실제 구현 + 테스트.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.UnrecoveredReminderPolicyTest' --offline --no-daemon` → RED. 증거 로그 AC5.
- [x] **Step 3:** 구현 → PASS.
- [x] **Step 4: Commit** — `feat: 미반영 항목 하루 1회 리마인더 정책`

### Task 6: 조합기 `AccountAlertPolicy` (AC6)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountAlertPolicy.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAlertPolicyTest.kt`

**Interfaces — Produces (전문):**

```kotlin
data class AccountAlertDecision(val next: AccountAlertState, val alerts: List<AccountAlert>)

object AccountAlertPolicy {
    /**
     * design §3.5. [round] == null(실패 회차)이면 (state, []).
     * 순서: 포지션 → 급변(positionChanged = 포지션 알림 있음, bucket = NetPercentSwingPolicy.bucketStart(round.observedAt, zone))
     *       → 리마인더. alerts 도 이 순서.
     */
    fun decide(state: AccountAlertState, round: AccountRound?, settings: AccountAlertSettings, zone: ZoneId): AccountAlertDecision
}
```

**테스트가 재는 것** (zone `Asia/Seoul`): dod AC6 (a)~(e) — 실패 회차 동일성, 첫 기동 Computed `2026-10-20T00:30:00Z`(미반영 없음) 알림 0·legs·기준선·펀딩 추적 채워짐·`reminder.lastDayStart` null,
빗썸 변경 manual → `[PositionChanged(경고)]`·`swing == (00:00Z, null, F, F)`, 겹침 순서 2건(`[NetPercentSwing, UnrecoveredReminder]`, 변경 겹침 시 `[PositionChanged, UnrecoveredReminder]`),
NoPosition 회차의 `reminder.lastDayStart` 불변.

- [x] **Step 1:** 시그니처 + **stub** `decide = AccountAlertDecision(state, emptyList())` + 테스트.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertPolicyTest' --offline --no-daemon` → RED((a) 는 stub 에서도 초록). 증거 로그 AC6.
- [x] **Step 3:** 구현 → PASS. `./gradlew :domain:test --offline --no-daemon` → PASS.
- [x] **Step 4: Commit** — `feat: owner 계정 알림 판정 조합`

### Task 7: 렌더링 (AC7)

**Files:** Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt`(함수 2개 추가만); Create `.../accountpnl/AccountAlertContent.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountAlertContentTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// AccountPnlFormat — 기존 멤버 무변경, 추가만
/** 부호 + 0.00 + "%p". */
fun percentPoint(value: BigDecimal): String
/** zone 날짜 `yyyy-MM-dd` (Locale.ROOT). */
fun date(instant: Instant, zone: ZoneId): String
```

```kotlin
object AccountAlertContent {
    const val COLOR_POSITION_CHANGED: Int = 0xF1C40F
    const val COLOR_HEDGE_MISMATCH: Int = 0xE67E22
    const val COLOR_SWING_UP: Int = 0x1ABC9C
    const val COLOR_SWING_DOWN: Int = 0xE91E63
    const val COLOR_REMINDER: Int = 0x7F8C8D

    /** design §3.6 — `account-pnl-position-{ms}` / `account-pnl-swing-{up|down}-{ms}` / `account-pnl-reminder-{ms}`. */
    fun eventKey(alert: AccountAlert): String

    fun mail(alert: AccountAlert, zone: ZoneId): AccountPnlMail
    fun discord(alert: AccountAlert, zone: ZoneId): DiscordMessage
}
```

`mail`·`discord` 는 sealed `AccountAlert` 에 대한 `when` 으로 분기한다. 문구는 design §7.2·§7.3 **그대로**(exact copy — 리마인더 안내 줄은 `하루 한 번, {HH}:00 이후 항목이 처음 확인된 회차에 보냅니다. …`),
Discord 는 `DiscordMessage.of`. 메일 행은 EMAIL 행(`AccountPnlMail(eventKey, subject, body)`).

**테스트가 재는 것** (zone `Asia/Seoul`, golden 은 design §7 템플릿으로 손으로 계산한 문자열):
- 메일 golden 6건 — 포지션(빗썸 `0.5`→`0.6`·평단 `100000000`→`101000000`, manual, 경고, `detectedAt = 2026-10-20T03:00:00Z`), 포지션(바이낸스 `숏 0.5 BTC · 진입가 70000 USDT → 없음`, API),
  급변 상승(기준 `0.40` → `0.90`, 버킷 `03:00Z` = `12:00`), 급변 하락(`0.40` → `-0.10`), 리마인더 4항목(manual, 펀딩 `3.2`, `days = 3`, `reminderHour = 9`), 리마인더 1항목(API 빗썸만, `detectedAt = 2026-10-20T06:00:00Z`) (AC7-a)
- Discord golden 6건 — 같은 시나리오 제목·설명·색 (AC7-b)
- eventKey 4종 값과 정규식 (AC7-c)
- 금지 문자열 없음 (AC7-d)
- `percentPoint(BigDecimal("0.5")) == "+0.50%p"`, `percentPoint(BigDecimal("-0.5")) == "-0.50%p"`, `date(2026-10-19T15:00:00Z, Asia/Seoul) == "2026-10-20"`

- [x] **Step 1:** 함수·객체 시그니처 + **stub**(`mail` = `AccountPnlMail(eventKey(alert), "x", "")`, `discord` = `DiscordMessage.of("x", "", 0)`, `eventKey`·`percentPoint`·`date` 는 실제 구현) + 테스트.
- [x] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountAlertContentTest' --offline --no-daemon` → RED(golden). 증거 로그 AC7.
- [x] **Step 3:** 구현. dod AC7 명령(기존 golden 포함) → PASS.
- [x] **Step 4: Commit** — `feat: owner 계정 알림 메일·Discord 렌더링`

### Task 8: V20 migration (AC8)

**Files:** Create `infrastructure/common/src/main/resources/db/migration/V20__add_owner_account_alert_state.sql`;
Test `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V20OwnerAccountAlertStateMigrationIntegrationTest.kt`

**Interfaces — Produces (DDL 전문):** design §4.2 의 SQL **그대로**(주석 포함). V18·V19 수정 금지.

테스트 상수 (전문 — base `JdbcAccountPnlAlertStore` 의 SQL 을 그대로 옮긴다. `$STATE_ID` 는 `1` 로 치환):

```kotlin
/** base(033d513) insertState — 9열. */
const val OLD_INSERT_STATE = """
INSERT INTO account_pnl_monitor_state (
    id, in_episode, position_fingerprint, last_sent_at,
    last_success_at, monitoring_since, outage_notified, version, updated_at
) VALUES (1, ?, ?, ?, ?, ?, ?, 1, ?)
"""

/** base(033d513) updateState — 새 열 미포함. */
const val OLD_UPDATE_STATE = """
UPDATE account_pnl_monitor_state
SET in_episode = ?,
    position_fingerprint = ?,
    last_sent_at = ?,
    last_success_at = ?,
    monitoring_since = ?,
    outage_notified = ?,
    version = version + 1,
    updated_at = ?
WHERE id = 1
  AND version = ?
"""
```

**테스트가 재는 것** (`V19OwnerAlertChannelMigrationIntegrationTest` 패턴 — 자체 `MySQLContainer("mysql:8.0")`, `clean()` 후 `migrate()`, `target(19)`):
- `빈 DB는 V20까지 migration 되고 상태 열 12개가 붙는다` — 버전 `1..20`, `information_schema.COLUMNS` 순서 = 기존 9 + `korea_quantity:varchar:YES`, `korea_average_buy_price:varchar:YES`, `foreign_signed_quantity:varchar:YES`,
  `foreign_entry_price:varchar:YES`, `hedge_mismatch_warned:tinyint:NO`, `swing_bucket_start:datetime:YES`, `swing_baseline_percent:decimal:YES`, `swing_up_sent:tinyint:NO`, `swing_down_sent:tinyint:NO`,
  `reminder_day_start:datetime:YES`, `manual_funding_usdt:varchar:YES`, `manual_funding_changed_at:datetime:YES`; 세 BOOLEAN `COLUMN_DEFAULT == '0'` (AC8-a)
- `V19 의 기존 상태 행은 보존되고 새 열은 비어 있다` — `target(19)` 후 행 INSERT(in_episode 1, fingerprint `0.5|100000000|-0.5|70000`, version 7) → latest → 기존 열 그대로, 새 열 NULL/0 (AC8-b)
- `구 binary 의 상태 INSERT 와 UPDATE 는 V20 에서 동작하고 새 열을 보존한다` — `OLD_INSERT_STATE` 성공 → 새 열 직접 UPDATE(`korea_quantity='0.5'` 등 4열, `swing_bucket_start`, `hedge_mismatch_warned=1`) → `OLD_UPDATE_STATE`(version 1) 영향 1행 → 새 열 값 그대로 (AC8-c)
- `CHECK 는 불완전한 leg 와 버킷 없는 플래그와 시각 없는 펀딩을 거부한다` — 3건 각각 `SQLException`(errorCode 3819) (AC8-d)

- [x] **Step 1:** 테스트 작성(V20 없음).
- [x] **Step 2:** `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V20OwnerAccountAlertStateMigrationIntegrationTest' --offline --no-daemon` → RED(버전·열 부재. (c)(d) 는 열 부재로 선행 조건 실패 — RED 근거는 (a)(b)). 증거 로그 AC8.
- [x] **Step 3:** V20 작성 → PASS. CHECK 생성 오류면 `[ASSUMPTION]` A1 이 깨진 것 — 멈추고 보고.
- [x] **Step 4:** `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` → PASS. `./gradlew :apps:api:integrationTest --offline --no-daemon` → PASS(R13, Flyway 전체 적용).
- [x] **Step 5: Commit** — `feat: owner 계정 알림 상태 열 V20 migration`

### Task 9: JDBC store 상태 열·batch fixture (AC9, AC12 일부)

**Files:**
- Modify `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt`
- Modify `apps/batch/src/integrationTest/resources/batch-schema.sql` (`account_pnl_monitor_state` 만)
- Modify (허용 예외 R8) `apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt`
- Test (추가만) `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt`

**Interfaces — Consumes:** Task 2 상태 타입. **Produces:** `JdbcAccountPnlAlertStore` 공개 시그니처 무변경.

열 ↔ 프로퍼티 매핑: design §4.3 표 **그대로**.

`batch-schema.sql` 의 `account_pnl_monitor_state` (전문):

```sql
-- V18 + V20 (owner 계정 알림 상태)과 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
-- 실계정 순손익 알림의 단일 상태 행. 두 실행이 겹쳐도 늦은 쪽이 덮어쓰지 못하도록 version 조건부로 갱신한다.
CREATE TABLE IF NOT EXISTS account_pnl_monitor_state (
    id                        TINYINT       NOT NULL PRIMARY KEY,
    in_episode                BOOLEAN       NOT NULL,
    position_fingerprint      VARCHAR(255)  NULL,
    last_sent_at              DATETIME(6)   NULL,
    last_success_at           DATETIME(6)   NULL,
    monitoring_since          DATETIME(6)   NULL,
    outage_notified           BOOLEAN       NOT NULL,
    version                   BIGINT        NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,
    korea_quantity            VARCHAR(64)   NULL,
    korea_average_buy_price   VARCHAR(64)   NULL,
    foreign_signed_quantity   VARCHAR(64)   NULL,
    foreign_entry_price       VARCHAR(64)   NULL,
    hedge_mismatch_warned     BOOLEAN       NOT NULL DEFAULT FALSE,
    swing_bucket_start        DATETIME(6)   NULL,
    swing_baseline_percent    DECIMAL(12,2) NULL,
    swing_up_sent             BOOLEAN       NOT NULL DEFAULT FALSE,
    swing_down_sent           BOOLEAN       NOT NULL DEFAULT FALSE,
    reminder_day_start        DATETIME(6)   NULL,
    manual_funding_usdt       VARCHAR(64)   NULL,
    manual_funding_changed_at DATETIME(6)   NULL,
    CONSTRAINT ck_account_pnl_monitor_state_singleton CHECK (id = 1),
    CONSTRAINT ck_account_pnl_monitor_state_legs CHECK (
        (korea_quantity IS NULL AND korea_average_buy_price IS NULL
            AND foreign_signed_quantity IS NULL AND foreign_entry_price IS NULL)
        OR (korea_quantity IS NOT NULL AND korea_average_buy_price IS NOT NULL
            AND foreign_signed_quantity IS NOT NULL AND foreign_entry_price IS NOT NULL)
    ),
    CONSTRAINT ck_account_pnl_monitor_state_swing CHECK (
        swing_bucket_start IS NOT NULL
        OR (swing_baseline_percent IS NULL AND swing_up_sent = FALSE AND swing_down_sent = FALSE)
    ),
    CONSTRAINT ck_account_pnl_monitor_state_funding CHECK (
        manual_funding_changed_at IS NOT NULL OR manual_funding_usdt IS NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**요건 (구현부):**
- `load` SELECT 에 12열 추가, `toMonitorState` 가 design §4.3 대로 `account` 를 만든다(legs 는 `korea_quantity` null 이면 null, 아니면 `KoreaLeg.of(BigDecimal(..), BigDecimal(..))`/`ForeignLeg.of(..)`).
- `insertState`·`updateState` 의 열 목록에 12열 추가(값 순서 = design §4.3 표 순서). 기존 9열·`version` 조건 무변경.
- `save` 의 기존 `mails.forEach(::validate)` 앞에 `validateState(state.account)`: legs 4값·펀딩의 `toPlainString().length <= 64`, `swing.baselinePercent?.abs() < 10^10`. 실패 메시지 design §4.3 전문(값 없음).
- 클래스 KDoc 에 "상태 행은 수익·중단 + owner 계정 알림(#89) 상태" 한 줄.
- `AccountPnlFixtureSchemaIntegrationTest` (R8): `account_pnl_monitor_state fixture 는 V18 컬럼과 같다` 기대 목록 끝에 Task 8 (a) 의 12개 문자열 추가, KDoc 경로 목록에 `V20__add_owner_account_alert_state.sql`.

**테스트가 재는 것** (기존 헬퍼 재사용, 새 헬퍼 추가만):
- `계정 알림 상태 전체가 저장 후 그대로 읽힌다` — dod AC9-a 값으로 `save(0, …)` → `load().account isEqualTo` (AC9-a)
- `계정 알림 상태 INITIAL 은 빈 열이고 INITIAL 로 읽힌다` — 열 직접 조회 NULL/0 (AC9-b)
- `leg 와 펀딩의 소수 자릿수가 손실 없이 왕복한다` — `0.00012345`, `123456789.123456789`, 펀딩 `-0.000001` (AC9-c)
- `version 이 다르면 계정 알림 열도 바뀌지 않는다` (AC9-d)
- `범위를 넘는 값은 값 없이 거부되고 아무것도 쓰지 않는다` — 65자 leg, 기준선 `10000000000.00` → `IllegalArgumentException`, 메시지에 입력 숫자 없음, 상태 행·outbox 무변경 (AC9-e)

- [x] **Step 1:** 테스트 추가 + fixture SQL·fixture 테스트 수정. store 무변경.
- [x] **Step 2:** `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest' --offline --no-daemon` → RED((a)(c) `load().account == INITIAL`, (e) 예외 없음). 증거 로그 AC9.
- [x] **Step 3:** store 구현 → PASS(기존 메서드 포함).
- [x] **Step 4:** `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.config.AccountPnlFixtureSchemaIntegrationTest' --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --tests 'io.premiumspread.application.job.OwnerAlertDeliveryIntegrationTest' --offline --no-daemon` → PASS.
- [x] **Step 5: Commit** — `feat: 실계정 알림 상태 행에 owner 계정 알림 상태 저장`

### Task 10: Job 판정·라우팅 (AC10)

**Files:** Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`;
Test Create `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobAccountAlertTest.kt`

**Interfaces — Produces (전문):**

```kotlin
class AccountPnlMonitorJob(
    private val korea: KoreaSpotAccountReadPort,
    private val foreign: ForeignPerpAccountReadPort,
    private val fx: FxRateReadPort,
    private val lastSuccess: JobLastSuccessReadPort,
    private val store: AccountPnlAlertStore,
    private val mailSender: AccountPnlMailSender,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
    private val hourlyReportEnabled: Boolean = false,
    private val routing: OwnerAlertRouting = OwnerAlertRouting.EMAIL_ONLY,
    private val discordSender: OwnerAlertDiscordSender? = null,
    /** owner 계정 알림 설정(#89). null 이면 판정하지 않고 account 상태를 그대로 저장한다. production 배선은 항상 넘긴다. */
    private val accountAlerts: AccountAlertSettings? = null,
) {
    fun run(): JobResult
}

// private — 관측 결과
private data class Observed(
    val observation: PnlObservation,
    val failure: AccountReadFailureCategory?,
    val round: AccountRound?,      // 성공 회차(Computed·NoPosition)만, 실패면 null
)
```

클래스 KDoc 보강 (전문 — 기존 `:57-58` 의 시각 문단 **뒤에** 한 항목 추가, 기존 문장은 그대로):

```
 * - 예외: owner 계정 알림(포지션 변경·순수익률 급변·미반영 리마인더)은 관측 완료 시각(`completedAt` = 포지션이 있으면 `pnl.calculatedAt`)을 쓴다.
 *   급변 버킷이 정시 보고와 같은 버킷(`calculatedAt` 의 zone 정시)이어야 하기 때문이다 (owner-account-alerts design §5.1).
```

`JobResult` 분기: design §5.2 표 그대로.

로그 (키 전문 — design §5.3):
- INFO `Owner account alert decided: kind={}, eventKey={}` — `kind` 는 `OwnerAlertKind.name`, 알림마다 1줄
- INFO `Account position changed: korea={}, foreign={}, hedgeMismatchWarning={}` — 세 값 `Boolean`
- INFO `Account position legs recorded; first observation sends no change alert` — 저장 legs 가 null 이었고 이번에 채워졌을 때 1회
- **담지 않는 값:** leg 수량·가격, 펀딩 값, 수신 주소, webhook, credential, 상태·알림 객체 `toString`

**요건 (구현부):**
- `observe()` 성공 경로에서 `AccountRound(PositionLegs.of(k, f), f.positionSource, f.fundingUsdt, pnl 또는 null, completedAt)` 를 try 안에서 만든다. 실패·취소 경로 무변경. `logObserved`·`noPositionReason` 무변경.
- `monitor()`: outage 판정 뒤 `accountAlerts?.let { AccountAlertPolicy.decide(loaded.account, observed.round, it, zone) } ?: AccountAlertDecision(loaded.account, emptyList())`.
  `next = AccountPnlMonitorState(alert.next, outage.next, loaded.version, account.next)`.
- `buildMails` 결과 **뒤에** `account.alerts.flatMap { route(it.kind, AccountAlertContent.mail(it, zone)) { AccountAlertContent.discord(it, zone) } }` (기존 private `route` 재사용).
- 로그는 행을 만든 뒤·save 전에 남긴다("decided" 이지 발송 로그가 아니다).

**테스트가 재는 것** (`AccountPnlMonitorJobAccountAlertTest` — #88 `FakeOwnerAlertStore`·`FakeMailSender`·`FakeDiscordSender`·`MutableClock` **무수정 재사용**, 계정·환율·lock fake 는 이 클래스 안에 만든다(동결 테스트 파일 무수정),
routing `OwnerAlertRouting(OwnerAlertChannels(true, true))`, `hourlyReportEnabled = false`, 기본 fixture 는 미반영 없음·manual 출처, clock 시작 `2026-10-20T00:30:00Z`(09:30 KST)):
- `설정이 없으면 계정 알림을 만들지 않고 상태를 그대로 둔다` — store.state.account 에 임의 값(legs·기준선)을 넣고 빗썸 변경·큰 변동·미반영 회차 3번 → `account-pnl-position-`·`-swing-`·`-reminder-` 접두사 행 0, `store.state.account` 불변 (AC10-a)
- `첫 회차는 leg 만 기록한다` — 행 0, `account.position.legs` 채워짐, `account.reminder.lastDayStart` null (AC10-b)
- `빗썸만 바뀐 수동 회차는 메일과 Discord @here 로 변경을 알리고 경고는 한 번뿐이다` — 행 [EMAIL, DISCORD(PNL/HERE, `COLOR_HEDGE_MISMATCH`)] `SENT`, 새 Job 인스턴스로 빗썸 재변경 → 경고 없는 변경(`COLOR_POSITION_CHANGED`) (AC10-c)
- `같은 시간 안에 0.5%p 움직이면 Discord 소리로 한 번만 알린다` — 버킷 첫 회차 뒤 `+0.50` → DISCORD(PNL/SOUND) 1행, EMAIL 0, 같은 버킷 추가 상승 → 행 증가 없음 (AC10-d)
- `미반영이 처음 생긴 회차에 무음 리마인더 한 번` — 09:30 KST 첫 회차(미반영 없음) 행 0 → `clock +5h` 빗썸 진입 수수료 null 회차 → DISCORD(PNL/SILENT) 1행 → `+1h` 펀딩까지 null 회차 → 행 증가 없음 (AC10-e)
- `관측 실패 회차는 계정 알림 상태를 바꾸지 않는다` — korea fake 예외 → `account` 불변, 새 행 0 (AC10-f)
- `메일만 쓰면 세 종류 모두 메일 한 통씩` — `EMAIL_ONLY` (AC10-g)
- `version 충돌이면 저장하지 않고 다음 회차가 다시 판정한다` — save 직전 version 을 올리는 store 래퍼(이 클래스 안, `FakeOwnerAlertStore` 위임) → `Skipped("state-version-conflict")`·행 0 → 다음 회차 변경 행 생성 (AC10-h)

- [x] **Step 1:** 생성자 인자·`Observed.round` 추가, **stub**: `monitor()` 가 `accountAlerts` 를 무시하고 `account = loaded.account` 로 저장 + 테스트 작성.
- [x] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobAccountAlertTest' --offline --no-daemon` → RED((a)(f) 는 초록일 수 있다). 증거 로그 AC10.
- [x] **Step 3:** 구현 + 클래스 KDoc 보강(위 전문). dod AC10 명령 → PASS(동결 Job 테스트 3개·#88 Job·디스패처 테스트 포함). `./gradlew architectureTest --offline --no-daemon` → PASS.
- [x] **Step 4: Commit** — `feat: 실계정 모니터 회차에서 owner 계정 알림 판정·발송`

### Task 11: 설정·배선 (AC11)

**Files:** Modify `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt`, `apps/batch/src/main/resources/application.yml`(`account-alerts` 블록만);
Test (추가만) `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt`

**Interfaces — Produces (전문, design §6.2):**

```kotlin
// AccountPnlMonitorConfiguration — 클래스·조건 무변경, bean 1개 추가 + Job bean 인자 끝에 1개
@Bean
fun accountAlertSettings(
    @Value("\${account-pnl-monitor.account-alerts.swing-threshold-percent:0.5}") swingThreshold: BigDecimal,
    @Value("\${account-pnl-monitor.account-alerts.reminder-hour:9}") reminderHour: Int,
    @Value("\${account-pnl-monitor.account-alerts.manual-funding-stale-days:3}") manualFundingStaleDays: Long,
): AccountAlertSettings = AccountAlertSettings(swingThreshold, reminderHour, Duration.ofDays(manualFundingStaleDays))

@Bean
fun accountPnlMonitorJob(
    /* 기존 12개 인자 그대로 */,
    accountAlerts: AccountAlertSettings,
): AccountPnlMonitorJob   // 기존 인자 전달 + accountAlerts = accountAlerts
```

yml (전문): design §6.2 의 `account-alerts` 블록을 `account-pnl-monitor.hourly-report` 바로 뒤에 그대로.

**테스트가 재는 것** (기존 `runner`·`Stubs`·`enabledProps`·`allWebhookProps` 재사용, 기존 메서드 무수정):
- `계정 알림 설정은 기본값으로 뜬다` — `getBean(AccountAlertSettings) == AccountAlertSettings()` (AC11-a)
- `계정 알림 설정 env 가 반영된다` — `0.8`·`7`·`5` (AC11-b)
- `잘못된 계정 알림 설정은 기동 실패다` — 3건, 원인 체인 메시지 (AC11-c)
- `기동 INFO 경로 요약에 계정 알림 세 종류가 있다` — 메일+Discord·메일만 두 구성 (AC11-d)
- `계정 알림 종류별 override 가 라우팅 bean 에 반영된다` (AC11-e)

- [x] **Step 1:** **stub** — `accountAlertSettings` bean 이 `@Value` 를 무시하고 `AccountAlertSettings()` 반환, Job 에 넘기지 않음, yml 미변경 + 테스트 작성.
- [x] **Step 2:** `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` → RED((b)(c)). 기존 wiring 메서드는 초록. 증거 로그 AC11.
- [x] **Step 3:** 구현 + yml. dod AC11 명령 → PASS. `./gradlew test --offline --no-daemon` → PASS(R2). `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` → PASS(R11).
- [x] **Step 4: Commit** — `feat: owner 계정 알림 설정 env 와 배선`

### Task 12: 계정 알림 E2E 통합 (AC12)

**Files:** Create `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAccountAlertIntegrationTest.kt`

**Interfaces — Consumes:** `BatchIntegrationTestBase`, autowired `AccountPnlAlertStore`(`JdbcAccountPnlAlertStore`), Task 10 Job 생성자. 새 production 코드 없음.

**테스트가 재는 것** (`AccountPnlAlertDurabilityIntegrationTest` 의 fake 계정·환율·lock 구성을 이 클래스 안에 다시 만든다 — 그 파일 무수정. 기록용 메일·Discord sender(이 클래스 안), routing `OwnerAlertChannels(true, true)`,
`hourlyReportEnabled = false`, `accountAlerts = AccountAlertSettings()`, manual 출처, 빗썸 진입 수수료 null(미반영 1항목), `MutableClock` 시작 `2026-10-19T23:30:00Z`(08:30 KST)). 한 메서드 안의 순차 시나리오:
1. `23:30Z` 첫 회차 → outbox 행 0(08:30 KST 라 리마인더 판정 전), 상태 4 leg 열 채워짐, `manual_funding_changed_at = 23:30Z`, `reminder_day_start` NULL (AC12-a·f)
2. `23:31Z` 빗썸 수량 `0.5 → 0.6` → `account-pnl-position-*` EMAIL·DISCORD 각 1행 `SENT`, `hedge_mismatch_warned = 1` (AC12-b)
3. 새 Job 인스턴스, 바이낸스 `-0.5 → -0.6`·펀딩 `3.2 → 4.0`, `23:32Z` → 경고 없는 변경 1건(Discord 기록 제목에 `헤지 불일치` 없음), `hedge_mismatch_warned = 0`, `manual_funding_changed_at = 23:32Z` (AC12-c·f)
4. `2026-10-20T00:00:05Z`(09:00 KST, 새 버킷, 항목 있음) → `swing_bucket_start = 00:00Z`·`swing_baseline_percent` 저장, `account-pnl-reminder-{2026-10-19T15:00:00Z ms}` DISCORD 1행, `reminder_day_start = 2026-10-19T15:00:00Z` (AC12-d·e)
5. `00:01Z` 빗썸 호가를 올려 `netPercent` 를 기준선 +0.50 이상으로 → `account-pnl-swing-up-{00:00Z ms}` DISCORD 1행 (AC12-d)
6. `00:02Z` → 리마인더·급변 행 수 증가 없음 (AC12-e)

- [x] **Step 1:** 테스트 작성.
- [x] **Step 2: RED (변이).** 앞 태스크가 구현을 끝냈으므로 첫 실행은 초록일 수 있다. Job 에서 `account.next` 대신 `loaded.account` 를 저장하도록 임시 변경 →
  `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerAccountAlertIntegrationTest' --offline --no-daemon` → 2~5단계 FAILED 확인 → 되돌리고 `git diff --stat -- apps/batch/src/main` 빈 출력. 증거 로그 AC12.
- [x] **Step 3:** dod AC12 명령 → PASS. `./gradlew :apps:batch:integrationTest --offline --no-daemon` → PASS(R10).
- [x] **Step 4: Commit** — `test: owner 계정 알림 재시작 지속 통합 테스트`

### Task 13: 문서 (AC13)

**Files:** Modify `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`

**요건:** design §11 목록과 dod AC13 (a)~(f) 전부. 메시지 예는 가짜 숫자. 리마인더 설명은 사용자 확정 D1 문구 그대로("설정 시각 이후 포지션 있는 성공 회차마다 확인, 항목이 처음 확인된 회차에 하루 1회, 보냈을 때만 그날 소비, 포지션 없음 회차는 확인하지 않음, 종일 없으면 보내지 않음").
AC14 체크리스트 절 제목 `## 계정 알림 Discord 체크리스트 (owner-account-alerts AC14)` 에 dod AC14 (a)~(e) 를 체크박스로.
`### 배포와 롤백` 에 "V20 은 additive — 구 binary 와 호환, 롤백 SQL 없음, 신 batch 는 API(V20) 배포 뒤 기동" 추가. `account_pnl_monitor_state` 설명에 "에피소드·마지막 발송·중단 알림 여부 + 계정 알림 leg·급변 기준선·리마인더 보낸 날·수동 펀딩 변경 시각".
`.ai/PROJECT_STATUS.md`: `:13` 모듈 표 `Flyway V1~V19` → `Flyway V1~V20`, migration 표의 V19 행(`:92`) 뒤에 `| V20 | account_pnl_monitor_state 에 owner 계정 알림 상태 12열 | 포지션 변경·순수익률 급변·미반영 리마인더 상태 |` 행.

- [x] **Step 1:** 문서 작성.
- [x] **Step 2:** `bash docs/check-documentation.sh` → exit 0. `./gradlew harnessCheck --offline --no-daemon` → PASS. `grep -n "V1~V20" .ai/PROJECT_STATUS.md` 1줄.
- [x] **Step 3: Commit** — `docs: owner 계정 알림 runbook 과 아키텍처 문서`

### Task 14: 최종 gate

- [x] **Step 1:** dod 회귀 방어선 R1~R13 전부 실행, R6·R7·R8 diff 출력을 증거 로그에 첨부.
- [x] **Step 2:** dod AC1~AC12 검증 명령 전부 실행·기록. AC13 사람 사후 검토 요청, AC14 owner 확인 요청(PR 코멘트).
- [x] **Step 3: Commit** — `docs: owner 계정 알림 최종 검증 기록`

## 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | — | — |
| ⑥ R1 | Task 1 — 허용 예외를 `OwnerAlertRoutingTest` 한 파일(단언 2곳 + 이름 2개)로, `OwnerAlertPropertiesTest` 수정 제거 | R1 #1 (MAJOR), #6 |
| ⑥ R1 | Task 10 — 클래스 KDoc 시각 규칙 보강 단계 | R1 #2 |
| ⑥ R1 | Task 13 — `.ai/PROJECT_STATUS.md:13` `V1~V20` + V20 행 | R1 #3 |
| ⑥ R1 | Task 11 — design §6.2 와 일치(별도 bean), "구체화" 문구 제거 | R1 #4 |
| ⑥ R1 | Task 0·8·14 — `:apps:api:integrationTest`(R13) | R1 #5 |
| ⑦ | Task 5·6·10·12·13 — 리마인더 "항목이 처음 생긴 회차에 하루 1회, 보냈을 때만 소비" 반영(테스트 경계 15:00 발송·같은 날 두 번째 항목 미발송·종일 없음·NoPosition 미소비) | 사용자 확정 D1 |
| ⑦ | Task 1·3 — D2·D3 확정 표기, P3 해소 아님 테스트 | 사용자 확정 D2·D3 |
| 구현 | 아래 구현 중 변경 반영 | ⑧~⑩ |
