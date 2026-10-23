# owner 알림 채널 기반 — Discord·메일 라우팅 (issue #88) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실계정 순손익 알림 4종을 env 로 켜고 끄는 메일·Discord 두 채널로, Domain 한 곳의 종류별 라우팅 표에 따라 보낸다.

**Architecture:** Domain `owneralert` 패키지가 채널·Discord 대상·푸시 enum 과 `OwnerAlertKind` 기본 경로·해석 규칙을 소유한다. 모니터 Job 은 알림마다
`OwnerAlertRows.expand` 로 켜진 채널 수만큼 outbox 행을 만들어 기존 `AccountPnlAlertStore.save` 한 트랜잭션에 넣고, application
`OwnerAlertDispatcher`(Job 의 기존 전달 루프를 추출)가 행의 채널에 맞는 port(`AccountPnlMailSender`·`OwnerAlertDiscordSender`)로 보낸다.
outbox 는 V19 로 `account_pnl_mail` 에 `channel`·Discord 메타를 더하고 UNIQUE 를 `(event_key, channel)` 로 넓힌다. Discord webhook adapter·
`owner-alert.*` 설정·채널 판정·수신 주소 판정(`OwnerAlertChannelRequirements`)은 `infrastructure:batch`, 배선은 기존 `AccountPnlMonitorAdapterConfiguration`
안이다(#88 한정 — design §9).

**Tech Stack:** Kotlin 2.0, Spring Boot 3.5 (`@ConfigurationProperties` constructor binding, `Condition`, `AnyNestedCondition`, `ObjectProvider`),
WebFlux `WebClient`, Jackson, Flyway·MySQL 8 (Testcontainers), JUnit5 + AssertJ + MockK, okhttp3 MockWebServer, `ApplicationContextRunner` +
`OutputCaptureExtension`.

**Spec:** `docs/work/owner-alert-channel/design.md` · **DoD:** `docs/work/owner-alert-channel/dod.md` · ⑥ R1 반영 내역은 맨 끝 `## 변경 이력`

## Global Constraints

- base `1bce3dd`. 직전 단위 `docs/work/live-account-pnl-*`·`account-pnl-round-log`·`bithumb-orders-query-hash` 는 수정하지 않는다.
- **동결·직전 단위 테스트 무수정** (dod R3): `AccountNetPnlTest`·`PnlAlertPolicyTest`·`MonitoringOutagePolicyTest`·`AccountHedgePositionTest`·
  `AccountPnlMonitorJobTest`·`AccountPnlMonitorJobHourlyReportTest`·`AccountPnlMonitorJobObservationLogTest`·`AccountPnlAlertDurabilityIntegrationTest`·
  `AccountPnlEmailSenderAdapterTest`·`infrastructure/batch/src/test/.../exchange/**`. 이 테스트들이 컴파일되도록:
  - `AccountPnlAlertStore` 에 **추상 메서드를 추가하지 않는다** (기본 구현 메서드만).
  - `AccountPnlMonitorJob` 생성자의 기존 11개 인자 순서·타입을 바꾸지 않고 **뒤에만** 기본값 있는 인자를 붙인다. 전달은 `run()` 회차 안에서 한다.
  - `AccountPnlMail(eventKey, subject, body)`·`ClaimedAccountPnlMail(id, mail, claimToken, attemptCount)` 생성과 `.mail` 프로퍼티를 유지한다.
  - 관측 경로(`observe()` 의 취소 재전파)는 건드리지 않는다.
- 확장하는 기존 테스트(`AccountPnlMailContentTest`·`AccountPnlMonitorWiringTest`·`JdbcAccountPnlAlertStoreIntegrationTest`)는 **추가만** (dod R6).
  `AccountPnlFixtureSchemaIntegrationTest` 는 dod R7 허용 예외만.
- 메일 4종의 eventKey·제목·본문 **바이트 불변** — Task 1 golden 6건이 이후 모든 태스크에서 초록이어야 한다. golden 을 고쳐야 할 상황이면 멈추고 보고.
- webhook URL·token 을 로그·예외 메시지·원인 체인·stack trace·`toString()`·DB 에 남기지 않는다. 설정은 **`String?` 으로 바인딩해 직접 해석**하고
  해석 실패 예외는 property key 와 규칙만 담으며 `cause` 를 연결하지 않는다. Discord 예외도 `cause` 를 연결하지 않는다(WebClient 예외 메시지가 URI 를 담는다).
  누락 WARN 은 env 이름만. 수신 주소 값도 로그·예외에 넣지 않는다.
- 수신 주소 property key `account-pnl-monitor.recipient` 와 env `ACCOUNT_PNL_ALERT_RECIPIENT` 는 바꾸지 않는다(design §7.2 I-3).
- 테스트의 URL 은 MockWebServer(localhost) 또는 `https://discord.example.test/...` 만 (`TestIsolationArchitectureTest`). 실 webhook·실 SMTP 금지.
  고정 `Clock`·`ZoneId.of("Asia/Seoul")`.
- Domain 에 Spring/HTTP/Jackson 의존 추가 금지. 앱(`apps:batch` main)은 infrastructure 타입을 import 하지 않는다. `OwnerAlertKind` 전체에 대한
  `when` 을 코드에 두지 않는다(후속 이슈가 enum 값을 더해도 컴파일이 깨지지 않게).
- Redis·`PublicEndpointPolicy`·`JobId`·`JobConfig`·Scheduler 무변경. migration 은 V19 **추가만**.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 변이에서의 assertion 실패다.
- 커밋: `<type>: <subject>` + 한글 bullet, `Co-Authored-By` 금지. 모든 gradle 명령은 `--offline --no-daemon`.

## Review Focus

1. **동결 테스트 호환** — Job 의 claim 횟수·owner 접두사·회차 상한·실패 건 회차 끝 되돌림이 디스패처 추출 뒤에도 같은가(Task 9 Step 1 에서
   동결 테스트가 무수정으로 초록인지).
2. **조건 이중화** — app `AccountPnlMonitorEnabledCondition` 과 infra `OwnerAlertChannelRequirements`·`AccountPnlCredentialsPresentCondition`·
   `OwnerAlertChannelAvailableCondition` 이 채널 판정·수신 주소 필수 판정에서 같은 답을 내는가(Task 10 행렬). 특히 "SMTP 속성 켜짐 + `EmailSender` bean 없음"(기존 테스트)과
   "SMTP 꺼짐 + Discord"(신규), "메일 채널 꺼짐 + recipient 빈 값"(신규).
3. **라우팅-sender 일치** — `OwnerAlertRouting.channels` 가 bean 존재에서 만들어지는가. 어긋나면 Discord 행이 sender 없이 쌓이거나 메일이 꺼진 채널로 간다.
4. **URL 누출** — `Webhook`·`DiscordWebhooks` 의 `toString`, URI 파싱 예외의 원인 체인, WebClient 예외의 원인 체인(취소 경로 포함), 디스패처 WARN 의 `reason`.
5. **메일 바이트 불변** — 서식 함수 추출(Task 4)이 숫자 반올림·locale·공백을 바꾸지 않는가. golden 6건이 지킨다.
6. **V19 원자성·배포 호환 범위** — 한 문장 ALTER, 구 binary INSERT 가 복합 UNIQUE 로 중복 제거되는가(Task 5). 호환은 **INSERT 뿐**이고 구 binary claim 은
   channel 을 모른다 — 배포는 구 batch 중지 후 신 batch 기동, 롤백 SQL 이 구 claim 에 EMAIL 만 남기는가(Task 5 AC4-f, Task 12 runbook).
7. **수신 주소 소유** — 메일 sender bean 이 `AccountPnlMonitorProperties` 를 받지 않고 `OwnerAlertChannelRequirements.recipient` 로 묶이는가(#90 이 bean 을 그대로 옮길 수 있는가).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt` (신규) | 채널·대상·푸시 enum, `OwnerAlertKind`, route·override·channels, `OwnerAlertRouting` | 2 |
| `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertDiscord.kt` (신규) | `DiscordMessage`(제한·절단), `OwnerAlertDiscordSender`, `OwnerAlertSendException` | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt` | `AccountPnlMail` 채널 프로퍼티(3), 서식 위임(4) | 3·4 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRows.kt` (신규) | 행 확장 | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertStore.kt` | `markFailed` 기본 메서드, KDoc(공용 outbox) | 3 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt` (신규) | 메일·Discord 공용 서식 (`internal`) | 4 |
| `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlDiscordContent.kt` (신규) | Discord 렌더링 4종 | 4 |
| `infrastructure/common/src/main/resources/db/migration/V19__add_owner_alert_channel.sql` (신규) | DDL | 5 |
| `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt` | 채널 컬럼·`markFailed` | 6 |
| `apps/batch/src/integrationTest/resources/batch-schema.sql` | V19 형태 fixture | 6 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapter.kt` (신규) | `DiscordWebhooks`, adapter, payload, 취소 판정 | 7 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertProperties.kt` (신규) | `owner-alert.*` 바인딩·해석·마스킹 | 8 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelRequirements.kt` (신규) | 채널 판정·수신 주소 판정·조건 3개 | 10 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorAdapterConfiguration.kt` | 클래스 조건 교체, 필수 키(recipient 분리), 메일 sender 수신 주소 출처, bean 2개 추가 | 10 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorProperties.kt` | `recipient` KDoc 한 줄(값·바인딩 무변경) | 10 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcher.kt` (신규) | 전달 루프·채널 분기 | 9 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt` | 행 생성 라우팅·디스패처 위임 | 9 |
| `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt` | 앱 조건 채널 판정·WARN, Job bean | 10 |
| `apps/batch/src/main/resources/application.yml` | `owner-alert` 블록 | 10 |
| `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md` | 문서 | 12 |
| tests — domain: `OwnerAlertRoutingTest`·`DiscordMessageTest`(owneralert), `OwnerAlertRowsTest`·`AccountPnlDiscordContentTest`·`AccountPnlMailContentTest`(accountpnl); infra:common: `V19OwnerAlertChannelMigrationIntegrationTest`·`JdbcAccountPnlAlertStoreIntegrationTest`; infra:batch: `DiscordWebhookSenderAdapterTest`·`OwnerAlertPropertiesTest`·`OwnerAlertChannelRequirementsTest`; apps:batch: `OwnerAlertDispatcherTest`·`AccountPnlMonitorJobOwnerAlertTest`·`AccountPnlMonitorWiringTest`·`OwnerAlertYamlContractTest`·`OwnerAlertDeliveryIntegrationTest`·`AccountPnlFixtureSchemaIntegrationTest` | | 1~11 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  ./gradlew architectureTest --offline --no-daemon                                          # R1
  ./gradlew test --offline --no-daemon                                                      # R2·R4
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon                   # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                    # R8 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                               # R9 (Docker)
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1              # V19 번호 근거
  printf '%s\n' 'System.out.println(java.time.ZonedDateTime.of(2026,10,23,9,26,0,0,java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", java.util.Locale.ROOT)))' '/exit' | jshell -q   # [ASSUMPTION] A1
  grep -rn "AccountPnlMonitorJob(" --include=*.kt apps                                      # 생성자 호출처
  grep -rln "AccountPnlAlertStore" --include=*.kt .                                         # store 구현·fake 목록
  grep -rn "properties.recipient\|\.recipient\b" --include=*.kt infrastructure apps         # 수신 주소 소비처 (I-3)
  ```
- [ ] **Step 2: 예상 결과.** gradle 5건 exit 0. `ls` 마지막 줄 `V18__create_account_pnl_alert.sql`. jshell 출력 `2026-10-23 09:26 KST`.
  `AccountPnlMonitorJob(` 호출처는 `AccountPnlMonitorConfiguration` + 테스트 3개 + 통합 테스트 1개뿐이고 모두 기존 11개 인자 이내다.
  `AccountPnlAlertStore` 구현은 `JdbcAccountPnlAlertStore` 와 테스트 fake 뿐이다. 수신 주소 production 소비처는 `AccountPnlMonitorAdapterConfiguration.accountPnlMailSender`
  하나뿐이다. **다르면 멈추고 보고한다** — jshell 이 `KST` 가 아니면 design §6.2 `short()` 형식(A1)을 Gate 1 에서 다시 정해야 하고, `ls` 가 V18 이 아니면
  V19 번호를 바꿔야 한다. `--offline` 이 cache 부재로 실패하면 온라인 1회 실행으로 cache 를 채우고 그 사실을 기록한다. Docker 부재로 R8·R9 를 못 돌리면
  "미실행"으로 기록한다(green 금지).
- [ ] **Step 3: Commit** — `docs: owner 알림 채널 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: 메일 golden 특성화 확대 (AC3-a)

**Files:** Modify `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt` (테스트 추가만, 기존 메서드·fixture 무수정)

**Interfaces:** 없음 (production 무변경).

**테스트가 재는 것** (golden 은 이 태스크 시점 = base 코드의 실제 출력을 raw string 상수로 옮긴 것, 각 `eventKey`·`subject`·`body` 전체 `isEqualTo`):
- `정시 보고 메일은 기준 golden 과 같다` — 기존 `pnl()` fixture, `calculatedAt` 은 fixture 그대로(`2026-10-20T00:00:05Z`), zone `Asia/Seoul`
- `빗썸 인증 중단 메일은 기준 golden 과 같다` — `OutageNotice.Outage(BITHUMB_AUTH, lastSuccessAt = null)`, `now = 2026-10-20T03:10:00Z`
- `환율 지연 중단 메일은 기준 golden 과 같다` — `OutageNotice.Outage(STALE_FX, lastSuccessAt = 2026-10-20T03:00:00Z)`, 같은 `now`
- `복구 메일은 기준 golden 과 같다` — `now = 2026-10-20T03:15:00Z`
- golden 상수는 이후 태스크에서 **고치지 않는다.**

- [ ] **Step 1:** 네 테스트 작성(base 출력 복사).
- [ ] **Step 2: RED (변이).** 특성화라 첫 실행은 초록이다. `AccountPnlMailContent` 의 `time()` 형식 문자열 끝에 임시로 공백 하나를 넣고
  `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` → 새 golden 4건(과 기존 수익 golden 2건) FAILED.
  `git checkout -- domain/src/main` 후 `git diff --stat -- domain/src/main` 빈 출력 기록.
- [ ] **Step 3:** 같은 명령 → BUILD SUCCESSFUL. 증거 로그 AC3-a.
- [ ] **Step 4: Commit** — `test: 실계정 정시 보고·중단·복구 메일 golden 특성화 테스트`

### Task 2: Domain 라우팅 (AC1)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRouting.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/owneralert/OwnerAlertRoutingTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.owneralert

/** owner 알림 outbox 행의 채널. DB `account_pnl_mail.channel` 값 = enum 이름. */
enum class OwnerAlertChannel { EMAIL, DISCORD }

/** Discord webhook(채널) 논리 대상. DB `discord_target` 값 = enum 이름. URL 은 adapter 설정이 소유한다. */
enum class DiscordTarget { PNL, MARKET, OPS }

/** Discord 푸시 수준. SILENT = flags 4096, SOUND = 기본, HERE = 기본 + content "@here". DB `discord_push` 값 = enum 이름. */
enum class DiscordPush { SILENT, SOUND, HERE }

data class DiscordRoute(val target: DiscordTarget, val push: DiscordPush)

data class OwnerAlertRoute(val email: Boolean, val discord: DiscordRoute?)

/**
 * owner 알림 종류와 기본 경로 — 라우팅 표의 유일한 코드 위치(design §3.2).
 * 이 enum 전체에 대한 `when` 을 코드 어디에도 두지 않는다 (#89~#91 이 값을 더한다).
 */
enum class OwnerAlertKind(val defaultRoute: OwnerAlertRoute) {
    PROFIT(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE))),
    HOURLY_REPORT(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))),
    MONITORING_OUTAGE(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    MONITORING_RECOVERED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    ;

    /** 설정 key — `HOURLY_REPORT` → `hourly-report`. */
    val key: String get() = name.lowercase().replace('_', '-')

    companion object {
        /** trim·소문자화한 [key] 와 같은 종류. 없으면 null. */
        fun fromKey(key: String): OwnerAlertKind?
    }
}

/** env 로 받은 종류별 덮어쓰기. null/false 는 "지정 안 함". */
data class OwnerAlertRouteOverride(
    val email: Boolean? = null,
    val discordTarget: DiscordTarget? = null,
    val discordDisabled: Boolean = false,
    val push: DiscordPush? = null,
) {
    init {
        require(!(discordDisabled && discordTarget != null)) { "discordTarget must not be set when discord is disabled." }
    }
}

/** 이번 기동에서 실제로 쓸 수 있는 채널 (sender bean 존재). */
data class OwnerAlertChannels(val email: Boolean, val discord: Boolean)

class OwnerAlertRouting(
    val channels: OwnerAlertChannels,
    private val overrides: Map<OwnerAlertKind, OwnerAlertRouteOverride> = emptyMap(),
) {
    /** 기본 경로 ⊕ override ⊕ 채널 가용성 (design §3.3 규칙 전문). */
    fun resolve(kind: OwnerAlertKind): OwnerAlertRoute

    override fun toString(): String   // "OwnerAlertRouting(channels=$channels, overrides=$overrides)"

    companion object {
        /** 메일만 — 기존 동작. Job 생성자 기본값. */
        val EMAIL_ONLY: OwnerAlertRouting = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = false))
    }
}
```

**요건 (구현부):** `resolve` 는 design §3.3 의사코드 그대로(Discord 꺼짐 → 4종 메일 = 사용자 확정 D1). `fromKey` 는 `entries.firstOrNull { it.key == key.trim().lowercase() }`.

**테스트가 재는 것:**
- `종류는 네 개이고 기본 경로는 설계 표와 같다` — `entries` 이름 목록 `containsExactly`, 4종 `defaultRoute` 각각 `isEqualTo`
- `key 는 소문자 kebab 이고 fromKey 로 되돌아온다` — 4종 key 값, `fromKey(" Hourly-Report ")` == `HOURLY_REPORT`, `fromKey("nope")` null
- `EMAIL_ONLY 는 네 종류 모두 메일만` (AC1-b)
- `메일과 Discord 를 모두 쓰면 기본 표대로` (AC1-c, 4종)
- `Discord 만 쓰면 메일 없이 Discord 기본값` (AC1-d)
- `Discord 가 없으면 메일이 유일한 채널이라 기본 메일 false 인 종류도 메일로 간다` — 정시 보고·복구 `(true, null)`
- `Discord 가 없어도 email=false 를 명시하면 그 종류는 행이 없다` — 정시 보고 `(false, null)` (AC1-e)
- `override 는 지정한 필드만 바꾼다` — 수익에 `discordTarget=OPS` → `(true, OPS/HERE)`, `push=SOUND` → `(true, PNL/SOUND)`, 정시 보고 `email=true` → `(true, PNL/SILENT)` (AC1-f)
- `discordDisabled 면 Discord 가 없다` — 수익 → `(true, null)`
- `discordDisabled 와 discordTarget 을 함께 주면 거부` → `IllegalArgumentException`
- `채널이 모두 없으면 모든 종류가 빈 경로` — `OwnerAlertChannels(false, false)` → 4종 `(false, null)`

- [ ] **Step 1:** 타입 전부 + **stub** `resolve(kind) = kind.defaultRoute`(채널·override 무시) 상태로 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.OwnerAlertRoutingTest' --offline --no-daemon` → RED
  (EMAIL_ONLY·Discord-only·fallback·override 케이스 assertion 실패. 기본 표·key 테스트는 초록일 수 있다 — 그대로 기록). 증거 로그 AC1.
- [ ] **Step 3:** 구현. 같은 명령 → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 종류별 채널 라우팅 Domain 모델`

### Task 3: outbox 행 채널·Discord 메시지·port (AC2)

**Files:**
- Create `domain/src/main/kotlin/io/premiumspread/domain/owneralert/OwnerAlertDiscord.kt`
- Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRows.kt`
- Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContent.kt` (`AccountPnlMail` 선언만)
- Modify `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlAlertStore.kt`
- Test `domain/src/test/kotlin/io/premiumspread/domain/owneralert/DiscordMessageTest.kt`, `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/OwnerAlertRowsTest.kt`

**Interfaces — Consumes:** Task 2 enum·`DiscordRoute`·`OwnerAlertRoute`. **Produces (전문):**

```kotlin
package io.premiumspread.domain.owneralert

/**
 * Discord embed 1개(제목·설명·색). 길이는 UTF-16 code unit 기준.
 * 기본 생성자는 상한을 검증만 하고, [of] 가 안전하게 절단한다 (design §6.3).
 */
data class DiscordMessage(val title: String, val description: String, val color: Int) {
    init {
        require(title.isNotBlank()) { "title must not be blank." }
        require(title.length <= TITLE_MAX) { "title must be at most $TITLE_MAX characters." }
        require(description.length <= DESCRIPTION_MAX) { "description must be at most $DESCRIPTION_MAX characters." }
        require(color in 0..COLOR_MAX) { "color must be in 0..$COLOR_MAX." }
    }

    companion object {
        const val TITLE_MAX = 255          // Discord 256, outbox subject VARCHAR(255)
        const val DESCRIPTION_MAX = 4096
        const val CONTENT_MAX = 2000       // content 는 "@here" 만 쓴다
        const val EMBED_TOTAL_MAX = 6000   // TITLE_MAX + DESCRIPTION_MAX < 이 값
        const val COLOR_MAX = 0xFFFFFF
        const val ELLIPSIS = "…"

        fun of(title: String, description: String, color: Int): DiscordMessage
        /** [max] 이하면 그대로. 넘치면 max-1 까지(surrogate pair 를 쪼개지 않게 필요하면 한 칸 더 앞) + [ELLIPSIS]. */
        fun truncate(text: String, max: Int): String
    }
}

/** Discord 발송 port. 구현은 [DiscordRoute.target] 을 webhook 으로 해석한다. 실패는 [OwnerAlertSendException]. */
fun interface OwnerAlertDiscordSender {
    fun send(route: DiscordRoute, message: DiscordMessage)
}

/**
 * 채널 발송 실패. [permanent] 면 재시도해도 성공할 수 없다(즉시 FAILED).
 * 메시지에는 URL·token·수신 주소를 넣지 않고 `cause` 를 연결하지 않는다.
 */
class OwnerAlertSendException(val permanent: Boolean, message: String) : RuntimeException(message)
```

```kotlin
package io.premiumspread.domain.accountpnl

/**
 * owner 알림 공용 outbox 행 (이름은 호환 때문에 유지 — design §4.4).
 * EMAIL 행: subject/body = 메일 제목/본문. DISCORD 행: subject/body = embed 제목/설명, Discord 필드 필수.
 */
data class AccountPnlMail(
    val eventKey: String,
    val subject: String,
    val body: String,
    val channel: OwnerAlertChannel = OwnerAlertChannel.EMAIL,
    val discordRoute: DiscordRoute? = null,
    val discordColor: Int? = null,
) {
    init {
        val discord = channel == OwnerAlertChannel.DISCORD
        require(discord == (discordRoute != null)) { "discordRoute must be set only for DISCORD rows." }
        require(discord == (discordColor != null)) { "discordColor must be set only for DISCORD rows." }
    }
}

object OwnerAlertRows {
    /**
     * [route] 에 따라 0~2행, 메일 행 먼저. 메일 행은 [mail] 그 자체, Discord 행은 같은 eventKey.
     * [discord] 는 Discord 행을 만들 때만 호출한다. [mail] 은 EMAIL 행이어야 한다.
     */
    fun expand(mail: AccountPnlMail, route: OwnerAlertRoute, discord: () -> DiscordMessage): List<AccountPnlMail>
}
```

```kotlin
// AccountPnlAlertStore — 기존 6개 메서드·companion 무변경, 아래 기본 메서드만 추가
/**
 * 재시도해도 성공할 수 없는 실패. token 이 일치하는 PROCESSING 행을 즉시 FAILED(attempt+1)로. 불일치면 false.
 * 기본 구현은 구분하지 못하는 구현을 위해 시도 실패로 센다.
 */
fun markFailed(id: Long, claimToken: String, now: Instant): Boolean = markAttemptFailed(id, claimToken, now)
```

`AccountPnlMail` 자동 `toString()` 노출: `eventKey`, `subject`, `body`, `channel`, `discordRoute`, `discordColor` — 비밀 없음(수신 주소·URL 은 행에 없다).

**테스트가 재는 것:**
- `DiscordMessageTest`: 제목 255자 그대로 / 256자 → 길이 255 + `…` 끝 / 설명 4096 그대로 / 4097 → 4096 + `…` / 자르는 지점(254번째 code unit)이 이모지
  `😀`(surrogate pair)의 앞 절반이면 결과 길이 ≤ 255, `…` 끝, `Character.isSurrogate` 인 고립 문자 없음 / 기본 생성자: 제목 `" "`·256자·color `-1`·
  `0x1000000` 거부, `0`·`0xFFFFFF` 허용 / `TITLE_MAX + DESCRIPTION_MAX < EMBED_TOTAL_MAX` (AC2-a)
- `OwnerAlertRowsTest` (`AccountPnlMail("account-pnl-profit-1", "s", "b")`, `DiscordMessage("T", "D", 0x2ECC71)`): 3-인자 생성 기본값 /
  DISCORD 인데 route 또는 color 없음·EMAIL 인데 route 있음 거부 / `(true, PNL/HERE)` → 2행, `[0] === mail`, `[1]` = `AccountPnlMail("account-pnl-profit-1", "T", "D", DISCORD, DiscordRoute(PNL, HERE), 0x2ECC71)` /
  `(false, OPS/SOUND)` → Discord 1행 / `(true, null)` → `[mail]` 이고 렌더러 호출 횟수 0 / `(false, null)` → 빈 목록, 호출 0 / DISCORD 행을 `mail` 로 주면 거부 (AC2-b·c)

- [ ] **Step 1:** 타입·시그니처 + **stub** `DiscordMessage.of` = 절단 없이 생성자 호출, `truncate` = 입력 그대로, `OwnerAlertRows.expand` = `emptyList()`. `AccountPnlMail` 의 `init`
  검증과 `markFailed` 기본 메서드는 실제로 넣는다. 테스트 작성.
- [ ] **Step 2:** `./gradlew :domain:test --tests 'io.premiumspread.domain.owneralert.DiscordMessageTest' --tests 'io.premiumspread.domain.accountpnl.OwnerAlertRowsTest' --offline --no-daemon`
  → RED (절단·확장 케이스 실패). 이어서 동결 테스트 컴파일 확인: `./gradlew :domain:test :apps:batch:compileTestKotlin :infrastructure:common:compileIntegrationTestKotlin --offline --no-daemon` → 기존 테스트 초록·컴파일 성공. 증거 로그 AC2.
- [ ] **Step 3:** 구현. Step 2 첫 명령 → PASS. `./gradlew :domain:test --offline --no-daemon` → PASS (Task 1 golden 포함).
- [ ] **Step 4: Commit** — `feat: owner 알림 outbox 행에 채널과 Discord 메시지 추가`

### Task 4: 서식 공유와 Discord 렌더링 (AC3-b)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/AccountPnlFormat.kt`, `.../accountpnl/AccountPnlDiscordContent.kt`;
Modify `.../accountpnl/AccountPnlMailContent.kt`; Test `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlDiscordContentTest.kt`

**Interfaces — Produces (전문):**

```kotlin
/**
 * 메일·Discord 공용 숫자·시각 서식. locale 무관. AccountPnlMailContent 의 private 서식 요소를 그대로 옮긴 것이다
 * (`AccountPnlMailContent.kt` :17 RUNBOOK, :20 TIME_FORMAT, :122 label, :132 time, :135 plain, :137 format, :141 sign,
 *  :143 percent, :146 krw, :152 plainKrw, :154 isZero, :157 cost, :161 krwOrZero, :163 costUsdt, :166 signedUsdt).
 */
internal object AccountPnlFormat {
    const val RUNBOOK = "docs/runbooks/account-pnl-alert.md"
    /** 메일 시각 형식 `yyyy-MM-dd HH:mm:ss` (기존 private val 그대로 — const 아님). */
    private val TIME_FORMAT: DateTimeFormatter
    /** Discord 시각 형식 `yyyy-MM-dd HH:mm z`, Locale.ROOT. */
    private val SHORT_TIME_FORMAT: DateTimeFormatter
    fun sign(value: BigDecimal): String
    fun isZero(value: BigDecimal): Boolean
    fun percent(value: BigDecimal): String
    fun krw(value: BigDecimal): String
    fun plainKrw(value: BigDecimal): String
    fun cost(value: BigDecimal): String
    fun krwOrZero(value: BigDecimal): String
    fun costUsdt(value: BigDecimal): String
    fun signedUsdt(value: BigDecimal): String
    fun plain(value: BigDecimal): String
    fun format(pattern: String, value: BigDecimal): String
    fun label(category: AccountReadFailureCategory): String
    /** 메일용 — `yyyy-MM-dd HH:mm:ss (instant)` (기존 그대로). */
    fun time(instant: Instant, zone: ZoneId): String
    /** Discord 용 — `yyyy-MM-dd HH:mm z`, Locale.ROOT (Asia/Seoul → `KST`). */
    fun shortTime(instant: Instant, zone: ZoneId): String
}

object AccountPnlDiscordContent {
    const val COLOR_PROFIT: Int = 0x2ECC71
    const val COLOR_HOURLY: Int = 0x3498DB
    const val COLOR_OUTAGE: Int = 0xE74C3C
    const val COLOR_RECOVERED: Int = 0x95A5A6

    fun profit(pnl: AccountNetPnl, zone: ZoneId): DiscordMessage
    fun hourly(pnl: AccountNetPnl, zone: ZoneId): DiscordMessage
    fun outage(notice: OutageNotice.Outage, now: Instant, zone: ZoneId): DiscordMessage
    fun recovered(now: Instant, zone: ZoneId): DiscordMessage
}
```

`AccountPnlMailContent` 공개 함수 4개 시그니처·결과 무변경. 문구는 design §6.2 템플릿 **그대로**(exact copy), 모두 `DiscordMessage.of` 로 만든다.
`AccountPnlMailContent` 에 남는 것: `HOURLY_HEADER`·`FOREIGN_PRICE_SCALE`·`eventKey`·`profitLines`(메일 전용).

**요건:** 추출은 함수 본문을 옮기기만 한다(서식 패턴·`RoundingMode.HALF_UP`·`DecimalFormatSymbols(Locale.ROOT)` 그대로). `AccountPnlMailContent` 는 위임.
Discord 설명의 수량 단위 `BTC` 는 상수(모니터가 BTC 단일 계정이다).

**테스트가 재는 것** (fixture 는 `AccountPnlMailContentTest` 의 `pnl()` 과 같은 값을 이 테스트 클래스 안에 private 헬퍼로 다시 만든다 — 기존 테스트 파일 무수정,
zone `Asia/Seoul`. fixture 기본 `calculatedAt = 2026-10-20T00:00:05Z` = **09:00 KST**, 정시 보고 케이스만 `calculatedAt` 을 명시해 바꾼다):
- `수익 Discord 는 기준 golden 과 같다` — 제목·설명 전체 `isEqualTo`(design §6.2 로 손으로 계산한 문자열, 마지막 줄 `바이낸스 포지션: 거래소 API · 2026-10-20 09:00 KST`), 색 `COLOR_PROFIT`
- `정시 보고 제목은 zone 의 정시이고 색은 파랑` — `calculatedAt = 2026-10-20T03:00:03Z` 명시 → `📊 정시 보고 12:00`, 설명은 같은 `calculatedAt` 의 수익 설명과 같음
- `미반영 항목은 한 줄로 사유와 함께 표시` — api 출처 3항목 미반영 → `⚠ 미반영: 진입 수수료(빗썸, 이력 없음), 진입 수수료(바이낸스, 이력 없음), 펀딩비(이력 없음)`
- `수동 출처는 수동 미입력 사유와 출처 줄을 쓴다` — manual 변형(`copy(positionSource = MANUAL_ENV)`, `calculatedAt` 은 fixture 기본) + 바이낸스·펀딩 미반영 → `진입 수수료(바이낸스, 수동 미입력)`·`펀딩비(수동 미입력)`,
  마지막 줄 `바이낸스 포지션: 수동 입력(env) · 2026-10-20 09:00 KST`
- `미반영이 없으면 경고 줄이 없다`, `평단 수정이면 표시 줄이 있다`
- `빗썸 인증 중단 Discord 는 golden 과 같다` (마지막 성공 `없음`, IP 안내 줄), `환율 지연 중단은 IP 안내 줄이 없다`, `복구 Discord 는 golden 과 같다`
- `네 종류 색 상수` 값

- [ ] **Step 1: 추출 (리팩터링).** `AccountPnlFormat` 로 옮기고 `AccountPnlMailContent` 가 위임. `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` → PASS (golden 6건 포함). 실패하면 추출이 서식을 바꾼 것이다 — golden 을 고치지 말고 추출을 되돌린다.
- [ ] **Step 2:** `AccountPnlDiscordContent` **stub**(4함수 모두 `DiscordMessage.of("x", "", 0)`) + 테스트 작성.
- [ ] **Step 3:** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlDiscordContentTest' --offline --no-daemon` → RED. 증거 로그 AC3-b.
- [ ] **Step 4:** 구현. dod AC3 명령 → PASS. `./gradlew :domain:test --offline --no-daemon` → PASS.
- [ ] **Step 5: Commit** — `feat: 실계정 알림 Discord embed 렌더링과 공용 서식`

### Task 5: V19 migration (AC4)

**Files:** Create `infrastructure/common/src/main/resources/db/migration/V19__add_owner_alert_channel.sql`; Test `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V19OwnerAlertChannelMigrationIntegrationTest.kt`

**Interfaces — Produces (DDL 전문):**

```sql
-- owner 알림 채널(메일·Discord) 확장. 기존 행은 모두 메일이므로 channel 기본값 'EMAIL' 로 채워진다.
-- 한 알림은 켜진 채널 수만큼 행이 된다 — 같은 event_key 가 채널마다 한 행씩 있도록 UNIQUE 를 (event_key, channel) 로 넓힌다.
-- Discord webhook URL 은 저장하지 않는다(adapter 설정이 소유). discord_* 는 DISCORD 행에만 있다.
ALTER TABLE account_pnl_mail
    ADD COLUMN channel        VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN discord_target VARCHAR(16) NULL,
    ADD COLUMN discord_push   VARCHAR(16) NULL,
    ADD COLUMN discord_color  INT         NULL,
    DROP INDEX uk_account_pnl_mail_event_key,
    ADD CONSTRAINT uk_account_pnl_mail_event_key_channel UNIQUE (event_key, channel),
    ADD CONSTRAINT ck_account_pnl_mail_channel CHECK (
        (channel = 'EMAIL' AND discord_target IS NULL AND discord_push IS NULL AND discord_color IS NULL)
        OR (channel = 'DISCORD' AND discord_target IS NOT NULL AND discord_push IS NOT NULL AND discord_color IS NOT NULL)
    );
```

V18 은 수정하지 않는다.

테스트 상수 (전문 — 구 binary SQL 은 base `JdbcAccountPnlAlertStore` 에서, 롤백 SQL 은 design §4.5 에서 그대로 옮긴다. runbook 과 문자열이 같아야 한다):

```kotlin
/** base(1bce3dd) JdbcAccountPnlAlertStore.claim 의 SELECT — channel 조건 없음. LIMIT 은 바인딩 대신 10 고정. */
const val OLD_CLAIM_SELECT = """
SELECT id, event_key, subject, body, attempt_count
FROM account_pnl_mail
WHERE status = 'PENDING'
ORDER BY id
LIMIT 10
FOR UPDATE SKIP LOCKED
"""

/** design §4.5 / runbook `### 배포와 롤백` 의 롤백 SQL. */
const val ROLLBACK_DISCORD_ROWS = """
UPDATE account_pnl_mail
SET status = 'FAILED',
    claim_token = NULL,
    locked_by = NULL,
    locked_at = NULL
WHERE channel = 'DISCORD'
  AND status IN ('PENDING', 'PROCESSING')
"""
```

**테스트가 재는 것** (`V17TradePreparationIndexMigrationIntegrationTest` 패턴 — 자체 `MySQLContainer("mysql:8.0")`, `Flyway.clean()` 후 `migrate()`, `target(18)`):
- `빈 DB는 V19까지 migration 되고 채널 컬럼과 복합 UNIQUE 가 붙는다` — 적용 버전 `1..19` 포함, `information_schema.COLUMNS` 의 `account_pnl_mail` 컬럼 목록(이름:타입:nullable, `ORDINAL_POSITION` 순)이 기존 11개 뒤에 `channel:varchar:NO`, `discord_target:varchar:YES`, `discord_push:varchar:YES`, `discord_color:int:YES`,
  `channel` 의 `COLUMN_DEFAULT == 'EMAIL'`, `getIndexInfo` 의 `uk_account_pnl_mail_event_key_channel` 컬럼 `containsExactly("event_key", "channel")`, `uk_account_pnl_mail_event_key` 없음 (AC4-a)
- `V18 의 기존 행은 EMAIL 행이 된다` — `target(18)` migrate 후 `PENDING`(`k-pending`)·`SENT`(`k-sent`, `sent_at` 있음) 2행 INSERT → latest → 두 행 `channel = 'EMAIL'`, Discord 3컬럼 null, `subject`·`body`·`status`·`attempt_count`·`sent_at` 그대로 (AC4-b)
- `구 binary 의 INSERT 는 V19 에서도 중복 제거된다` — base `JdbcAccountPnlAlertStore.insertMail` 의 SQL 문자열을 테스트 상수로 그대로 두고 같은 eventKey 로 2회 → `COUNT(*) == 1`, `channel == 'EMAIL'` (AC4-c)
- `같은 eventKey 의 Discord 행은 들어가고 같은 채널 중복과 CHECK 위반은 거부된다` — `DISCORD`/`PNL`/`HERE`/`3066993` 행 성공, 같은 `(event_key, DISCORD)` 재삽입 → `SQLIntegrityConstraintViolationException`, `EMAIL` + `discord_target='PNL'` → `SQLException`(CHECK, SQLState `HY000` 또는 errorCode 3819), `DISCORD` + `discord_push` null → 같음, `channel='SMS'` → 같음 (AC4-d)
- `롤백 SQL 뒤 구 binary claim 은 메일 행만 집는다` — latest 에서 `k-mail`(EMAIL `PENDING`), `k-d1`(DISCORD `PENDING`), `k-d2`(DISCORD `PROCESSING`, `claim_token`·`locked_by`·`locked_at` 있음, `attempt_count = 1`), `k-d3`(DISCORD `SENT`) INSERT → `ROLLBACK_DISCORD_ROWS` 실행, 영향 행 수 2 → `k-d1`·`k-d2` 가 `FAILED`·token/locked_by/locked_at null·`attempt_count` 각각 0·1 그대로, `k-mail`·`k-d3` 는 모든 컬럼 그대로 → 트랜잭션 안에서 `OLD_CLAIM_SELECT` 결과 `event_key` 목록 `containsExactly("k-mail")` (AC4-f)

- [ ] **Step 1:** 테스트 작성 (V19 파일 없음).
- [ ] **Step 2:** `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.migration.V19OwnerAlertChannelMigrationIntegrationTest' --offline --no-daemon` → RED (버전 19 없음·컬럼 없음 assertion 실패. AC4-f 는 V19 없이 `channel` 컬럼이 없어 INSERT 단계에서 실패한다 — 이 메서드는 스키마 선행 조건 실패로 기록하고 RED 근거는 (a)(b) assertion 으로 삼는다). 증거 로그 AC4.
- [ ] **Step 3:** V19 작성. 같은 명령 → PASS. 실패가 "check constraint refers to non-existing column" 류면 `[ASSUMPTION]` A2 가 깨진 것이다 — 멈추고 보고(두 문장 분리는 원자성 trade-off 라 Gate 판단).
- [ ] **Step 4:** `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` → PASS.
- [ ] **Step 5: Commit** — `feat: owner 알림 outbox 채널 컬럼 V19 migration`

### Task 6: JDBC store 채널·`markFailed`, batch fixture (AC5)

**Files:**
- Modify `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStore.kt`
- Modify `apps/batch/src/integrationTest/resources/batch-schema.sql` (`account_pnl_mail` 을 V18+V19 최종 형태로)
- Modify `apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt` (dod R7 허용 예외만 + 신규 메서드 1개)
- Test `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt` (추가만)

**Interfaces — Consumes:** Task 3 `AccountPnlMail` 채널 프로퍼티·`markFailed`. **Produces:** `JdbcAccountPnlAlertStore` 공개 시그니처 무변경 + `override fun markFailed(id: Long, claimToken: String, now: Instant): Boolean`.

`batch-schema.sql` 의 `account_pnl_mail` (전문):

```sql
-- V18 + V19 (owner 알림 채널)과 같은 스키마. Batch 는 Flyway 를 쓰지 않으므로 fixture 로 만든다.
CREATE TABLE IF NOT EXISTS account_pnl_mail (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_key      VARCHAR(128) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    body           TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count  INT          NOT NULL DEFAULT 0,
    locked_by      VARCHAR(64)  NULL,
    claim_token    CHAR(36)     NULL,
    locked_at      DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    sent_at        DATETIME(6)  NULL,
    channel        VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    discord_target VARCHAR(16)  NULL,
    discord_push   VARCHAR(16)  NULL,
    discord_color  INT          NULL,
    CONSTRAINT uk_account_pnl_mail_event_key_channel UNIQUE (event_key, channel),
    CONSTRAINT ck_account_pnl_mail_channel CHECK (
        (channel = 'EMAIL' AND discord_target IS NULL AND discord_push IS NULL AND discord_color IS NULL)
        OR (channel = 'DISCORD' AND discord_target IS NOT NULL AND discord_push IS NOT NULL AND discord_color IS NOT NULL)
    ),
    INDEX idx_account_pnl_mail_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**요건 (구현부):**
- `insertMail`: `INSERT INTO account_pnl_mail (event_key, channel, subject, body, discord_target, discord_push, discord_color, status, attempt_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?) ON DUPLICATE KEY UPDATE id = id` — channel/target/push 는 enum 이름.
- `claim` SELECT 에 `channel, discord_target, discord_push, discord_color` 추가, `toPendingMail` 이 enum 으로 되살린다(`EMAIL` 이면 Discord 필드 null).
- `markFailed`: `UPDATE account_pnl_mail SET status = 'FAILED', attempt_count = attempt_count + 1, claim_token = NULL, locked_by = NULL, locked_at = NULL WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?` → `== 1`. `now` 는 기록하지 않는다(기존 `markAttemptFailed` 와 같은 이유, KDoc).
- `validate` 기존 규칙 유지(DISCORD 행 subject ≤ 255 는 같은 규칙이 지킨다). 클래스 KDoc 에 "owner 알림 공용 outbox(메일·Discord). 이 binary 이전 버전의 claim 은 channel 을 모른다 — 구·신 batch 를 겹쳐 돌리지 않는다(runbook 배포와 롤백)".
- `AccountPnlFixtureSchemaIntegrationTest`: `account_pnl_mail fixture 는 V18 컬럼과 같다` 의 기대 목록 끝에 `"channel:varchar:NO"`, `"discord_target:varchar:YES"`, `"discord_push:varchar:YES"`, `"discord_color:int:YES"` 추가, KDoc 의 V18 경로 언급을 V18·V19 로. 신규 메서드 `account_pnl_mail fixture 의 UNIQUE 는 event_key 와 channel 이다`(`information_schema.STATISTICS`, `INDEX_NAME = 'uk_account_pnl_mail_event_key_channel'`, `SEQ_IN_INDEX` 순 `event_key`, `channel`).

**테스트가 재는 것** (AC5, 기존 `NOW`·`STATE`·`mail()` 헬퍼 재사용, 새 헬퍼는 추가):
- `같은 eventKey 의 메일과 Discord 행은 각각 한 행이고 다시 저장해도 늘지 않는다` — `[mail("k"), discordRow("k")]` save → 2행, version 증가 후 같은 목록 다시 save → 2행
- `Discord 행은 claim 에서 채널과 Discord 메타를 그대로 돌려준다` — `AccountPnlMail("k-d", "📊 정시 보고 12:00", "설명", DISCORD, DiscordRoute(OPS, SILENT), 0x3498DB)` 저장 → claim `.mail` `isEqualTo` 원본
- `channel 없이 넣은 행은 메일 행으로 읽힌다` — raw INSERT(Task 5 의 구 binary SQL) → claim `.mail == AccountPnlMail(k, s, b)`
- `markFailed 는 claim 된 행을 바로 FAILED 로 바꾸고 시도 횟수를 1 늘린다` — 컬럼 4개 확인
- `markFailed 는 token 이 다르거나 PENDING 이면 false 이고 행을 바꾸지 않는다`

- [ ] **Step 1:** 테스트 추가 + fixture SQL·fixture 테스트 수정(위 요건). store 는 아직 무변경.
- [ ] **Step 2:** `./gradlew :infrastructure:common:integrationTest --tests 'io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPnlAlertStoreIntegrationTest' --offline --no-daemon` → RED (Discord 행이 `EMAIL` 로 읽힘·중복 키·`markFailed` 가 기본 구현이라 `PENDING`). 증거 로그 AC5.
- [ ] **Step 3:** store 구현. 같은 명령 → PASS (기존 메서드 포함).
- [ ] **Step 4:** `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.config.AccountPnlFixtureSchemaIntegrationTest' --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --offline --no-daemon` → PASS (R7·직전 단위 AC6).
- [ ] **Step 5: Commit** — `feat: 실계정 알림 outbox store 채널 컬럼과 즉시 실패 전이`

### Task 7: Discord webhook adapter (AC6)

**Files:** Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapter.kt`; Test `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/notification/DiscordWebhookSenderAdapterTest.kt`

**Interfaces — Consumes:** Task 2·3 `DiscordRoute`·`DiscordTarget`·`DiscordPush`·`DiscordMessage`·`OwnerAlertDiscordSender`·`OwnerAlertSendException`. **Produces (전문):**

```kotlin
/**
 * 대상별 webhook URI. 설정 안 된 대상은 [PNL, MARKET, OPS] 순서의 첫 설정 대상으로 간다 (design §5.3, 판정 A5).
 * data class 가 아니다 — 자동 toString 이 URI 를 찍지 않게 한다.
 */
class DiscordWebhooks(pnl: URI?, market: URI?, ops: URI?) {
    init { /* 셋 다 null 이면 IllegalArgumentException("at least one Discord webhook is required.") */ }
    fun resolve(target: DiscordTarget): URI
    /** "set" 또는 "fallback(PNL)" 형식 — 기동 INFO 와 toString 이 쓴다. */
    fun describe(target: DiscordTarget): String
    override fun toString(): String   // "DiscordWebhooks(pnl=${describe(PNL)}, market=${describe(MARKET)}, ops=${describe(OPS)})"
}

class DiscordWebhookSenderAdapter(
    private val webhooks: DiscordWebhooks,
    private val timeout: Duration = Duration.ofSeconds(3),
    private val maxRetryAfter: Duration = Duration.ofSeconds(2),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : OwnerAlertDiscordSender {
    override fun send(route: DiscordRoute, message: DiscordMessage)

    internal companion object {
        /**
         * 원인 체인(자기 자신 포함, 같은 객체 순환 차단, 깊이 16)에 InterruptedException·CancellationException 이 있으면 true.
         * AccountPnlMonitorJob.isCancellation 과 같은 규칙 (app 함수는 private 이고 infra 는 app 을 참조하지 않으므로 따로 둔다).
         */
        fun isCancellation(error: Throwable): Boolean
    }
}
```

생성자에 `WebClient` 를 받지 않는다(내부 `WebClient.builder().build()`) — timeout·URI 해석·예외 정제를 adapter 가 혼자 소유하고, 배선(Task 10)과 통합 테스트(Task 11)가
WebClient 를 조립하지 않아도 되게 하기 위해서다. (`apps:batch` 통합 테스트 classpath 에는 WebFlux 가 이미 있다 — `BatchTestConfig.kt:18,71` 이 `WebClient` 를
import·사용한다. classpath 는 이 결정의 이유가 아니다.)

payload (JSON 전문, `push` 별):

```json
{"embeds":[{"title":"<title>","description":"<description>","color":<color>}],"allowed_mentions":{"parse":[]},"flags":4096}
{"embeds":[{"title":"<title>","description":"<description>","color":<color>}],"allowed_mentions":{"parse":[]}}
{"content":"@here","embeds":[{"title":"<title>","description":"<description>","color":<color>}],"allowed_mentions":{"parse":["everyone"]}}
```
(순서대로 `SILENT`, `SOUND`, `HERE`.)

예외 메시지 (값 전부, `{T}` = `route.target.name`, `cause` 없음, 위에서부터 먼저 맞는 행):

| 상황 | `permanent` | 메시지 |
|---|---|---|
| 2xx 외 4xx (429 제외) | true | `Discord webhook {T} responded {status}` |
| 5xx·1xx·3xx | false | `Discord webhook {T} responded {status}` |
| 429 (대기 불가 또는 재시도도 429) | false | `Discord webhook {T} rate limited` |
| 취소 — `sleeper` 의 `InterruptedException`, 또는 요청 예외가 `isCancellation` | false | `Discord webhook {T} interrupted` — **던지기 전에 `Thread.currentThread().interrupt()`** |
| 그 밖 요청 예외(연결·timeout·DNS) | false | `Discord webhook {T} request failed: {예외 단순 클래스 이름}` |

로그 (키 전문): WARN `Discord webhook rate limited; retrying once: target={}, retryAfterMs={}` — URL·token·본문 없음.

**요건 (구현부):** 응답 분류는 design §5.3 표. 429 대기 시간은 `Retry-After` 헤더(초, `BigDecimal`) → 없으면 JSON 본문 `retry_after` → 없음.
대기 ≤ `maxRetryAfter` 이고 이번 `send` 의 첫 시도일 때만 `sleeper` 후 1회 재시도. `.timeout(timeout)` 적용. 예외를 잡을 때 원본의 메시지·cause 를 쓰지 않는다
(취소 경로도 원본을 다시 던지지 않는다 — 원본 메시지가 URI 를 담는다). JSON 은 Jackson 으로 만든다(문자열 연결 금지 — 제목·설명 escape).

**테스트가 재는 것** (MockWebServer 3개 또는 path 3개, token 구별 값 `TOKENpnlQ7`·`TOKENmktQ7`·`TOKENopsQ7`, `sleeper` 는 호출 기록 fake):
- `무음 소리 here 별 payload` — 3케이스 요청 본문 JSON 트리 `isEqualTo`, method `POST`, `Content-Type` `application/json` 시작 (AC6-a)
- `대상별 webhook 으로 보낸다`, `하나만 설정하면 모든 대상이 그리로 간다`, `PNL OPS 만 설정하면 MARKET 은 PNL 로`, `MARKET OPS 만 설정하면 PNL 은 MARKET 으로` — 요청 path (AC6-b)
- `204 와 200 은 성공` (AC6-c)
- `429 Retry-After 0.5 면 500ms 기다려 한 번 재시도한다` — `sleeper` 호출 `[PT0.5S]`, `requestCount == 2`; `429 본문 retry_after 1.2 면 1200ms`; `429 Retry-After 30 은 기다리지 않고 재시도 가능 실패` (`requestCount == 1`, `sleeper` 호출 없음); `429 두 번이면 재시도 가능 실패` (`requestCount == 2`) (AC6-d)
- `500 503 은 재시도 가능`, `400 401 403 404 는 재시도 불가`, `연결 거부는 재시도 가능`(server.shutdown 후), `timeout 은 재시도 가능`(`timeout = 200ms`, `setBodyDelay(1, SECONDS)`) (AC6-e)
- `대기 중 interrupt 면 플래그를 복구하고 재시도 가능 실패` — `sleeper` 가 `InterruptedException` 던짐, 메시지 `Discord webhook PNL interrupted`, 단언 후 `Thread.interrupted()` 로 플래그 정리 (AC6-f)
- `요청 중 interrupt 면 플래그를 복구하고 원본 예외를 노출하지 않는다` — 응답 지연 1초, `Thread.currentThread().interrupt()` 후 `send` → `permanent=false`·메시지 `Discord webhook PNL interrupted`·`cause == null`·플래그 true·메시지/`stackTraceToString()` 에 URL·token 없음, `finally` 에서 `Thread.interrupted()` 로 정리 (AC6-i)
- `취소 판정은 원인 체인을 본다` — `isCancellation(RuntimeException(InterruptedException()))` true, `isCancellation(IllegalStateException(RuntimeException(CancellationException())))` true, `isCancellation(IOException())` false (AC6-i)
- `어떤 실패에도 URL 과 token 이 없다` — 위 실패 전부에서 `message`, 원인 체인 모든 `message`, `stackTraceToString()`, `OutputCaptureExtension` 출력에 `TOKEN`·`/api/webhooks/`·`server.url("/")` 문자열 없음, `cause == null` (AC6-g)
- `DiscordWebhooks toString 에 URL 이 없다` — `DiscordWebhooks(pnl, null, null).toString() == "DiscordWebhooks(pnl=set, market=fallback(PNL), ops=fallback(PNL))"`, 셋 다 null 이면 거부 (AC6-h)

- [ ] **Step 1:** 시그니처 + `send` 가 `UnsupportedOperationException` 을 던지는 stub, `isCancellation` stub `= false`, `DiscordWebhooks` 는 구현(단순 해석) + 테스트 작성.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapterTest' --offline --no-daemon` → RED. 증거 로그 AC6.
- [ ] **Step 3:** 구현. 같은 명령 → PASS. `./gradlew :infrastructure:batch:test --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: Discord webhook 발송 adapter`

### Task 8: `owner-alert` 설정 (AC7)

**Files:** Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertProperties.kt`; Test `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertPropertiesTest.kt`

**Interfaces — Consumes:** Task 2 `OwnerAlertKind`·`OwnerAlertRouteOverride`·`DiscordTarget`·`DiscordPush`, Task 7 `DiscordWebhooks`. **Produces (전문):**

```kotlin
/**
 * owner 알림 채널 설정. 모든 값은 `String?` 원문으로 받아 여기서 해석한다 — Spring 변환기의 실패 메시지·원인 체인에
 * 입력 값(특히 webhook token)이 실리지 않게. 해석 실패 예외는 property key 와 규칙만 담고 cause 를 연결하지 않는다.
 * webhook 형식은 discord.enabled 와 무관하게 검증한다 (design §7.3 검증 시점 결정).
 * 수신 주소는 여기 없다 — OwnerAlertChannelRequirements.recipient 가 읽는다 (design §7.2 I-3).
 */
@ConfigurationProperties(prefix = "owner-alert")
data class OwnerAlertProperties(
    val email: Email = Email(),
    val discord: Discord = Discord(),
    val routes: Map<String, RouteOverride> = emptyMap(),
) {
    private val parsedOverrides: Map<OwnerAlertKind, OwnerAlertRouteOverride>   // init 시점 해석

    fun resolvedOverrides(): Map<OwnerAlertKind, OwnerAlertRouteOverride>

    data class Email(val enabled: String? = null) {
        /** 비었으면 true, 아니면 equalsIgnoreCase("true") — 조건(OwnerAlertChannelRequirements)과 같은 규칙. */
        fun resolvedEnabled(): Boolean
    }

    data class Discord(val enabled: String? = null, val webhook: Webhook = Webhook()) {
        /** equalsIgnoreCase("true"). */
        fun resolvedEnabled(): Boolean
    }

    data class Webhook(val pnl: String? = null, val market: String? = null, val ops: String? = null) {
        private val parsed: Map<DiscordTarget, URI>   // init 시점 해석, 빈 값 제외
        fun isAnyConfigured(): Boolean
        /** 하나도 없으면 IllegalStateException("owner-alert.discord.webhook requires at least one URL") */
        fun toDiscordWebhooks(): DiscordWebhooks
        override fun toString(): String =
            "Webhook(pnl=${presence(pnl)}, market=${presence(market)}, ops=${presence(ops)})"
    }

    data class RouteOverride(val email: String? = null, val discord: String? = null, val push: String? = null)

    companion object {
        private fun presence(value: String?): String = if (value.isNullOrBlank()) "<empty>" else "set"
    }
}
```

자동 생성 `toString()` 노출 (전문): `OwnerAlertProperties(email=Email(enabled=…), discord=Discord(enabled=…, webhook=<Webhook.toString>), routes={key=RouteOverride(email=…, discord=…, push=…)})`.
`Webhook` 만 override 하며 그 밖의 프로퍼티(플래그 원문·route 값)는 비밀이 아니다. `parsed`·`parsedOverrides` 는 생성자 프로퍼티가 아니라 자동 `toString` 에 없다.

해석 실패 메시지 (값 전부): design §7.3 표의 5개 그대로 — `owner-alert.discord.webhook.{pnl|market|ops} must be an https URL`,
`owner-alert.routes.{key} is not a known alert kind (known: profit, hourly-report, monitoring-outage, monitoring-recovered)`(목록은 `OwnerAlertKind.entries` 에서 생성),
`owner-alert.routes.{key}.email must be true or false`, `owner-alert.routes.{key}.discord must be pnl, market, ops or none`, `owner-alert.routes.{key}.push must be silent, sound or here`.
모두 `IllegalArgumentException`, `cause` 없음. route 값은 trim·대소문자 무관, 빈 값 = 지정 안 함. `discord=none` → `discordDisabled = true`. 세 필드가 모두 비면 그 key 는 결과 map 에서 뺀다.

**테스트가 재는 것** (`ApplicationContextRunner` + `@EnableConfigurationProperties(OwnerAlertProperties::class)` 만 담은 테스트 configuration, 실패 케이스는 `startupFailure` 원인 체인):
- `webhook 빈 값과 공백은 미설정이고 https URL 은 설정이다`, `대문자 scheme 허용` (AC7-a)
- `잘못된 webhook 은 기동 실패이고 값이 어디에도 없다` — `notaurlQ7`·`http://discord.example.test/api/webhooks/1/TOKENhttpQ7`·`https:///nohostQ7` 각각: `hasFailed`, 체인 메시지 하나에 `owner-alert.discord.webhook.pnl`, 체인 전체·`stackTraceToString()` 에 입력 값 없음 (AC7-b)
- `route override 가 종류별로 해석된다` — 4개 입력 → `resolvedOverrides()` 가 `HOURLY_REPORT→(email=false)`, `PROFIT→(discordTarget=OPS, push=SOUND)`, `MONITORING_OUTAGE→(discordDisabled=true)` (AC7-c, dash key 보존 = `[ASSUMPTION]` A3)
- `잘못된 route 값은 key 만 담고 값은 없다` — `loudQ9`·`dmQ9`·`yesQ9`; `알 수 없는 종류 key 는 known 목록과 함께 실패` (AC7-d)
- `toString 에 webhook 이 없다` — 세 webhook 설정 → `TOKEN`·`/api/webhooks/` 없음, `pnl=set`; `OwnerAlertProperties().toString()` 에 `<empty>` (AC7-e)
- `toDiscordWebhooks 는 설정된 것만으로 fallback 을 만든다` — market 만 → `describe(PNL) == "fallback(MARKET)"`

- [ ] **Step 1:** 시그니처 + stub(`parsedOverrides = emptyMap()`, webhook 검증 없음 — `parsed` 는 빈 값 제외만, `Webhook.toString` override 없음) + 테스트 작성.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertPropertiesTest' --offline --no-daemon` → RED. 증거 로그 AC7.
- [ ] **Step 3:** 구현. 같은 명령 → PASS.
- [ ] **Step 4: Commit** — `feat: owner 알림 채널 설정 바인딩과 webhook 마스킹`

### Task 9: 디스패처 추출과 Job 라우팅 (AC8)

**Files:**
- Create `apps/batch/src/main/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcher.kt`
- Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`
- Test `apps/batch/src/test/kotlin/io/premiumspread/application/notification/owneralert/OwnerAlertDispatcherTest.kt`, `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobOwnerAlertTest.kt`

**Interfaces — Consumes:** Task 2·3·4. **Produces (전문):**

```kotlin
package io.premiumspread.application.notification.owneralert

/**
 * owner 알림 outbox 전달 1회분 (design §5.2 전이표). claim 은 실행 가능한 concurrency(1)만큼, 회차 상한 [MAX_DELIVERIES_PER_RUN].
 * 재시도 가능 실패는 회차 끝에 되돌린다(같은 회차 재claim 방지). 매 claim 전 interrupt 를 확인한다.
 * #90 의 독립 전달 Job 이 그대로 재사용한다.
 */
class OwnerAlertDispatcher(
    private val store: AccountPnlAlertStore,
    private val mailSender: AccountPnlMailSender,
    private val discordSender: OwnerAlertDiscordSender?,
    private val clock: Clock,
    private val owner: String,
) {
    fun dispatch(): JobResult

    companion object {
        const val CLAIM_CONCURRENCY = 1
        const val MAX_DELIVERIES_PER_RUN = 10
        /** 메일 채널이 없는 기동에서 Job 에 넘긴다. 남은 메일 행은 1회 시도 후 FAILED. */
        val UNAVAILABLE_MAIL_SENDER: AccountPnlMailSender =
            AccountPnlMailSender { throw OwnerAlertSendException(true, "owner alert channel unavailable: EMAIL") }
    }
}
```

```kotlin
// AccountPnlMonitorJob — 기존 11개 인자 무변경, 뒤에 2개 추가
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
) {
    // init: require(!routing.channels.discord || discordSender != null) {
    //     "owner-alert discord channel is enabled but no Discord sender was provided" }
    fun run(): JobResult
}
```

`JobResult` 분기: design §8 표 그대로(추가 행 = `markFailed` 예외 → `Failure`, 재시도 불가 실패 → 행 `FAILED`·`Success`. 관측 중 취소 재전파는 기존 그대로).

로그 (키 전문):
- WARN `Owner alert send failed: id={}, channel={}, attempt={}, permanent={}, error={}, cause={}, reason={}` — `error`·`cause` 는 단순 클래스 이름, `reason` 은
  `OwnerAlertSendException.message` 또는 `-`. **담지 않는 값:** 수신 주소, webhook URL·token, 그 밖 예외의 메시지, 본문.
- WARN `Owner alert {} transition was fenced (claim no longer owned)` (id), ERROR `Owner alert store failed during {}` (operation 이름 — `recoverStale/load`·`save` 는 Job 이 기존 문구로 남긴다).
- WARN `Owner alert delivery was cancelled; remaining rows stay pending` (기존 `Account PnL mail delivery was cancelled; …` 와 같은 자리).
- 그 밖 Job 로그 무변경.

**요건 (구현부):**
- `deliver()`·`deliverNext`·`markSent`·`send`·`releaseFailed`·`storeFailure`(전달 부분)를 디스패처로 **동작 그대로** 옮긴다: 회차 시작 interrupt 확인 → `claim(owner, 1, clock.instant())` → 빈 결과면 `Success` → 발송 → 성공 `markSent` / 영구 실패 `markFailed` 즉시 / 그 밖 실패 목록에 모음 → 최대 10회 → 모은 건 `markAttemptFailed`(저장소 예외가 나도 나머지 계속, 첫 실패만 결과). Job 은 `private val dispatcher = OwnerAlertDispatcher(store, mailSender, discordSender, clock, owner)` 이고 `deliver()` 는 `dispatcher.dispatch()`.
- 채널 분기: `EMAIL` → `mailSender.send(row)`, `DISCORD` → `discordSender?.send(row.discordRoute!!, DiscordMessage(row.subject, row.body, row.discordColor!!))`, sender 가 null 이면 `OwnerAlertSendException(true, "owner alert channel unavailable: DISCORD")` 와 같은 처리.
- Job `buildMails` → 알림마다 `OwnerAlertRows.expand(mail, routing.resolve(kind)) { discord 렌더러 }`: 수익 `PROFIT`/`profit`, 정시 보고 `HOURLY_REPORT`/`hourly`, 중단 `MONITORING_OUTAGE`/`outage`, 복구 `MONITORING_RECOVERED`/`recovered`. 순서는 기존(수익 → 정시 보고 → 중단/복구) 그대로, 각 알림 안에서는 메일 → Discord.
- `observe()` 의 취소 재전파(`AccountPnlMonitorJob.kt:137-141`)·`isCancellation` 은 손대지 않는다.

**테스트가 재는 것:**
- `OwnerAlertDispatcherTest` (fake store = (eventKey, channel) 중복 제거·token fencing·`markFailed` 기록, fake 메일·Discord sender, `MutableClock(2026-10-20T03:00:00Z)`):
  메일 성공 → `SENT` / Discord 성공 → `SENT`, Discord fake 가 받은 route·message 가 행과 같음 / Discord sender null 인 DISCORD 행 → 같은 회차 `markFailed` 1회, Discord 호출 0 /
  `permanent=true` → `markFailed` 즉시(회차 끝 `markAttemptFailed` 없음) / `permanent=false`·`IllegalStateException` → 회차 끝 `markAttemptFailed` / `markFailed` 예외 → `JobResult.Failure` /
  `markFailed` fenced → 다음 행 계속 / `UNAVAILABLE_MAIL_SENDER` 로 메일 행 → `FAILED` / 실패 WARN 출력에 `channel=DISCORD`·`permanent=true` 가 있고 일반 예외 메시지 `smtp to owner@example.test`·`TOKENq` 가 없다 / 회차 상한 10 /
  `발송 중 취소되면 그 행은 시도 실패로 되돌리고 남은 행은 claim 하지 않는다` — PENDING 2행, Discord fake 가 `Thread.currentThread().interrupt()` 후 `OwnerAlertSendException(false, "Discord webhook PNL interrupted")` → claim 1회, 첫 행 회차 끝 `markAttemptFailed`, 둘째 행 `PENDING`·attempt 0, 결과 `Success`, 단언 뒤 `Thread.interrupted()` 정리 (AC8-a)
- `AccountPnlMonitorJobOwnerAlertTest` (동결 테스트의 시나리오 수치를 이 클래스 안에 다시 만든 fake — 1.96% 기본, `Asia/Seoul`):
  메일+Discord 수익 2행 순서·내용·`SENT` / 정시 보고 켜짐 → DISCORD(PNL/SILENT) 1행 / 중단(5분 초과 실패) → EMAIL+DISCORD(OPS/SOUND) / 복구 → DISCORD(OPS/SOUND) 1행 /
  `EMAIL_ONLY` 4종 각 EMAIL 1행·eventKey 기존과 같음 / Discord-only → EMAIL 0 / Discord `permanent=false` → 메일 `SENT`·Discord `PENDING` attempt 1 → 새 Job 인스턴스 다음 회차(`clock +1m`) Discord `SENT`, 행 수 그대로 /
  `permanent=true` → `FAILED` attempt 1, 다음 회차 Discord 호출 0 / Discord 켜짐 routing + sender null → 생성자 `IllegalArgumentException` (AC8-b·c·d)

- [ ] **Step 1: 추출 (리팩터링).** 디스패처를 만들고 Job 이 위임하되 채널 분기 없이 기존 동작만(`DISCORD` 행 분기 미구현 — `mailSender` 로 보냄). 동결 테스트:
  `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` → PASS (`AccountPnlMonitorJobTest`·`HourlyReportTest`·`ObservationLogTest` 무수정). 실패하면 추출이 동작을 바꾼 것이다 — 테스트를 고치지 말고 추출을 고친다.
- [ ] **Step 2:** Job 생성자에 `routing`·`discordSender` 추가(아직 `buildMails` 는 라우팅 무시, `init` 검증 없음) + 두 테스트 작성.
- [ ] **Step 3:** `./gradlew :apps:batch:test --tests 'io.premiumspread.application.notification.owneralert.OwnerAlertDispatcherTest' --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobOwnerAlertTest' --offline --no-daemon` → RED. 증거 로그 AC8.
- [ ] **Step 4:** 채널 분기·영구 실패·라우팅·`init` 구현. 같은 명령 → PASS. Step 1 명령 → PASS.
- [ ] **Step 5: Commit** — `feat: owner 알림 전달을 디스패처로 추출하고 채널별로 라우팅`

### Task 10: 채널 판정·수신 주소·배선·yml (AC9, AC10)

**Files:**
- Create `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelRequirements.kt`
- Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorAdapterConfiguration.kt`
- Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/accountpnl/AccountPnlMonitorProperties.kt` (`recipient` KDoc 한 줄만)
- Modify `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt`
- Modify `apps/batch/src/main/resources/application.yml`
- Test Create `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/owneralert/OwnerAlertChannelRequirementsTest.kt`, `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt` (메서드 추가만), Create `apps/batch/src/test/kotlin/io/premiumspread/config/OwnerAlertYamlContractTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// infrastructure/batch/.../owneralert/OwnerAlertChannelRequirements.kt
/**
 * owner 알림 채널 판정과 메일 채널 필수 키·수신 주소의 단일 소유자 (design §7.2, 판정 I-3).
 * app `AccountPnlMonitorEnabledCondition` 이 같은 규칙을 따로 갖는다. 판정은 값이 비었는지만 읽고, [recipient] 만 값을 돌려준다(로그 금지).
 */
internal object OwnerAlertChannelRequirements {
    const val EMAIL_ENABLED_KEY = "owner-alert.email.enabled"
    const val SMTP_ENABLED_KEY = "notification.email.enabled"
    const val DISCORD_ENABLED_KEY = "owner-alert.discord.enabled"
    /** 수신 주소 key. env ACCOUNT_PNL_ALERT_RECIPIENT 는 application.yml 이 연결한다 — key·env 는 호환 때문에 유지. */
    const val RECIPIENT_KEY = "account-pnl-monitor.recipient"
    val WEBHOOK_KEYS = listOf(
        "owner-alert.discord.webhook.pnl",
        "owner-alert.discord.webhook.market",
        "owner-alert.discord.webhook.ops",
    )
    fun emailRequested(env: PropertyResolver): Boolean      // 비었거나 없으면 true, 아니면 equalsIgnoreCase("true")
    fun emailEffective(env: PropertyResolver): Boolean      // emailRequested && SMTP equalsIgnoreCase("true")
    fun discordRequested(env: PropertyResolver): Boolean    // equalsIgnoreCase("true")
    fun webhookConfigured(env: PropertyResolver): Boolean   // WEBHOOK_KEYS 중 공백 아닌 값 하나 이상
    fun discordEffective(env: PropertyResolver): Boolean    // discordRequested && webhookConfigured
    fun discordMisconfigured(env: PropertyResolver): Boolean // discordRequested && !webhookConfigured
    fun anyChannel(env: PropertyResolver): Boolean          // emailEffective || discordEffective
    fun recipientRequired(env: PropertyResolver): Boolean   // == emailEffective
    /** 채널 쪽 필수 키 중 비어 있는 key — recipientRequired ∧ RECIPIENT_KEY 공백이면 [RECIPIENT_KEY], 아니면 []. */
    fun missingKeys(env: PropertyResolver): List<String>
    /** missingKeys.isEmpty() && !discordMisconfigured && anyChannel — #90 OwnerAlertChannelConfiguration 의 조건. */
    fun ready(env: PropertyResolver): Boolean
    /** 메일 sender 생성용 수신 주소(원문). 공백이면 IllegalStateException("account-pnl-monitor.recipient is required when the owner email channel is on"). */
    fun recipient(env: PropertyResolver): String
}

class OwnerAlertEmailRequestedCondition : Condition     // emailEffective
class DiscordWebhookConfiguredCondition : Condition     // discordEffective

/** [EmailSender bean ∧ emailEffective] ∨ [discordEffective]. */
class OwnerAlertChannelAvailableCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnBean(EmailSender::class)
    @Conditional(OwnerAlertEmailRequestedCondition::class)
    class EmailChannel

    @Conditional(DiscordWebhookConfiguredCondition::class)
    class DiscordChannel
}
```

`AccountPnlMonitorAdapterConfiguration` (infra) 변경 (전문 — Step 3 에서 적용, Step 1 에서는 아래 "Step 1 범위"만):

| 위치 | 변경 |
|---|---|
| 클래스 애노테이션 | `@ConditionalOnBean(EmailSender::class)` → `@Conditional(OwnerAlertChannelAvailableCondition::class)`. `@EnableConfigurationProperties(AccountPnlMonitorProperties::class, OwnerAlertProperties::class)` |
| `AccountPnlMonitorRequirements.missingKeys` | `COMMON` 에서 recipient 를 뺀다 — `OwnerAlertChannelRequirements.missingKeys(env) + (bithumb 2개 + 모드별 중 공백)`, 결과 순서 = 기존(recipient → bithumb → 모드) |
| `AccountPnlCredentialsPresentCondition` | `enabled && missingKeys.isEmpty() && !discordMisconfigured && anyChannel` |
| `accountPnlMailSender` (기존 이름) | 시그니처 `accountPnlMailSender(emailSender: EmailSender, environment: Environment): AccountPnlMailSender` = `AccountPnlEmailSenderAdapter(emailSender, OwnerAlertChannelRequirements.recipient(environment))`. `@ConditionalOnBean(EmailSender::class)` + `@Conditional(OwnerAlertEmailRequestedCondition::class)` 추가. `AccountPnlMonitorProperties` 를 받지 않는다 |
| `ownerAlertDiscordSender(ownerAlert: OwnerAlertProperties): DiscordWebhookSenderAdapter` (신규) | `@Conditional(DiscordWebhookConfiguredCondition::class)`. `DiscordWebhookSenderAdapter(ownerAlert.discord.webhook.toDiscordWebhooks())`. 생성 시 INFO 1줄 |
| `ownerAlertRouting(ownerAlert: OwnerAlertProperties, mailSender: ObjectProvider<AccountPnlMailSender>, discordSender: ObjectProvider<OwnerAlertDiscordSender>): OwnerAlertRouting` (신규) | 조건 없음. `OwnerAlertRouting(OwnerAlertChannels(email = mailSender.ifAvailable != null, discord = discordSender.ifAvailable != null), ownerAlert.resolvedOverrides())` |
| `AccountPnlMonitorProperties.recipient` | 값·바인딩·마스킹 무변경. KDoc 한 줄: "owner 메일 수신 주소는 `OwnerAlertChannelRequirements.recipient` 가 읽는다 — 이 필드는 바인딩 호환용" |

```kotlin
// apps/batch AccountPnlMonitorConfiguration.kt — 클래스·애노테이션 이름 무변경
class AccountPnlMonitorEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean
    // design §7.2 표 순서 1~7 그대로 (모든 로그는 className == AccountPnlMonitorConfiguration 일 때만)
    private companion object {
        val RECIPIENT = "account-pnl-monitor.recipient" to "ACCOUNT_PNL_ALERT_RECIPIENT"   // emailEffective 일 때만 필수 (= infra recipientRequired)
        val COMMON = listOf(
            "account-pnl-monitor.bithumb.access-key" to "BITHUMB_ACCESS_KEY",
            "account-pnl-monitor.bithumb.secret-key" to "BITHUMB_SECRET_KEY",
        )
        // API·MANUAL 기존 그대로. missing 목록 = (emailEffective 면 [RECIPIENT]) + COMMON + 모드별, 공백인 것의 env 이름
        const val OWNER_EMAIL_ENABLED_KEY = "owner-alert.email.enabled"
        const val SMTP_ENABLED_KEY = "notification.email.enabled"
        const val DISCORD_ENABLED_KEY = "owner-alert.discord.enabled"
        val WEBHOOKS = listOf(
            "owner-alert.discord.webhook.pnl" to "OWNER_ALERT_DISCORD_WEBHOOK_PNL",
            "owner-alert.discord.webhook.market" to "OWNER_ALERT_DISCORD_WEBHOOK_MARKET",
            "owner-alert.discord.webhook.ops" to "OWNER_ALERT_DISCORD_WEBHOOK_OPS",
        )
    }
}

@Bean
fun accountPnlMonitorJob(
    korea: KoreaSpotAccountReadPort,
    foreign: ForeignPerpAccountReadPort,
    fx: FxRateReadPort,
    lastSuccess: JobLastSuccessReadPort,
    store: AccountPnlAlertStore,
    mailSender: ObjectProvider<AccountPnlMailSender>,
    executor: JobExecutor,
    clock: Clock,
    jobConfigs: JobConfigProvider,
    @Value("\${account-pnl-monitor.hourly-report.enabled:true}") hourlyReportEnabled: Boolean,
    routing: OwnerAlertRouting,
    discordSender: ObjectProvider<OwnerAlertDiscordSender>,
): AccountPnlMonitorJob
// mailSender.ifAvailable ?: OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER, discordSender.ifAvailable 를 넘긴다. zone 은 넘기지 않는다(기존 그대로, design §10)
```

로그 (문구 전문 — 테스트가 부분 문자열로 잰다):

| 로그 | 레벨·위치 | 형식 |
|---|---|---|
| 누락 (기존) | WARN, app 조건 3번 | `account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={}, missing={}` — `missing` 은 env 이름 목록, recipient 는 `emailEffective` 일 때만 대상 |
| webhook 없음 | WARN, app 조건 4번 | `owner-alert discord enabled but no webhook configured; monitor not started: set one of [OWNER_ALERT_DISCORD_WEBHOOK_PNL, OWNER_ALERT_DISCORD_WEBHOOK_MARKET, OWNER_ALERT_DISCORD_WEBHOOK_OPS]` |
| 채널 없음 (기존 문구) | WARN, app 조건 5번 | `account-pnl-monitor enabled but email disabled; monitor not started` |
| 메일만 꺼짐 | WARN, app 조건 6번 | `owner-alert email enabled but NOTIFICATION_EMAIL_ENABLED is not true; email channel off` |
| webhook 해석 | INFO, infra `ownerAlertDiscordSender` | `owner-alert discord webhooks: pnl={}, market={}, ops={}` — 값 `DiscordWebhooks.describe()` (`set`·`fallback(PNL)` 등) |

어떤 로그도 webhook URL·token·수신 주소·key 값을 담지 않는다.

`application.yml` 추가 블록 (전문, `account-pnl-monitor` 블록 바로 뒤. 기존 `account-pnl-monitor.recipient: ${ACCOUNT_PNL_ALERT_RECIPIENT:}` 줄은 그대로 둔다):

```yaml
# owner 알림 채널 (메일·Discord). webhook URL 은 비밀값 — 환경변수로만 주입하며 기본값을 두지 않는다.
# 메일 수신 주소는 account-pnl-monitor.recipient (ACCOUNT_PNL_ALERT_RECIPIENT) — 메일 채널을 쓸 때만 필수.
owner-alert:
  email:
    enabled: ${OWNER_ALERT_EMAIL_ENABLED:true}
  discord:
    enabled: ${OWNER_ALERT_DISCORD_ENABLED:false}
    webhook:
      pnl: ${OWNER_ALERT_DISCORD_WEBHOOK_PNL:}
      market: ${OWNER_ALERT_DISCORD_WEBHOOK_MARKET:}
      ops: ${OWNER_ALERT_DISCORD_WEBHOOK_OPS:}
  # 알림 종류별 경로 덮어쓰기. 빈 값이면 Domain OwnerAlertKind 기본값. 종류를 추가하면 여기 세 줄과 runbook 을 함께 추가한다.
  routes:
    profit:
      email: ${OWNER_ALERT_PROFIT_EMAIL:}
      discord: ${OWNER_ALERT_PROFIT_DISCORD:}
      push: ${OWNER_ALERT_PROFIT_PUSH:}
    hourly-report:
      email: ${OWNER_ALERT_HOURLY_REPORT_EMAIL:}
      discord: ${OWNER_ALERT_HOURLY_REPORT_DISCORD:}
      push: ${OWNER_ALERT_HOURLY_REPORT_PUSH:}
    monitoring-outage:
      email: ${OWNER_ALERT_MONITORING_OUTAGE_EMAIL:}
      discord: ${OWNER_ALERT_MONITORING_OUTAGE_DISCORD:}
      push: ${OWNER_ALERT_MONITORING_OUTAGE_PUSH:}
    monitoring-recovered:
      email: ${OWNER_ALERT_MONITORING_RECOVERED_EMAIL:}
      discord: ${OWNER_ALERT_MONITORING_RECOVERED_DISCORD:}
      push: ${OWNER_ALERT_MONITORING_RECOVERED_PUSH:}
```

**테스트가 재는 것** — `OwnerAlertChannelRequirementsTest` (`MockEnvironment`, AC9-j):
- `메일 채널이 실제로 쓰일 때만 수신 주소가 필수다` — {메일 요청 미설정·`true`·`false`} × {SMTP `true`·`false`} 6조합에서 `recipientRequired == emailEffective` (기대값은 테스트 안 표로 직접 적는다)
- `채널 필수 키는 수신 주소 하나뿐이고 메일 채널이 꺼지면 비어 있다` — `emailEffective ∧ recipient 공백("", "  ")` → `[account-pnl-monitor.recipient]`, `emailEffective ∧ recipient 있음` → `[]`, `¬emailEffective ∧ recipient 공백` → `[]`
- `ready 는 채널 준비식과 같다` — 메일만·Discord만·Discord 켜짐 webhook 없음·둘 다 꺼짐·메일 켜짐 recipient 없음 5케이스
- `recipient 는 값을 그대로 돌려주고 비면 값 없이 실패한다` — `owner@example.test` 반환, 공백이면 `IllegalStateException`, 메시지에 `account-pnl-monitor.recipient` 가 있고 `@example.test` 가 없다

`AccountPnlMonitorWiringTest` 에 메서드 추가만 — 기존 `runner`·`Stubs`·`enabledProps`·`assertNoBeans` 재사용, `assertNoBeans` 는 **수정하지 않는다**.
새 property 묶음과 구별 값:

```
discordProps = owner-alert.discord.enabled=true,
  owner-alert.discord.webhook.pnl=https://discord.example.test/api/webhooks/101/TOKENpnlQ7
allWebhookProps = discordProps + owner-alert.discord.webhook.market=https://discord.example.test/api/webhooks/102/TOKENmktQ7,
  owner-alert.discord.webhook.ops=https://discord.example.test/api/webhooks/103/TOKENopsQ7
webhookValues = TOKENpnlQ7, TOKENmktQ7, TOKENopsQ7, /api/webhooks/, discord.example.test
```

- `메일과 Discord 를 모두 켜면 두 sender 와 Job 이 뜨고 라우팅이 두 채널이다` (AC9-a)
- `SMTP 가 꺼져도 Discord 가 있으면 Discord 만으로 뜨고 메일 채널 꺼짐 WARN 을 남긴다` — `enabledProps` + `notification.email.enabled=false` + `account-pnl-monitor.recipient=` + `discordProps`, 출력에 `ACCOUNT_PNL_ALERT_RECIPIENT` 없음 (AC9-b)
- `owner 메일을 끄고 Discord 만 켜면 recipient 없이 뜬다` — 출력에 `ACCOUNT_PNL_ALERT_RECIPIENT` 없음 (AC9-c)
- `Discord 를 켰는데 webhook 이 없으면 메일이 있어도 뜨지 않고 env 이름 셋을 WARN 한다` — webhook 3개 빈 값·공백 두 케이스, 세 env 이름 포함 (AC9-d)
- `메일을 끄고 Discord 도 없으면 email disabled WARN 으로 뜨지 않는다` (AC9-e)
- `채널 판정은 app 과 infra 가 같은 답을 낸다` — dod AC9-(f) 의 조합 전부(3×2×3×4×2 = 144), bithumb·바이낸스 api 키는 항상 채움, 각 조합에서 먼저 `hasNotFailed()`, 그다음 Job 수 == `ForeignPerpAccountReadPort` 수 ∈ {0,1} == 기대값(테스트 안에 design §7.2 판정식을 **독립적으로 다시 적은** oracle 함수, recipient 는 `emailEffective` 일 때만 필수), Job 이 있으면 `OwnerAlertRouting.channels.email == hasBean(AccountPnlEmailSenderAdapter)`, `.discord == hasBean(DiscordWebhookSenderAdapter)` (AC9-f)
- `종류별 override 가 라우팅 bean 에 반영된다` (AC9-g)
- `메일 채널을 쓰는데 수신 주소가 비면 env 이름만 WARN 하고 뜨지 않는다` — `enabledProps` + `account-pnl-monitor.recipient=` → Job 0, 출력에 `missing=[ACCOUNT_PNL_ALERT_RECIPIENT]`, `owner@example.test`·credential 구별 값 없음 (AC9-k)
- `메일 sender 는 account-pnl-monitor.recipient 로 보낸다` — `enabledProps`, `Stubs` 가 등록한 relaxed mock 을 `context.getBean(EmailSender::class.java)` 로 꺼낸다(두 번째 `EmailSender` 를 `withBean` 으로 넣지 않는다 — 넣으면 `NoUniqueBeanDefinitionException`) → `context.getBean(AccountPnlMailSender::class.java).send(AccountPnlMail("k", "s", "b"))` → mockk `slot` 으로 잡은 `EmailMessage.to == "owner@example.test"` (AC9-k)
- `Properties 와 webhook 해석 toString 에 webhook 이 없다`, `기동 INFO 는 set 과 fallback 만 찍는다`(셋 다 / pnl 만), `http webhook 은 기동 실패이고 값이 어디에도 없다` — `owner-alert.discord.enabled=true` 와 `false`(메일만 켜진 구성) 두 경우, `잘못된 push 값은 기동 실패이고 값이 어디에도 없다` — 기존 `assertFailsWithoutValue` 재사용 (AC10)
- `OwnerAlertYamlContractTest` — `YamlPropertySourceLoader` 로 `application.yml`(첫 문서) 로드, `owner-alert.routes.` 하위 key 집합 == `OwnerAlertKind.entries.map { it.key }`, 각 `{key}.{email|discord|push}` 원문 == `${OWNER_ALERT_${kind.name}_${FIELD}:}` (AC9-h)

- [ ] **Step 1: 시그니처만 (기존 배선 동작 무변경).** 아래만 추가하고 테스트를 작성한다.
  - `OwnerAlertChannelRequirements` 시그니처(판정 함수는 모두 `false`·`missingKeys = emptyList()`·`recipient` 는 `error("stub")`), 조건 3개(`matches = false`) — **어디에도 붙이지 않는다.**
  - infra bean 2개: `ownerAlertRouting()` — **인자 없이** `OwnerAlertRouting.EMAIL_ONLY` 반환(아직 `OwnerAlertProperties` bean 이 없으므로 받지 않는다), `ownerAlertDiscordSender` 는 Step 1 에서 **추가하지 않는다**.
  - Job bean 인자 변경(`ObjectProvider<AccountPnlMailSender>`·`routing`·`ObjectProvider<OwnerAlertDiscordSender>` 를 받되 `routing`·`discordSender` 는 무시하고 기존처럼 생성, 메일 sender 는 `getObject()`).
  - **바꾸지 않는 것:** 클래스 조건(`@ConditionalOnBean(EmailSender::class)` 유지 — `OwnerAlertChannelAvailableCondition` 미부착), `@EnableConfigurationProperties` 는 `AccountPnlMonitorProperties` 만(`OwnerAlertProperties` 미활성), `accountPnlMailSender` 본문·조건, `AccountPnlMonitorRequirements`·`AccountPnlCredentialsPresentCondition`, app 조건, yml. 그래서 기존 wiring 테스트 메서드가 Step 1·2 에서 초록이다.
- [ ] **Step 2:** `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.owneralert.OwnerAlertChannelRequirementsTest' --offline --no-daemon && ./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --tests 'io.premiumspread.config.OwnerAlertYamlContractTest' --offline --no-daemon` → RED (신규 케이스 실패, **기존 메서드는 초록** — 기존 메서드가 빨가면 Step 1 이 배선을 바꾼 것이다, 멈추고 고친다). 증거 로그 AC9·AC10.
- [ ] **Step 3: 구현 (이 순서로).**
  1. `OwnerAlertChannelRequirements` 판정·`missingKeys`·`ready`·`recipient` 실제 구현, 조건 3개 실제 구현.
  2. `AccountPnlMonitorRequirements.missingKeys` 에서 recipient 분리(채널 필수 키 위임), `AccountPnlCredentialsPresentCondition` 갱신.
  3. 클래스 조건 교체 — `@ConditionalOnBean(EmailSender::class)` 제거, `@Conditional(OwnerAlertChannelAvailableCondition::class)` 부착, `@EnableConfigurationProperties(AccountPnlMonitorProperties::class, OwnerAlertProperties::class)`. **이 시점부터** `OwnerAlertProperties` 가 바인딩된다.
  4. `accountPnlMailSender` 를 `(EmailSender, Environment)` + `OwnerAlertChannelRequirements.recipient` 로, 조건 2개 추가. `AccountPnlMonitorProperties.recipient` KDoc.
  5. `ownerAlertDiscordSender` 추가, `ownerAlertRouting` 을 `(OwnerAlertProperties, ObjectProvider, ObjectProvider)` 로 교체.
  6. app 조건 순서 1~7·WARN, Job bean 이 `routing`·`discordSender`·`UNAVAILABLE_MAIL_SENDER` 를 넘기게.
  7. yml 블록.
- [ ] **Step 4:** Step 2 명령 → PASS. `./gradlew :apps:batch:test :infrastructure:batch:test --offline --no-daemon` → PASS. `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` (R10) → PASS.
- [ ] **Step 5: Commit** — `feat: owner 알림 채널 기동 판정과 Discord 배선`

### Task 11: 전달 E2E 통합 (AC11)

**Files:** Create `apps/batch/src/integrationTest/kotlin/io/premiumspread/application/job/OwnerAlertDeliveryIntegrationTest.kt`

**Interfaces — Consumes:** `BatchIntegrationTestBase`(실제 MySQL fixture), `AccountPnlAlertStore`(autowired, `JdbcAccountPnlAlertStore`), Task 7 `DiscordWebhookSenderAdapter`·`DiscordWebhooks`,
Task 9 Job 생성자. 새 production 코드 없음.

**테스트가 재는 것** (`AccountPnlAlertDurabilityIntegrationTest` 의 fake 계정·환율·lock 구성을 이 클래스 안에 다시 만든다 — 그 파일 무수정. MockWebServer 1개에 path
`/api/webhooks/1/TOKENpnlQ7`(PNL)·`/api/webhooks/3/TOKENopsQ7`(OPS), `sleeper` no-op, `MutableClock(2026-10-20T03:00:00Z)`, routing `OwnerAlertChannels(true, true)`, 정시 보고 꺼짐):
- `수익 알림은 메일과 Discord 로 한 번씩 간다` — 1.2% 회차 1번 → `account_pnl_mail` 에 같은 eventKey 의 `EMAIL`·`DISCORD` 각 1행, 둘 다 `SENT`, MockWebServer `requestCount == 1`, path PNL, 본문 `content == "@here"` (AC11-a)
- `Discord 503 은 다음 회차 재시작 뒤 재발송되고 메일은 다시 가지 않는다` — 첫 응답 503 → 메일 `SENT`·Discord `PENDING`(attempt 1) → 새 Job 인스턴스, `clock +1m`, 같은 1.2%(에피소드 중이라 새 알림 없음) 회차 → Discord `SENT`, 메일 sender 기록 1건 (AC11-b)
- `Discord 404 는 한 번에 FAILED 이고 다시 시도하지 않는다` — 404 → Discord `FAILED`(attempt 1) → 다음 회차 `requestCount` 증가 없음 (AC11-c)
- `outbox 어디에도 webhook token 이 없다` — 각 시나리오 뒤 `SELECT * FROM account_pnl_mail` 모든 값 문자열에 `TOKEN` 없음 (AC11-d)

- [ ] **Step 1:** 테스트 작성.
- [ ] **Step 2: RED (변이).** 앞 태스크가 구현을 끝냈으므로 첫 실행은 초록일 수 있다. `OwnerAlertDispatcher` 의 `DISCORD` 분기를 임시로 "sender null 처리"로 바꾸고
  `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.OwnerAlertDeliveryIntegrationTest' --offline --no-daemon` → (a)(b) FAILED 확인 →
  `git checkout -- apps/batch/src/main` → `git diff --stat -- apps/batch/src/main` 빈 출력 기록. 증거 로그 AC11.
- [ ] **Step 3:** 같은 명령 → PASS. `./gradlew :apps:batch:integrationTest --offline --no-daemon` (R9) → PASS.
- [ ] **Step 4: Commit** — `test: owner 알림 메일·Discord 전달 통합 테스트`

### Task 12: runbook·아키텍처 문서와 회귀 (AC12, R1~R11)

**Files:** Modify `docs/runbooks/account-pnl-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`

**요건 (현재 runbook 절 이름 기준, 실제 값 금지 — placeholder 만):**
- `## 환경변수` 표: 고정 5종 `OWNER_ALERT_EMAIL_ENABLED`·`OWNER_ALERT_DISCORD_ENABLED`·`OWNER_ALERT_DISCORD_WEBHOOK_PNL`·`OWNER_ALERT_DISCORD_WEBHOOK_MARKET`·`OWNER_ALERT_DISCORD_WEBHOOK_OPS`
  행 추가(webhook 은 **비밀**), 종류별 패턴 3종은 아래 신규 절 표로. `ACCOUNT_PNL_ALERT_RECIPIENT` 필수 칸을 "메일 채널을 쓸 때만(`OWNER_ALERT_EMAIL_ENABLED`≠false ∧ `NOTIFICATION_EMAIL_ENABLED=true`)"으로,
  `NOTIFICATION_EMAIL_ENABLED` 설명을 "메일 채널을 쓸 때 필요"로. 표 아래 기동 판정 목록에 webhook 없음 WARN·메일만 꺼짐 WARN 문구 전문.
- 신규 절 `## owner 알림 채널 (메일·Discord)` (`## 바이낸스 수동 입력 모드 (manual)` 앞):
  - webhook 만들기: Discord 채널 설정 → 연동 → 웹후크 → 새 웹후크 → URL 복사. 용도별 채널 3개 권장(pnl·market·ops), 하나만 만들면 모두 그리로, 두 개만 만들면 빠진 대상은 PNL → MARKET → OPS 순의 첫 설정 대상으로.
  - **webhook URL 은 비밀번호와 같다** — URL 만 있으면 누구나 그 채널에 글을 쓸 수 있다. 유출되면 Discord 에서 삭제하고 새로 만든다. 셸 history·스크린샷 주의(기존 `### credential 취급` 참조).
  - 기본 라우팅 표 4종(design §3.2), 후속 10종은 #89·#90·#91 링크 한 줄. **Discord 를 켜면 정시 보고·복구 메일은 더 이상 오지 않는다**(굵게). Discord 가 꺼져 있으면 지금처럼 4종 모두 메일.
  - 종류별 override env 패턴 3종 `OWNER_ALERT_{KIND}_EMAIL`·`OWNER_ALERT_{KIND}_DISCORD`·`OWNER_ALERT_{KIND}_PUSH` 규칙·값·예시(정시 보고 소리로 받기 `OWNER_ALERT_HOURLY_REPORT_PUSH=sound`).
  - 무음 = 알림 소리·배지 없이 채널에만 쌓임, @here = 채널 멤버 전원 알림.
  - 동작: 재시도(429·5xx·네트워크 → 다음 회차, 최대 5회, 429 대기 2초 이하면 즉시 1회), 그 밖 4xx(webhook 삭제·잘못된 URL) → 즉시 `FAILED`, 채널을 끄면 그 채널의 남은 행은 1회 시도 후 `FAILED`, 드물게 중복 가능, 기동 INFO `owner-alert discord webhooks: ...` 문구 전문.
  - **Discord 를 꺼 둔 상태여도 webhook env 에 잘못된 값(http·URL 아님)이 있으면 모니터 기동이 실패한다** — 비우거나 https URL 로 고친다(값은 오류에 찍히지 않고 key 만 찍힌다).
  - 한계: Spring `org.springframework.web.reactive.function.client` 로거를 DEBUG 로 켜지 않는다(요청 URL 이 찍힌다).
  - `### 배포와 롤백` — (1) API 배포(V19) → (2) **구 batch 중지 확인** → (3) 신 batch 기동. 구·신 batch 를 겹쳐 돌리지 않는 이유 한 줄(구 버전은 channel 을 몰라 Discord 행을 메일로 보낸다).
    롤백: 신 batch 중지 → 아래 SQL → 구 batch 기동. SQL 전문(design §4.5, Task 5 `ROLLBACK_DISCORD_ROWS` 와 같은 문자열):
    ```sql
    UPDATE account_pnl_mail
    SET status = 'FAILED',
        claim_token = NULL,
        locked_by = NULL,
        locked_at = NULL
    WHERE channel = 'DISCORD'
      AND status IN ('PENDING', 'PROCESSING');
    ```
    효과 한 줄: 미발송 Discord 사본만 닫히고 메일 행은 영향 없음. 스키마(V19)는 되돌리지 않는다.
- `## 끄는 법과 데이터 정리`: `account_pnl_mail` 설명을 "owner 알림 공용 outbox(메일·Discord, `channel` 컬럼). 수신 주소·webhook URL 은 저장하지 않는다"로. Discord 끄기 = `OWNER_ALERT_DISCORD_ENABLED=false` + 재시작.
- `## manual 모드 검산 체크리스트 (manual-binance AC9)` 다음에 `## Discord 수신 체크리스트 (owner-alert-channel AC13)` — dod AC13 (a)~(e).
- `.ai/architecture/ARCHITECTURE_DESIGN.md`: 실계정 알림 흐름 서술에 "outbox 행은 채널(EMAIL·DISCORD)별, Discord 는 `infrastructure:batch` webhook adapter" 한 줄.

- [ ] **Step 1:** 문서 갱신. 롤백 SQL 은 Task 5 테스트 상수와 문자열 비교로 같은지 눈으로 확인하고 증거 로그에 두 블록을 나란히 붙인다.
- [ ] **Step 2:** 회귀 일괄 (dod 회귀 방어선 표 그대로):
  ```bash
  ./gradlew test architectureTest --offline --no-daemon                                                        # R1·R2
  # R3: dod 표의 git diff --exit-code 명령 그대로
  ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon                  # R4
  ./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon   # R4
  test -z "$(git diff --name-only --diff-filter=MDR 1bce3dd -- infrastructure/common/src/main/resources/db/migration)" && ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon   # R5
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon                                       # R8 (Docker)
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                                                  # R9 (Docker)
  ./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon                            # R10
  ./gradlew harnessCheck --offline --no-daemon && bash docs/check-documentation.sh                              # R11·AC12
  ```
  예상: 전부 exit 0. Docker 부재로 R8·R9 를 못 돌리면 green 으로 기록하지 않고 미실행으로 남긴다.
- [ ] **Step 3:** dod AC1~AC11 검증 명령 각각 재실행 → exit 0. 증거 로그 Task 12.
- [ ] **Step 4:** R6 — `git diff 1bce3dd -- domain/src/test/kotlin/io/premiumspread/domain/accountpnl/AccountPnlMailContentTest.kt apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorWiringTest.kt infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPnlAlertStoreIntegrationTest.kt | grep '^-[^-]'`
  출력(빈 출력이어야 함)과 R7 — `git diff 1bce3dd -- apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPnlFixtureSchemaIntegrationTest.kt` 전문을 증거 로그에 붙인다(⑨ 판정용).
  허용 예외 밖 삭제가 있으면 멈추고 보고.
- [ ] **Step 5: Commit** — `docs: 실계정 알림 runbook 에 owner 알림 채널(메일·Discord)과 배포·롤백 추가`
- [ ] **Step 6 (owner, AC13):** runbook 의 Discord 수신 체크리스트대로 로컬 실행 → PR 코멘트.

## 태스크 의존

```
Task 0 → Task 1 → Task 2 → Task 3 → Task 4 ─┐
                           │                ├→ Task 9 → Task 10 → Task 11 → Task 12
                           ├→ Task 5 → Task 6┤
                           └→ Task 7 → Task 8┘
```

Task 5·6(persistence)과 Task 7·8(adapter·설정)은 Task 3 뒤 서로 독립이지만 요청 순서(Domain → adapter → Job → 배선 → 통합)를 따라 번호 순으로 실행한다.
Task 6 은 batch fixture 를 바꾸므로 Task 11·R9 전에 끝나야 한다. Task 10 은 Task 7(adapter 타입)·Task 8(Properties)·Task 9(Job 생성자)가 있어야 컴파일된다.
Task 12 runbook 롤백 SQL 은 Task 5 테스트 상수와 같은 문자열이어야 한다.

## 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| ⑥ R1 | Review Focus 6·7, Task 5 롤백 SQL·구 claim 상수와 AC4-f 테스트, Task 6 store KDoc, Task 12 `### 배포와 롤백` | Important 1, 판정 I-1 |
| ⑥ R1 | Task 4 manual 출처 마지막 줄 기대 `09:00 KST`(fixture `calculatedAt` 00:00:05Z), 정시 보고만 `calculatedAt` 명시 | Important 2 |
| ⑥ R1 | Task 10 `OwnerAlertChannelRequirements` 에 `RECIPIENT_KEY`·`recipientRequired`·`missingKeys`·`ready`·`recipient`, 메일 sender 가 `Environment` 로 수신 주소를 묶음, `OwnerAlertChannelRequirementsTest`, AC9-j·k 테스트 | Important 3, 판정 I-3 |
| ⑥ R1 | Task 4 `AccountPnlFormat` 이동 목록에 `sign`·`isZero`·`TIME_FORMAT`(`private val`) | Minor 4 |
| ⑥ R1 | Task 7 WebClient 미주입 근거 문장 정정(`BatchTestConfig.kt:18,71`) | Minor 2 |
| ⑥ R1 | Task 7 원인 체인 취소 → 플래그 복구 + cause 없는 예외, `isCancellation`, AC6-i 테스트; Task 9 디스패처 취소 테스트 | Minor 6 |
| ⑥ R1 | Task 10 Step 1 범위 명시(stub 조건·`OwnerAlertProperties` 미부착, Step 3-3 에서 부착) | Minor 5 |
| ⑥ R1 | Task 10 AC10 http webhook 케이스를 Discord 켜짐·꺼짐 둘 다로, Task 12 runbook 한 줄 | Minor 8 (Discord 꺼짐에도 검증 유지) |
| ⑥ R1 | Task 12 env 표기 "고정 5종 + 패턴 3종" | Minor 7 |
