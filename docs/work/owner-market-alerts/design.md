# owner 시장 알림 — 김프 급변·기준선 돌파·신고가/신저가·일일 요약 (issue #90, 상위 #87) 설계

- slug: `owner-market-alerts` · base `origin/dev` `4f8727c`(Task 0 확인) · branch `feat/issue-90-owner-market-alerts`
- 입력: issue #90 · ③ owner 합의(§0.1) · `docs/work/owner-alert-channel/design.md`(#88 §3.2 목표 표·§9 연결 경로) ·
  `docs/work/owner-ops-alerts/*`(#91 공용 기반) · `.ai/rules/{architecture,batch,testing,naming}.md` · `.claude/skills/module-layout`
- 상태: DRAFT (⑥ R1 반영 — §15)

## 0. 요약

batch 가 이미 수집·집계하는 김프 데이터(Redis current·DB/Redis 분·시간·일 집계)만으로 owner 에게 시장 알림 4종을 보낸다.
새 데이터 수집·거래소 API 호출은 없다. 실계정 모니터(#89)와 독립적으로 켜고 끈다.

| 알림 | 조건 (기본값) | `OwnerAlertKind` | 기본 경로 |
|---|---|---|---|
| 김프 급변 | 최근 10분 분봉 종가의 저점(고점) 대비 현재 김프가 +0.5%p 이상(−0.5%p 이하). 방향별 쿨다운 30분 | `PREMIUM_SWING` | MARKET · SOUND · 메일 false |
| 기준선 돌파 | owner 가 env 로 정한 상단 이상 진입 / 하단 이하 진입. 히스테리시스 0.1%p. 상단·하단 env 가 없으면 꺼짐 | `PREMIUM_THRESHOLD_CROSSED` | MARKET · SOUND · 메일 false |
| 신고가·신저가 | 현재 김프가 직전 1시간(24시간) 고점(저점)을 0.1%p 이상 넘음. 둘 다면 24시간만. 쿨다운 1h=30분·24h=2시간 | `PREMIUM_NEW_EXTREME` | MARKET · SILENT · 메일 false |
| 일일 요약 | 09:00 KST 이후 첫 회차에 하루 1회. 전일 김프 OHLC·평균, 환율, 실계정 순수익률 | `DAILY_SUMMARY` | MARKET · SILENT · 메일 true |

30초 주기 `OwnerMarketSignalJob` 하나가 4종을 판정하고, 상태(V22 `owner_market_alert_state`)와 outbox 행을 한 트랜잭션에 쓴다.
발송은 #91 의 `OwnerAlertDeliveryJob`(60초)이 한다. 실계정 순수익률은 PnL 모니터가 매 회차 V22 `account_pnl_latest` 에 남긴 값을 읽는다.

### 0.1 owner 합의 (③ — 이 문서가 바꾸지 않는다)

| # | 합의 |
|---|---|
| U1 | 요약의 실계정 순수익률: PnL 모니터(`AccountPnlMonitorJob`)가 매 회차 최신 순수익률(%·원)과 계산 시각을 저장 → 요약이 읽는다. 모니터 꺼짐/값이 오래됨이면 요약에 '없음'. 거래소 API 추가 호출 없음 |
| U2 | 신고가·신저가: 현재 김프가 직전 1시간(또는 24시간) 구간의 고점·저점을 최소 폭 이상 넘으면 알림. 1h/24h 각각 판정, 둘 다 해당하면 24h 하나만. 쿨다운 1h=30분, 24h=2시간. 모두 무음 |
| U3 | 기본 임계값(전부 env): 급변 10분 내 ±0.5%p·쿨다운 30분. 기준선은 상단/하단 env 설정 시에만 활성(기본 꺼짐)·히스테리시스 0.1%p. 신고가 최소 폭 0.1%p |
| U4 | 시장 알림 기본 켜짐(채널 ready 시), `OWNER_ALERT_MARKET_ENABLED=false` 로 끔. 기준선 돌파는 상단·하단 env 가 있어야 동작 |

### 0.2 핵심 결정

| # | 결정 | 절 | 오케스트레이터 방향과의 관계 |
|---|---|---|---|
| K1 | 범위 데이터는 **기존 Domain port `PremiumAggregationReadPort`** 로 읽는다. 구현은 `infrastructure:common` `JpaPremiumRepositoryAdapter` 가 추가로 맡아 기존 `findAggregationByPair`(Redis+DB 병합, DB 정본)를 그대로 쓴다. 새 port·새 Redis 읽기 경로 없음 | §3 | 추가 결정 |
| K2 | 기준 데이터는 **영속 집계(분·시간·일 bucket)** 다. 메모리 기준선이 없으므로 재시작이 가짜 알림을 만들지 않는다. 근거가 부족하면(현재값 없음·오래됨, bucket 80% 미만) 그 판정은 "모름"이고 상태도 바꾸지 않는다 | §4 | #91 Unknown 원칙 계승 |
| K3 | 시장 상태는 새 테이블 `owner_market_alert_state`(신호당 1행, 행별 version) + 새 port `OwnerMarketAlertStore`(`OwnerAlertOutbox` 와 같은 한 트랜잭션 계약). V21 `owner_alert_signal_state` 는 CHECK 상 쿨다운·구역·기준값·일자를 못 담는다 | §5 | 방향 그대로 |
| K4 | 실계정 최신값은 **별도 단일 행 테이블 `account_pnl_latest`** + port `AccountPnlLatestStore` 다. `account_pnl_monitor_state`·`AccountPnlMonitorState`·`AccountPnlAlertStore` 는 바꾸지 않는다 | §6 | U1 의 "상태"를 별도 테이블로 구현 — 이유 §6.1 |
| K5 | Job 은 `OwnerMarketSignalJob` 하나(30초). 일일 요약은 09:00 이후 첫 회차, 일자 키(`summary_date`)로 하루 1회 | §7 | 방향 그대로 |
| K6 | 대상은 batch 기본 pair 하나. 상태 key·eventKey 에 MarketPair(`btc-bithumb-binance`)를 넣는다 | §2.2 | 방향 그대로 |
| K7 | #88 §9 의 "outbox 4메서드 상위 interface 승격"은 **하지 않는다** | §1.2 | 비목표 — 이유 §1.2 |

## 1. 범위

### 1.1 포함

- Domain: `OwnerAlertKind` 4종, `domain.ownermarket`(key·설정·상태·port·관측 입력·정책 4종·렌더러), `domain.accountpnl` `AccountPnlLatest`·`AccountPnlLatestStore`, `JobId.OWNER_MARKET_SIGNAL`
- infrastructure:common: V22(테이블 2개), `JdbcOwnerMarketAlertStore`·`JdbcAccountPnlLatestStore`(`CommonJdbcConfiguration` `@Import`), `JpaPremiumRepositoryAdapter` 의 `PremiumAggregationReadPort` 구현
- apps:batch: `OwnerMarketSignalJob`·Scheduler, `AccountPnlMonitorJob` 의 최신값 기록(선택 생성자 인자), JobConfig·BatchJobProperties·BatchSchedulingProperties, 기동 조건·배선·`OwnerMarketAlertProperties`, `application.yml`(routes 4블록·`owner-alert.market`·jobs·scheduling)
- batch fixture 스키마(`batch-schema.sql`)에 V22 두 테이블
- 문서: 새 runbook `docs/runbooks/owner-market-alert.md`, `docs/runbooks/account-pnl-alert.md`, `docs/runbooks/owner-ops-alert.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`

### 1.2 제외 (scope creep 차단선)

- 새 데이터 수집, 거래소·FX API 추가 호출, Redis key·TTL·payload 변경(`modules:redis`·`docs/runbooks/redis-contract.md` 무변경), 새 메트릭
- 다중 pair 동시 감시(batch 는 실행 중 pair 하나 — `.ai/rules/batch.md`)
- `AccountPnlMonitorJob` 의 관측·정책·회차 안 전달·`AccountPnlMonitorState` 변경, `OwnerAlertDispatcher`·`OwnerAlertDeliveryJob` 동작 변경
- **outbox 4메서드 상위 interface 승격(K7).** #88 §9 는 #90 이 독립 전달 Job 을 만들 것을 전제로 이 일을 #90 몫으로 뒀다. 독립 전달 Job 은 #91 이 이미
  `AccountPnlAlertStore` 를 그대로 주입해 만들었고(`OwnerAlertDeliveryJob`), #90 은 enqueue 만 한다. 새 소비자가 없는 상위 interface 는
  "필요할 때만 interface" 규칙(CLAUDE.md Coding Guidelines 3)에 어긋나고, 동결 fake(`FakeOwnerAlertStore`·`FakeStore`)의 상속 구조를 건드릴 위험만 남는다.
- outbox 테이블·행 타입 rename(#88 §4.4 이름 부채 유지), 공개 endpoint(`PublicEndpointPolicy`·`docs/runbooks/auth-security.md` 무변경)
- 운영 알림(#91) 문구·임계값 변경. 단 새 JobId 가 #91 연속 실패 감시에 **자동 편입**되는 것은 포함(§7.5)
- 직전 단위 산출물(`docs/work/owner-alert-channel/**`·`owner-account-alerts/**`·`owner-ops-alerts/**`) 수정

## 2. 알림 종류·라우팅과 identity

### 2.1 `OwnerAlertKind` 4종 (전문, `OPS_RECOVERED` 뒤에 순서대로)

```kotlin
    PREMIUM_SWING(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))),
    PREMIUM_THRESHOLD_CROSSED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SOUND))),
    PREMIUM_NEW_EXTREME(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))),
    DAILY_SUMMARY(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT))),
```

| enum | key | Discord | 푸시 | 메일 |
|---|---|---|---|---|
| `PREMIUM_SWING` | `premium-swing` | `MARKET` | `SOUND` | false |
| `PREMIUM_THRESHOLD_CROSSED` | `premium-threshold-crossed` | `MARKET` | `SOUND` | false |
| `PREMIUM_NEW_EXTREME` | `premium-new-extreme` | `MARKET` | `SILENT` | false |
| `DAILY_SUMMARY` | `daily-summary` | `MARKET` | `SILENT` | true |

- enum 전체 `when` 금지(`OwnerAlertRouting.kt:18`)를 지킨다. 해석 규칙은 #88 §3.3 그대로다 — **Discord 가 꺼져 있으면 4종 모두 메일**(#88 D1). 급변 메일이
  최대 30분마다 올 수 있으므로 runbook 에 `OWNER_ALERT_PREMIUM_SWING_EMAIL=false` 예시를 둔다.
- market webhook(`OWNER_ALERT_DISCORD_WEBHOOK_MARKET`)이 비어 있으면 기존 fallback(`PNL → MARKET → OPS` 첫 설정 대상, `DiscordWebhookSenderAdapter.kt:22,46-49`)으로 간다.
- override env 12개(`OWNER_ALERT_{KIND}_EMAIL/_DISCORD/_PUSH`)는 yml `owner-alert.routes` 4블록으로 생긴다(§9.1).

### 2.2 MarketPair identity

- 대상 pair = `BatchMarketProvider.defaultMarket().pair` 하나. **pair key** = `"{symbol}-{koreaExchange}-{foreignExchange}".lowercase()`
  (기본 `btc-bithumb-binance`). 각 부분이 `[a-z0-9]+` 가 아니거나 pair key 가 40자를 넘으면 `IllegalArgumentException("market pair key must be [a-z0-9]+ parts within 40 chars.")`
  — Job 의 `defaultMarket` 단계 Failure 가 된다.
- 상태 key(§5)와 eventKey(§8.1)는 모두 pair key 를 담는다. `batch.market` 을 바꾸면 새 pair 의 상태 행이 새로 생기고 이전 행은 남는다(runbook 정리 SQL).
- 현재 김프: `PremiumReadPort.findLatest(pair)` 결과의 `pair` 가 요청 pair 와 다르면 **없음**으로 본다(다른 pair 로 보정하지 않음).
- 집계: `PremiumAggregationReadPort.read` 구현이 돌려줄 snapshot 중 하나라도 `pair` 가 요청과 다르면 `IllegalStateException("premium aggregation pair mismatch")`
  (값 없는 메시지)를 던진다. Job 은 그 원천을 "모름"으로 본다(§7.4). 부분 결과를 쓰지 않는다.
  **이 검사는 방어적 이중 확인이다** — 현재 두 원천은 모두 요청 pair 로 snapshot 을 만든다(`PremiumAggregationQueryRepository.kt:48-56` 은 pair 로 WHERE 후 `pair = pair`,
  `PremiumAggregationCacheReader.kt:45-82` 는 pair 의 key 를 읽고 `pair = pair`). 실제 경로에서는 일어나지 않으며 검증은 mock 으로만 한다(dod AC10(c)).

## 3. 김프 데이터 읽기 경로 (K1)

### 3.1 무엇을 어디서 읽나

| 용도 | port (Domain) | 구현 (모듈) | 원천 |
|---|---|---|---|
| 현재 김프 | `PremiumReadPort.findLatest(pair)` (기존) | `PremiumReadAdapter` (infra:batch, 기존 — `MarketSnapshotReadAdapters.kt:45`) | Redis `premium:{k}:{f}:{s}` current Hash (TTL 5s) |
| 분·시간·일 bucket | `PremiumAggregationReadPort.read(pair, window, unit)` (기존, 구현 없음 → 이번에 구현) | `JpaPremiumRepositoryAdapter` (infra:common, 기존 클래스에 interface 추가) | Redis `…:{minutes,hours,days}` ZSet + DB `premium_{minute,hour,day}` 병합 |
| 현재 환율 | `FxRateReadPort.findLatest(base, quote)` (기존) | `FxRateReadAdapter` (infra:batch, 기존) | Redis `fx:usd:krw` (TTL 40m) |
| 실계정 최신 | `AccountPnlLatestStore.find()` (신규 §6) | `JdbcAccountPnlLatestStore` (infra:common, 신규) | DB `account_pnl_latest` |

**`PremiumAggregationReadPort` 를 고른 이유.** (1) 이미 Domain 에 있는 범위 읽기 capability 다(`AggregationPorts.kt:47-49`) — pair·`AggregationWindow`(`[from,to)` + zone)·
`AggregationUnit` 을 받으므로 시간 계약이 타입에 들어 있다. (2) 기존 `PremiumRepository.findAggregationByPair(pair, "1m", from, to)` 는 JPA 저장소 전체(save·findById…)를
끌고 오고 interval 이 문자열이다. Job 이 그 넓은 port 에 의존할 이유가 없다. (3) 새 adapter 를 만들면 `JpaPremiumRepositoryAdapter.kt:70-99` 의
cache/DB 병합 규칙(Redis 부분 cache 가 DB bucket 을 가리지 않음·같은 시각은 DB 정본)을 복제해야 한다. 그래서 같은 클래스가 interface 를 하나 더 구현하고 기존
메서드에 위임한다. (4) seconds ZSet(5분)·history ZSet(1시간, 초 단위)은 24시간·10분 판정을 모두 덮지 못하고, history 는 fallback DB 가 없다.

### 3.2 `PremiumAggregationReadPort` 구현 (전문 계약)

```kotlin
// infrastructure/common/.../persistence/jpa/premium/JpaPremiumRepositoryAdapter.kt
class JpaPremiumRepositoryAdapter(/* 생성자 무변경 */) : PremiumRepository, PremiumAggregationReadPort {
    /**
     * [window] 의 `[from, to)` 를 [unit] interval("1m"/"1h"/"1d")로 [findAggregationByPair] 에 위임한다.
     * [window] 의 zone 은 쓰지 않는다 — DAY 의 날짜 변환은 infra `AggregationInfrastructureProperties.aggregationZone`
     * (= `aggregation.zone`)이 한다. 호출자는 같은 `aggregation.zone` 으로 창을 만들어야 한다.
     * 결과 snapshot 의 pair 가 하나라도 [pair] 와 다르면 IllegalStateException("premium aggregation pair mismatch")
     * — 방어적 이중 확인(현재 원천은 요청 pair 로만 snapshot 을 만든다, §2.2).
     */
    override fun read(pair: MarketPair, window: AggregationWindow, unit: AggregationUnit): List<PremiumAggregationSnapshot>
}
```

| `AggregationUnit` | interval | DB 열 | bucket `observedAt` |
|---|---|---|---|
| `MINUTE` | `1m` | `premium_minute.minute_at` (UTC) | 분 시작 |
| `HOUR` | `1h` | `premium_hour.hour_at` (UTC) | 시 시작 |
| `DAY` | `1d` | `premium_day.day_at` (DATE, `aggregation.zone`) | `day_at` 의 zone 자정 |

- cache→DB fallback·병합은 기존 메서드 안에 숨겨져 있다 — Application 은 hit/miss 를 모른다. Redis 손상 entry 가 하나라도 있으면 cache 결과 전체를 버리고(`PremiumAggregationCacheReader.kt:76-101`) DB 만 쓴다.
- DB 는 정본이고 항상 함께 읽힌다(`JpaPremiumRepositoryAdapter.kt:79`) — 회차당 DB 질의 2개(분·시간) + 하루 1개(일).
- `DAY` 는 DB 질의가 `from`/`to` 를 `AggregationInfrastructureProperties.aggregationZone` 의 날짜로 바꾼다(`PremiumAggregationQueryRepository.kt:33-42`). Job 의 zone 은
  `aggregation.zone` 이고 같은 값이다(Task 0 (g6) 확인). API 도 같은 auto-configuration 이라 bean 이 interface 를 하나 더 갖지만 소비자가 없다.
- batch 에서의 bean 존재: `CommonJpaConfiguration`(`CommonInfrastructureAutoConfiguration.kt:85-98`)이 `@ConditionalOnBean(EntityManagerFactory)` 로 `JpaPremiumRepositoryAdapter` 를 등록하고,
  batch 는 `jpa.yml` 을 import(`application.yml:9`)하며 infra:common → modules:jpa 로 EntityManagerFactory 가 있다(오케스트레이터 확인 — 로컬 batch 기동 실측).

## 4. 시간·범위 계약과 판정 입력

### 4.1 회차 시각과 창 (전문)

모든 시각은 주입 `Clock`(`Clock.systemUTC()` bean)의 `Instant` 다. `now = clock.instant()` 를 회차 시작에 한 번 읽는다. `zone` = `aggregation.zone`(기본 `Asia/Seoul`).
분·시 경계는 UTC `Instant.truncatedTo` 다(KST 는 정수 시간 offset 이라 시 경계가 같다). 일 경계만 zone 이다. 모든 창은 `[from, to)`.

```kotlin
package io.premiumspread.domain.ownermarket

/** 한 회차가 읽는 창. 모두 [from, to). */
data class MarketWindows(
    /** 분 bucket: [floorHour(now) − 1h, floorMinute(now)) — 60~119개. 급변·1h·24h 최근부 공용. */
    val minutes: AggregationWindow,
    /** 시 bucket: [floorHour(now) − 24h, floorHour(now) − 1h) — 23개. 24h 과거부. */
    val hours: AggregationWindow,
    /** 전일: [zone 오늘 00:00 − 1일, zone 오늘 00:00). 요약 전용. */
    val previousDay: AggregationWindow,
) {
    companion object {
        fun of(now: Instant, zone: ZoneId): MarketWindows
    }
}
```

| 판정 | 창 | 기대 bucket 수 |
|---|---|---|
| 급변 | `minutes` 중 `observedAt ≥ floorMinute(now) − swingWindow` | `swingWindow` 분 수 (기본 10) |
| 1h 고저 | `minutes` 중 `observedAt ≥ floorMinute(now) − 60m` | 60 |
| 24h 고저 | `minutes` 전체 ∪ `hours` 전체 (≈ 24h00m ~ 24h59m) | 분 `60 + minuteOfHour(now)`, 시 23 — **각각** 판정 |
| 일일 요약 | `previousDay` 의 `DAY` bucket 1개 (`observedAt == previousDay.from`) | — |

- 진행 중인 분(floorMinute(now) 이후)은 집계가 없으므로 기준에서 빠지고, 현재값이 그 분을 대표한다. 분 집계는 다음 분 0초 cron(`PremiumAggregationScheduler`, `0 * * * * *`)이
  쓰므로 분이 바뀐 직후 수 초 동안 직전 분 bucket 이 없을 수 있다 — 기준이 최대 약 2분 덜 최신이다(한계로 runbook 에 적는다).
- 24h 의 시 bucket 은 `floorHour − 1h` 에서 끝나므로 시 집계 cron(`5 0 * * * *`)의 지연과 무관하게 항상 이미 쓰인 bucket 이다.
- 전일 창: `previousDay.from = today.minusDays(1).atStartOfDay(zone)`, `to = today.atStartOfDay(zone)` (`today = now.atZone(zone).toLocalDate()`). 일 집계(`10 0 0 * * *`)의
  `AggregationWindowPolicy.previous` 와 같은 창이다. 요약 시각 하한을 01시로 둔 이유(§7.1 `summaryHour` 1..23): 00:00:10 일 집계 전에 요약이 확정되지 않게 한다.

### 4.2 근거 충분성 (K2, 전문)

```kotlin
/** 한 회차의 시장 관측. null = 그 원천을 모름(없음·오래됨·읽기 실패·pair 불일치). */
data class MarketObservation(
    val pair: MarketPair,
    val current: PremiumSnapshot?,
    val minutes: List<PremiumAggregationSnapshot>?,
    val hours: List<PremiumAggregationSnapshot>?,
)

object MarketEvidence {
    /** 현재값의 최대 나이 — 거래 준비 평가(`trade-preparation.evaluation.max-age`)와 같은 10초. */
    val CURRENT_MAX_AGE: Duration = Duration.ofSeconds(10)

    /** now − observedAt (음수면 0) ≤ CURRENT_MAX_AGE 이고 pair 가 같을 때만 현재값으로 쓴다. 아니면 null. */
    fun current(snapshot: PremiumSnapshot?, pair: MarketPair, now: Instant): PremiumSnapshot?

    /** [actual] ≥ ceil(expected × 0.8) 이면 충분. expected ≥ 1. */
    fun sufficient(actual: Int, expected: Int): Boolean
}
```

| 원천 | "모름" 조건 | 영향 |
|---|---|---|
| 현재 김프 | null, pair 다름, 나이 > 10s, 읽기 예외 | 급변·돌파·신고가 모두 판정 안 함(상태 무변경) |
| 분 bucket | 읽기 예외, pair 불일치 | 급변·1h·24h 판정 안 함 |
| 시 bucket | 읽기 예외, pair 불일치 (`hours == null`) | **24h 충분 = false** — 24h 판정 안 함, 1h 는 판정 |
| 창별 bucket 수 | 기대 수의 80% 미만(급변 10 → 8, 1h 60 → 48, 24h 시 23 → 19) | 그 창만 판정 안 함 |
| 요약 입력(일 bucket·환율·실계정) | 읽기 **예외** | 그 회차 요약 보류(다음 회차 재시도). 값이 **없음**(빈 결과·null)은 "없음"으로 표시하고 보낸다 |

**왜 80% 인가 (`[ASSUMPTION]` A2).** 신고가 판정에서 빠진 bucket 은 진짜 고점을 숨겨 **가짜 신고가**를 만든다. 급변은 빠질수록 폭이 줄어 보수적이지만 같은 규칙으로 둔다.
수집이 몇 분 끊긴 정도(24h 중 최대 4시간 40분)는 판정을 계속하고, 그 이상이면 쉰다. 상수이며 env 로 바꾸지 않는다.

## 5. 시장 상태 저장소 (K3)

### 5.1 상태 key (전문, `{pk}` = pair key)

| 신호 | signal key | `alert_kind` | 쓰는 열 |
|---|---|---|---|
| 급변 상승 / 하락 | `market.{pk}.swing-up` / `market.{pk}.swing-down` | `PREMIUM_SWING` | `last_notified_at` |
| 기준선 | `market.{pk}.threshold` | `PREMIUM_THRESHOLD_CROSSED` | `premium_zone`·`upper/lower/hysteresis_percent`·`last_notified_at` |
| 1h 신고가 / 신저가 | `market.{pk}.extreme-1h-high` / `…-1h-low` | `PREMIUM_NEW_EXTREME` | `last_notified_at` |
| 24h 신고가 / 신저가 | `market.{pk}.extreme-24h-high` / `…-24h-low` | `PREMIUM_NEW_EXTREME` | `last_notified_at` |
| 일일 요약 | `market.{pk}.daily-summary` | `DAILY_SUMMARY` | `summary_date`·`last_notified_at` |

기본 pair 에서 가장 긴 key `market.btc-bithumb-binance.extreme-24h-high` = 43자 ≤ 64. 기준선 env 가 모두 비면 `threshold` key 는 load·write 하지 않는다.

### 5.2 Domain 타입과 port (전문, `io.premiumspread.domain.ownermarket`)

```kotlin
enum class PremiumZone { ABOVE, BETWEEN, BELOW }

/** 기준선 구역을 정할 때 쓴 설정. 비교는 compareTo(scale 무관). upper·lower 중 하나 이상 non-null. */
data class ThresholdBaseline(val upper: BigDecimal?, val lower: BigDecimal?, val hysteresis: BigDecimal) {
    init {
        require(upper != null || lower != null) { "threshold baseline needs upper or lower." }
        require(hysteresis.signum() >= 0) { "hysteresis must not be negative." }
    }
    /** 세 값이 모두 compareTo == 0 (null 은 null 과만 같다). */
    fun sameAs(other: ThresholdBaseline): Boolean
}

/**
 * 시장 알림 신호 하나의 지속 상태 (owner_market_alert_state 한 행). [version] 0 = 아직 행이 없다.
 * zone·baseline 은 함께 있거나 함께 없다(기준선 신호만). summaryDate 는 일일 요약 신호만.
 */
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

/**
 * 시장 알림 상태 + outbox(account_pnl_mail) 행을 한 트랜잭션으로 쓴다 (DB-first). 계약은 OwnerAlertOutbox 와 같다.
 * 호출자 트랜잭션 밖에서 호출한다 — 구현이 자체 트랜잭션을 연다.
 */
interface OwnerMarketAlertStore {
    /** [signalKeys] 중 저장된 행만. 없는 key 는 map 에 없다. 빈 입력이면 질의 없이 빈 map. */
    fun load(signalKeys: Collection<String>): Map<String, MarketAlertState>

    /**
     * 한 트랜잭션: [states] 각각을 version 조건부로 쓰고(version 0 = INSERT, 그 밖 = `version = ?` UPDATE, 저장 후 +1)
     * 모두 성공하면 [rows] 를 넣는다. 같은 (eventKey, channel) 행은 무시한다. 하나라도 version 이 맞지 않으면 아무것도 쓰지 않고 false.
     * [states] 의 signalKey 는 서로 달라야 한다(아니면 IllegalArgumentException("signal keys must be distinct.")). 둘 다 비면 트랜잭션 없이 true.
     */
    fun enqueue(states: List<MarketAlertState>, rows: List<AccountPnlMail>): Boolean
}
```

- port 를 `ownermarket` 에 둔다 — `ownermarket → accountpnl`(행 타입 `AccountPnlMail`) 방향은 #91 `ownerops` 와 같고 역방향이 없다.
- `toString` 노출: signalKey·kind·시각·구역·기준값(%)·일자·version. 비밀·계정 값 없음.

### 5.3 V22 DDL (전문)

위치 `infrastructure/common/src/main/resources/db/migration/V22__create_owner_market_alert_state.sql`. 번호 = Task 0 의 `ls … | sort -V | tail -1`(예상 V21) + 1.

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

- 순수 `CREATE TABLE` 두 개 — `verifyMigrations` 금지 목록과 무관, 구 binary 호환(모르는 테이블), 롤백 SQL 없음. V12 등 기존 migration 무변경.
- 열 타입 근거: 김프 `DECIMAL(10,4)` = `premium_*` 집계와 같음(`PremiumPolicy.STORAGE_SCALE` 4). 그래서 기준선 설정값은 **scale ≤ 4·절댓값 < 10^6** 만 받는다(§7.1) — 그보다 정밀하면
  저장 시 반올림돼 `sameAs` 가 영원히 false 가 되고(H2 무한 재설정), 10^6 이상은 저장 오류다. `net_percent DECIMAL(12,2)` = `swing_baseline_percent` 와 같음(`AccountNetPnl` 의 `netPercent` scale 2).
  `net_krw` 는 원 단위 HALF_UP 반올림 값(표시와 같다). `BOOLEAN` 은 MySQL 에서 `tinyint(1)` 이다.
- batch fixture `apps/batch/src/integrationTest/resources/batch-schema.sql` 끝에 같은 DDL 을 `CREATE TABLE IF NOT EXISTS` 로 같은 테이블 옵션과 함께 추가한다(주석 `-- V22 와 같은 스키마`).

### 5.4 컬럼 ↔ Domain

| 컬럼 | Domain | 비고 |
|---|---|---|
| `signal_key` / `alert_kind` | `signalKey` / `alertKind.name` | 모르는 kind → `IllegalStateException("unknown owner alert kind in owner_market_alert_state")` |
| `last_notified_at` | `lastNotifiedAt` | |
| `premium_zone` | `zone?.name` | 모르는 값 → `IllegalStateException("unknown premium zone in owner_market_alert_state")` |
| `upper_percent`·`lower_percent`·`hysteresis_percent` | `baseline` | 셋 다 NULL ⇔ baseline null |
| `summary_date` | `summaryDate` | `java.sql.Date` ↔ `LocalDate` (zone 무관 날짜) |
| `version` | `version` | 저장 후 +1, INSERT 는 1 |
| `updated_at` | — | store 주입 `Clock` |

### 5.5 `JdbcOwnerMarketAlertStore` (infrastructure:common)

위치 `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/ownermarket/JdbcOwnerMarketAlertStore.kt`,
`@Repository`, 생성자 `(JdbcTemplate, PlatformTransactionManager, Clock)`. `CommonJdbcConfiguration` `@Import`(`CommonInfrastructureAutoConfiguration.kt:70-82`)의
`JdbcOwnerAlertOutbox::class` 바로 뒤에 추가한다. 동작은 `JdbcOwnerAlertOutbox` 와 같다: 입력 검증(중복 key, 행은 `OwnerAlertOutboxRows.validate`) →
`TransactionTemplate`(REQUIRED) 안에서 상태 순서대로 INSERT(version 0, `DuplicateKeyException` → 충돌) 또는 `UPDATE … WHERE signal_key = ? AND version = ?`(영향 0행 → 충돌)
→ 충돌이면 `setRollbackOnly()` 후 false → 모두 성공하면 `OwnerAlertOutboxRows.insert` 로 행 삽입 후 true. `OwnerAlertOutboxRows`(`internal object`, 같은 모듈)는 무변경으로 재사용한다.

## 6. 실계정 최신 순수익률 (K4)

### 6.1 왜 별도 테이블인가

U1 의 "PnL 모니터가 상태에 저장"을 `account_pnl_monitor_state` 열 추가가 아니라 별도 단일 행 테이블로 구현한다.
(1) `AccountPnlMonitorState` 는 동결 테스트 4개(`AccountAlertStateTest`·`AccountPnlMonitorJobTest`·`AccountPnlMonitorJobOwnerAlertTest`·`JdbcAccountPnlAlertStoreIntegrationTest`)가
생성자·`equals` 로 고정한 값이다(오케스트레이터 확인). 필드를 더하면 Computed 회차에 저장 상태가 달라져 기존 단언이 깨진다. (2) `AccountPnlAlertStore.save` 는 version 조건부라 충돌 회차에
최신값까지 버려진다 — 최신값은 알림 정확성과 무관한 read model 이다. (3) `JdbcAccountPnlAlertStore` SQL·동결 통합 테스트를 건드리지 않는다.
대가: 상태 저장과 최신값 기록이 한 트랜잭션이 아니다 — 최신값은 상태 저장 **성공 뒤**에만 쓰므로 "저장되지 않은 회차의 값"이 요약에 실리지 않는다.

### 6.2 Domain (전문, `io.premiumspread.domain.accountpnl`)

```kotlin
/**
 * PnL 모니터의 최신 관측 한 건 (account_pnl_latest). [position] false = 포지션 없음(금액 null).
 * 금액은 실계정 값이므로 toString 에 넣지 않는다.
 */
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
        /** netPercent = pnl.netPercent(scale 2 그대로), netKrw = pnl.netKrw 의 scale 0 HALF_UP, calculatedAt = pnl.calculatedAt. */
        fun computed(pnl: AccountNetPnl): AccountPnlLatest
        fun noPosition(calculatedAt: Instant): AccountPnlLatest = AccountPnlLatest(false, null, null, calculatedAt)
    }
}

/** 실계정 최신 순수익률 read model. PnL 모니터가 쓰고 시장 일일 요약이 읽는다. */
interface AccountPnlLatestStore {
    /** 단일 행 upsert(last write wins). */
    fun record(latest: AccountPnlLatest)

    /** 행이 없으면 null. */
    fun find(): AccountPnlLatest?
}
```

구현 `infrastructure/common/.../persistence/jdbc/accountpnl/JdbcAccountPnlLatestStore.kt`(`@Repository`, `(JdbcTemplate, Clock)`), `CommonJdbcConfiguration` `@Import` 에
`JdbcOwnerMarketAlertStore::class` 뒤로 등록. `record` = `INSERT INTO account_pnl_latest (id, has_position, net_percent, net_krw, calculated_at, updated_at) VALUES (1, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE has_position = ?, net_percent = ?, net_krw = ?, calculated_at = ?, updated_at = ?`(값 재바인딩, `VALUES()` 함수 미사용).
last-write-wins 인 이유: 쓰는 쪽은 lock 을 잡은 PnL 모니터 하나다. lock 상실로 두 회차가 겹치면 1분 이전 값이 남을 수 있으나 요약 표시에만 영향이 있다.

### 6.3 `AccountPnlMonitorJob` 변경 (범위 한정)

- 생성자 **끝에** `private val latestPnl: AccountPnlLatestStore? = null` 을 추가한다(기본 null = 기존 동작 — 동결 테스트 무수정 컴파일).
- `store.save(...)` 가 **true 를 돌려준 뒤, `deliver()` 전에** 관측별로 한 번 기록한다:

| 관측 | 기록 |
|---|---|
| `PnlObservation.Computed(pnl)` | `AccountPnlLatest.computed(pnl)` |
| `PnlObservation.NoPosition` | `AccountPnlLatest.noPosition(observed.round.observedAt)` — 성공 회차의 `AccountRound.observedAt` 은 관측 완료 시각 `completedAt` 이다(`AccountPnlMonitorJob.kt:169,173,191-197`). `Observed` 는 바꾸지 않는다 |
| `PnlObservation.Failed` | 기록 안 함 (이전 값이 늙어 요약에서 "없음"이 된다) |
| save false·save 예외·저장 전 취소 | 기록 안 함 (기존 분기 그대로) |

- `record` 예외(취소 아님)는 삼킨다: WARN `Account PnL latest record failed: error={}`(error = 예외 단순 클래스 이름, 메시지·값 없음) 후 `deliver()` 로 계속 — JobResult 무변경.
  취소(원인 체인에 `InterruptedException`·`CancellationException`)는 interrupt 플래그를 복구하고 **기록을 건너뛰고** `deliver()` 로 간다(dispatcher 가 claim 전 interrupt 를 확인한다 — #88 §5.2).
- 앱 배선 `AccountPnlMonitorConfiguration.accountPnlMonitorJob` 에 `latestPnl: ObjectProvider<AccountPnlLatestStore>` 인자를 더하고 `latestPnl = latestPnl.ifAvailable` 로 넘긴다
  (`ObjectProvider` 라서 기존 `AccountPnlMonitorWiringTest` runner 가 bean 을 주지 않아도 무수정 통과).
- 그 밖 관측·정책·로그·전달·상태는 무변경. 기존 테스트 영향: 없음(기본 null). 새 테스트 `AccountPnlMonitorLatestRecordTest` 가 위 표를 잰다.

## 7. 시장 신호 Job — `OwnerMarketSignalJob`

### 7.1 설정 (전문)

```kotlin
package io.premiumspread.domain.ownermarket

/** 기본값 = owner 합의 U2·U3. 단위: %p(김프 퍼센트 포인트). */
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
    init {
        require(swingWindow >= Duration.ofMinutes(1) && swingWindow <= Duration.ofMinutes(60) && swingWindow.toSecondsPart() == 0 &&
            swingWindow.toNanosPart() == 0) {
            "swingWindow must be whole minutes between 1m and 60m."
        }
        require(swingThreshold.signum() > 0) { "swingThreshold must be positive." }
        require(swingCooldown > Duration.ZERO) { "swingCooldown must be positive." }
        // owner_market_alert_state 의 DECIMAL(10,4) 에 그대로 저장되는 값만 받는다 (§5.3 — 반올림되면 sameAs 가 영원히 false)
        listOfNotNull(upperThreshold, lowerThreshold, thresholdHysteresis).forEach {
            require(it.scale() <= MAX_THRESHOLD_SCALE && it.abs() < MAX_THRESHOLD_ABS) {
                "threshold values must have at most 4 decimal places and an absolute value below 1000000."
            }
        }
        require(thresholdHysteresis.signum() >= 0) { "thresholdHysteresis must not be negative." }
        require(upperThreshold == null || lowerThreshold == null ||
            upperThreshold - lowerThreshold > thresholdHysteresis.multiply(BigDecimal(2))) {
            "upperThreshold must exceed lowerThreshold by more than twice the hysteresis."
        }
        require(extremeMinDelta.signum() > 0) { "extremeMinDelta must be positive." }
        require(extremeHourCooldown > Duration.ZERO) { "extremeHourCooldown must be positive." }
        require(extremeDayCooldown > Duration.ZERO) { "extremeDayCooldown must be positive." }
        require(summaryHour in 1..23) { "summaryHour must be between 1 and 23." }
        require(summaryPnlMaxAge > Duration.ZERO) { "summaryPnlMaxAge must be positive." }
    }

    /** upper·lower 가 모두 null 이면 null(기준선 꺼짐). */
    val thresholdBaseline: ThresholdBaseline?

    companion object {
        const val MAX_THRESHOLD_SCALE = 4
        val MAX_THRESHOLD_ABS: BigDecimal = BigDecimal("1000000")
    }
}
```

- `summaryHour` 는 1..23 이다 — 0 이면 일 집계 cron(`10 0 0 * * *`)이 전일 bucket 을 쓰기 전(00:00:00~00:00:10)에 요약이 "전일 집계 없음"으로 확정 발송된다(S4 는 재시도하지 않음).
- `toString` = 임계값·기간만(비밀 없음).

### 7.2 정책과 상태 전이표 (전문)

공통: 모든 정책은 순수 함수다(`object`). 입력 상태는 `load` 결과 또는 `MarketAlertState.initial`. 돌려주는 `next` 의 `version` 은 입력 `version`(저장 조건),
`alertKind`·`signalKey` 는 입력 그대로. 상태가 바뀌지 않으면 `next` 를 내지 않는다(쓰지 않는다). 쿨다운 경과 = `lastNotifiedAt == null ∨ now − lastNotifiedAt ≥ cooldown`
(경계 **이상** 이면 다시 알림; `lastNotifiedAt` 이 미래면 경과 안 함).

**상태 key 검사 (모든 정책 공통 규칙).** 각 정책은 입력 상태의 `signalKey` 가 `MarketKeys.*(MarketKeys.pairKey(pair), …)` 로 만든 기대 key 와 같은지 확인하고, 다르면
`IllegalArgumentException("market state key mismatch.")` 을 던진다. `pair` = 급변·기준선·신고가는 `observation.pair`, 요약은 `decide` 의 `pair` 인자. `due` 는 검사하지 않는다(판정만).

```kotlin
enum class MarketDirection { UP, DOWN }
enum class ExtremeWindow(val duration: Duration) { ONE_HOUR(Duration.ofHours(1)), ONE_DAY(Duration.ofHours(24)) }
enum class ThresholdLine { UPPER, LOWER }

/** 이번 회차에 보낼 시장 알림. [kind] 가 라우팅 종류. */
sealed interface MarketNotice {
    val pair: MarketPair
    val at: Instant
    val kind: OwnerAlertKind

    /** kind = PREMIUM_SWING. reference = 창 안 분봉 종가 저점(UP)·고점(DOWN). delta = current − reference (UP 양수, DOWN 음수). */
    data class Swing(
        override val pair: MarketPair, override val at: Instant,
        val direction: MarketDirection, val window: Duration,
        val reference: BigDecimal, val current: BigDecimal, val currentObservedAt: Instant, val delta: BigDecimal,
    ) : MarketNotice

    /** kind = PREMIUM_THRESHOLD_CROSSED. threshold = 넘은 기준선 값. */
    data class ThresholdCrossed(
        override val pair: MarketPair, override val at: Instant,
        val line: ThresholdLine, val threshold: BigDecimal, val hysteresis: BigDecimal,
        val current: BigDecimal, val currentObservedAt: Instant,
    ) : MarketNotice

    /** kind = PREMIUM_NEW_EXTREME. previous = 창의 직전 고점(UP)·저점(DOWN). */
    data class NewExtreme(
        override val pair: MarketPair, override val at: Instant,
        val window: ExtremeWindow, val direction: MarketDirection,
        val previous: BigDecimal, val current: BigDecimal, val currentObservedAt: Instant,
    ) : MarketNotice

    /**
     * kind = DAILY_SUMMARY. date = 대상일(전일). day null = 전일 집계 없음, fx null = 현재 환율 없음.
     * fxBase·fxQuote = 환율 통화쌍 표기 재료(batch.market fx-base/fx-quote) — fx 가 null 이어도 라벨을 낸다.
     */
    data class DailySummary(
        override val pair: MarketPair, override val at: Instant,
        val date: LocalDate, val day: PremiumAggregationSnapshot?,
        val fxBase: Currency, val fxQuote: Currency, val fx: ExchangeRateSnapshot?,
        val pnl: SummaryPnl,
    ) : MarketNotice
}

/** 요약의 실계정 줄. */
sealed interface SummaryPnl {
    data class Value(val netPercent: BigDecimal, val netKrw: BigDecimal, val calculatedAt: Instant) : SummaryPnl {
        override fun toString(): String = "Value(calculatedAt=$calculatedAt)"
    }
    data class NoPosition(val calculatedAt: Instant) : SummaryPnl
    /** 모니터 꺼짐·기록 없음·오래됨. */
    data object Unavailable : SummaryPnl
}

/** 정책 결과. next = 쓸 상태(빈 목록 = 쓰지 않음). */
data class MarketDecision(val next: List<MarketAlertState>, val notices: List<MarketNotice>) {
    companion object { val NONE: MarketDecision = MarketDecision(emptyList(), emptyList()) }
}
```

`Currency` = `io.premiumspread.domain.ticker.Currency`, `ExchangeRateSnapshot` = `io.premiumspread.domain.exchangerate.ExchangeRateSnapshot`.

#### (A) 급변 `PremiumSwingPolicy.decide(observation, up, down, settings, now): MarketDecision`

`ref` = `minutes` 중 `observedAt ≥ floorMinute(now) − swingWindow` 인 bucket 의 `close`. `low = min(ref)`, `high = max(ref)`. 방향마다 독립이다(같은 회차에 둘 다 날 수 있다 —
10분 안에 양방향으로 각각 0.5%p 이상 움직였다는 두 사실이 모두 참이다).

| # | 조건 (위에서부터) | next | notice |
|---|---|---|---|
| W1 | `current == null` ∨ `minutes == null` ∨ `¬sufficient(|ref|, swingWindow 분)` | — | 없음 |
| W2 | UP: `current − low ≥ swingThreshold` ∧ 쿨다운 경과(`up`) | `up.lastNotifiedAt = now` | `Swing(UP, reference = low, delta = current − low)` |
| W3 | UP: `current − low ≥ swingThreshold` ∧ 쿨다운 중 | — | 없음 |
| W4 | UP: `current − low < swingThreshold` | — | 없음 |
| W2′~W4′ | DOWN: 같은 규칙, `high − current ≥ swingThreshold`, `reference = high`, `delta = current − high`, 상태 `down` | | |

분봉 **종가**를 쓰는 이유(`[ASSUMPTION]` A3): 고가·저가는 1초 튀는 값 하나로 급변을 만든다. 종가는 분당 표본 하나라 더 보수적이다. 신고가는 반대로 고가·저가를 쓴다(아래).

#### (B) 기준선 `PremiumThresholdPolicy.decide(observation, state, settings, now): MarketDecision`

`settings.thresholdBaseline == null` 이면 호출하지 않는다(H0 — 호출하면 `IllegalArgumentException("threshold is disabled.")`). `U`·`L`·`h` = baseline 의 upper·lower·hysteresis. `rate` = `current.premiumRate`.

`classify(rate, prev)` (위에서부터 먼저 맞는 것):

1. `U != null ∧ rate ≥ U` → `ABOVE`
2. `L != null ∧ rate ≤ L` → `BELOW`
3. `prev == ABOVE ∧ rate > U − h` → `ABOVE` (U 아래로 h 이상 내려와야 벗어난다)
4. `prev == BELOW ∧ rate < L + h` → `BELOW`
5. 그 밖 → `BETWEEN`

| # | 조건 | next | notice |
|---|---|---|---|
| H1 | `current == null` | — | 없음 |
| H2 | 행 없음(version 0) ∨ `state.baseline` 없음 ∨ `¬state.baseline.sameAs(settings.thresholdBaseline)` | `zone = classify(rate, null)`, `baseline = settings`, `lastNotifiedAt` 유지 | **없음** (기준 재설정 — 이미 넘어 있어도 알리지 않는다) |
| H3 | `z′ = classify(rate, state.zone)` == `state.zone` | — | 없음 |
| H4 | `z′ == ABOVE` (이전 BETWEEN·BELOW) | `zone = ABOVE`, `lastNotifiedAt = now` | `ThresholdCrossed(UPPER, threshold = U)` |
| H5 | `z′ == BELOW` (이전 BETWEEN·ABOVE) | `zone = BELOW`, `lastNotifiedAt = now` | `ThresholdCrossed(LOWER, threshold = L)` |
| H6 | `z′ == BETWEEN` (이전 ABOVE·BELOW) | `zone = BETWEEN` | 없음 (복귀는 알리지 않는다 — `[ASSUMPTION]` A4) |

- 데이터 공백(H1) 동안 구역이 바뀌었으면 데이터가 돌아온 첫 회차에 H4/H5 로 알린다(늦었지만 실제 돌파다).
- 경계: `rate == U` → ABOVE(이상). ABOVE 에서 `rate == U − h` → BETWEEN(초과가 아니므로 벗어남). BELOW 대칭.
- H2 가 반복되지 않는 근거: 저장 열이 `DECIMAL(10,4)` 이고 설정값은 scale ≤ 4 만 받으므로(§7.1) 저장·읽기 후에도 `sameAs` 가 참이다.

#### (C) 신고가·신저가 `PremiumExtremePolicy.decide(observation, states: ExtremeStates, settings, now): MarketDecision`

```kotlin
data class ExtremeStates(val hourHigh: MarketAlertState, val hourLow: MarketAlertState, val dayHigh: MarketAlertState, val dayLow: MarketAlertState)
```

- 1h 기준: `minutes` 중 `observedAt ≥ floorMinute(now) − 60m`. 충분 = `sufficient(n, 60)`. `H1h = max(high)`, `L1h = min(low)`.
- 24h 기준: `minutes` 전체 ∪ `hours` 전체. 충분 = `hours != null ∧ sufficient(분 수, 60 + minuteOfHour(now)) ∧ sufficient(시 수, 23)` — **`hours == null` 이면 24h 충분 = false**. `H24 = max(high)`, `L24 = min(low)`.
- 신고가 조건(창 W): `current − H_W ≥ extremeMinDelta`. 신저가: `L_W − current ≥ extremeMinDelta`. 고가·저가 bucket 을 쓴다 — 튀는 값은 기준을 높여 알림을 줄이는 쪽이다.

방향마다(HIGH 기준, LOW 대칭):

| # | 조건 (위에서부터) | next | notice |
|---|---|---|---|
| E1 | `current == null` ∨ `minutes == null` | — | 없음 |
| E2 | 24h 충분 ∧ 24h 조건 ∧ 24h 쿨다운 경과(`extremeDayCooldown`) | `dayHigh.last = now` **그리고** `hourHigh.last = now` | `NewExtreme(ONE_DAY, UP, previous = H24)` |
| E3 | 24h 충분 ∧ 24h 조건 ∧ 24h 쿨다운 중 | — | 없음 (24h 조건이 참이면 1h 는 보지 않는다 — U2 "둘 다면 24h 하나만") |
| E4 | ¬(24h 충분 ∧ 24h 조건) ∧ 1h 충분 ∧ 1h 조건 ∧ 1h 쿨다운 경과(`extremeHourCooldown`) | `hourHigh.last = now` | `NewExtreme(ONE_HOUR, UP, previous = H1h)` |
| E5 | 그 밖 | — | 없음 |

- E2 에서 1h 쿨다운도 찍는 이유: 같은 분 안의 다음 회차(30초 뒤)는 기준에 그 분이 아직 없어 1h 조건이 여전히 참이다 — 24h 직후 1h 가 따라 울리지 않게 한다.
- 24h 가 근거 부족(¬충분, `hours == null` 포함)이면 1h 만 본다 — 24h 신고가가 1h 신고가로 나갈 수 있으나 참인 문장이다.
- 신고가 조건과 신저가 조건은 동시에 참일 수 없다(`H ≥ L`, `extremeMinDelta > 0`).

#### (D) 일일 요약 `DailySummaryPolicy`

```kotlin
object DailySummaryPolicy {
    /** 이번 회차가 요약을 보낼 차례인가. 입력 읽기 전에 부른다(차례가 아니면 요약 입력을 읽지 않는다). */
    fun due(state: MarketAlertState, settings: MarketAlertSettings, now: Instant, zone: ZoneId): Boolean

    /** due 가 참이고 입력 읽기가 모두 예외 없이 끝났을 때만 부른다. */
    fun decide(
        state: MarketAlertState, pair: MarketPair, input: DailySummaryInput,
        settings: MarketAlertSettings, now: Instant, zone: ZoneId,
    ): MarketDecision

    fun summaryPnl(pnl: AccountPnlLatest?, settings: MarketAlertSettings, now: Instant): SummaryPnl
}

/**
 * 읽기에 성공한 요약 입력. day null = 전일 bucket 없음, fx null = 환율 없음, pnl null = 최신값 행 없음.
 * fxBase·fxQuote = Job 이 batchMarket.fxBase·fxQuote 에서 채운다(notice 로 그대로 옮긴다).
 */
data class DailySummaryInput(
    val day: PremiumAggregationSnapshot?,
    val fxBase: Currency,
    val fxQuote: Currency,
    val fx: ExchangeRateSnapshot?,
    val pnl: AccountPnlLatest?,
)
```

`local = now.atZone(zone)`, `today = local.toLocalDate()`.

| # | 조건 | next | notice |
|---|---|---|---|
| S1 | `local.hour < summaryHour` | — | 없음 (`due` = false) |
| S2 | `state.summaryDate != null ∧ state.summaryDate ≥ today` | — | 없음 (`due` = false, 시계가 되감겨도 다시 보내지 않음) |
| S3 | 그 밖 (`due` = true) + 입력 읽기 예외 | — | 없음 (Job 이 `decide` 를 부르지 않는다 — 다음 회차 재시도) |
| S4 | `due` = true + 입력 읽기 성공 | `summaryDate = today`, `lastNotifiedAt = now` | `DailySummary(date = today − 1일, fxBase/fxQuote = input 값, …)` |

`SummaryPnl` 결정(`summaryPnl`): `pnl == null` → `Unavailable`; `now − pnl.calculatedAt > summaryPnlMaxAge` → `Unavailable`(정확히 maxAge 는 유효); `pnl.position` false → `NoPosition(calculatedAt)`;
그 밖 → `Value(netPercent, netKrw, calculatedAt)`. `calculatedAt` 이 미래면 나이 0.

- 첫 배포·장기 정지 후 기동: 오늘 `summaryHour` 이후면 그 날 요약을 즉시 1회 보낸다(그날 것만 — 지난 날짜는 소급하지 않는다).
- 재시작: `summary_date` 가 DB 에 있으므로 같은 날 다시 보내지 않는다. version 충돌로 저장이 실패하면 다음 회차가 다시 판정한다(eventKey 가 날짜라 중복 행도 없다).

### 7.3 시그니처 (전문)

```kotlin
package io.premiumspread.application.job.ownermarket

/** owner 시장 신호(급변·기준선·신고가·일일 요약) 1회차 판정과 enqueue. 발송은 OwnerAlertDeliveryJob 이 한다. */
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
    fun run(): JobResult

    companion object {
        const val CANCELLED = "cancelled"
        const val VERSION_CONFLICT = "state-version-conflict"
    }
}
```

`firstRunAt` 같은 인스턴스 기억은 없다 — 모든 기준이 영속 데이터다(K2).

### 7.4 회차 순서

1. `now = clock.instant()`.
2. `val batchMarket = market.defaultMarket()`, `pk = MarketKeys.pairKey(batchMarket.pair)`, 상태 key 목록(§5.1, 기준선 꺼짐이면 `threshold` 제외) — 예외 → `Failure`.
3. `store.load(keys)` — 예외 → `Failure`.
4. 관측(원천마다 독립, 예외는 그 원천만 null + WARN, **취소는 재전파** — 원인 체인(깊이 16)에 `InterruptedException`·`CancellationException` 이면 interrupt 복구 후 다시 던짐.
   `OwnerOpsSignalJob.kt:126-131` 과 같은 규칙): `current = MarketEvidence.current(premiums.findLatest(pair), pair, now)`,
   `minutes = aggregations.read(pair, windows.minutes, MINUTE)`, `hours = aggregations.read(pair, windows.hours, HOUR)`. `windows = MarketWindows.of(now, zone)`.
5. 정책: 급변 → 기준선(켜졌을 때) → 신고가 → 요약. 요약은 `DailySummaryPolicy.due` 가 참일 때만 입력을 읽는다:
   `day = aggregations.read(pair, windows.previousDay, DAY).firstOrNull { it.observedAt == windows.previousDay.from }`, `fx = fx.findLatest(batchMarket.fxBase, batchMarket.fxQuote)`,
   `pnl = pnlLatest.find()` → `DailySummaryInput(day, batchMarket.fxBase, batchMarket.fxQuote, fx, pnl)`. 셋 중 하나라도 예외(취소 아님)면 WARN 후 요약만 보류(S3).
6. notice 마다 `OwnerAlertRows.expand(OwnerMarketAlertContent.mail(notice, settings, zone), routing.resolve(notice.kind)) { OwnerMarketAlertContent.discord(notice, settings, zone) }`.
7. `next` 가 모두 비면 `Success`(쓰지 않음).
8. interrupt 플래그 → `Skipped("cancelled")`.
9. `store.enqueue(next, rows)` — 예외 `Failure`, false `Skipped("state-version-conflict")`, true → notice 마다 INFO 후 `Success`.

`JobResult` 분기 (전문):

| 상황 | 결과 | 로그 (키 전문) |
|---|---|---|
| `defaultMarket`·pair key·`load` 예외 | `Failure` | ERROR `Owner market signal store failed during {}` (operation = `defaultMarket`/`load`, 예외 stack) |
| 원천 읽기 예외(취소 아님) | 그 원천 null, 계속 | WARN `Owner market observation failed: source={}, error={}` (source ∈ `current`·`minutes`·`hours`·`day`·`fx`·`pnl`, error = 예외 단순 클래스 이름) |
| 원천 읽기 예외의 원인 체인에 취소 | interrupt 복구 후 **다시 던짐**(결과 없음, 저장 없음) | 없음 |
| 바뀐 상태 없음 | `Success` | 없음 |
| 저장 전 interrupt | `Skipped("cancelled")` | WARN `Owner market signal was cancelled before saving state` |
| `enqueue` 예외 | `Failure` | ERROR `Owner market signal store failed during enqueue` |
| `enqueue` false | `Skipped("state-version-conflict")` | INFO `Skipped owner market signal: signal state version changed by another run` |
| `enqueue` true | `Success` | notice 마다 INFO `Owner market alert decided: kind={}, eventKey={}` |

로그가 **담지 않는 값**: 김프·환율 수치, 실계정 순수익률·금액, webhook URL, 수신 주소, 예외 메시지, 상태·notice 객체 전체(`toString`).

### 7.5 lock·주기와 #91 연속 실패 감시 편입

| JobId | tag | lock key | lease | timeout | 주기 | env |
|---|---|---|---|---|---|---|
| `OWNER_MARKET_SIGNAL` | `owner_market_signal` | `lock:owner-market:signal` | 20s | 10s | fixed-rate 30s | `OWNER_MARKET_SIGNAL_LOCK_KEY`/`_LEASE`/`_TIMEOUT`, `OWNER_MARKET_SIGNAL_FIXED_RATE` |

- lease 20s > timeout 10s(`JobExecutor` 계약). 회차 비용: Redis current 1 + 분·시 읽기(각 Redis ZSet range + DB 질의, ≤ 119·23 행) + 상태 SELECT 1 + 변화 있을 때 트랜잭션 1. 10s 면 충분하다.
- 행을 추가하는 곳: `DefaultJobConfigProvider`(`application/common/JobConfig.kt:20` `when`), `BatchJobProperties`(필드·`:42` `when`) — JobId 전수 `when` 은 이 두 곳뿐(오케스트레이터 확인) —
  그리고 yml `batch.jobs.owner-market-signal`. 주기는 `BatchSchedulingProperties.ownerMarketSignal: FixedRate = FixedRate(Duration.ofSeconds(30))` + yml `batch.scheduling.owner-market-signal.fixed-rate`.
- **#91 편입.** `OwnerOpsSignalJob.MONITORED_JOBS = JobId.entries − {OWNER_OPS_SIGNAL, OWNER_ALERT_DELIVERY}` 이므로 `OWNER_MARKET_SIGNAL` 은 자동으로 감시 대상이 된다
  (연속 Failure 3회 ∧ 5분 → OPS `JOB_CONSECUTIVE_FAILURES`, 신호 key `job-failures.owner_market_signal` — `OpsSignal.kt:34` 의 `"job-failures.${jobId.tag.lowercase()}"`). 의도된 동작이다 — 시장 Job 의 실패는 운영 경로로 알리고 자기 경로로
  알리지 않는다(순환 없음). `Skipped`(충돌·취소)는 세지 않는다. 기존 #91 테스트는 `MONITORED_JOBS` 를 참조하므로 무수정 통과가 기대값이다(Task 0 (g4) 확인, 깨지면 멈추고 보고).
- 메트릭: 기존 `batch.job.run` 의 job tag 에 `owner_market_signal` 이 붙는 것뿐(bounded enum).

### 7.6 Scheduler

```kotlin
package io.premiumspread.interfaces.scheduling

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

09:00 판정을 cron 이 아니라 30초 fixed-rate 회차 안에서 하는 이유: cron 회차가 lock 미획득·실패로 빠지면 그날 요약이 사라진다. 회차 안 판정 + `summary_date` 는
놓친 회차를 다음 회차가 메운다(`UnrecoveredReminderPolicy` 의 시각 판정 + 일자 버킷과 같은 패턴). 시각은 주입 `Clock` 의 `Instant` 를 `zone` 으로 바꿔 판정한다.

## 8. 알림 본문 (Domain `ownermarket/OwnerMarketAlertContent`)

### 8.1 eventKey (전문)

| notice | eventKey |
|---|---|
| `Swing` | `owner-market.{pk}.swing-{up\|down}.{at.toEpochMilli()}` |
| `ThresholdCrossed` | `owner-market.{pk}.threshold-{upper\|lower}.{at.toEpochMilli()}` |
| `NewExtreme` | `owner-market.{pk}.extreme-{1h\|24h}-{high\|low}.{at.toEpochMilli()}` |
| `DailySummary` | `owner-market.{pk}.daily-summary.{date}` (`date` = 대상일 `yyyy-MM-dd`) |

- 예 `owner-market.btc-bithumb-binance.extreme-24h-high.1793232000000`(57자). 최대 = 13 + 40 + 18 + 13 < 128. 정규식 `[A-Za-z0-9._-]{1,128}`.
- identity = (pair, 신호, 방향/창, 알림 시각) — 쿨다운·구역 전이가 같은 회차의 같은 알림을 하나로 만든다. 요약은 대상일로 유일하다. outbox `UNIQUE(event_key, channel)` 이 중복을 무시한다.

### 8.2 서식

- `{p}` = `{symbol} {koreaExchange.name}/{foreignExchange.name}` 예 `BTC BITHUMB/BINANCE`.
- `{r(x)}` = `AccountPnlFormat.percent(x)` 예 `+2.35%`·`-0.40%`. `{pp(x)}` = `AccountPnlFormat.percentPoint(x)` 예 `+0.52%p`. `{v(x)}` = `AccountPnlFormat.plain(x)` 예 `0.5`.
- `{t(x)}` = `AccountPnlFormat.time(x, zone)`(메일), Discord 는 `{st(x)}` = `AccountPnlFormat.shortTime(x, zone)`.
- `{d(x)}` = `OwnerOpsAlertContent.threshold(x)` 를 **그대로 재사용**한다(`internal fun`, `OwnerOpsAlertContent.kt:115` — domain 모듈 안에서 공유 가능). 시간/분/초 표기(`30분`·`2시간`·`90초`).
- `{fx}` = `{notice.fxBase.code}/{notice.fxQuote.code}` 예 `USD/KRW` — `DailySummary` 의 `fxBase`·`fxQuote` 필드에서 만들므로 `fx == null` 이어도 라벨이 있다. `{rate(x)}` = `AccountPnlFormat.format("#,##0.00", x)` 예 `1,385.20`.
- `AccountPnlFormat` 은 domain 모듈 `internal object` — `ownermarket` 에서 그대로 쓴다.

### 8.3 제목 (메일 subject = `[시장] ` + 핵심, Discord title = 이모지 + 핵심)

| notice | 핵심 | Discord 이모지 | 색 |
|---|---|---|---|
| `Swing` UP | `김프 급등 {p} {pp(delta)} ({d(window)})` | `📈` | `UP_COLOR` |
| `Swing` DOWN | `김프 급락 {p} {pp(delta)} ({d(window)})` | `📉` | `DOWN_COLOR` |
| `ThresholdCrossed` UPPER | `김프 상단 기준선 돌파 {p} {r(current)} ≥ {r(threshold)}` | `🔺` | `UP_COLOR` |
| `ThresholdCrossed` LOWER | `김프 하단 기준선 이탈 {p} {r(current)} ≤ {r(threshold)}` | `🔻` | `DOWN_COLOR` |
| `NewExtreme` UP | `김프 {w} 신고가 {p} {r(current)}` (`{w}` = `1시간`·`24시간`) | `⬆️` | `UP_COLOR` |
| `NewExtreme` DOWN | `김프 {w} 신저가 {p} {r(current)}` | `⬇️` | `DOWN_COLOR` |
| `DailySummary` | `김프 일일 요약 {p} {date}` | `🗓️` | `SUMMARY_COLOR` |

색 상수: `UP_COLOR = 0xE74C3C`(15158332, 한국 시세 관례 상승 = 빨강), `DOWN_COLOR = 0x3498DB`(3447003), `SUMMARY_COLOR = 0x95A5A6`(9807270). Discord 는 `DiscordMessage.of` 로 절단(#88 §6.3).

### 8.4 본문 (줄 단위 `\n`, Discord 설명은 `{t}` 대신 `{st}`, 마지막 줄 공통 `조치 안내: docs/runbooks/owner-market-alert.md`)

급변:
```
현재 김프: {r(current)} ({t(currentObservedAt)})
최근 {d(window)} 분봉 종가 {저점|고점}: {r(reference)}
변화: {pp(delta)}
기준: {d(window)} 안에서 ±{v(swingThreshold)}%p 이상 · 같은 방향은 {d(swingCooldown)} 동안 다시 알리지 않습니다.
알림 시각: {t(at)}
```

기준선:
```
현재 김프: {r(current)} ({t(currentObservedAt)})
{상단|하단} 기준선: {r(threshold)}
기준선 안쪽으로 {v(hysteresis)}%p 이상 돌아온 뒤 다시 넘으면 다시 알립니다.
알림 시각: {t(at)}
```

신고가·신저가:
```
현재 김프: {r(current)} ({t(currentObservedAt)})
직전 {w} {고점|저점}: {r(previous)} (차이 {pp(current − previous)})
기준: 최소 {v(extremeMinDelta)}%p · 같은 창·방향은 {d(쿨다운)} 동안 다시 알리지 않습니다.
알림 시각: {t(at)}
```
(`{d(쿨다운)}` = 1h → `extremeHourCooldown`, 24h → `extremeDayCooldown`)

일일 요약:
```
대상일: {date} 00:00 ~ 24:00 ({zone.id})
김프 시가 {r(open)} · 고가 {r(high)} · 저가 {r(low)} · 종가 {r(close)} · 평균 {r(avg)}
환율({fx}) 전일 마감: {rate(day.fxRate)}
환율({fx}) 현재: {rate(fx.rate)} ({t(fx.observedAt)})
실계정 순수익률: {r(netPercent)} ({AccountPnlFormat.krw(netKrw)}) · 계산 {t(calculatedAt)}
요약 시각: {t(at)}
```

| 값 없음 | 대체 줄 |
|---|---|
| `day == null` | 2·3번째 줄 대신 `김프: 전일 집계 없음` 한 줄 |
| `day.fxRate == null` | `환율({fx}) 전일 마감: 없음` |
| `fx == null` | `환율({fx}) 현재: 없음` (`{fx}` 는 `fxBase`/`fxQuote` 에서 나오므로 예 `환율(USD/KRW) 현재: 없음`) |
| `SummaryPnl.NoPosition` | `실계정 순수익률: 포지션 없음 · 계산 {t(calculatedAt)}` |
| `SummaryPnl.Unavailable` | `실계정 순수익률: 없음 (PnL 모니터 꺼짐 또는 최근 {d(summaryPnlMaxAge)} 안에 계산 없음)` |

"전일 마감" 근거: 집계 `fxRate` 는 bucket 의 마지막 값이다(`PremiumCacheOperations.kt:264,294`, 오케스트레이터 확인).

```kotlin
object OwnerMarketAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-market-alert.md"
    const val UP_COLOR = 0xE74C3C
    const val DOWN_COLOR = 0x3498DB
    const val SUMMARY_COLOR = 0x95A5A6

    fun eventKey(notice: MarketNotice): String
    /** EMAIL 행(channel 기본값). */
    fun mail(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): AccountPnlMail
    fun discord(notice: MarketNotice, settings: MarketAlertSettings, zone: ZoneId): DiscordMessage
}
```

### 8.5 담는 것과 담지 않는 것 (전문)

| 담는다 | 담지 않는다 |
|---|---|
| pair(심볼·거래소 이름), 김프 수치(현재·기준·OHLC·평균), 기준선·임계값·쿨다운, 환율 통화쌍·환율(전일 마감·현재·관측 시각), **실계정 순수익률 %와 순손익 원(일일 요약만)**과 계산 시각, 시각, runbook 경로 | webhook URL·token, 수신 주소, API key·secret, 예외 메시지·클래스, 실계정 수량·평균가·청산가·수수료·펀딩·포지션 방향, 김프 원천 가격(빗썸·바이낸스 가격) |

- **실계정 금액 노출 범위.** 순수익률 %·순손익 원 두 값만, 일일 요약에만, MARKET 채널(또는 fallback 채널)과 메일에. 이 값은 이미 PNL 채널 정시 보고에 나가는 값이다.
  MARKET 채널을 다른 사람과 공유한다면 노출이므로 ⑦ owner 확인 항목(D1)이다. 로그에는 남기지 않는다(`AccountPnlLatest`·`SummaryPnl.Value` 의 `toString` 이 금액을 뺀다).

### 8.6 `data class` 자동 `toString` 이 노출하는 프로퍼티 (전문)

| 타입 | 노출 |
|---|---|
| `MarketAlertState` | signalKey·alertKind·lastNotifiedAt·zone·baseline(upper·lower·hysteresis %)·summaryDate·version |
| `ThresholdBaseline` | upper·lower·hysteresis |
| `MarketAlertSettings`·`OwnerMarketAlertProperties` | 임계값·기간·summaryHour (비밀 없음) |
| `MarketWindows`·`MarketObservation` | 창 시각·pair·김프 snapshot(김프·가격·환율 — 공개 시세) |
| `MarketNotice.Swing`·`ThresholdCrossed`·`NewExtreme` | pair·시각·방향/창/선·김프 값 |
| `MarketNotice.DailySummary` | pair·at·date·day snapshot·**fxBase·fxQuote**·fx snapshot·pnl(아래 마스킹 `toString`) |
| `DailySummaryInput` | day·fxBase·fxQuote·fx·pnl(마스킹) |
| `SummaryPnl.Value` | **override** — calculatedAt 만 |
| `SummaryPnl.NoPosition` | calculatedAt |
| `AccountPnlLatest` | **override** — position·calculatedAt 만 |
| `ExtremeStates`·`MarketDecision` | 위 타입들의 `toString` |

어느 타입도 webhook·주소·key 를 프로퍼티로 갖지 않는다. 그래도 Job 로그는 객체를 통째로 넣지 않는다(§7.4).

## 9. 설정·기동 조건

### 9.1 env 와 yml (전문, 새로 생기는 것)

`owner-alert.market` (yml, `owner-alert.ops` 블록 뒤):

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

routes (yml, `ops-recovered` 블록 뒤):

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

batch (yml):

```yaml
# batch.scheduling — owner-ops-signal 뒤
    owner-market-signal:
      fixed-rate: ${OWNER_MARKET_SIGNAL_FIXED_RATE:30s}
# batch.jobs — owner-ops-signal 뒤
    owner-market-signal: { lock-key: "${OWNER_MARKET_SIGNAL_LOCK_KEY:lock:owner-market:signal}", lease: "${OWNER_MARKET_SIGNAL_LEASE:20s}", execution-timeout: "${OWNER_MARKET_SIGNAL_TIMEOUT:10s}" }
```

env 전부: `OWNER_ALERT_MARKET_ENABLED`, `OWNER_ALERT_MARKET_SWING_WINDOW`, `_SWING_THRESHOLD`, `_SWING_COOLDOWN`, `_UPPER_THRESHOLD`, `_LOWER_THRESHOLD`, `_THRESHOLD_HYSTERESIS`,
`_EXTREME_MIN_DELTA`, `_EXTREME_HOUR_COOLDOWN`, `_EXTREME_DAY_COOLDOWN`, `_SUMMARY_HOUR`, `_SUMMARY_PNL_MAX_AGE`(`OWNER_ALERT_MARKET_` 접두 12개), `OWNER_MARKET_SIGNAL_FIXED_RATE`,
`OWNER_MARKET_SIGNAL_LOCK_KEY`·`_LEASE`·`_TIMEOUT`, `OWNER_ALERT_{PREMIUM_SWING|PREMIUM_THRESHOLD_CROSSED|PREMIUM_NEW_EXTREME|DAILY_SUMMARY}_{EMAIL|DISCORD|PUSH}`(12개). 채널 env 는 #88 그대로.
기준선 env 는 소수 넷째 자리까지(`3.1234` 가능, `3.12345` 는 기동 실패), `OWNER_ALERT_MARKET_SUMMARY_HOUR` 는 1~23.

`OwnerMarketAlertProperties` (apps:batch `config`, 전문):

```kotlin
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
    fun toSettings(): MarketAlertSettings
}
```

- `enabled` 는 조건만 읽는다(#91 과 같은 이유). 빈 `upper-threshold` 는 `BigDecimal?` null 로 바인딩된다(`[ASSUMPTION]` A5 — Task 14 바인딩 테스트가 고정).
- 잘못된 값은 `MarketAlertSettings` init 메시지로 기동 실패. infra `OwnerAlertProperties` 는 `owner-alert.market` 을 읽지 않는다(#91 A4 와 같은 unknown field 무시 — Task 14 확인).

### 9.2 기동 판정

```
marketEnabled = owner-alert.market.enabled 가 비었거나 없으면 true, 아니면 trim 후 equalsIgnoreCase("true")
시장 신호 Job·Scheduler ⇔ scheduling 켜짐 ∧ ready(#91 §9.2 규칙 그대로) ∧ marketEnabled
```

- 새 조건 `OwnerMarketAlertEnabledCondition`(apps:batch `config/OwnerMarketAlertConfiguration.kt`)이 `OwnerAlertReadiness.ready(env, logging = false)` ∧ `marketEnabled` 를 본다.
  `OwnerAlertReadiness` 는 `OwnerAlertDeliveryConfiguration.kt:66` 의 `private object` 다 — **`internal object` 로만 바꾼다**(본문 무변경).
- 로그: `ready ∧ ¬marketEnabled` 이고 `OwnerMarketAlertConfiguration` 평가 때만 INFO `owner-alert market alerts disabled by OWNER_ALERT_MARKET_ENABLED`.
  `¬ready` 의 WARN/INFO 는 기존 전달 조건이 이미 한 번 낸다(문구 `… owner alert delivery and ops alerts not started …` 는 동결 wiring 테스트가 고정하므로 바꾸지 않는다 — runbook 에 "시장 알림도 같은 조건으로 시작되지 않는다"를 적는다).
- 기준선: `upper`·`lower` 가 모두 비면 기준선 판정·상태 key 를 건너뛴다(U4). 다른 3종은 그대로 동작한다.
- PnL 모니터 꺼짐과 무관하다. `AccountPnlLatestStore` bean 은 JDBC 가 있으면 항상 있다(값이 없으면 요약에 "없음").

### 9.3 앱 배선 (전문)

```kotlin
package io.premiumspread.config

/** OwnerAlertDeliveryEnabledCondition 의 ready ∧ owner-alert.market.enabled(빈 값 = true). */
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
}
```

## 10. DB·캐시 순서와 트랜잭션

| 쓰기 | 순서 |
|---|---|
| 시장 상태 + outbox 행 | 한 DB 트랜잭션(`JdbcOwnerMarketAlertStore.enqueue`, DB-first). 캐시 쓰기 없음 |
| 실계정 최신값 | PnL 상태 저장 commit 뒤 별도 upsert(비필수 read model, DB 만) |
| 외부 발송 | 전달 Job 의 claim commit 뒤(#88·#91 그대로) |

읽기 쪽 cache→DB 병합은 `JpaPremiumRepositoryAdapter` 안에 숨는다(§3.2). Redis key·TTL·payload 무변경 — `modules:redis`·`docs/runbooks/redis-contract.md` 무변경.

## 11. 설계 완결성 체크리스트

| 항목 | 결정 |
|---|---|
| Facade 이름·주입 | **N/A** — batch 전용, REST 없음. 단일 진입 `OwnerMarketSignalScheduler → OwnerMarketSignalJob.run()` |
| Domain port · 구현 모듈 | 신규 `OwnerMarketAlertStore` ← infra:common `JdbcOwnerMarketAlertStore`; 신규 `AccountPnlLatestStore` ← infra:common `JdbcAccountPnlLatestStore`; 기존(구현 없던) `PremiumAggregationReadPort` ← infra:common `JpaPremiumRepositoryAdapter`. 재사용 `PremiumReadPort`·`FxRateReadPort`(infra:batch), `BatchMarketProvider`, `OwnerAlertRouting`. 두 신규 port 는 Application 이 JDBC 를 몰라야 해서 port 다 |
| MarketPair identity | §2.2 — pair key 가 상태 key·eventKey 에 있다. current pair 불일치 = 없음, 집계 pair 불일치 = 예외(모름, 방어적 이중 확인). 보정 없음 |
| 시간·범위 | §4.1 — `Instant` + 주입 Clock, 분·시 UTC, 일 = `aggregation.zone` `[전일 00:00, 당일 00:00)`, 모든 창 `[from,to)`, 09:00 은 회차 안 판정 |
| DB·cache 순서 | §10 — DB-first 한 트랜잭션, cache 쓰기 없음 |
| Flyway | 필요. V22(테이블 2개, §5.3 전문), fixture 동기화(`IF NOT EXISTS`). 번호는 Task 0 이 `ls … \| sort -V \| tail -1` + 1 로 확정. 롤백 SQL 없음 |
| batch lock·lease·timeout·JobResult | §7.5 표, 분기 §7.4 |
| 알림 event identity | owner 알림은 구독이 없다 — identity = `(event_key, channel)`, eventKey = pair key + 신호·방향·창 + 알림 시각/대상일(§8.1). cooldown = 정책 소유(§7.2). claim/fencing 경로 기존 그대로 |
| 공개 endpoint | 변경 없음 — `PublicEndpointPolicy`·`docs/runbooks/auth-security.md` 무변경 |
| Redis key/TTL/payload | 변경 없음 — 새 lock key 문자열 1개는 기존 `lock:*` 경로의 `JobConfig` 값 |
| 테스트 전략 | §12 |

## 12. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| Domain | enum 4종 경로·key, pair key 규칙, 설정 기본값·검증(기준선 scale 4·10^6, summaryHour 1..23), 상태 불변식, 창 계산(분·시·전일 KST), 근거 충분성 80% 경계, 정책 전이표 W·H·E·S 전 행과 경계값(0.5%p 정확히, 쿨다운 정확히, `rate == U`, `U − h`, 09:00:00 vs 08:59:59, maxAge 정확히), 상태 key 검사, 렌더러 golden·eventKey·금지 문자열, `AccountPnlLatest` 불변식·`toString` | `domain` unit (고정 `Instant`, `ZoneId.of("Asia/Seoul")`) |
| adapter | V22 두 경로(빈 DB, V21→V22)·CHECK, `JdbcOwnerMarketAlertStore` round-trip·충돌 롤백·행만/상태만·중복 무시·검증·claim 호환, `JdbcAccountPnlLatestStore` upsert·CHECK, auto-configuration bean 해석 | `infrastructure:common` integration (MySQL) |
| adapter | `PremiumAggregationReadPort` unit→interval·창 위임·병합 규칙 위임·pair 불일치 예외(실제 경로에서 불가능 — **mock 으로만** 검증하는 방어 코드) | `infrastructure:common` unit (MockK) |
| application | 시장 Job 의 4종 판정·라우팅·JobResult 분기 전부·원천별 모름·취소 재전파·요약 보류/재시도·재시작(새 인스턴스) 무알림; PnL 모니터 최신값 기록 분기 | `apps:batch` unit (fake port·store, `MutableClock`, fake 시각은 clock 상대값) |
| wiring | 조건(ready ∧ market.enabled), INFO 문구, scheduling off, Properties 바인딩(빈 기준선 = null)·검증 실패, JobConfig 기본값·yml, yml routes key = enum key | `apps:batch` unit (`ApplicationContextRunner`, `OutputCaptureExtension`) |
| E2E | 실제 MySQL fixture + 실제 store·실제 `PremiumAggregationReadPort`(DB 분·시·일 행, Redis 집계 key 사전 삭제) + fake 현재값 + 기록용 sender: 급변 SOUND 행 SENT, 쿨다운(근거 재적재 후 `last_notified_at` 유지), 24h 신고가 SILENT, 요약 메일+Discord, 재시작 후 같은 날 요약 없음 | `apps:batch` integration |
| 경계 | 모듈·import·관측 cardinality·test isolation | root `architectureTest` (기존 규칙) |
| 사람 | 실제 Discord market 채널 수신·소리/무음 체감 | T4 owner |

## 13. 문서 갱신 대상

- **신규** `docs/runbooks/owner-market-alert.md` — 4종 판정 기준·경계·쿨다운·히스테리시스, 근거 부족 시 쉬는 규칙(현재값 10초·bucket 80%), 분 집계 지연 한계(최대 약 2분),
  기본 라우팅 표와 **Discord 가 꺼져 있으면 4종 모두 메일**(굵게, 급변 메일 끄는 예시), market webhook 미설정 fallback, env 전부(§9.1)와 단위 %p·기준선 소수 넷째 자리·요약 시각 1~23, 기준선 설정 직후 이미 넘어 있으면 알리지 않음,
  일일 요약 구성·'없음' 조건·실계정 금액 노출 범위, 기동 판정과 INFO, `batch.market` 변경 뒤 남은 상태 행 확인·정리 SQL
  (`SELECT signal_key, alert_kind, last_notified_at, premium_zone, summary_date FROM owner_market_alert_state;` ·
  `DELETE FROM owner_market_alert_state WHERE signal_key LIKE 'market.<pk>.%';`), V22 배포(additive), Discord 수신 체크리스트(dod AC17)
- `docs/runbooks/account-pnl-alert.md` — 라우팅 표·override 목록(현재 "11개" 목록 → 15개), `:98` "market 은 후속 이슈용" 문구 정정, `:125` "#90 에서 이 표에 추가" 정리,
  `:169` 기동 INFO `owner-alert routes:` 예문에 4종, PnL 모니터가 `account_pnl_latest` 를 쓴다는 것(요약 전용, 실패해도 모니터 결과 무영향). 정확한 줄은 Task 0 (g8) 이 확정
- `docs/runbooks/owner-ops-alert.md` — 연속 실패 감시 대상에 `owner_market_signal` 이 포함됨
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — owner 알림 절에 시장 알림 흐름 한 단락(`OwnerMarketSignalJob` → `owner_market_alert_state` + outbox → `OwnerAlertDeliveryJob`, `account_pnl_latest`)
- `.ai/PROJECT_STATUS.md` — `Flyway V1~V22`, migration 표 V22 행, 기능 상태 한 항목
- `docs/runbooks/redis-contract.md`·`auth-security.md`·`durable-notification-delivery.md` — 무변경. `metrics-alerting.md`·`observability-readiness.md` 는 job tag 목록이 있으면 새 tag 추가(Task 0 grep)

## 14. 가정과 결정

### [ASSUMPTION]

| # | 가정 | 확인 방법·시점 |
|---|---|---|
| A1 | 시장 알림 기본 켜짐(U4)이므로 채널 ready 인 기존 배포는 배포 직후 급변·신고가·요약을 받기 시작한다. 오늘 09:00 이후 배포면 그날 요약이 즉시 1통 온다 | ⑦ owner 확인 |
| A2 | 근거 충분 기준 80%(상수) — 수집 공백이 그 이상이면 해당 창 판정을 쉰다 | ⑦ owner 확인, 운영 관찰 |
| A3 | 급변 기준은 분봉 **종가**의 저점·고점(튀는 값 억제). 신고가 기준은 분·시봉 고가·저가 | ⑦ owner 확인 |
| A4 | 기준선 안쪽 복귀(ABOVE/BELOW → BETWEEN)는 알리지 않는다. 기준선 설정·변경 직후 이미 넘어 있으면 알리지 않는다 | ⑦ owner 확인 |
| A5 | Spring Boot 생성자 바인딩이 빈 문자열 `upper-threshold` 를 `BigDecimal?` null 로 바인딩한다 | Task 14 바인딩 테스트. 실패하면 String 으로 받아 `toSettings` 에서 변환(설계 변경 없이 Properties 내부만) |
| A7 | 요약을 `summaryHour`(기본 09시) 이후 그날 언제든(늦은 기동 포함) 1회 보낸다. 지난 날짜는 소급하지 않는다. `summaryHour` 는 1~23 | ⑦ owner 확인 |

### 확인된 사실 (⑥ R1 에서 가정 → 사실)

| # | 사실 | 근거 |
|---|---|---|
| A6 | batch 컨텍스트에 `JpaPremiumRepositoryAdapter` bean 이 있다 | `CommonInfrastructureAutoConfiguration.kt:85-98` `CommonJpaConfiguration`(`@ConditionalOnBean(EntityManagerFactory)`) 등록 + batch `application.yml:9` `jpa.yml` import + infra:common → modules:jpa 로 EMF 존재, 로컬 batch 기동 실측(오케스트레이터). Task 0 (g5) 가 두 줄로 재확인 |
| F1 | JobId 전수 `when` 은 `JobConfig.kt:20`·`BatchJobProperties.kt:42` 두 곳뿐 | 오케스트레이터 확인 |
| F2 | 운영 신호 Job 신호 key = `"job-failures.${jobId.tag.lowercase()}"` | `OpsSignal.kt:34` |
| F3 | `AccountPnlMonitorState` 생성자를 쓰는 기존 테스트 4개 — K4 근거 | 오케스트레이터 확인(§6.1) |
| F4 | 집계 `fxRate` 는 bucket 마지막 값 — "전일 마감 환율" 서술 근거 | `PremiumCacheOperations.kt:264,294` |
| F5 | 성공 회차의 `AccountRound.observedAt` = 관측 완료 시각 | `AccountPnlMonitorJob.kt:169,173,191-197` |
| F6 | `OwnerOpsAlertContent.threshold` 는 `internal fun` — 재사용 가능 | `OwnerOpsAlertContent.kt:115` |

### [DECISION_NEEDED]

| # | 판단 | 선택지 | 설계 기본값 |
|---|---|---|---|
| D1 | 일일 요약의 실계정 **순손익 원** 을 MARKET 채널에 싣는가 | (a) %·원 모두(U1 문언 그대로) (b) % 만 | (a) — U1 이 "%·원"을 명시. MARKET 채널을 공유한다면 (b) 로 바꾼다(렌더러 한 줄·golden) |

## 15. 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | 최초 작성 | ④ |
| ⑥ R1 #1 | §5.3·§7.1·§7.2(B)·§9.1 — 기준선 값 scale ≤ 4·절댓값 < 10^6 require, H2 무한 재설정 방지 근거 | Important THRESHOLD-SCALE |
| ⑥ R1 #2 | §7.2·§7.2(D)·§7.4·§8.2·§8.4·§8.6 — `DailySummary`·`DailySummaryInput` 에 `fxBase`·`fxQuote`, Job 이 batchMarket 에서 채움, toString 표 | Important SUMMARY-FX-LABEL |
| ⑥ R1 #3 | §3.2·§14 — A6 근거를 실제 등록 위치로, 확인된 사실로 이동 | Important TASK0-G5 |
| ⑥ R1 #5 | §2.2·§3.2·§11·§12 — pair 불일치 예외는 방어적 이중 확인, mock 으로만 검증 | Minor |
| ⑥ R1 #6 | §6.3 — `Observed.completedAt` 추가 철회, `observed.round.observedAt` 사용 | Minor |
| ⑥ R1 #7 | §8.2 — `{d}` 는 `OwnerOpsAlertContent.threshold` 재사용 | Minor |
| ⑥ R1 #8 | §4.1·§7.1 — `summaryHour` 1..23 | Minor |
| ⑥ R1 #9 | §4.2·§7.2(C) — `hours == null ⇒ 24h 충분 = false` 명시 | Minor |
| ⑥ R1 #10 | §7.2 — 상태 key 검사 규칙을 모든 정책 공통으로 통일 | Minor |
| ⑥ R1 #11 | §3.2 — `read()` 가 `window.zone` 대신 infra zone 을 쓴다는 KDoc | Minor |
| ⑥ R1 #12 | §5.3 — BOOLEAN = `tinyint(1)` 명시 | Minor |
| ⑥ R1 추가 | §6.1·§7.5·§8.4·§14 — 오케스트레이터 확인 사실 F1~F6 반영 | 추가 확인 사실 |
