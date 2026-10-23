# owner 계정 알림 — 포지션 변경·순수익률 급변·미반영 리마인더 (issue #89) 설계

- slug: `owner-account-alerts` · base `033d513`(#88 머지, Task 0 이 `git merge-base HEAD origin/dev` 로 확인) · branch `feat/issue-89-owner-account-alerts`
- 입력: ③ 합의(오케스트레이터 전달 범위), `docs/work/owner-alert-channel/{design,dod,plan}.md`(#88, FROZEN), `.ai/rules/{architecture,batch,testing,naming}.md`,
  `docs/runbooks/account-pnl-alert.md`
- 상태: DRAFT — ⑥ 스펙 리뷰 전

## 0. 요약

기존 `AccountPnlMonitorJob` 회차가 owner 계정 알림 3종을 더 만든다. 세 알림은 #88 이 만든 라우팅(`OwnerAlertKind`)·행 확장(`OwnerAlertRows.expand`)·
outbox(`account_pnl_mail`)·전달(`OwnerAlertDispatcher`)을 **그대로** 탄다. 새 Job·Scheduler·port·테이블은 없다. 상태는 기존 단일 행
`account_pnl_monitor_state` 에 V20 으로 열을 더해 **같은 트랜잭션·같은 낙관적 version** 으로 저장한다.

| 알림 | `OwnerAlertKind` | 판정 | 기본 경로 |
|---|---|---|---|
| 순수익률 급변 | `NET_PERCENT_SWING` | 정시 버킷(zone 정시, 정시 보고와 같은 버킷)의 첫 성공 회차 `netPercent` 가 기준선. 현재값이 기준선 대비 ±임계값(기본 0.5%p) 이상이면 알림. 같은 버킷 안에서 방향(상승/하락)당 1회. 포지션 변경이면 그 버킷의 기준선을 지우고 다음 정시에 다시 잡는다 | 메일 ✗, Discord PNL `SOUND` |
| 포지션 변경 | `POSITION_CHANGED` | 성공 회차마다 빗썸 leg(수량·평단)와 바이낸스 leg(부호 수량·진입가)를 따로 저장하고, 저장값과 다르면 바뀐 leg 의 before→after 를 알린다. 빗썸만 바뀌었고 바이낸스 출처가 `MANUAL_ENV` 면 "바이낸스 env 미갱신 — 헤지 불일치" 경고를 **해소될 때까지 1회만** 덧붙인다. 첫 기동(저장값 없음)은 저장만 | 메일 ✓, Discord PNL `HERE` |
| 미반영 리마인더 | `UNRECOVERED_REMINDER` | zone(KST) 하루 한 번, 설정 시각(기본 09시) 이후 첫 **포지션 있는** 성공 회차에서 판정. 빗썸 진입 수수료·바이낸스 진입 수수료·펀딩비 미반영, 또는 수동 펀딩 값이 N일(기본 3) 이상 그대로면 해당 항목 전부를 한 메시지로 | 메일 ✗, Discord PNL `SILENT` |

핵심 결정 (근거는 각 절):

| # | 결정 | 절 |
|---|---|---|
| K1 | 상태는 **V20 으로 `account_pnl_monitor_state` 에 열 12개 추가**. Domain `AccountPnlMonitorState` 에 **기본값 있는 4번째 프로퍼티** `account: AccountAlertState = INITIAL` 을 더한다 — 새 port·새 테이블 없이 같은 트랜잭션·같은 version 으로 저장되고 동결 테스트의 fake store 가 무수정 컴파일된다 | §4 |
| K2 | 새 알림은 Job 생성자 **맨 끝**의 `accountAlerts: AccountAlertSettings? = null` 이 null 이 아닐 때만 판정한다. null 이면 `account` 상태를 읽은 그대로 다시 저장한다. production 배선은 항상 넘긴다 — 동결·#88 Job 테스트가 무수정으로 같은 결과를 낸다 | §5.1, §2 |
| K3 | 판정은 Domain 순수 정책 3개(`PositionChangePolicy`·`NetPercentSwingPolicy`·`UnrecoveredReminderPolicy`) + 조합기 `AccountAlertPolicy`. 결과는 sealed `AccountAlert` 이고 Job 은 sealed 타입에 대해서만 분기한다(`OwnerAlertKind` 전체 `when` 없음) | §3 |
| K4 | leg·수동 펀딩 값은 **정규화한 10진 문자열(VARCHAR(64))** 로 저장하고 `compareTo` 로 비교한다 — DECIMAL scale 절단이 "매 회차 변경" 오탐을 만들 수 없게 | §4.2 |
| K5 | 실패 회차는 세 정책 모두 **판정하지 않는다**(상태 그대로). 포지션 없음(`NoPosition`)은 성공 회차다 — leg 를 기록하고 청산도 변경으로 알린다 | §3.5 |
| K6 | eventKey: 급변 `account-pnl-swing-{up\|down}-{버킷 시작 ms}`, 포지션 변경 `account-pnl-position-{감지 시각 ms}`, 리마인더 `account-pnl-reminder-{zone 자정 ms}`. outbox `(event_key, channel)` UNIQUE 가 상태 판정과 이중으로 중복을 막는다 | §3.6 |
| K7 | #88 테스트 중 **`OwnerAlertKind` 개수를 고정한 단언 3곳**만 허용 예외로 고친다(값 3개 끝에 추가). 나머지 #88 테스트는 무수정 | §2, dod R7 |
| K8 | V20 은 additive — 구 binary 의 상태 INSERT(9열)·UPDATE(새 열 미포함)가 V20 에서 그대로 동작하고 새 열 값을 보존한다. **롤백 SQL 없음** | §4.4 |

## 1. 범위

### 1.1 포함

- `OwnerAlertKind` 값 3개와 기본 경로(#88 design §3.2 목표 표 그대로), override env 9개, yml `routes` 3블록 (§6)
- Domain: leg·상태·설정 모델, 정책 3개 + 조합기, sealed `AccountAlert`, 메일·Discord 렌더링 3종 (§3, §7)
- V20 migration, JDBC store 상태 열 읽기·쓰기, batch fixture 스키마 (§4)
- `AccountPnlMonitorJob` 관측 결과에 leg·출처·펀딩·관측 시각 추가, 알림 생성·라우팅, 상태 저장 (§5)
- 설정 env 3개(임계값·리마인더 시각·수동 펀딩 경과 일수)와 앱 배선 (§6)
- runbook·아키텍처 문서 갱신 (§11)

### 1.2 제외 (scope creep 차단선)

- 수익 알림(`PnlAlertPolicy`)·중단/복구(`MonitoringOutagePolicy`)·정시 보고 생성 규칙 변경. **`position_fingerprint` 열의 의미(수익 에피소드 중에만 저장)는 그대로**
- 순손익 공식(`AccountNetPnl`) 변경, 관측 로그(`Account PnL observed: …`) 문구 변경
- 새 Job·Scheduler·JobId·JobConfig, 새 port, 새 테이블, outbox 스키마 변경
- 시장 알림(#90)·운영 알림(#91), 독립 전달 Job
- 바이낸스 API 모드에서 수동 값 비교, 헤지 수량 불일치(빗썸 수량 ≠ 바이낸스 수량) 자체의 판정 — 이 단위의 불일치 경고는 "빗썸만 바뀌고 바이낸스가 수동 env" 한 경우뿐
- 알림 종류별 on/off env 신설 — 끄기는 기존 route override(`_EMAIL=false` + `_DISCORD=none`)로 한다
- 테이블·타입 rename (#88 §4.4 이름 부채 유지), 직전 단위 산출물(`docs/work/owner-alert-channel/**`, `live-account-pnl-*` 등) 수정

## 2. 현재 구조와 기존 테스트가 만드는 제약

현재(`033d513`) 흐름: `AccountPnlMonitorScheduler` → `AccountPnlMonitorJob.run()` → `JobExecutor`(lock `lock:account-pnl:monitor`, lease 50s, timeout 40s)
→ `recoverStale` → `load` → `observe()` → `PnlAlertPolicy.decide` → `MonitoringOutagePolicy.decide` → `buildMails`(종류별 `routing.resolve` + `OwnerAlertRows.expand`)
→ interrupt 확인 → `store.save(version, next, rows)` → `OwnerAlertDispatcher.dispatch()`.

| 테스트 | 강제하는 것 | 이 설계의 대응 |
|---|---|---|
| `AccountPnlMonitorJobTest`·`...HourlyReportTest`·`...ObservationLogTest` (동결) | Job 생성자 앞 13개 인자, `FakeStore` 6메서드 override, `AccountPnlMonitorState(alert, outage, version)` 3인자 생성, 회차당 행 수·종류, 관측 로그 문구, 관측 중 interrupt 재전파 | 생성자 **끝에** `accountAlerts = null` 추가(K2). 상태 4번째 프로퍼티는 기본값(K1). store interface 무변경. 관측 로그 무변경 — `observe()` 는 반환 타입(private)에 필드만 더한다 |
| #88 `AccountPnlMonitorJobOwnerAlertTest`·`OwnerAlertDispatcherTest`·`OwnerAlertTestFakes`·`OwnerAlertDeliveryIntegrationTest`·`AccountPnlAlertDurabilityIntegrationTest` | 같은 생성자, 회차당 행 구성 | `accountAlerts` 미지정 → 새 알림 없음. 무수정 |
| #88 `OwnerAlertRoutingTest` | `entries` 이름·key 목록 `containsExactly` 4개 (`:24-25`, `:34-35`) | **허용 예외** — 두 단언의 기대 목록 끝에 3개 추가. 나머지 메서드는 `entries.forEach` 라 새 값에도 성립 |
| #88 `OwnerAlertPropertiesTest` | 알 수 없는 key 메시지 `known: profit, hourly-report, monitoring-outage, monitoring-recovered` (`:113`) | **허용 예외** — 기대 문자열 끝에 `, position-changed, net-percent-swing, unrecovered-reminder` |
| #88 `AccountPnlMonitorWiringTest` | 기동 INFO `owner-alert routes: …` 를 `contains` 로 잼(`:794-803`) | 새 값은 enum **끝**에 붙으므로 기존 부분 문자열이 그대로 포함된다. 확장만 |
| #88 `OwnerAlertYamlContractTest` | yml `routes` key 집합 = `OwnerAlertKind.entries` | yml 3블록 추가로 무수정 통과 |
| `AccountPnlFixtureSchemaIntegrationTest` | `account_pnl_monitor_state` 열 목록 = V18 | 클래스 KDoc 이 "새 migration 이 바꾸면 함께 고친다" — **허용 예외**(기대 목록 끝에 12열, KDoc 에 V20) |
| `JdbcAccountPnlAlertStoreIntegrationTest` (확장만) | `load()` 결과 동등성 | 새 열이 NULL/FALSE 면 `AccountAlertState.INITIAL` 로 읽혀 기존 단언 그대로 성립 |

## 3. Domain 모델과 정책 (`io.premiumspread.domain.accountpnl`)

### 3.1 leg 와 정규화

- `KoreaLeg(quantity, averageBuyPrice)` — 빗썸 현물. `KoreaLeg.of(snapshot)` 은 수량이 `AccountHedgePosition.KOREA_DUST`(0.0001) 미만이면 `KoreaLeg.EMPTY`(0, 0),
  아니면 두 값을 `stripTrailingZeros()` 로 정규화한다. `[ASSUMPTION]` A5 — dust 잔량 변동·청산 후 남은 평단은 변경으로 보지 않는다.
- `ForeignLeg(signedQuantity, entryPrice)` — 바이낸스 선물. `ForeignLeg.of(snapshot)` 은 `signedQuantity == 0` 이면 `ForeignLeg.FLAT`(0, 0), 아니면 정규화.
- `PositionLegs(korea, foreign)`, `PositionLegs.of(koreaSnapshot, foreignSnapshot)`.
- 비교는 `samePosition(other)` = 두 필드 `compareTo == 0`(scale 무관). data class 동등성은 정규화 덕분에 같은 값이면 같지만 정책은 `samePosition` 만 쓴다.
- leg 생성은 `observe()` 의 try 안에서 한다 — 정규화 중 예외(이론상 없음)는 `UNEXPECTED` 실패 회차가 된다.

### 3.2 포지션 변경 `PositionChangePolicy`

상태 `PositionWatchState(legs: PositionLegs?, hedgeMismatchWarned: Boolean)`. `legs == null` = 아직 기록 없음.

입력: 이번 성공 회차의 `legs`, 바이낸스 출처 `source`, 감지 시각 `now`(= 회차 관측 시각 `observedAt`). 전이표 (전문, 위에서부터 먼저 맞는 행):

| # | 저장 legs | 빗썸 변경 | 바이낸스 변경 | 출처 | `warned` | → next.legs | → next.warned | 알림 |
|---|---|---|---|---|---|---|---|---|
| P1 | null | — | — | — | 무엇이든 | 이번 legs | false | 없음 (첫 기록) |
| P2 | 있음 | ✗ | ✗ | `EXCHANGE_API` | 무엇이든 | 이번 legs | false | 없음 |
| P3 | 있음 | ✗ | ✗ | `MANUAL_ENV` | w | 이번 legs | w | 없음 |
| P4 | 있음 | ✓ | ✗ | `MANUAL_ENV` | false | 이번 legs | **true** | 변경(빗썸) + **헤지 불일치 경고** |
| P5 | 있음 | ✓ | ✗ | `MANUAL_ENV` | true | 이번 legs | true | 변경(빗썸), 경고 없음 |
| P6 | 있음 | ✓ | ✗ | `EXCHANGE_API` | 무엇이든 | 이번 legs | false | 변경(빗썸) |
| P7 | 있음 | ✗ | ✓ | 무엇이든 | 무엇이든 | 이번 legs | false | 변경(바이낸스) — 경고 해소 |
| P8 | 있음 | ✓ | ✓ | 무엇이든 | 무엇이든 | 이번 legs | false | 변경(빗썸·바이낸스) |

- "해소"는 바이낸스 leg 가 바뀌었을 때(P7·P8 — env 갱신 + 재시작) 또는 출처가 API 일 때(P2·P6)다. 빗썸이 원래 값으로 돌아오는 경우는 해소로 보지 않는다
  (P3 에서 `warned` 유지 — 다음 빗썸 변경도 경고 없이 알린다). `[ASSUMPTION]` A6.
- 전량 청산(`NoPosition`)도 leg 가 바뀌므로 P4~P8 중 하나로 알린다(빗썸 → `EMPTY` 이면 "없음"으로 표시).
- 모드 전환(`api` ↔ `manual`)은 수량·진입가가 같으면 변경 아님(P2·P3) — runbook 의 기존 "에피소드 유지" 설명과 같은 규칙.
- 재시작 사이에 바뀐 포지션도 재시작 후 첫 성공 회차에서 저장값과 비교해 알린다(상태는 DB 에 있다).

### 3.3 순수익률 급변 `NetPercentSwingPolicy`

상태 `SwingState(bucketStart: Instant?, baselinePercent: BigDecimal?, upSent: Boolean, downSent: Boolean)`.
불변식: `bucketStart == null` 이면 나머지는 `null/false/false`.

버킷: `bucketStart(at, zone) = at.atZone(zone).truncatedTo(HOURS).toInstant()` — `AccountPnlMailContent.hourly` 의 정시 버킷과 같은 식이며 `at` 은 회차 관측 시각
`observedAt`(포지션이 있으면 `pnl.calculatedAt` 과 같다). 범위는 `[bucketStart, bucketStart + 1h)`. `[ASSUMPTION]` A2.

입력: 이번 버킷 `bucket`, `pnl: AccountNetPnl?`(null = 포지션 없음), `positionChanged`(이번 회차 P4~P8), 임계값 `threshold`. 전이표 (전문, 위에서부터):

| # | 조건 | → next | 알림 |
|---|---|---|---|
| S1 | `positionChanged` | `(bucket, null, false, false)` | 없음 — 이 버킷은 기준선 없음, 다음 정시에 다시 잡는다 |
| S2 | `state.bucketStart != bucket` (새 버킷 또는 첫 회차) | `(bucket, pnl?.netPercent, false, false)` | 없음 — 이 회차가 기준선 |
| S3 | 같은 버킷, `baselinePercent == null` 또는 `pnl == null` | state 그대로 | 없음 |
| S4 | 같은 버킷, `delta = pnl.netPercent − baseline ≥ threshold`, `!upSent` | `upSent = true` | **상승** |
| S5 | 같은 버킷, `delta ≤ −threshold`, `!downSent` | `downSent = true` | **하락** |
| S6 | 그 밖 | state 그대로 | 없음 |

- `netPercent` 는 소수 둘째 자리(HALF_UP) 값이고 비교는 `compareTo` — 경계 포함(0.50 차이면 알림, 0.49 면 아님).
- 기준선은 정시 보고 **생성 여부와 무관**하게 저장한다(`ACCOUNT_PNL_HOURLY_REPORT_ENABLED=false` 여도 동작).
- 정시 보고 행은 버킷의 첫 Computed 회차 내용이 outbox 에 남으므로(같은 eventKey 는 무시) 정시 보고의 순수익률 = 그 버킷의 급변 기준선이다(포지션 변경 버킷 제외).
- 상승 알림 뒤 값이 기준선 근처로 돌아와도 같은 버킷 안에서 상승은 다시 알리지 않는다. 하락은 별도로 1회 가능.
- 첫 기동 직후(P1)는 변경이 아니므로 S2 로 기준선을 잡는다.

### 3.4 미반영 리마인더 `UnrecoveredReminderPolicy`

상태 `ReminderState(lastDayStart: Instant?, manualFundingUsdt: BigDecimal?, manualFundingChangedAt: Instant?)`.
불변식: `manualFundingChangedAt == null` 이면 `manualFundingUsdt == null`.

**1단계 — 수동 펀딩 값 추적** (모든 성공 회차, 포지션 유무 무관):

| 출처 | 조건 | → (manualFundingUsdt, manualFundingChangedAt) |
|---|---|---|
| `EXCHANGE_API` | — | `(null, null)` (추적 해제) |
| `MANUAL_ENV` | `changedAt == null` (첫 관측) | `(이번 값, observedAt)` |
| `MANUAL_ENV` | 저장 값과 이번 값이 다름 (null ↔ 값 포함, 값끼리는 `compareTo`) | `(이번 값, observedAt)` |
| `MANUAL_ENV` | 같음 | 그대로 |

"이번 값" = 바이낸스 스냅샷 `fundingUsdt`(`BINANCE_MANUAL_FUNDING_USDT`, 미입력이면 null) 를 정규화한 값. 변경 시각은 DB 가 자동 기록한다(새 날짜 env 없음).
V20 배포 후 첫 관측이 기준이므로 "N일째 미갱신"은 배포 후 N일이 지나야 처음 나올 수 있다. `[ASSUMPTION]` A4.

**2단계 — 리마인더 판정.** `dayStart(at, zone) = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()`, 하루 범위 `[dayStart, 다음 dayStart)`.

| # | 조건 | → lastDayStart | 알림 |
|---|---|---|---|
| R1 | `pnl == null` (포지션 없음) | 그대로 | 없음 — 그날을 소비하지 않는다 |
| R2 | `observedAt` 의 zone 시각 `hour < reminderHour` | 그대로 | 없음 |
| R3 | `lastDayStart == dayStart` (오늘 이미 판정) | 그대로 | 없음 |
| R4 | 그 밖 — 오늘 첫 판정, 항목 0개 | `dayStart` | 없음 |
| R5 | 그 밖 — 오늘 첫 판정, 항목 1개 이상 | `dayStart` | **리마인더**(항목 전부) |

항목 (순서 고정, 해당하는 것만):

| `ReminderItem` | 조건 |
|---|---|
| `KOREA_ENTRY_FEE` | `pnl.koreaEntryFeeUnrecovered` |
| `FOREIGN_ENTRY_FEE` | `pnl.foreignEntryFeeUnrecovered` |
| `FUNDING` | `pnl.fundingUnrecovered` |
| `MANUAL_FUNDING_STALE` | 출처 `MANUAL_ENV` ∧ 1단계 뒤 `manualFundingUsdt != null` ∧ `observedAt − manualFundingChangedAt ≥ manualFundingStaleAfter` |

- "그날 첫 판정"은 설정 시각 이후 **첫 포지션 있는 성공 회차**다. 그때 항목이 없으면(R4) 그날은 끝이다 — 오후에 미반영이 생겨도 다음 날 09시에 알린다.
  실패 회차·포지션 없음 회차는 그날을 소비하지 않는다. `[ASSUMPTION]` A3.
- 경과 일수 표시 = `Duration.between(changedAt, observedAt).toDays()`(내림).

### 3.5 조합기 `AccountAlertPolicy` 와 회차 종류

`decide(state, round: AccountRound?, settings, zone)` — `round == null` 은 실패 회차.

| 회차 | 포지션 | 급변 | 리마인더 |
|---|---|---|---|
| 실패(`PnlObservation.Failed`) — `round == null` | 판정 없음, 상태 그대로 | 판정 없음 | 판정 없음(펀딩 추적도 없음) |
| 포지션 없음(`NoPosition`) | P1~P8 | S1 또는 S2(기준선 null)·S3 | 펀딩 추적만, R1 |
| 포지션 있음(`Computed`) | P1~P8 | S1~S6 | 1단계 + R2~R5 |

순서: 포지션 → 급변(`positionChanged = 포지션 알림 있음`) → 리마인더. 알림 목록 순서도 같다.
outbox 행 순서는 기존 알림(수익 → 정시 보고 → 중단/복구) **뒤에** 포지션 → 급변 → 리마인더, 각 알림 안에서 메일 → Discord. `[ASSUMPTION]` A8.

기존 정책과의 관계:
- `PnlAlertPolicy`·`MonitoringOutagePolicy` 입력·출력 무변경. 수익 에피소드의 포지션 리셋은 기존 `position_fingerprint` 가 계속 담당한다(이 단위의 leg 와 독립).
- 모니터링 중단 중에는 실패 회차라 세 알림이 모두 멈추고, 복구 회차(성공)에서 다시 판정한다. 중단 동안 바뀐 포지션은 복구 회차에 알린다.
- 저장 전 취소(`Skipped("cancelled")`)·version 충돌(`Skipped("state-version-conflict")`)이면 상태·행이 모두 저장되지 않는다 — 다음 회차가 같은 판정을 다시 한다.

### 3.6 eventKey 와 중복 제거 (전문)

형식은 기존 `account-pnl-{kind}-{epoch ms}`(SMTP Message-ID 규칙 `[A-Za-z0-9._-]{1,128}` 준수).

| 알림 | eventKey | 같은 키가 되는 경우 | 상태 판정의 중복 방지 |
|---|---|---|---|
| 급변 상승 | `account-pnl-swing-up-{bucketStart ms}` | 같은 버킷의 상승 | `upSent` |
| 급변 하락 | `account-pnl-swing-down-{bucketStart ms}` | 같은 버킷의 하락 | `downSent` |
| 포지션 변경 | `account-pnl-position-{detectedAt ms}` | 같은 회차(재저장)뿐 — 변경 이벤트마다 다르다 | 저장 legs 갱신 |
| 리마인더 | `account-pnl-reminder-{dayStart ms}` | 같은 zone 날짜 | `lastDayStart` |

identity = `(event_key, channel)`(#88). 구독·MarketPair 는 없다(owner 단일 계정, BTC).

### 3.7 설정 `AccountAlertSettings`

| 프로퍼티 | 타입 | 기본 | 검증(`require`, 메시지 전문) |
|---|---|---|---|
| `swingThreshold` | `BigDecimal` (%p) | `0.50` | `> 0` — `swingThreshold must be positive.` |
| `reminderHour` | `Int` | `9` | `0..23` — `reminderHour must be between 0 and 23.` |
| `manualFundingStaleAfter` | `Duration` | 3일 | `> 0` — `manualFundingStaleAfter must be positive.` |

## 4. 상태 저장

### 4.1 대안 비교

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A (채택)** V20 `ALTER account_pnl_monitor_state` + `AccountPnlMonitorState.account`(기본값) | 기존 단일 행에 열 추가, 기존 `load`/`save` SQL 확장 | 상태·outbox 행이 **기존 한 트랜잭션·한 version** 으로 원자적. port·fake 무변경. 구 binary INSERT/UPDATE 호환 | 테이블 이름이 "monitor state" 이고 열이 늘어난다(같은 모니터 회차가 소유하므로 의미상 맞다) |
| B 새 테이블 `owner_account_alert_state` + 새 port | 분리 | 이름이 정직 | outbox 와 같은 트랜잭션을 만들려면 store 를 합치거나 트랜잭션 경계를 app 으로 올려야 한다. version 이 둘이 되어 경합 규칙이 복잡. 새 port 를 Job 에 넣으면 생성자 인자·fake 추가 |
| C `AccountPnlAlertStore` 에 메서드 추가 | `loadAccount`/`saveAccount` | | 추상 메서드면 동결 `FakeStore` 컴파일 실패, 기본 메서드면 같은 트랜잭션 보장 불가 |

### 4.2 V20 DDL (전문)

위치 `infrastructure/common/src/main/resources/db/migration/V20__add_owner_account_alert_state.sql`
(번호 = `ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1` 의 V19 + 1, Task 0 확인).

```sql
-- owner 계정 알림(#89) 상태 — 포지션 변경 감지용 leg, 순수익률 급변 기준선, 미반영 리마인더.
-- 기존 열(position_fingerprint 등 수익 알림 에피소드)의 의미는 바꾸지 않는다.
-- 새 열은 NULL 이거나 기본값이 있어 이 migration 이전 binary 의 INSERT(9열)·UPDATE(새 열 미포함)가 그대로 동작하고 새 열 값을 보존한다.
-- 수량·가격·펀딩은 소수 자릿수 손실 없이 비교하려고 정규화한 10진 문자열로 저장한다.
ALTER TABLE account_pnl_monitor_state
    ADD COLUMN korea_quantity            VARCHAR(64)   NULL,
    ADD COLUMN korea_average_buy_price   VARCHAR(64)   NULL,
    ADD COLUMN foreign_signed_quantity   VARCHAR(64)   NULL,
    ADD COLUMN foreign_entry_price       VARCHAR(64)   NULL,
    ADD COLUMN hedge_mismatch_warned     BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN swing_bucket_start        DATETIME(6)   NULL,
    ADD COLUMN swing_baseline_percent    DECIMAL(12,2) NULL,
    ADD COLUMN swing_up_sent             BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN swing_down_sent           BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN reminder_day_start        DATETIME(6)   NULL,
    ADD COLUMN manual_funding_usdt       VARCHAR(64)   NULL,
    ADD COLUMN manual_funding_changed_at DATETIME(6)   NULL,
    ADD CONSTRAINT ck_account_pnl_monitor_state_legs CHECK (
        (korea_quantity IS NULL AND korea_average_buy_price IS NULL
            AND foreign_signed_quantity IS NULL AND foreign_entry_price IS NULL)
        OR (korea_quantity IS NOT NULL AND korea_average_buy_price IS NOT NULL
            AND foreign_signed_quantity IS NOT NULL AND foreign_entry_price IS NOT NULL)
    ),
    ADD CONSTRAINT ck_account_pnl_monitor_state_swing CHECK (
        swing_bucket_start IS NOT NULL
        OR (swing_baseline_percent IS NULL AND swing_up_sent = FALSE AND swing_down_sent = FALSE)
    ),
    ADD CONSTRAINT ck_account_pnl_monitor_state_funding CHECK (
        manual_funding_changed_at IS NOT NULL OR manual_funding_usdt IS NULL
    );
```

- 한 문장 — MySQL DDL 은 트랜잭션이 아니므로 반쯤 적용된 V20 을 남기지 않는다. 한 ALTER 안에서 새 열을 참조하는 CHECK 는 V19 가 이미 검증했다. `[ASSUMPTION]` A1.
- 새 열은 기존 9열 **뒤에** 붙는다. 기존 CHECK `ck_account_pnl_monitor_state_singleton` 무변경.
- `swing_baseline_percent` 는 `netPercent`(scale 2)라 DECIMAL 로 둔다. 범위 `|x| < 10^10` 은 store 가 저장 전 검증한다(§4.3).
- batch fixture(`apps/batch/src/integrationTest/resources/batch-schema.sql`)의 `account_pnl_monitor_state` 를 V18+V20 최종 형태로 맞춘다(batch 는 Flyway 를 쓰지 않는다).

### 4.3 열 ↔ 프로퍼티 (전문)

| 열 | 타입 | `AccountAlertState` 경로 | 쓰기 | 읽기 |
|---|---|---|---|---|
| `korea_quantity` | VARCHAR(64) NULL | `position.legs?.korea.quantity` | `toPlainString()` | `BigDecimal(s)` → `KoreaLeg.of(q, p)` (정규화) |
| `korea_average_buy_price` | VARCHAR(64) NULL | `position.legs?.korea.averageBuyPrice` | 〃 | 〃 |
| `foreign_signed_quantity` | VARCHAR(64) NULL | `position.legs?.foreign.signedQuantity` | 〃 | `ForeignLeg.of(q, p)` |
| `foreign_entry_price` | VARCHAR(64) NULL | `position.legs?.foreign.entryPrice` | 〃 | 〃 |
| `hedge_mismatch_warned` | BOOLEAN NOT NULL | `position.hedgeMismatchWarned` | | |
| `swing_bucket_start` | DATETIME(6) NULL | `swing.bucketStart` | `Timestamp.from` | `getTimestamp?.toInstant()` |
| `swing_baseline_percent` | DECIMAL(12,2) NULL | `swing.baselinePercent` | `BigDecimal` | `getBigDecimal` |
| `swing_up_sent` / `swing_down_sent` | BOOLEAN NOT NULL | `swing.upSent` / `swing.downSent` | | |
| `reminder_day_start` | DATETIME(6) NULL | `reminder.lastDayStart` | | |
| `manual_funding_usdt` | VARCHAR(64) NULL | `reminder.manualFundingUsdt` | `toPlainString()` | `BigDecimal(s)` 후 `stripTrailingZeros()` |
| `manual_funding_changed_at` | DATETIME(6) NULL | `reminder.manualFundingChangedAt` | | |

store 저장 전 검증(값 없이 실패, `IllegalArgumentException`, 트랜잭션을 열기 전):
`position leg must be at most 64 characters as a plain decimal.` / `manual funding must be at most 64 characters as a plain decimal.` /
`swing baseline percent is out of range.`(`abs() >= 10^10`).

### 4.4 배포·롤백

- 배포: 기존 순서 그대로 — API 배포(Flyway V20) → 구 batch 중지 → 신 batch 기동(#88 §4.5 의 "겹치지 않음" 제약은 outbox claim 때문에 계속 유효).
  신 batch 가 V20 전에 뜨면 `load` 가 unknown column 으로 실패해 회차가 `Failure` 다(운영 알림) — API 먼저.
- V20 은 구 binary 와 호환된다: 구 `insertState` 는 9열만 넣고 새 열은 NULL/FALSE 기본값, 구 `updateState` 는 새 열을 건드리지 않아 값이 보존된다.
  새 알림의 outbox 행은 기존 `EMAIL`/`DISCORD` 행일 뿐이라 구(#88) binary 가 그대로 전달한다.
- 롤백(신 → #88 binary): **SQL 없음.** 롤백 기간에는 새 알림이 멈추고 상태 열은 마지막 값으로 남는다. 다시 신 binary 로 올리면 롤백 기간에 바뀐 포지션을
  첫 성공 회차에 알리고(저장 legs 와 비교), 급변 기준선은 새 버킷에서 다시 잡으며, 리마인더는 `lastDayStart` 기준으로 이어진다.
- 스키마는 되돌리지 않는다(append-only).

## 5. 배치 Job

### 5.1 생성자와 흐름

생성자: 기존 13개 인자 뒤에 `accountAlerts: AccountAlertSettings? = null` 하나만 추가(K2). 그 밖 lock key·lease·timeout·JobId·Scheduler 무변경.

```
monitor():
  now ← clock.instant(); recoverStale/load (기존)
  observed ← observe()                         // + round: AccountRound? (성공이면 legs·출처·펀딩·pnl?·observedAt)
  alert   ← PnlAlertPolicy.decide(...)           (기존)
  outage  ← MonitoringOutagePolicy.decide(...)   (기존)
  account ← accountAlerts?.let { AccountAlertPolicy.decide(loaded.account, observed.round, it, zone) }
            ?: AccountAlertDecision(loaded.account, emptyList())
  rows    ← buildMails(기존 3종) + account.alerts 마다 route(alert.kind, 메일){ Discord }
  next    ← AccountPnlMonitorState(alert.next, outage.next, loaded.version, account.next)
  interrupt 확인 → save(version, next, rows) → dispatcher.dispatch()   (기존)
```

- `observe()` 의 private `Observed` 에 `round: AccountRound?` 를 더한다. 성공 시 `AccountRound(PositionLegs.of(k, f), f.positionSource, f.fundingUsdt,
  pnl 또는 null, completedAt)`. 실패면 null. 관측 로그·취소 재전파·실패 범주 정규화는 그대로.
- `zone` 은 기존 Job 생성자 기본값 `Asia/Seoul`(앱 배선이 넘기지 않음 — #88 §10). 급변 버킷·리마인더 날짜·표시 시각 모두 이 zone.
- 정책 예외(불변식 위반 버그)는 기존 `PnlAlertPolicy` 와 같이 잡지 않는다 → `JobExecutor` 가 `Failure` 로 기록.

### 5.2 JobResult 분기 (전문 — 굵은 행이 이 단위의 추가/명시)

| 상황 | 결과 |
|---|---|
| `recoverStale`/`load` 예외 (V20 미적용 DB 의 unknown column 포함) | `Failure` |
| 관측 중 취소(원인 체인 `InterruptedException`·`CancellationException`) | 플래그 복구 후 재던짐(기존) |
| **관측 실패(실패 범주)** | **계정 알림 판정 없음, `account` 상태 그대로 저장**, 그 밖 기존과 같음 |
| **`AccountAlertPolicy` 예외** | 잡지 않음 → `JobExecutor` 가 `Failure` |
| 저장 전 interrupt | `Skipped("cancelled")` — 계정 알림 상태·행 저장 없음 |
| `save` 예외 (store 검증 `IllegalArgumentException` 포함) | `Failure` |
| version 충돌 | `Skipped("state-version-conflict")` — 계정 알림 상태·행 저장 없음 |
| 전달 단계 | #88 §8 그대로 |
| 그 밖 | `Success` |

### 5.3 로그 (키 전문)

| 로그 | 레벨 | 형식 | 담지 않는 값 |
|---|---|---|---|
| 알림 생성 | INFO | `Owner account alert decided: kind={}, eventKey={}` (알림마다) | leg 수량·가격, 펀딩 값, 수신 주소, webhook, credential |
| 포지션 변경 상세 | INFO | `Account position changed: korea={}, foreign={}, hedgeMismatchWarning={}` — 값은 `true`/`false` | leg 수량·가격 |
| 첫 leg 기록 | INFO | `Account position legs recorded; first observation sends no change alert` | 값 |

기존 `Account PnL observed: …` 한 줄이 회차마다 수량을 이미 남기므로 새 로그에 숫자를 반복하지 않는다. 알림 객체(`AccountAlert`)·상태 객체를 통째로 로그에 넣지 않는다(§7.4 toString 노출 참조).

## 6. 라우팅·설정

### 6.1 `OwnerAlertKind` 추가 (enum 끝에, 이 순서로)

| enum | key | Discord 대상 | 푸시 | 메일 |
|---|---|---|---|---|
| `POSITION_CHANGED` | `position-changed` | `PNL` | `HERE` | true |
| `NET_PERCENT_SWING` | `net-percent-swing` | `PNL` | `SOUND` | false |
| `UNRECOVERED_REMINDER` | `unrecovered-reminder` | `PNL` | `SILENT` | false |

- #88 해석 규칙(§3.3) 그대로: **Discord 가 꺼져 있으면 세 종류 모두 메일로 간다**(사용자 확정 D1 의 귀결). 급변은 최대 시간당 2통, 리마인더는 하루 1통이 메일로 온다 —
  원치 않으면 `OWNER_ALERT_NET_PERCENT_SWING_EMAIL=false` 등. runbook 에 굵게 적는다. `[ASSUMPTION]` A7.
- override env 9개: `OWNER_ALERT_{POSITION_CHANGED|NET_PERCENT_SWING|UNRECOVERED_REMINDER}_{EMAIL|DISCORD|PUSH}` → `owner-alert.routes.{key}.{email|discord|push}`.
  해석·검증은 #88 `OwnerAlertProperties` 그대로(코드 변경 없음, known 목록은 enum 에서 생성).

### 6.2 계정 알림 설정 env

| env | property | 기본 | 의미 |
|---|---|---|---|
| `ACCOUNT_PNL_SWING_THRESHOLD_PERCENT` | `account-pnl-monitor.account-alerts.swing-threshold-percent` | `0.5` | 급변 임계값(%p, 양수) |
| `ACCOUNT_PNL_REMINDER_HOUR` | `account-pnl-monitor.account-alerts.reminder-hour` | `9` | 리마인더 판정 시작 시각(zone 시, 0~23) |
| `ACCOUNT_PNL_MANUAL_FUNDING_STALE_DAYS` | `account-pnl-monitor.account-alerts.manual-funding-stale-days` | `3` | 수동 펀딩 미갱신 일수(양의 정수) |

- 앱 `AccountPnlMonitorConfiguration.accountPnlMonitorJob` 이 `@Value` 로 받아 `AccountAlertSettings` 를 만든다. 잘못된 값은 Domain `require` 또는 Spring 변환
  실패로 **기동 실패**다(값은 비밀이 아니다). `AccountPnlMonitorProperties` 는 바꾸지 않는다(이 prefix 의 미바인딩 key 는 무시된다 — `hourly-report` 와 같다).
- 수익·중단·정시 보고 env 무변경.

yml 추가 (전문 — `account-pnl-monitor.hourly-report` 블록 바로 뒤, 그리고 `owner-alert.routes` 끝):

```yaml
  # owner 계정 알림(포지션 변경·순수익률 급변·미반영 리마인더). 경로는 owner-alert.routes 가 정한다.
  account-alerts:
    swing-threshold-percent: ${ACCOUNT_PNL_SWING_THRESHOLD_PERCENT:0.5}
    reminder-hour: ${ACCOUNT_PNL_REMINDER_HOUR:9}
    manual-funding-stale-days: ${ACCOUNT_PNL_MANUAL_FUNDING_STALE_DAYS:3}
```

```yaml
    position-changed:
      email: ${OWNER_ALERT_POSITION_CHANGED_EMAIL:}
      discord: ${OWNER_ALERT_POSITION_CHANGED_DISCORD:}
      push: ${OWNER_ALERT_POSITION_CHANGED_PUSH:}
    net-percent-swing:
      email: ${OWNER_ALERT_NET_PERCENT_SWING_EMAIL:}
      discord: ${OWNER_ALERT_NET_PERCENT_SWING_DISCORD:}
      push: ${OWNER_ALERT_NET_PERCENT_SWING_PUSH:}
    unrecovered-reminder:
      email: ${OWNER_ALERT_UNRECOVERED_REMINDER_EMAIL:}
      discord: ${OWNER_ALERT_UNRECOVERED_REMINDER_DISCORD:}
      push: ${OWNER_ALERT_UNRECOVERED_REMINDER_PUSH:}
```

## 7. 렌더링 (`AccountAlertContent`, Domain `accountpnl`)

### 7.1 공통

- 서식은 `AccountPnlFormat`(#88) 재사용. 새 함수 2개만 더한다: `percentPoint(v)` = `sign(v) + format("0.00", |v|) + "%p"`, `date(instant, zone)` = `yyyy-MM-dd`.
  기존 함수·`AccountPnlMailContent`·`AccountPnlDiscordContent` 는 무변경(기존 golden 그대로).
- `{p}` = `percent()`, `{pp}` = `percentPoint()`, `{krw}` = `krw()`, `{time}` = 메일 `time()`, `{short}` = Discord `shortTime()`, `{plain}`·`{plainKrw}`·`{signedUsdt}` = 기존.
  `{HH}` = 2자리 시(`%02d`). 모두 `Locale.ROOT`·zone 명시.
- leg 표기 (메일·Discord 공용):
  - 빗썸 `{kleg}`: `EMPTY` → `없음`, 그 밖 → `{plain(quantity)} BTC · 평단 {plainKrw(averageBuyPrice)}원`
  - 바이낸스 `{fleg}`: `FLAT` → `없음`, `signedQuantity < 0` → `숏 {plain(|q|)} BTC · 진입가 {plain(entryPrice)} USDT`, `> 0` → `롱 …`(같은 형식)
  - `{legs}`(변경 leg 이름): `빗썸` · `바이낸스` · `빗썸·바이낸스`
  - `{source}`: `MANUAL_ENV` → `수동 입력(env)`, `EXCHANGE_API` → `거래소 API`
- 표시하지 않는 값: 수신 주소, webhook URL, API key·secret, env 원문 문자열(수동 펀딩은 **해석된 숫자**만 `signedUsdt` 로), 내부 상태 플래그 이름.

### 7.2 메일 (exact copy, 줄 단위 `\n` 연결)

**포지션 변경** — 제목 `[김프 알림] 포지션 변경 — {legs}`, 경고 시 끝에 ` · 헤지 불일치 확인`.

```
실계정 포지션 변경을 감지했습니다.
빗썸 현물: {kleg(before)} → {kleg(after)}                ← 빗썸 변경 시
빗썸 현물(변경 없음): {kleg(after)}                       ← 빗썸 변경 없을 때
바이낸스 선물: {fleg(before)} → {fleg(after)}            ← 바이낸스 변경 시
바이낸스 선물(변경 없음): {fleg(after)}                   ← 바이낸스 변경 없을 때
바이낸스 포지션 출처: {source}
감지 시각: {time(detectedAt)}
                                                          ← 경고 시 빈 줄
⚠ 바이낸스 env 미갱신 — 헤지 불일치                        ← 경고 시
빗썸 포지션만 바뀌었고 바이낸스 포지션은 수동 입력(env) 값 그대로입니다. 바이낸스 포지션도 바꿨다면 BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 를 고치고 batch 를 재시작하세요. 이 경고는 바이낸스 값이 바뀔 때까지 다시 보내지 않습니다.   ← 경고 시
                                                          ← 빈 줄
순수익률 급변 기준선은 다음 정시 첫 성공 회차에 다시 잡습니다.
조치 안내: docs/runbooks/account-pnl-alert.md
```

**순수익률 급변** — 제목 `[김프 알림] 순수익률 급변 {▲ 상승|▼ 하락} {pp(delta)} · 현재 {p(current)}`.

```
순수익률이 이번 시간 기준선에서 {format("0.00", threshold)}%p 이상 {올랐|내렸}습니다.
기준선({HH(bucketStart)}:00 시간대 첫 성공 회차): {p(baseline)}
현재: {p(current)} (변화 {pp(delta)})
순손익: {krw(netKrw)} · 체결가 기준 {p(netPercentAtLast)}
계산 시각: {time(calculatedAt)}

같은 시간대 안에서 같은 방향은 한 번만 알립니다. 기준선은 매시 첫 성공 회차에 다시 잡습니다.
```

**미반영 리마인더** — 제목 `[김프 알림] 미반영 항목 리마인더 {date(dayStart)} · {n}건`.

```
아래 항목이 순손익 계산에 반영되지 않았거나 오래 갱신되지 않았습니다.
- 진입 수수료(빗썸) 미반영 — 이력 없음                                          ← KOREA_ENTRY_FEE
- 진입 수수료(바이낸스) 미반영 — {수동 미입력|이력 없음}                          ← FOREIGN_ENTRY_FEE (출처 MANUAL_ENV 면 수동 미입력)
- 펀딩비 미반영 — {수동 미입력|이력 없음}                                        ← FUNDING
- 수동 펀딩비 {days}일째 미갱신 — 현재 값 {signedUsdt(value)}, 마지막 변경 {time(changedAt)}   ← MANUAL_FUNDING_STALE

하루 한 번({HH(reminderHour)}:00 이후 첫 성공 회차) 보냅니다. 조치 안내: docs/runbooks/account-pnl-alert.md
```

### 7.3 Discord (exact copy)

| 종류 | 제목 | 색 |
|---|---|---|
| 포지션 변경 | `🔄 포지션 변경 — {legs}` | `0xF1C40F` (15844367) |
| 포지션 변경 + 경고 | `⚠️ 포지션 변경 — {legs} · 헤지 불일치` | `0xE67E22` (15105570) |
| 급변 상승 | `📈 순수익률 급등 {pp(delta)}` | `0x1ABC9C` (1752220) |
| 급변 하락 | `📉 순수익률 급락 {pp(delta)}` | `0xE91E63` (15277667) |
| 리마인더 | `🗒️ 미반영 리마인더 · {n}건` | `0x7F8C8D` (8359053) |

포지션 변경 설명:
```
빗썸 {kleg(before)} → {kleg(after)}          ← 변경 시 / 아니면: 빗썸 {kleg(after)} (변경 없음)
바이낸스 {fleg(before)} → {fleg(after)}      ← 변경 시 / 아니면: 바이낸스 {fleg(after)} (변경 없음)
⚠ 바이낸스 env 미갱신 — 헤지 불일치. BINANCE_MANUAL_SHORT_QUANTITY·BINANCE_MANUAL_ENTRY_PRICE 갱신 후 재시작   ← 경고 시
바이낸스 포지션: {source} · {short(detectedAt)}
```

급변 설명:
```
{HH(bucketStart)}:00 기준 {p(baseline)} → 현재 {p(current)}
순손익 {krw(netKrw)} · 체결가 기준 {p(netPercentAtLast)}
임계값 ±{format("0.00", threshold)}%p · {short(calculatedAt)}
```

리마인더 설명: 항목마다 `• ` + 메일 항목 문구(앞의 `- ` 제외), 마지막 줄 `바이낸스 포지션: {source} · {short(detectedAt)}`.

모두 `DiscordMessage.of` 로 만들어 #88 절단 규칙(제목 255·설명 4096)을 따른다.

### 7.4 data class 자동 `toString()` 노출

| 타입 | 노출 프로퍼티 | 비밀 여부 |
|---|---|---|
| `KoreaLeg`·`ForeignLeg`·`PositionLegs` | 수량·평단·진입가 | 비밀 아님(owner 자기 포지션). 로그에 넣지 않는다(§5.3) |
| `PositionWatchState`·`SwingState`·`ReminderState`·`AccountAlertState` | 위 leg, 기준선, 플래그, 시각, 수동 펀딩 값 | 비밀 아님. 로그 금지 |
| `AccountPnlMonitorState` | 기존 + `account` | 같음 |
| `AccountRound` | legs, 출처, `fundingUsdt`, `pnl`(스냅샷 호가 전체 포함), `observedAt` | 비밀 아님. credential·수신 주소는 스냅샷에 없다 |
| `AccountAlert.*` | 위 + `AccountNetPnl` | 같음 |
| `AccountAlertSettings` | 임계값·시각·일수 | 비밀 아님 |

## 8. DB·cache 순서

DB 만. 상태(기존 열 + V20 열) 갱신과 outbox 행 삽입이 한 트랜잭션(DB-first), 외부 발송은 claim commit 뒤(#88). Redis·cache 무변경.

## 9. 설계 완결성 체크리스트 (orchestrator ④)

| 항목 | 결정 |
|---|---|
| Facade 이름·주입 | **N/A** — batch 전용, REST 없음. 진입은 기존 `AccountPnlMonitorScheduler` → `AccountPnlMonitorJob` |
| Domain port · 구현 모듈 | **새 port 없음.** 기존 `AccountPnlAlertStore`(← `infrastructure:common` `JdbcAccountPnlAlertStore`)가 확장된 `AccountPnlMonitorState` 를 저장한다. 판정은 port 가 아니라 Domain 순수 정책(`object`) — 구현이 하나뿐이고 I/O 가 없다 |
| MarketPair identity | **N/A** — owner 단일 계정(BTC) 헤지. eventKey·상태 행에 pair 가 없다(#88 과 같음) |
| 시간·범위 | 모든 시각 `Instant` + 주입 `Clock`. 급변 버킷 `[정시, +1h)`·리마인더 날짜 `[zone 자정, 다음 자정)` 은 Job `zone`(기본 `Asia/Seoul`). DB 집계 버킷이 아니므로 UTC 버킷 규칙 대상 아님. `aggregation.zone` 은 이 Job 에 연결돼 있지 않다(#88 §10) |
| DB·cache 순서 | DB-first 한 트랜잭션, cache 없음 |
| Flyway | 필요. `V20__add_owner_account_alert_state.sql`(V19 + 1, Task 0 확인). DDL §4.2. batch fixture 동기화 |
| batch lock·lease·timeout·JobResult | 무변경(`lock:account-pnl:monitor`, 50s, 40s). 분기 §5.2 |
| 알림 event identity | 구독 없음. identity = `(event_key, channel)`, eventKey §3.6(급변: 버킷+방향, 포지션: 감지 시각, 리마인더: zone 날짜). cooldown 은 상태(`upSent`/`downSent`/`lastDayStart`/`hedgeMismatchWarned`)가 소유. claim/fencing 은 #88 그대로 |
| 공개 endpoint | 변경 없음 |
| Redis | 변경 없음 |

## 10. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| Domain | leg 정규화(dust·flat·scale), 상태 불변식, 설정 검증, 정책 3개 전이표 전 행 + 경계값(임계값 0.49/0.50, 정시 경계 `10:59:59.999`/`11:00:00` KST, 리마인더 `08:59:59`/`09:00:00` KST, 자정 `15:00Z`, 펀딩 `3d−1ms`/`3d`), 조합기(실패·포지션 없음·첫 기동·변경→기준선 초기화·알림 순서), 렌더링 golden 6종(메일 3·Discord 3 + 경고·하락·manual 변형), eventKey, 라우팅 기본표 | `domain` unit (고정 `Instant`, `ZoneId.of("Asia/Seoul")`, 가짜 수치 — 실제 포지션 값 금지) |
| persistence | V20 두 경로(빈 DB, V19→V20 + 기존 행), CHECK 3개, 구 binary INSERT/UPDATE 호환·새 열 보존, store round-trip(INITIAL·전체 값·정밀도·version 충돌 시 미기록·길이 검증) | `infrastructure:common` integration (MySQL Testcontainers) |
| application | Job: `accountAlerts=null` 이면 새 행 0·`account` 그대로, 설정 시 3종 행·경로(메일+Discord 구성), 실패 회차 무판정, 정시 보고 꺼짐에도 기준선, 재시작(새 인스턴스) 지속, version 충돌 | `apps:batch` unit (#88 `FakeOwnerAlertStore`·`MutableClock` 재사용 — 무수정) |
| wiring | env 3개 기본값·override·잘못된 값 기동 실패, 기동 INFO routes 에 새 3종, yml routes key = enum | `apps:batch` unit (`ApplicationContextRunner`) |
| E2E | 실제 MySQL fixture + 실제 store 로 첫 기동 → 빗썸 변경(manual, 경고) → 재시작 → 바이낸스 변경 → 급변 → 리마인더, outbox 행·상태 열 | `apps:batch` integration |
| 경계 | 모듈·import·test isolation | root `architectureTest` (신규 규칙 없음) |
| 사람 | 실제 Discord 에서 3종 수신·푸시 수준 | T4 owner |

## 11. 문서 갱신 대상

- `docs/runbooks/account-pnl-alert.md` — `## 환경변수` 에 env 3개, `### 기본 라우팅` 표에 3종(+ Discord 꺼짐이면 메일로 온다), `### 종류별 override env` 의 `{KIND}` 목록,
  신규 `## owner 계정 알림 (포지션 변경·급변·리마인더)` 절(판정 규칙, 헤지 불일치 경고 해소 조건, 첫 기동, 리마인더 시각·소비 규칙, 수동 펀딩 변경 시각 자동 기록과 배포 후 N일, 메시지 예),
  `### 배포와 롤백` 에 V20(롤백 SQL 없음), `## 끄는 법과 데이터 정리` 의 `account_pnl_monitor_state` 설명, 신규 `## 계정 알림 Discord 체크리스트 (owner-account-alerts AC14)`
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — 실계정 알림 흐름에 계정 알림 3종 한 줄
- `.ai/PROJECT_STATUS.md` — migration 표에 V20 행
- `docs/runbooks/redis-contract.md`·`auth-security.md`·`durable-notification-delivery.md` — 무변경

## 12. 가정과 결정

### [ASSUMPTION]

| # | 가정 | 확인 방법·시점 |
|---|---|---|
| A1 | MySQL 8.0 에서 한 `ALTER TABLE` 로 열 12개 + 새 열을 참조하는 CHECK 3개가 성공한다 | Task 8 migration integration test (V19 선례) |
| A2 | 급변 버킷 기준 시각 = 회차 관측 완료 시각(`observedAt`, 포지션 있으면 `pnl.calculatedAt`) — 정시 보고와 같은 버킷 | Task 4·10 테스트 |
| A3 | 리마인더는 설정 시각 이후 **첫 포지션 있는 성공 회차** 한 번만 판정하고, 그때 항목이 없으면 그날은 보내지 않는다. 실패·포지션 없음 회차는 그날을 소비하지 않는다 | ⑥·⑦ 확인 — 사용자 답에 따라 R4 를 "항목이 생길 때까지 계속 판정"으로 바꿀 수 있다 |
| A4 | 수동 펀딩 "변경 시각"의 첫 값은 V20 배포 후 첫 관측 시각이다(과거 변경 시각은 알 수 없다). API 모드로 전환하면 추적을 지운다 | runbook 명시 |
| A5 | 빗썸 수량 < 0.0001(dust)은 빈 leg, 바이낸스 수량 0 은 진입가 무시 — dust 변동·청산 후 잔여 평단은 변경이 아니다 | Task 2 테스트 |
| A6 | 헤지 불일치 경고는 바이낸스 leg 변경 또는 API 출처일 때만 해소된다. 빗썸이 원래 값으로 돌아와도 해소로 보지 않는다 | ⑦ 확인 |
| A7 | Discord 가 꺼져 있으면 #88 D1 규칙대로 새 3종도 메일로 간다(급변 최대 시간당 2통) | ⑦ 확인 — 원치 않으면 기본 경로를 바꾸는 대신 runbook 의 override 로 끈다 |
| A8 | 한 회차 행 순서는 기존 알림 뒤 포지션 → 급변 → 리마인더 | Task 10 테스트 |
| A9 | 운영 중 `netPercent` 절댓값은 10^10 미만이다(DECIMAL(12,2)). 넘으면 save 검증 실패로 회차 `Failure` | 정상 포지션(수량 ≥ dust)에서는 성립 |

### [DECISION_NEEDED]

없음 — 범위는 ③ 합의. A3·A6·A7 은 결과가 달라지는 해석이라 ⑦ 에서 확인을 요청한다.

## 13. 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | — | — |
