# owner 운영 알림 — 환율 수집 지연·시세 수집 끊김·Job 연속 실패 (issue #91, 상위 #87) 설계

- slug: `owner-ops-alerts` · base `origin/dev` `20a7484`(Task 0 확인) · branch `feat/issue-91-owner-ops-alerts`
- 입력: issue #91 · ③ 사용자 합의(아래 §0.1) · `docs/work/owner-alert-channel/design.md`(#88, 라우팅 §3·outbox §4·전달 §5·연결 경로 §9) ·
  `docs/work/owner-account-alerts/*`(#89) · `.ai/rules/{architecture,batch,testing,naming}.md`
- 상태: DRAFT — ⑥ R1 REQUEST_CHANGES 9건 반영(§15)

## 0. 요약

batch 가 스스로 "지속되는 운영 장애"를 감지해 owner 에게 Discord(·메일)로 알린다. 대상은 셋이다.

| 신호 | 장애 조건 | 알림 종류(`OwnerAlertKind`) | 기본 경로 |
|---|---|---|---|
| 환율 수집 | 우리 쪽 마지막 FX 수집 성공 뒤 **30분 초과** | `FX_INGESTION_DELAYED` | OPS · SOUND · 메일 false |
| 시세 수집 (빗썸·바이낸스 각각) | 최신 tick 나이 **5분 이상** | `MARKET_STREAM_DOWN` | OPS · SOUND · 메일 false |
| batch Job (job 별) | 연속 Failure **3회 이상 ∧ 첫 실패 후 5분 이상** | `JOB_CONSECUTIVE_FAILURES` | OPS · SOUND · 메일 true |
| 복구 (세 신호 공통) | 장애였던 신호가 정상으로 확인됨 | `OPS_RECOVERED` | OPS · SILENT · 메일 false |

장애가 이어지면 **6시간마다 1회** 다시 알리고, 해소되면 **1회** 복구를 알린다. 복구 뒤 다시 나빠지면 새 장애다.

이 단위는 #88 §9 가 #90 몫으로 적어 둔 **공용 owner 알림 기반**도 함께 만든다(사용자 합의 U1):
(a) PnL 외 Job 이 owner outbox 에 행을 넣는 범용 Domain port `OwnerAlertOutbox`, (b) 독립 전달 Job `OwnerAlertDeliveryJob`,
(c) 채널 bean 3개를 PnL 모니터 구성에서 떼어 낸 `OwnerAlertChannelConfiguration`. PnL 모니터가 꺼져 있어도 운영 알림이 나간다.

### 0.1 사용자 합의 (③ — 이 문서가 바꾸지 않는다)

| # | 합의 |
|---|---|
| U1 | 공용 기반(a)(b)(c)를 #91 에서 만든다. 기존 PnL 알림 동작(이중 발송 금지, claim 1건씩, 회차 상한)은 회귀하지 않는다. #90 은 이 기반을 재사용하되 #90 기능은 만들지 않는다 |
| U2 | 기준값 — 환율: `JobLastSuccessReadPort.findLastSuccess(FX_INGESTION)`, null 이면 프로세스 첫 실행 시각 기준(`AccountPnlMonitorJob.checkFxFreshness` 패턴) 후 30분. 제공자 timestamp·DB `observed_at` 은 근거로 쓰지 않는다. 시세: 거래소별 최신 tick(`LatestMarketTickReadPort`) 나이 5분 이상. Job: job 별 연속 Failure 3회 이상 ∧ 첫 실패 후 5분 이상, Skipped 는 세지도 리셋하지도 않음, Success 면 리셋 |
| U3 | 복구 알림: 해소되면 1회, OPS 무음(SILENT), 메일 false |
| U4 | 재알림: 장애 지속 중 6시간마다 1회. 복구 뒤 재발생은 새 장애 |
| U5 | 기존 Slack `OperatorAlert`(`BATCH_JOB_FAILED`, websocket reconnect 등)는 그대로 둔다 — 순간 이벤트용. #91 은 지속 상태·복구용 |

### 0.2 핵심 결정

| # | 결정 | 절 |
|---|---|---|
| K1 | 범용 enqueue port 는 **신호 상태 + outbox 행을 한 트랜잭션**으로 쓰는 `OwnerAlertOutbox.enqueue(signals, rows)` 다. 상태가 없는 생산자는 `signals = []` 로 행만 넣는다 | §3 |
| K2 | 신호 상태는 새 테이블 `owner_alert_signal_state`(V21, 신호당 1행, 행별 낙관적 version). `account_pnl_monitor_state` 는 건드리지 않는다 | §4 |
| K3 | 복구는 종류 하나 `OPS_RECOVERED` 를 더한다 — 라우팅이 종류 단위이고 복구 경로(SILENT·메일 false)가 장애 경로(SOUND)와 달라서다. enum 은 4개 추가(#88 §3.2 목표 표의 3개 + 복구 1개) | §2 |
| K4 | 관측은 **3상태**(`Down`/`Up`/`Unknown`)다. 재시작 직후처럼 근거가 없으면 `Unknown` 이고, `Unknown` 은 장애를 열지도 닫지도 않는다 — 재시작이 가짜 복구를 만들지 않는다 | §5.2 |
| K5 | Job 연속 실패 카운터는 `infrastructure:batch` `RedisJobRunRecorderAdapter` 의 **in-memory** 맵이다(Redis 무변경). 재시작하면 0 부터 다시 센다 — 한계로 명시 | §6 |
| K6 | 전달은 새 `OwnerAlertDeliveryJob`(60초, `OwnerAlertDispatcher` 재사용)이 한다. `AccountPnlMonitorJob` 의 회차 안 전달은 그대로 둔다 — 두 디스패처가 같은 outbox 를 claim 해도 `FOR UPDATE SKIP LOCKED` + `PENDING` 조건 claim 이라 한 행은 한 번만 집힌다 | §7 |
| K7 | 운영 신호 Job 과 전달 Job 자신은 Job 연속 실패 감시 대상에서 **뺀다** — 자기 실패를 자기 경로로 알릴 수 없다. 두 Job 의 실패는 기존 Slack `BATCH_JOB_FAILED` 가 맡는다 | §6.3 |
| K8 | 운영 알림은 기본 **켜짐**(`OWNER_ALERT_OPS_ENABLED=true`). 쓸 수 있는 owner 채널이 없으면 기동하지 않는다 | §9 · `[ASSUMPTION]` A1 |

## 1. 범위

### 1.1 포함

- Domain: `OwnerAlertKind` 4종, 범용 port `OwnerAlertOutbox` 와 신호 상태 타입, 운영 신호 모델·관측 함수·장애 정책·렌더러(`domain.ownerops`),
  `JobId` 2종, `JobFailureStreakReadPort`
- infrastructure:common: V21, `JdbcOwnerAlertOutbox`(+ `CommonJdbcConfiguration` 의 `@Import` 등록), outbox INSERT SQL 의 단일 소유자 추출(`JdbcAccountPnlAlertStore` 동작 무변경)
- infrastructure:batch: 연속 실패 카운터(`RedisJobRunRecorderAdapter`), 채널 bean 이동(`OwnerAlertChannelConfiguration`)
- apps:batch: `OwnerOpsSignalJob`·`OwnerAlertDeliveryJob`·Scheduler 2개, `JobConfig`·`BatchJobProperties`·`BatchSchedulingProperties` 항목, 앱 기동 조건·배선,
  `application.yml`(routes 4블록·`owner-alert.ops`·jobs·scheduling)
- batch fixture 스키마(`batch-schema.sql`)에 V21 테이블
- 문서: 새 runbook `docs/runbooks/owner-ops-alert.md`, `docs/runbooks/account-pnl-alert.md`(채널 bean 독립·라우팅 표·override 목록), `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`

### 1.2 제외 (scope creep 차단선)

- #90 시장 알림 기능(김프 돌파·급변·신고가·일일 요약), #89 계정 알림 변경
- 기존 Slack `OperatorAlert` 경로·문구 변경(U5), `WebSocketConnectionManager` 재연결 로직 변경
- `AccountPnlMonitorJob` 의 관측·정책·회차 안 전달 변경, `OwnerAlertDispatcher` 동작 변경
- outbox 테이블·Domain 행 타입 rename(#88 §4.4 이름 부채 유지), outbox 4메서드를 상위 interface 로 올리는 일(#88 §9 가 #90 몫으로 둔 것)
- Redis key·TTL·payload, 메트릭 추가(기존 `batch.job.run` 의 job tag 에 새 JobId 2개가 붙는 것뿐), 공개 endpoint
- FX 허용 공백 30분 값 변경(`FxIngestionFreshness` 재사용 — 변경 시 TTL·주기 계약이 함께 움직인다)
- 다중 batch 인스턴스에서의 연속 실패 합산(인스턴스마다 따로 센다)

## 2. 알림 종류와 라우팅 (Domain `owneralert`)

`OwnerAlertKind` 끝(기존 7개 뒤)에 순서대로 추가한다(전문). enum 전체 `when` 금지 규칙(`OwnerAlertRouting.kt:18`)을 지킨다.

```kotlin
    FX_INGESTION_DELAYED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    MARKET_STREAM_DOWN(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    JOB_CONSECUTIVE_FAILURES(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    OPS_RECOVERED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SILENT))),
```

| enum | key | Discord | 푸시 | 메일 |
|---|---|---|---|---|
| `FX_INGESTION_DELAYED` | `fx-ingestion-delayed` | `OPS` | `SOUND` | false |
| `MARKET_STREAM_DOWN` | `market-stream-down` | `OPS` | `SOUND` | false |
| `JOB_CONSECUTIVE_FAILURES` | `job-consecutive-failures` | `OPS` | `SOUND` | true |
| `OPS_RECOVERED` | `ops-recovered` | `OPS` | `SILENT` | false |

- 해석은 #88 §3.3 그대로다. **Discord 가 꺼져 있으면 4종 모두 메일로 간다**(#88 사용자 확정 D1 — 복구와 6시간 재알림도 메일). owner 가
  `OWNER_ALERT_{KIND}_EMAIL=false` 를 명시했을 때만 빠진다. runbook 에 굵게 적는다.
- env override 는 #88 패턴(`OWNER_ALERT_{KIND}_EMAIL/_DISCORD/_PUSH`) 12개가 yml `owner-alert.routes` 4블록으로 생긴다.
- 재알림은 장애와 같은 종류(SOUND)다. 복구만 `OPS_RECOVERED` 다.

## 3. 범용 enqueue port (Domain `accountpnl`)

### 3.1 위치

port 는 outbox 행 타입 `AccountPnlMail` 과 같은 패키지 `io.premiumspread.domain.accountpnl` 에 둔다. 의존 방향 `accountpnl → owneralert` 를
유지하려면(#88 §3.4) 행 타입을 참조하는 port 가 `owneralert` 에 있을 수 없다. 이름 부채(#88 §4.4)와 같은 이유이며 KDoc 에 "owner 알림 공용"을 적는다.

### 3.2 타입과 port (전문)

```kotlin
package io.premiumspread.domain.accountpnl

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

- `toString` 노출: `OwnerAlertIncident`·`OwnerAlertSignalState` 자동 `toString` = signalKey·kind·시각·횟수·version. 비밀 없음.
- `AccountPnlAlertStore` 는 바꾸지 않는다(동결 `FakeStore` 가 메서드를 override 한다 — #88 §2). 두 port 는 같은 outbox 테이블을 쓰고 claim·전이는
  여전히 `AccountPnlAlertStore` 만 한다.

## 4. 저장소

### 4.1 V21 DDL (전문)

위치 `infrastructure/common/src/main/resources/db/migration/V21__create_owner_alert_signal_state.sql`. 번호 = `ls … | sort -V | tail -1` 결과(현재 V20,
`.ai/PROJECT_STATUS.md:105`) + 1 — Task 0 이 확인한다.

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

- 테이블 옵션은 V18 과 같다(`V18__create_account_pnl_alert.sql:13`, `:30` — 확인된 사실).
- 순수 `CREATE TABLE` 이므로 `verifyMigrations` 금지 목록과 무관하고 구 binary 와 호환된다(구 binary 는 이 테이블을 모른다). 롤백 SQL 은 없다.
- batch 는 Flyway 를 쓰지 않으므로 `apps/batch/src/integrationTest/resources/batch-schema.sql` 에 같은 DDL 을 **`CREATE TABLE IF NOT EXISTS`** 로, 같은
  테이블 옵션(`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`)과 함께 추가한다.

### 4.2 컬럼 ↔ Domain

| 컬럼 | Domain | 비고 |
|---|---|---|
| `signal_key` | `signalKey` | §5.1 key 규칙. 정규식 `[a-z0-9._-]{1,64}` |
| `alert_kind` | `alertKind.name` | `OwnerAlertKind.valueOf` 로 읽는다. 모르는 값이면 `IllegalStateException`(값 없는 메시지 `unknown owner alert kind in owner_alert_signal_state`) |
| `active` | `incident != null` | |
| `incident_started_at` / `last_notified_at` / `notify_count` | `incident.startedAt` / `.lastNotifiedAt` / `.notifyCount` | 비활성이면 NULL/NULL/0 |
| `version` | `version` | 저장 후 +1. INSERT 는 1 |
| `updated_at` | — | store 의 주입 `Clock` |

### 4.3 `JdbcOwnerAlertOutbox` (infrastructure:common)

위치 `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcOwnerAlertOutbox.kt`,
생성자는 `JdbcAccountPnlAlertStore` 와 같다(`(JdbcTemplate, PlatformTransactionManager, Clock)`, `@Repository`).

**등록.** component scan 이 아니라 `CommonInfrastructureAutoConfiguration.kt:67-81` 의 `CommonJdbcConfiguration`(`@ConditionalOnBean(JdbcTemplate)`) `@Import` 목록에
`JdbcAccountPnlAlertStore::class`(`:74`) 바로 뒤로 `JdbcOwnerAlertOutbox::class` 를 추가해 등록한다. 실제 auto-configuration 으로 `OwnerAlertOutbox` bean 이
해석되는지는 테스트가 고정한다(dod AC7-i). API 도 같은 auto-configuration 을 쓰므로 bean 이 생기지만 소비자가 없어 무해하다(`JdbcAccountPnlAlertStore` 와 같다).

- `loadSignals`: `SELECT … FROM owner_alert_signal_state WHERE signal_key IN (…)`. 빈 입력이면 쿼리 없이 빈 map.
- `enqueue`: 입력 검증(중복 signalKey → `IllegalArgumentException("signal keys must be distinct.")`, 행 검증은 §4.4) → `TransactionTemplate`(REQUIRED)
  안에서 신호 순서대로 INSERT(version 0, `DuplicateKeyException` → 충돌) 또는 `UPDATE … WHERE signal_key = ? AND version = ?`(영향 0행 → 충돌).
  충돌이면 `setRollbackOnly()` 후 false — 앞서 쓴 신호도 되돌린다. 모두 성공하면 행 INSERT 후 true.
- 신호도 행도 없으면 트랜잭션을 열지 않고 true.

### 4.4 outbox INSERT 의 단일 소유자

`JdbcAccountPnlAlertStore` 의 `insertMail`·`validate`(`JdbcAccountPnlAlertStore.kt:298-327`)를 같은 패키지의 `internal object OwnerAlertOutboxRows` 로
**동작 그대로** 옮기고 두 store 가 그것을 호출한다. SQL 문자열·검증 메시지·`ON DUPLICATE KEY UPDATE id = id`·상수 값(eventKey ≤128, subject ≤255, body ≤65,535 bytes)
무변경. 회귀는 기존 `JdbcAccountPnlAlertStoreIntegrationTest`(무수정)가 지킨다.

```kotlin
internal object OwnerAlertOutboxRows {
    /** 값은 메시지에 넣지 않는다. */
    fun validate(mail: AccountPnlMail)

    /** 같은 (event_key, channel) 은 무시한다. 다른 제약 위반·오류는 그대로 던진다. 호출자 트랜잭션에 참여한다. */
    fun insert(jdbcTemplate: JdbcTemplate, mail: AccountPnlMail, createdAt: Instant)
}
```

## 5. 운영 신호 (Domain `io.premiumspread.domain.ownerops`)

### 5.1 신호와 identity

```kotlin
package io.premiumspread.domain.ownerops

/** 운영 신호 하나. [key] 는 owner_alert_signal_state.signal_key 이자 eventKey 의 일부다. */
sealed interface OpsSignal {
    val key: String
    val alertKind: OwnerAlertKind

    /** 환율 수집. key = "fx-ingestion.{base}-{quote}" (소문자) 예 fx-ingestion.usd-krw */
    data class FxIngestion(val base: Currency, val quote: Currency) : OpsSignal

    /** 거래소 시세 수집. key = "market-stream.{exchange}.{baseCode}-{currency}" (소문자) 예 market-stream.bithumb.btc-krw */
    data class MarketStream(val exchange: Exchange, val quote: Quote) : OpsSignal

    /** Job 연속 실패. key = "job-failures.{jobId.tag}" 예 job-failures.fx_ingestion */
    data class JobFailures(val jobId: JobId) : OpsSignal
}
```

`alertKind`: `FxIngestion` → `FX_INGESTION_DELAYED`, `MarketStream` → `MARKET_STREAM_DOWN`, `JobFailures` → `JOB_CONSECUTIVE_FAILURES`.
key 는 모두 `OwnerAlertSignalState.SIGNAL_KEY_PATTERN` 에 맞는다(Currency code·Exchange 이름·`JobId.tag` 는 영문·숫자·`_`).

**MarketPair identity.** 시세 신호의 identity 는 `(Exchange, Quote)` 다. 운영 신호 Job 은 `BatchMarketProvider.defaultMarket()` 의
`(pair.koreaExchange, koreaQuote)` 와 `(pair.foreignExchange, foreignQuote)` 두 개만 본다 — 기본값으로 `market-stream.bithumb.btc-krw`,
`market-stream.binance.btc-usd`. 확인된 사실: 바이낸스 USDT stream 은 수집 시 USD quote 로 정규화되고(`infrastructure/batch/.../exchange/MarketData.kt:41`),
`LatestMarketTickReaderAdapter.kt:18` 은 요청 quote 와 일치하는 tick 만 돌려준다 — 이 두 쌍으로 조회하면 실제 tick 이 나온다.
- 설정된 quote 의 tick 이 없으면 다른 quote 의 tick 으로 보정하지 않는다 — 없음으로 판정한다(architecture "요청 pair 와 payload pair 가 다르면 miss").
- Premium/Position/Notification identity 를 만들지 않는다. eventKey 와 상태 key 는 거래소 + quote 를 담는다.
- `batch.market` 을 바꿔 관측 대상에서 빠진 신호 행은 그대로 남는다(장애 중이었으면 복구 알림이 오지 않는다). runbook 에 확인·정리 SQL 을 둔다(§13).

감시하는 Job: `OwnerOpsSignalJob.MONITORED_JOBS = JobId.entries − {OWNER_OPS_SIGNAL, OWNER_ALERT_DELIVERY}`(K7), enum 선언 순서.
한 회차의 신호 순서: `FxIngestion` → `MarketStream`(korea, foreign) → `JobFailures`(MONITORED_JOBS 순서). 행·로그 순서가 이 순서다.

### 5.2 관측 (3상태)

```kotlin
/** 운영 신호의 이번 회차 관측. */
sealed interface OpsObservation {
    /** 장애 조건 충족. [since] = 마지막 정상 근거 시각(FX 마지막 성공·마지막 tick·Job 첫 실패), 없으면 null. [failureCount] 는 Job 만. */
    data class Down(val since: Instant?, val failureCount: Int? = null) : OpsObservation {
        init { require(failureCount == null || failureCount >= 1) { "failureCount must be at least 1." } }
    }

    /** 정상 근거가 있다. */
    data object Up : OpsObservation

    /** 판정할 근거가 아직 없다(재시작 직후 등). 장애를 열지도 닫지도 않는다. */
    data object Unknown : OpsObservation
}

/** 임계값. 기본값 = 사용자 합의 U2·U4. */
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

/** 관측 함수 — 순수. 시각은 모두 호출자가 준 [now](주입 Clock) 기준. */
object OpsSignals {
    fun fx(lastSuccessAt: Instant?, firstRunAt: Instant, now: Instant): OpsObservation
    fun market(tick: MarketTick?, firstRunAt: Instant, now: Instant, settings: OpsAlertSettings): OpsObservation
    fun job(streak: JobFailureStreak?, lastSuccessAt: Instant?, now: Instant, settings: OpsAlertSettings): OpsObservation
}
```

판정표 (전문, 위에서부터 먼저 맞는 행):

| 신호 | 조건 | 관측 |
|---|---|---|
| FX | `lastSuccessAt != null ∧ FxIngestionFreshness.isStale(lastSuccessAt, now)` | `Down(lastSuccessAt)` |
| FX | `lastSuccessAt != null` | `Up` |
| FX | `FxIngestionFreshness.isStale(firstRunAt, now)` | `Down(null)` |
| FX | 그 밖 | `Unknown` |
| 시세 | `tick != null ∧ age ≥ marketStaleAfter` (age = `now − tick.observedAt`, 음수면 0) | `Down(tick.observedAt)` |
| 시세 | `tick != null` | `Up` |
| 시세 | `now − firstRunAt ≥ marketStaleAfter` | `Down(null)` |
| 시세 | 그 밖 | `Unknown` |
| Job | `streak != null ∧ streak.consecutiveFailures ≥ jobFailureCount ∧ now − streak.firstFailureAt ≥ jobFailureDuration` | `Down(streak.firstFailureAt, streak.consecutiveFailures)` |
| Job | `lastSuccessAt != null` | `Up` |
| Job | 그 밖 | `Unknown` |

경계값 (시각은 모두 주입 `Clock` 의 벽시계 `Instant` — 스케줄러 단조 시계와 무관):

| 신호 | 미발동 | 발동 | 근거 |
|---|---|---|---|
| FX | 공백 정확히 30분 | 30분 + 1ns | `FxIngestionFreshness.isStale` 의 `>` 재사용(K-FX, 아래) |
| 시세 | 나이 5분 − 1ms | 나이 정확히 5분 | U2 "5분 이상" |
| Job | 3회·첫 실패 후 5분 − 1ms / 2회·5분 이상 | 3회 ∧ 정확히 5분 | U2 "3회 이상 AND 5분 이상" |
| 재알림 | 마지막 알림 후 6시간 − 1ms | 정확히 6시간 | U4 "6시간마다" |

**K-FX — `FxIngestionFreshness` 재사용.** 30분을 따로 두지 않고 `FxIngestionFreshness.isStale` 를 쓴다. 이 값은 수집 주기(≤ 절반)·FX 캐시 TTL(+5분 이상)과
테스트로 묶여 있어(`FxIngestionFreshness.kt:6-10`) 별도 상수를 두면 계약이 갈린다. PnL 모니터의 `STALE_FX` 판정과 같은 순간에 같은 답을 낸다.
그래서 FX 임계값은 env 로 바꿀 수 없다.

**Job `Up` 의 의미.** 연속 실패 카운터는 Success 에서만 0 으로 돌아가므로(§6) 같은 프로세스 안에서 "Down 이 아님 ∧ 마지막 성공 있음"은 장애 뒤
성공이 있었다는 뜻이다. 재시작 뒤에는 성공 전까지 `lastSuccessAt` 이 null 이라 `Unknown` 이다.

**`Unknown` 이 필요한 이유(K4).** 재시작 직후 FX 성공 기록·tick·카운터가 비어 있다. 이를 `Up` 으로 보면 DB 에 남은 장애가 가짜로 복구되고, `Down` 으로 보면
매 재시작이 장애가 된다. `firstRunAt`(이 Job 인스턴스의 첫 실행 시각) 기준 대기 시간이 지나도 근거가 없으면 그때 `Down` 이다(FX 30분·시세 5분).
Job 신호에는 이 대기 규칙이 없다 — 카운터가 비면 실패가 없었거나 재시작이다.

### 5.3 장애 정책 `OpsIncidentPolicy`

```kotlin
/** 이번 회차에 보낼 알림. [kind] 가 라우팅 종류다. */
sealed interface OpsNotice {
    val signal: OpsSignal
    val incidentStartedAt: Instant
    val at: Instant
    val kind: OwnerAlertKind

    /** 장애 알림. [sequence] 1 = 첫 알림, 2 이상 = 재알림. kind = signal.alertKind */
    data class Alert(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val sequence: Int,
        val observation: OpsObservation.Down,
    ) : OpsNotice

    /** 복구 알림. [notifyCount] = 장애 동안 보낸 알림 수. kind = OPS_RECOVERED */
    data class Recovered(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val notifyCount: Int,
    ) : OpsNotice
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

상태 전이표 (전문). `next` 의 `version` 은 항상 `current.version`(저장 시 조건으로 쓰인다), `alertKind` 는 `signal.alertKind`.

| # | current | 관측 | next.incident | notice |
|---|---|---|---|---|
| T1 | 정상 | `Down` | `(startedAt = now, lastNotifiedAt = now, notifyCount = 1)` | `Alert(sequence = 1)` |
| T2 | 정상 | `Up` / `Unknown` | — (next null) | 없음 |
| T3 | 장애 | `Down`, `now − lastNotifiedAt ≥ realertInterval` | `(startedAt 유지, lastNotifiedAt = now, notifyCount + 1)` | `Alert(sequence = notifyCount + 1)` |
| T4 | 장애 | `Down`, `now − lastNotifiedAt < realertInterval` | — | 없음 |
| T5 | 장애 | `Up` | `null`(정상) | `Recovered(notifyCount = current.notifyCount)` |
| T6 | 장애 | `Unknown` | — | 없음 |

- 복구 뒤 다시 `Down` 이면 T1 — 새 `startedAt` 이므로 새 eventKey(U4 "새 장애").
- 재알림 간격은 **마지막 알림 시각**에서 잰다(첫 알림에서 6h, 12h …). 재시작해도 DB 의 `last_notified_at` 이 이어진다.
- `incidentStartedAt` 은 감지 시각(now)이다. 장애가 실제로 시작된 근거 시각은 `Down.since` 로 본문에 따로 싣는다.

## 6. Job 연속 실패 카운터 (`domain.job` + `infrastructure:batch`)

### 6.1 port (전문, `domain/.../job/JobPorts.kt` 에 추가)

```kotlin
enum class JobId(val tag: String) {
    // … 기존 14개 그대로 …
    OWNER_ALERT_DELIVERY("owner_alert_delivery"),
    OWNER_OPS_SIGNAL("owner_ops_signal"),
}

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

### 6.2 구현 (`RedisJobRunRecorderAdapter`)

`RedisJobRunRecorderAdapter` 가 `JobFailureStreakReadPort` 를 추가로 구현한다(이미 `JobLastSuccessReadPort` 를 같은 방식으로 구현 — `:23-24`).
`ConcurrentHashMap<JobId, JobFailureStreak>` 을 `compute` 로 원자 갱신한다.

| `completed` outcome | 카운터 |
|---|---|
| `FAILED` | 없으면 `(1, at, at)`, 있으면 `(n + 1, first, maxOf(last, at))` |
| `SUCCEEDED` | 제거 |
| `SKIPPED` (lock 미획득·취소·version 충돌 등 모든 Skipped) | 무변경 |

- `last` 를 `maxOf(last, at)` 으로 두는 이유: 동시 완료가 역순으로 도착해도 `last` 가 first 보다 앞서거나 되감겨 불변식 예외가 나지 않게 한다.
- 갱신은 `completed` 의 **첫 문장**이다 — 뒤의 메트릭·Redis 쓰기가 예외를 던져도(Redis 장애 = 대표적인 연속 실패 원인) 카운트가 남는다.
- `JobExecutor.recordCompletion`(`JobExecutor.kt:186-199`)이 모든 결과 경로(lock 획득 예외·timeout·lock 상실·action 결과)를 `completed` 로 보내므로
  JobExecutor 는 바꾸지 않는다. timeout 은 Failure 로 센다.
- `at` 은 `JobExecutor` 의 주입 `Clock` 시각이다.
- Redis key·TTL·payload 무변경 → `modules:redis`·`docs/runbooks/redis-contract.md` 무변경.

**한계 (K5).** in-memory 다. batch 를 재시작하면 0 부터 다시 센다 — 재시작 직전까지 실패하던 Job 은 재시작 뒤 다시 3회·5분을 채워야 알린다(DB 에 남은
장애는 §5.2 `Unknown` 규칙으로 유지되어 복구가 가짜로 나가지 않는다). 다중 인스턴스면 인스턴스별로 센다(§1.2). DB 에 카운터를 두지 않는 이유: 1초 주기 Job
(`PREMIUM_REALTIME`·`*_TICKER_FLUSH`·`TRADE_PREPARATION_EVALUATION`)의 매 완료마다 DB 쓰기가 생기고, DB 장애 자체가 감시 대상이다.

### 6.3 자기 자신과 순환 (K7)

| 실패하는 것 | 이 단위의 처리 | 대신 알리는 경로 |
|---|---|---|
| `OwnerOpsSignalJob` 자신 | 감시 대상에서 뺀다. 이 Job 이 실패하면 판정·저장 자체가 안 된다 | Slack `BATCH_JOB_FAILED`(기존) |
| `OwnerAlertDeliveryJob` | 감시 대상에서 뺀다. 전달 Job 이 죽으면 그 실패 알림도 outbox 에 쌓일 뿐 나가지 않는다 | Slack `BATCH_JOB_FAILED`. PnL 모니터가 켜져 있으면 PnL 회차 안 디스패처가 같은 outbox 를 계속 비운다 |
| Redis 장애(모든 JobExecutor Job 이 lock 획득 실패) | 운영 신호 Job 도 lock 을 못 잡아 Failure — 장애 동안은 이 경로로 알릴 수 없다. Redis 가 돌아오면 각 Job 이 곧 성공해 카운터가 리셋되므로, 운영 신호 Job 이 그 전에 한 번 돌아 조건(3회 ∧ 5분)을 본 경우에만 장애·복구 알림이 늦게 간다 | Slack `BATCH_JOB_FAILED`(기존) |
| MySQL 장애 | 운영 신호 Job `loadSignals`/`enqueue` 예외 → Failure. 알림 저장 불가 | Slack `BATCH_JOB_FAILED` |

FX 수집 실패는 두 신호가 함께 볼 수 있다: `JOB_CONSECUTIVE_FAILURES(fx_ingestion)`(주기 5분 → 3번째 실패 = 약 10분) 와 `FX_INGESTION_DELAYED`(30분).
서로 다른 정보(원인 Job 의 실패 / 환율 값 신뢰 불가)이므로 둘 다 보낸다.

## 7. 전달 — `OwnerAlertDeliveryJob`

### 7.1 흐름

```
OwnerAlertDeliveryScheduler(60s) ──run()──▶ OwnerAlertDeliveryJob (JobExecutor: lock:owner-alert:delivery, 50s/40s)
   store.recoverStale(now, 5m) → OwnerAlertDispatcher(store, mail, discord, clock, "owner-alert-delivery:{uuid}").dispatch()
OwnerOpsSignalScheduler(30s) ──run()──▶ OwnerOpsSignalJob (JobExecutor: lock:owner-ops:signal, 20s/10s)
   관측 → OpsIncidentPolicy → OwnerOpsAlertContent → routing.resolve(kind) → OwnerAlertRows.expand
   → OwnerAlertOutbox.enqueue(changedSignals, rows)   [한 트랜잭션, DB-first]
AccountPnlMonitorScheduler(60s) → AccountPnlMonitorJob   [무변경 — 회차 안 전달 유지]
```

### 7.2 시그니처 (전문)

```kotlin
package io.premiumspread.application.notification.owneralert

/** owner 알림 outbox 독립 전달 1회분. PnL 모니터와 무관하게 돈다(#91 사용자 합의 U1(b), #90 재사용). */
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

- owner = `OWNER_PREFIX + UUID` (57자 ≤ 64, `JdbcAccountPnlAlertStore.MAX_OWNER_LENGTH`).
- 디스패처는 그대로 재사용 — claim concurrency 1, 회차 상한 10, 재시도 가능 실패 회차 끝 되돌림, claim 전 interrupt 확인(#88 §5.2 전이표 무변경).
- `STALE_CLAIM_AFTER` 는 PnL 모니터의 값(`AccountPnlMonitorJob.kt:363`)과 같은 5분. 두 Job 이 모두 `recoverStale` 을 불러도 조건부 UPDATE 라 한 행은 한 번만
  복구된다. 5분은 두 Job 의 timeout(40s)보다 충분히 길어 살아 있는 claim 을 빼앗지 않는다.

`JobResult` 분기 (전문):

| 상황 | 결과 |
|---|---|
| `recoverStale` 예외 | `Failure` (ERROR `Owner alert store failed during recoverStale`) |
| `recoverStale` > 0 | WARN `Recovered {} stale owner alert claims` 후 계속 |
| 디스패처 결과 | 그대로 반환 — 빈 outbox·발송 실패(재시도 가능/불가)·취소 = `Success`, `claim`/`mark*` 예외 = `Failure` (#88 §8) |

### 7.3 이중 발송이 없는 이유 (U1)

PnL 모니터가 켜져 있으면 PnL 회차 디스패처(owner `account-pnl-monitor:…`)와 전달 Job 디스패처가 같은 outbox 를 claim 한다.
- claim 은 `SELECT … WHERE status = 'PENDING' … FOR UPDATE SKIP LOCKED` 뒤 `UPDATE … WHERE id = ? AND status = 'PENDING'`(영향 1행 확인)이고
  REQUIRES_NEW 로 commit 한 뒤 돌려준다(`JdbcAccountPnlAlertStore.kt:89-108, 329-348`) — 한 행은 한 디스패처만 집는다.
- 모든 전이는 `claim_token` fencing 이다. 기존 동시 claim integration 테스트가 이를 고정하고, 이 단위의 E2E(dod AC12)가 두 디스패처 동시 회차에서
  행당 발송 1회를 확인한다.
- 발송은 기존처럼 at-least-once(발송 성공 후 `markSent` 전 crash 면 1회 더).

## 8. 운영 신호 Job — `OwnerOpsSignalJob`

### 8.1 시그니처 (전문)

```kotlin
package io.premiumspread.application.job.ownerops

/** owner 운영 신호(환율 수집·시세 수집·Job 연속 실패) 1회차 판정과 enqueue. 발송은 OwnerAlertDeliveryJob 이 한다. */
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
        /** 자기 자신과 전달 Job 은 감시하지 않는다 (design §6.3). */
        val MONITORED_JOBS: List<JobId> =
            JobId.entries.filterNot { it == JobId.OWNER_OPS_SIGNAL || it == JobId.OWNER_ALERT_DELIVERY }
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}
```

### 8.2 회차 순서

1. `now = clock.instant()`, `firstRunAt.compareAndSet(null, now)`(인스턴스 필드 `AtomicReference<Instant?>` — PnL 모니터와 같은 패턴).
2. `val market = market.defaultMarket()` — 예외 → `Failure`. 신호 목록(§5.1 순서)을 만든다: `FxIngestion(market.fxBase, market.fxQuote)`(FX 수집 Job 이 쓰는 것과 같은
   출처 — `FxIngestionJob.kt:29-30`), `MarketStream(market.pair.koreaExchange, market.koreaQuote)`, `MarketStream(market.pair.foreignExchange, market.foreignQuote)`,
   `MONITORED_JOBS.map(::JobFailures)`.
3. `outbox.loadSignals(keys)` — 예외 → `Failure`.
4. 신호마다 관측(§5.2). 관측 중 예외(port 가 던짐)는 그 신호만 `Unknown` 으로 보고 WARN 후 계속한다. **단 취소는 삼키지 않는다** — 예외의 원인 체인(자기 자신 포함,
   깊이 16)에 `InterruptedException`·`CancellationException` 이 있으면 interrupt 플래그를 복구하고 그 예외를 다시 던진다(`AccountPnlMonitorJob.kt:176-180` 과 같은 규칙.
   결과 없음, `JobExecutor` 로 전파 — 저장 없음).
5. 신호마다 `OpsIncidentPolicy.decide(signal, loaded[key] ?: initial(key, kind), observation, now, settings)`.
6. notice 마다 `OwnerAlertRows.expand(OwnerOpsAlertContent.mail(notice, settings, zone), routing.resolve(notice.kind)) { OwnerOpsAlertContent.discord(notice, settings, zone) }`.
7. 바뀐 상태가 없으면 쓰지 않고 `Success`.
8. interrupt 플래그가 서 있으면 `Skipped("cancelled")`(쓰지 않는다).
9. `outbox.enqueue(changed, rows)` — 예외 → `Failure`, false → `Skipped("state-version-conflict")`, true → `Success`.

`JobResult` 분기 (전문):

| 상황 | 결과 | 로그 |
|---|---|---|
| `defaultMarket`·`loadSignals` 예외 | `Failure` | ERROR `Owner ops signal store failed during {}` (operation = `defaultMarket`/`loadSignals`, 예외 stack) |
| 신호 관측 예외(취소 아님) | 그 신호 `Unknown`, 계속 | WARN `Owner ops signal observation failed: signal={}, error={}` (error = 예외 단순 클래스 이름) |
| 신호 관측 예외의 원인 체인에 `InterruptedException`·`CancellationException` | interrupt 플래그 복구 후 **예외를 다시 던진다**(결과 없음, 저장 없음) | 없음 |
| 바뀐 상태 없음 | `Success` | 없음 |
| 저장 전 interrupt | `Skipped("cancelled")` | WARN `Owner ops signal was cancelled before saving state` |
| `enqueue` 예외 | `Failure` | ERROR `Owner ops signal store failed during enqueue` |
| `enqueue` false | `Skipped("state-version-conflict")` | INFO `Skipped owner ops signal: signal state version changed by another run` |
| `enqueue` true | `Success` | notice 마다 INFO `Owner ops alert decided: signal={}, notice={}, sequence={}, eventKey={}` (notice = `alert`/`recovered`, sequence = Alert 의 sequence 또는 Recovered 의 notifyCount) |

- `Skipped` 는 연속 실패 카운터를 바꾸지 않고(§6.2), 이 Job 은 어차피 감시 대상이 아니다.
- version 충돌 뒤 다음 회차는 새로 load 해 같은 판정을 다시 내린다(첫 알림은 감지 시각이 바뀌어 eventKey 도 새 값이다 — 충돌 회차에서는 아무것도 쓰이지 않았으므로 중복이 없다).

### 8.3 Scheduler

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
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-alert-delivery.fixed-rate:60000}")
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

trigger 는 `ExchangeRateScheduler`(`ExchangeRateScheduler.kt:16` — yml 값은 `5m` 형식, fallback 은 ms, `BatchSchedulingProperties` 주입으로 기동 검증)와 같은 패턴이다.
판정은 주입 `Clock` 기준이라 단조 시계 지연(WSL 약 10%)은 주기만 늘린다.

### 8.4 lock·lease·timeout·주기 (전문)

| JobId | lock key | lease | timeout | 주기 | 주기 env |
|---|---|---|---|---|---|
| `OWNER_ALERT_DELIVERY` | `lock:owner-alert:delivery` | 50s | 40s | fixed-rate 60s | `OWNER_ALERT_DELIVERY_FIXED_RATE` |
| `OWNER_OPS_SIGNAL` | `lock:owner-ops:signal` | 20s | 10s | fixed-rate 30s | `OWNER_OPS_SIGNAL_FIXED_RATE` |

- 전달 timeout 40s 는 PnL 모니터와 같다(Discord 행당 최대 3s+429 경로, #88 A6). 넘으면 JobExecutor 가 interrupt 하고 남은 행은 PENDING(#88 §5.3).
- 운영 신호 Job 은 in-memory 조회 + DB 2문장이라 10s 면 충분하다. 30초 주기는 가장 짧은 임계값(5분)의 1/10 이다.
- `DefaultJobConfigProvider`(`JobConfig.kt:20-88`)와 `BatchJobProperties`(`:38-53`) 의 `when` 두 곳에 행을 추가하고, yml `batch.jobs` 에 env 3개씩
  (`OWNER_ALERT_DELIVERY_LOCK_KEY`/`_LEASE`/`_TIMEOUT`, `OWNER_OPS_SIGNAL_LOCK_KEY`/`_LEASE`/`_TIMEOUT`)을 기존 행 형식으로 둔다.
- Redis lock key 는 `JobConfig` 의 문자열일 뿐 key 패턴·TTL 계약(`modules:redis`)은 무변경이다 — 기존 `lock:*` 과 같은 `RedisJobLockAdapter` 경로.

## 9. 설정·기동 조건

### 9.1 env (전문, 새로 생기는 것)

| env | property | 기본 | 의미 |
|---|---|---|---|
| `OWNER_ALERT_OPS_ENABLED` | `owner-alert.ops.enabled` | `true` | 운영 신호 Job on/off. 빈 값 = true, 그 밖은 trim 후 `equalsIgnoreCase("true")` |
| `OWNER_ALERT_OPS_MARKET_STALE_AFTER` | `owner-alert.ops.market-stale-after` | `5m` | 시세 장애 나이(이상) |
| `OWNER_ALERT_OPS_JOB_FAILURE_COUNT` | `owner-alert.ops.job-failure-count` | `3` | Job 연속 실패 횟수(이상) |
| `OWNER_ALERT_OPS_JOB_FAILURE_DURATION` | `owner-alert.ops.job-failure-duration` | `5m` | 첫 실패 후 경과(이상) |
| `OWNER_ALERT_OPS_REALERT_INTERVAL` | `owner-alert.ops.realert-interval` | `6h` | 재알림 간격(이상) |
| `OWNER_ALERT_DELIVERY_FIXED_RATE` | `batch.scheduling.owner-alert-delivery.fixed-rate` | `60s` | 전달 Job 주기 |
| `OWNER_OPS_SIGNAL_FIXED_RATE` | `batch.scheduling.owner-ops-signal.fixed-rate` | `30s` | 운영 신호 Job 주기 |
| `OWNER_ALERT_DELIVERY_LOCK_KEY`·`_LEASE`·`_TIMEOUT` | `batch.jobs.owner-alert-delivery.*` | §8.4 | |
| `OWNER_OPS_SIGNAL_LOCK_KEY`·`_LEASE`·`_TIMEOUT` | `batch.jobs.owner-ops-signal.*` | §8.4 | |
| `OWNER_ALERT_{KIND}_EMAIL`·`_DISCORD`·`_PUSH` | `owner-alert.routes.{key}.*` | 빈 값 | `{KIND}` ∈ `FX_INGESTION_DELAYED`, `MARKET_STREAM_DOWN`, `JOB_CONSECUTIVE_FAILURES`, `OPS_RECOVERED` (12개) |

FX 임계값은 env 가 없다(K-FX). 채널 env(`OWNER_ALERT_EMAIL_ENABLED`·`OWNER_ALERT_DISCORD_*`·`ACCOUNT_PNL_ALERT_RECIPIENT`·SMTP)는 #88 그대로다.

`owner-alert.ops.*`(enabled 제외 4개)는 apps:batch `OwnerOpsAlertProperties`(`@ConfigurationProperties("owner-alert.ops")`, data class, `toSettings(): OpsAlertSettings`)가
바인딩한다. `enabled` 는 조건만 읽는다(빈 값을 Boolean 바인딩 실패로 만들지 않으려고 properties 에 두지 않는다). 같은 prefix 트리의 infra `OwnerAlertProperties` 는
constructor binding 의 unknown field 무시로 `ops` 를 읽지 않는다(확인된 사실). `toString` = 네 값(비밀 없음). 잘못된 값(`0s`, `0` 등)은
`OpsAlertSettings` init 메시지로 기동 실패.

### 9.2 기동 판정 (전문)

채널 준비(`ready`)는 #88 §7.2 규칙 그대로이며 infra `OwnerAlertChannelRequirements.ready(env)`(`OwnerAlertChannelRequirements.kt:63`)가 정본이다.

```
emailRequested    = owner-alert.email.enabled 가 비었거나 없으면 true, 아니면 trim 후 equalsIgnoreCase("true")
emailEffective    = emailRequested ∧ notification.email.enabled equalsIgnoreCase "true" (trim 없음)
discordRequested  = owner-alert.discord.enabled trim 후 equalsIgnoreCase "true"
webhookConfigured = webhook.pnl / .market / .ops 중 하나라도 공백 아님
discordEffective  = discordRequested ∧ webhookConfigured
recipientMissing  = emailEffective ∧ account-pnl-monitor.recipient 공백
discordMisconfigured = discordRequested ∧ ¬webhookConfigured
ready             = ¬recipientMissing ∧ ¬discordMisconfigured ∧ (emailEffective ∨ discordEffective)

opsEnabled        = owner-alert.ops.enabled 가 비었거나 없으면 true, 아니면 trim 후 equalsIgnoreCase("true")

채널 bean (infra OwnerAlertChannelConfiguration) ⇔ scheduling 켜짐 ∧ ready ∧ OwnerAlertChannelAvailableCondition(기존)
전달 Job·Scheduler                              ⇔ scheduling 켜짐 ∧ ready
운영 신호 Job·Scheduler                          ⇔ scheduling 켜짐 ∧ ready ∧ opsEnabled
PnL 모니터                                       ⇔ 기존 그대로 (그 조건이 참이면 ready 도 참이다 — AccountPnlCredentialsPresentCondition ⊇ ready)
```

app 은 infra 를 import 하지 않으므로 app `OwnerAlertDeliveryEnabledCondition` 이 같은 규칙을 다시 적는다(#88 이 PnL 조건에서 한 것과 같은 이중화).
일치는 wiring 행렬 테스트(dod AC11)가 고정한다. 기존 `AccountPnlMonitorEnabledCondition` 은 바꾸지 않는다 — 규칙 사본이 셋이 되는 부채는 별도 리팩터링 후보로 남긴다.

app 조건 로그 (className 가드 — 해당 구성 클래스 평가 때만, **env 이름과 boolean 만**, 위에서부터 먼저 맞는 행 하나):

| 조건 | 로그 (문구 전문) |
|---|---|
| `¬ready ∧ discordMisconfigured` — owner 가 Discord 를 켰는데 webhook 이 없음 (`OwnerAlertDeliveryConfiguration` 평가 때) | WARN `owner-alert channel not ready; owner alert delivery and ops alerts not started: missing={}, discordWebhookMissing=true` (`missing` = `[ACCOUNT_PNL_ALERT_RECIPIENT]` 또는 `[]`) |
| `¬ready ∧ recipientMissing` — SMTP 는 켜졌지만 owner 수신 주소가 없음 (같은 평가 때) | INFO `owner-alert email channel has no owner recipient; owner alert delivery and ops alerts not started: missing=[ACCOUNT_PNL_ALERT_RECIPIENT]` |
| `¬ready` 그 밖 (채널을 아무것도 켜지 않음 — 로컬 기본) | 없음 |
| `ready ∧ ¬opsEnabled` (`OwnerOpsAlertConfiguration` 평가 때) | INFO `owner-alert ops alerts disabled by OWNER_ALERT_OPS_ENABLED` |

수신 주소 누락이 WARN 이 아니라 INFO 인 이유(오케스트레이터 판정): SMTP(`NOTIFICATION_EMAIL_ENABLED`)는 회원 김프 구독 알림과 공유된다 — SMTP 를 켰다고 owner 알림을
의도한 것은 아니므로 정상 구성일 수 있다. runbook 에 적는다.

### 9.3 채널 bean 이동 (사용자 합의 U1(c))

`AccountPnlMonitorAdapterConfiguration.kt:170-214` 의 bean 3개(`accountPnlMailSender`·`ownerAlertDiscordSender`·`ownerAlertRouting`)와 `describeRoutes`,
`@EnableConfigurationProperties` 의 `OwnerAlertProperties` 를 **본문 그대로** 새 `infrastructure/batch/.../owneralert/OwnerAlertChannelConfiguration.kt` 로 옮긴다
(#88 §9 의 #90 계획과 같은 이동).

```kotlin
/** owner 알림 채널(메일·Discord) sender 와 라우팅. PnL 모니터와 독립이다 (owner-ops-alerts design §9.3). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${batch.scheduling.enabled:true} and \${scheduling.enabled:true}")
@Conditional(OwnerAlertChannelReadyCondition::class, OwnerAlertChannelAvailableCondition::class)
@EnableConfigurationProperties(OwnerAlertProperties::class)
class OwnerAlertChannelConfiguration { /* 옮긴 bean 3개 */ }

/** OwnerAlertChannelRequirements.ready(env). 로그 없음. */
class OwnerAlertChannelReadyCondition : Condition
```

- `BatchInfrastructureAutoConfiguration` 의 `@Import` 에 `OwnerAlertChannelConfiguration` 을 더한다(`EmailAutoConfiguration` 뒤에 평가되는 같은 auto-configuration).
- `AccountPnlMonitorAdapterConfiguration` 은 클래스 조건·나머지 bean(계정 조회 adapter 3개)·`AccountPnlMonitorProperties` 바인딩을 그대로 둔다.
- **바인딩·검증 시점 변화.** `OwnerAlertProperties` 는 이제 "PnL 모니터 구성이 켜질 때"가 아니라 "채널 구성이 켜질 때(scheduling ∧ ready)" 바인딩된다. 바인딩되면
  **Discord 켜짐 여부와 무관하게** webhook 3개의 형식(https)과 `OWNER_ALERT_*_EMAIL/_DISCORD/_PUSH` override 값을 검증하고, 틀리면 기동 실패다
  (`OwnerAlertProperties.kt:15`, `:82-118`; 기존 테스트 `:809-822`, `:824-828` 가 같은 규칙을 고정). 즉 PnL 을 끄고 메일 채널만 ready 여도 `http://` webhook 값이
  남아 있으면 기동하지 않는다. runbook 에 적는다(dod AC11-h).
- **wiring 테스트 영향(허용 예외 R8).** `AccountPnlMonitorWiringTest.kt:81-91` `assertNoBeans()` 의 `:89` `doesNotHaveBean(AccountPnlEmailSenderAdapter)` 는 이동 뒤
  "PnL 꺼짐 + 채널 ready"(`:98-101`, `:103-108`, `:337-361`, `:363-370`, `:372-388`, `:390-398`)에서 깨진다 — 메일 sender 는 이제 PnL 과 무관하게 ready 로 뜬다.
  R8 은 이 파일에서 (1) runner 에 `OwnerAlertChannelConfiguration::class` 추가, (2) `assertNoBeans()` 의 옮겨진 채널 bean 부재 단언(`:89` 및 같은 함수 안의 다른 채널 bean
  부재 단언)을 지우고 PnL 전용 bean 부재 단언(`:83-88`)만 남기는 것으로 한정한다. 대신 "채널 bean 은 PnL 과 무관하게 ready 조건으로만 뜬다"는 양성 단언을 새 테스트가
  추가한다(dod AC15). 이런 부재 단언은 이 파일 하나뿐이다(확인된 사실 — infrastructure/batch 테스트에는 없다).
- 메일 sender 가 없고(Discord 만) PnL 이 꺼진 기동에서도 `ownerAlertRouting` 은 bean 존재에서 채널 플래그를 만든다(#88 §7.2 그대로).

### 9.4 앱 배선 (`apps/batch/.../config/OwnerAlertDeliveryConfiguration.kt`)

```kotlin
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

/** §9.2 ready 규칙의 app 사본 + WARN/INFO. */
class OwnerAlertDeliveryEnabledCondition : Condition
/** OwnerAlertDeliveryEnabledCondition ∧ opsEnabled. */
class OwnerOpsAlertEnabledCondition : Condition
```

`OwnerAlertDeliveryJob` 이 `discordSender` 를 받지 않았는데 `routing.channels.discord` 가 참인 일은 없다 — 두 Job 이 같은 bean 존재에서 만들어진다.
확인된 사실: `EmailAutoConfiguration` 은 `notification.email.enabled=true` 일 때 `EmailSender` 를 등록한다 — app 조건(속성만 본다)과 infra 조건(bean 존재)이 같은 답을 낸다.

## 10. 알림 본문 (Domain `ownerops/OwnerOpsAlertContent`)

### 10.1 eventKey (전문)

| notice | eventKey |
|---|---|
| `Alert` | `owner-ops.{signal.key}.{incidentStartedAt.toEpochMilli()}.alert-{sequence}` |
| `Recovered` | `owner-ops.{signal.key}.{incidentStartedAt.toEpochMilli()}.recovered` |

- 예: `owner-ops.market-stream.bithumb.btc-krw.1767225600000.alert-1`. 최대 길이 = 10 + 64 + 1 + 13 + 1 + `alert-{n}`(≤ 16) < 128. 정규식 `[A-Za-z0-9._-]{1,128}`.
- 한 장애의 알림은 `(signal, startedAt, sequence)` 로, 복구는 `(signal, startedAt)` 으로 유일하다. 같은 eventKey 는 outbox `UNIQUE(event_key, channel)` 이 무시한다.
- 채널은 별도 컬럼이 식별한다(#88 §4.1) — EMAIL·DISCORD 행이 같은 eventKey.

### 10.2 템플릿 (exact copy)

- `{t(x)}` = `AccountPnlFormat.time(x, zone)`(메일, `yyyy-MM-dd HH:mm:ss (instant)`), `{st(x)}` = `AccountPnlFormat.shortTime(x, zone)`(Discord, `yyyy-MM-dd HH:mm z`).
  `AccountPnlFormat` 은 `internal object`(domain 모듈 안에서 공유 — `AccountPnlFormat.kt:13`)이므로 `ownerops` 에서 그대로 쓴다.
- `{d(x)}` = 임계값 표기: `x` 가 시간 단위로 나누어떨어지면 `{h}시간`, 분 단위면 `{m}분`, 아니면 `{초 내림}초`. 예 `30m` → `30분`, `6h` → `6시간`, `90s` → `90초`.
- `{e(x)}` = 경과 표기: 분 내림, `{h}시간 {m}분`(h ≥ 1) 또는 `{m}분`. 음수는 0. 예 `6h 0m 30s` → `6시간 0분`, `4m59s` → `4분`.
- `{market}` = `{exchange.name} {quote.baseCode}/{quote.currency.code}` 예 `BITHUMB BTC/KRW`. `{fx}` = `{base.code}/{quote.code}` 예 `USD/KRW`. `{job}` = `jobId.tag`.

headline (`{h}`):

| 신호 | headline |
|---|---|
| FX | `환율 수집 지연 {fx}` |
| 시세 | `시세 수집 끊김 {market}` |
| Job | `Job 연속 실패 {job}` |

제목 (메일 subject 와 Discord title):

| notice | 메일 subject | Discord title |
|---|---|---|
| `Alert`, sequence 1 | `[운영] {h}` | `⚠️ {h}` |
| `Alert`, sequence n ≥ 2 | `[운영] {h} (계속, {n}번째 알림)` | `⚠️ {h} (계속, {n}번째 알림)` |
| `Recovered` | `[운영] 복구 — {h}` | `✅ 복구 — {h}` |

장애 본문 (줄 단위 `\n` 연결, Discord 설명은 `{t}` 대신 `{st}`):

```
{상황}
{근거}
장애 감지: {t(incidentStartedAt)}
알림 시각: {t(at)}
{안내}
조치 안내: docs/runbooks/owner-ops-alert.md
```

| 신호 | `{상황}` | `{근거}` |
|---|---|---|
| FX | `환율({fx}) 수집이 {d(FxIngestionFreshness.MAX_INGESTION_GAP)} 넘게 성공하지 않았습니다.` | `마지막 수집 성공: {t(since)}` · since null 이면 `마지막 수집 성공: 기록 없음(이 batch 프로세스 시작 후 성공 없음)` |
| 시세 | `{market} 시세가 {d(settings.marketStaleAfter)} 이상 들어오지 않았습니다.` | `마지막 시세(거래소 시각): {t(since)}` · null 이면 `마지막 시세(거래소 시각): 기록 없음(이 batch 프로세스 시작 후 수신 없음)` |
| Job | `{job} Job 이 {failureCount}회 연속 실패했습니다 (첫 실패 후 {e(at − since)}).` | `첫 실패: {t(since)} · 기준: {settings.jobFailureCount}회 연속이고 {d(settings.jobFailureDuration)} 이상` |

`{안내}`: sequence 1 → `해소될 때까지 {d(settings.realertInterval)}마다 다시 알리고, 해소되면 한 번 알립니다.`,
sequence n ≥ 2 → `장애가 {e(at − incidentStartedAt)}째 이어지고 있습니다. {d(settings.realertInterval)}마다 다시 알립니다.`

복구 본문:

```
{h} 상태가 해소되었습니다.
장애 감지: {t(incidentStartedAt)}
복구 확인: {t(at)}
장애 동안 보낸 알림: {notifyCount}회
```

색: 장애 `ALERT_COLOR = 0xE67E22`(15105570), 복구 `RECOVERED_COLOR = 0x95A5A6`(9807270). Discord 는 `DiscordMessage.of` 로 절단한다(#88 §6.3).

```kotlin
object OwnerOpsAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-ops-alert.md"
    const val ALERT_COLOR = 0xE67E22
    const val RECOVERED_COLOR = 0x95A5A6

    fun eventKey(notice: OpsNotice): String
    /** EMAIL 행(channel 기본값). */
    fun mail(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): AccountPnlMail
    fun discord(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): DiscordMessage
}
```

### 10.3 담는 것과 담지 않는 것 (전문)

| 담는다 | 담지 않는다 |
|---|---|
| 신호 종류, 환율 통화쌍, 거래소 이름·quote, `JobId.tag`, 연속 실패 횟수, 시각(감지·알림·복구·마지막 성공·마지막 tick·첫 실패), 임계값, 알림 회차, runbook 경로 | webhook URL·token, 수신 주소, API key·secret, Job 예외 메시지·예외 클래스(예외 메시지는 요청 URL·key 를 담을 수 있다 — FX 클라이언트 등), WebSocket endpoint URL, 가격·환율 값, 계정 정보 |

로그도 같은 규칙이다(§8.2 키 전문). 상태·notice 객체를 통째로 로그에 넣지 않는다(자동 `toString` 은 비밀이 없지만 키 계약을 고정하려고 막는다).

## 11. 설계 완결성 체크리스트

| 항목 | 결정 |
|---|---|
| Facade 이름·주입 | **N/A** — batch 전용, REST 없음. 단일 진입: `OwnerAlertDeliveryScheduler → OwnerAlertDeliveryJob.run()`, `OwnerOpsSignalScheduler → OwnerOpsSignalJob.run()` |
| Domain port · 구현 모듈 | `OwnerAlertOutbox`(신규) ← infrastructure:common `JdbcOwnerAlertOutbox`(`CommonJdbcConfiguration` `@Import` 등록). `JobFailureStreakReadPort`(신규) ← infrastructure:batch `RedisJobRunRecorderAdapter`. 기존 재사용: `JobLastSuccessReadPort`(같은 adapter), `LatestMarketTickReadPort`(infra:batch ingestion), `BatchMarketProvider`, `AccountPnlAlertStore`·`AccountPnlMailSender`·`OwnerAlertDiscordSender`. `OwnerAlertOutbox` 는 #90 이 재사용할 경계이고 `JobFailureStreakReadPort` 는 Application 이 infrastructure 의 in-memory 맵을 몰라야 해서 port 다 |
| MarketPair identity | 시세 신호 identity = `(Exchange, Quote)`, `BatchMarket` 의 korea/foreign 두 쌍만(§5.1). 다른 quote 로 보정하지 않는다. Premium/Position identity 를 만들지 않는다 |
| 시간·범위 | 모든 시각 `Instant` + 주입 `Clock`. 표시 zone = `aggregation.zone`(기본 `Asia/Seoul`). 경계값 §5.2 표. 새 범위 질의 없음(범위 계약 N/A) |
| DB·cache 순서 | DB 만. 신호 상태 + outbox 행 = 한 트랜잭션(DB-first, §4.3). 외부 발송은 전달 Job 의 claim commit 뒤. cache 없음 |
| Flyway | 필요. `V21__create_owner_alert_signal_state.sql`(번호는 Task 0 `ls … \| sort -V \| tail -1` + 1), DDL §4.1, batch fixture 동기화(`IF NOT EXISTS`). 롤백 SQL 없음 |
| batch lock·lease·timeout·JobResult | §8.4 표, 분기 §7.2·§8.2 |
| 알림 event identity | owner 알림은 구독이 없다 — identity = `(event_key, channel)`, eventKey = 신호 key + 장애 감지 시각 + 회차/복구(§10.1). cooldown = 재알림 간격(정책 소유, §5.3). claim/fencing 경로 기존 그대로(§7.3) |
| 공개 endpoint | 변경 없음 — `PublicEndpointPolicy`·`docs/runbooks/auth-security.md` 무변경 |
| Redis key/TTL/payload | 변경 없음 — 새 lock key 문자열 2개는 기존 `lock:*` 경로의 `JobConfig` 값 |
| 테스트 전략 | §12 |

## 12. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| Domain | enum 4종 기본 경로·key, 신호 key 형식, 관측 판정표 전 행과 경계값(30분/+1ns, 5분−1ms/5분, 3회·5분), 정책 전이표 T1~T6·6시간 경계·복구 후 재발생, 렌더러 golden(장애·재알림·복구)·eventKey·금지 문자열, 상태 타입 불변식 | `domain` unit (고정 `Instant`, `ZoneId.of("Asia/Seoul")`) |
| adapter | V21 두 경로(빈 DB, V20→V21)·CHECK·version CHECK, `JdbcOwnerAlertOutbox` round-trip·INSERT/UPDATE·충돌 롤백(앞 신호 포함)·행만 enqueue·중복 무시·검증, enqueue 한 행을 `JdbcAccountPnlAlertStore.claim` 이 집음, 실제 auto-configuration 으로 `OwnerAlertOutbox` bean 해석 | `infrastructure:common` integration (MySQL Testcontainers) |
| adapter | 연속 실패 카운터 FAILED/SKIPPED/SUCCEEDED, Redis 쓰기 예외에도 카운트, 동시 갱신 | `infrastructure:batch` unit (Redis·MeterRegistry fake/mock) |
| application | 운영 신호 Job 의 신호 목록·순서·감시 제외 Job·quote·FX 통화쌍 출처, JobResult 분기 전부(관측 중 취소 재전파 포함), 라우팅(메일+Discord / `EMAIL_ONLY`), 재시작(새 인스턴스) 후 `Unknown`; 전달 Job 의 recoverStale·디스패처 위임·owner 접두사·JobResult | `apps:batch` unit (fake port·store·sender, `MutableClock` — fake 는 clock 기준 상대 시각) |
| wiring | 채널 bean 이 PnL 꺼짐에도 ready 로만 뜸(AC15), 조건 행렬(app = infra), WARN·INFO 문구, ops off, scheduling off, Discord 꺼짐에도 webhook·override 형식 검증, `OwnerOpsAlertProperties` 바인딩·실패, JobConfig 기본값·yml, yml routes key = enum key | `infrastructure:batch`·`apps:batch` unit (`ApplicationContextRunner`, `OutputCaptureExtension`) |
| E2E | 실제 MySQL fixture + 실제 store 2개 + 기록용 sender: FX 지연 → OPS 행 SENT, 6시간 재알림, 복구 SILENT, 재시작 지속, PnL 디스패처와 동시 claim 시 행당 발송 1회 | `apps:batch` integration |
| 경계 | 모듈·import·관측 cardinality·test isolation | root `architectureTest`(기존 규칙, 신규 규칙 없음) |
| 사람 | 실제 Discord ops 채널 수신·소리/무음 체감 | T4 owner |

## 13. 문서 갱신 대상

- **신규** `docs/runbooks/owner-ops-alert.md` — 신호 3종 판정 기준·경계(FX 30분 초과, 시세 5분 이상, Job 3회 ∧ 5분), 재알림 6시간·복구 1회·재발생=새 장애,
  기본 라우팅 표 4종과 **Discord 가 꺼져 있으면 4종 모두 메일로 온다**(굵게), override env 12개, `owner-alert.ops.*`·주기·lock env, 기동 판정·WARN/INFO 전문
  (수신 주소 누락은 SMTP 가 회원 알림과 공유되기 때문에 INFO 라는 것 포함), **채널 구성이 ready 면 Discord 켜짐 여부와 무관하게 webhook 형식과
  `OWNER_ALERT_*_EMAIL/_DISCORD/_PUSH` override 값을 검증해 틀리면 기동 실패**,
  재시작 한계(카운터 0 부터, `Unknown` 대기 FX 30분·시세 5분), 감시 제외 Job 과 Slack 대체 경로(§6.3), Slack `BATCH_JOB_FAILED` 와의 관계(U5),
  `batch.market` 변경 뒤 남은 신호 행 확인·정리 SQL(`SELECT signal_key, alert_kind, active, incident_started_at FROM owner_alert_signal_state;` 와
  `UPDATE owner_alert_signal_state SET active = FALSE, incident_started_at = NULL, last_notified_at = NULL, notify_count = 0, version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE signal_key = '<key>';`),
  V21 배포(additive, 롤백 SQL 없음), Discord 수신 체크리스트(dod AC14)
- `docs/runbooks/account-pnl-alert.md` — 채널 bean 이 PnL 모니터와 독립이 됐다는 것(PnL 을 꺼도 채널은 운영 알림용으로 뜬다), 위 검증 시점 변화(§9.3),
  기본 라우팅 표·override `{KIND}` 목록에 4종 추가, 전달 Job 이 같은 outbox 를 비운다는 것, owner 알림 공용 outbox 에 운영 행이 섞인다는 것
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — 실계정 알림 절 뒤에 운영 알림 흐름 한 단락(`OwnerOpsSignalJob` → `owner_alert_signal_state` + outbox → `OwnerAlertDeliveryJob`)
- `.ai/PROJECT_STATUS.md` — 모듈 표 `Flyway V1~V21`(`:13`), migration 표 V21 행, 기능 상태 한 항목
- env 예시: Task 0 이 `OWNER_ALERT_DISCORD_ENABLED` 가 나오는 env 예시 파일(`.env.example`·`deploy/`·`docker/`)을 찾아 같은 곳에 새 env 를 더한다. 없으면 runbook 만
- `docs/runbooks/redis-contract.md`·`auth-security.md`·`durable-notification-delivery.md` — 무변경(Redis·endpoint·`notification_delivery` 무관)
- `docs/runbooks/metrics-alerting.md`·`observability-readiness.md` — job tag 값 목록을 적어 둔 곳이 있으면 새 JobId 2개를 더한다(Task 0 grep)

## 14. 가정과 결정

### [ASSUMPTION]

| # | 가정 | 확인 방법·시점 |
|---|---|---|
| A1 | 운영 알림 기본 켜짐이 owner 의도와 맞다(채널이 준비된 배포에서 기동 즉시 감시 시작). 기존에 Discord/메일 채널을 켜 둔 환경은 배포 후 운영 알림을 받기 시작한다 | ⑦ owner 승인에서 확인 — 아니면 기본값 `false` 로 바꾼다(yml 한 줄·조건 한 줄·runbook) |
| A5 | PnL 모니터가 꺼졌을 때 `OwnerAlertProperties` 가 바인딩되지 않는다는 것을 단언하는 기존 테스트가 없다 | Task 0 grep. 있으면 멈추고 보고(허용 예외 여부를 Gate 1 에서 정한다) |
| A7 | 운영 신호 Job 30초 주기·전달 Job 60초 주기가 Redis lock·MySQL 부하에서 무시할 수준이다(회차당 SELECT 1 + 변화 있을 때만 트랜잭션 1, 전달은 빈 claim 1) | 운영 관찰 |

### 확인된 사실 (⑥ R1 에서 가정 → 사실)

| # | 사실 | 근거 |
|---|---|---|
| A2 | V18 `CREATE TABLE` 은 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` 를 붙인다 — V21 과 fixture 가 같은 옵션을 쓴다 | `V18__create_account_pnl_alert.sql:13`, `:30` |
| A3 | 바이낸스 USDT stream 은 USD quote 로 정규화되고, 최신 tick 조회는 quote 가 일치하는 것만 돌려준다 | `infrastructure/batch/.../exchange/MarketData.kt:41`, `LatestMarketTickReaderAdapter.kt:18` |
| A4 | infra `OwnerAlertProperties` 바인딩은 `owner-alert.ops.*` 를 읽지 않는다 | ⑥ R1 확인 |
| A6 | `EmailAutoConfiguration` 은 `notification.email.enabled=true` 면 `EmailSender` 를 등록한다 | ⑥ R1 확인 |

### [DECISION_NEEDED]

없음. 사용자 합의(U1~U5) 범위 안에서 설계가 정한 판단(K3 복구 종류 추가, K7 감시 제외, A1 기본 켜짐, 임계값 env 노출, FX 임계값 env 없음)은
⑦ 승인에서 함께 확인받는다. 수신 주소 누락 INFO(§9.2)는 오케스트레이터 판정이다.

## 15. 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | 최초 작성 | ④ |
| ⑥ R1 | §9.3 — R8 허용 예외에 `assertNoBeans()` 채널 bean 부재 단언 축소 추가, 양성 단언 AC15 | Critical 1 |
| ⑥ R1 | §1.1·§4.3·§11 — `JdbcOwnerAlertOutbox` 를 `CommonJdbcConfiguration` `@Import` 로 등록, auto-configuration 해석 테스트 | Critical 2 |
| ⑥ R1 | §9.3·§13 — 채널 ready 면 Discord 켜짐과 무관하게 webhook·override 검증 | Major 3 |
| ⑥ R1 | §8.2 2단계 — FX 신호 통화쌍 출처 `market.fxBase/fxQuote` | Minor 4 |
| ⑥ R1 | §8.2 4단계·분기표 — 관측 중 취소는 interrupt 복구 후 재던짐 | Minor 5 |
| ⑥ R1 | §4.1 — V21·fixture 테이블 옵션, fixture `IF NOT EXISTS` | Minor 7 |
| ⑥ R1 | §9.2·§13 — 수신 주소 누락은 INFO(오케스트레이터 판정), `discordMisconfigured` 명시 | Minor 8 |
| ⑥ R1 | §5.1·§9.1·§9.4·§14 — A2·A3·A4·A6 를 확인된 사실로 | 추가 확인 사실 |
| ⑨ 변경 요청 #1 | §7·§8.4 등 — 독립 전달 Job 주기 15s → 60s (lock 50s·timeout 40s 유지) | dod 변경 요청 #1 |
| ⑨ fix wave | §6.2 — FAILED 갱신을 `(n+1, first, maxOf(last, at))` 로 명시, 이유 한 줄 | 최종 리뷰 minor |
