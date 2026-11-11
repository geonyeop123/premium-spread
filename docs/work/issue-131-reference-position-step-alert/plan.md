# #131 기준 포지션 대비 단계 알림 — plan

결정 수준 계획이다. 결정의 근거는 `design.md`, 완료 기준은 동결된 `dod.md`(AC1~AC10, R1~R6)다.
구현자는 아래 결정을 받아 관용적인 코드를 직접 쓴다. 각 태스크는 테스트 먼저(RED 확인) → 구현(GREEN) 순서이며,
RED·GREEN 출력 요약을 보고에 포함한다. 규칙: `.ai/rules/architecture.md`·`testing.md`·`batch.md`·`naming.md`.

공통 금지: R1 대상 테스트 5파일(dod 회귀 방어선)을 수정하지 않는다. 기존 public 시그니처는 인자 추가(기본값 있음)만 허용한다.
실계정 값(수량·가격)은 toString·로그에 싣지 않는다(#129 관례).

---

## Task 1 — Domain: 단계 상태·국면·판정 정책·알림 종류

**파일**
- 신규 `domain/.../accountpnl/ReferenceStep.kt` — `ReferencePhase { HOLDING, FLAT }`, `ReferenceStepState`, `ReferenceGap`, `ReferenceStepDecision`, `ReferenceStepPolicy`
- 수정 `AccountAlertState.kt` — `AccountAlertState` 에 `val step: ReferenceStepState = ReferenceStepState.INITIAL` (4번째, 기본값), `AccountAlertSettings` 에 `val referenceStepWidth: BigDecimal = BigDecimal("0.50")` (마지막, 기본값) + `require(referenceStepWidth.signum() > 0) { "referenceStepWidth must be positive." }`
- 수정 `AccountAlert.kt` — `data class ReferenceStep(direction: SwingDirection, level: Int, stepWidth: BigDecimal, phase: ReferencePhase, observation: PositionObservation) : AccountAlert`, `kind = REFERENCE_STEP`, 파생 `boundary = level × stepWidth`
- 수정 `AccountAlertPolicy.kt` — `decide(state, round, settings, zone, gap: ReferenceGap? = null)`. 단계 정책은 리마인더 뒤, alerts 순서 끝
- 수정 `owneralert/OwnerAlertRouting.kt` — `REFERENCE_STEP(OwnerAlertRoute(email = false, discord = DiscordRoute(PNL, SOUND)))` 를 `UNRECOVERED_REMINDER` 다음에

**공개 시그니처 (결정)**
```kotlin
data class ReferenceStepState(val reference: ReferenceLegs?, val phase: ReferencePhase?, val level: Int?) {
    // init: 셋 다 null 또는 셋 다 non-null
    companion object { val INITIAL = ReferenceStepState(null, null, null) }
}
data class ReferenceGap(val phase: ReferencePhase, val reference: ReferenceLegs, val observation: PositionObservation) {
    val value: BigDecimal get() = observation.premiumGapPercentPoint
    companion object {
        /** FLAT 국면(정규화 leg 완전 flat)인데 state.reference == null 이면 true — Job 이 부트스트랩 조회를 할지 결정한다. */
        fun needsBootstrap(round: PositionHistoryRound, state: ReferenceStepState): Boolean
        /** design D2 표. 판정 안 하는 회차는 null. bootstrap 은 state.reference 가 없을 때만 쓴다. */
        fun of(round: PositionHistoryRound, state: ReferenceStepState, bootstrap: ReferenceLegs?): ReferenceGap?
    }
}
data class ReferenceStepDecision(val next: ReferenceStepState, val alert: AccountAlert.ReferenceStep?)
object ReferenceStepPolicy { fun decide(state: ReferenceStepState, gap: ReferenceGap?, stepWidth: BigDecimal): ReferenceStepDecision }
```
- `of`: `round.pnl != null` → HOLDING, reference = `pnl.position` 의 leg 로 만든 `ReferenceLegs`(foreign 수량은 절댓값), observation = `PositionObservation.holdingOrNull(pnl)`.
  정규화 leg(`PositionEpisodePolicy.normalize`) 가 빗썸 empty + 바이낸스 flat → FLAT, reference = `state.reference ?: bootstrap`, observation = `PositionObservation.flat(reference, round.market, round.observedAt)`.
  그 밖(한쪽 leg)·reference null·observation null → null.
- `decide` (design D4·D5): gap null → (state, null). 전환 = `state.reference == null` ∨ (`state.phase == FLAT` ∧ `gap.phase == HOLDING`) ∨ (`gap.phase == HOLDING` ∧ 평단 2값 중 하나라도 compareTo ≠ 0).
  전환이면 level = `trunc(g/w)`(RoundingMode.DOWN), 알림 없음. 아니면 `g ≥ (level+1)w` → level = floor(g/w)(RoundingMode.FLOOR) 상승, `g ≤ (level−1)w` → level = ceil(g/w)(RoundingMode.CEILING) 하락.
  next 는 항상 reference = gap.reference, phase = gap.phase. toString 은 phase·level 만.

**테스트 (RED 먼저)**
- `ReferenceStepPolicyTest` — AC1 문장의 시나리오 각각을 별도 테스트로: 상승 0.50·1.00 각 1건, 진동 0건, −0.10 으로 0 경계 하락 1건, 0.10→1.60 한 건(level 3), 상태 없음 초기화 0건, 평단 변경 전환 0건, FLAT→HOLDING 같은 평단 전환 0건, 수량만 변경은 이어감, HOLDING→FLAT 이어감, 경계 포함(compareTo). 음수 trunc(−0.70 → level −1).
- `ReferenceGapTest` — AC2: 국면 표 5가지(HOLDING·FLAT 상태 기준·FLAT 부트스트랩·한쪽 leg null·기준 없음 null·계산 불가 null), `needsBootstrap` 참/거짓, 환율 1300→1400 시나리오 3개(0 기준 0건·0.30 기준 0건·0.49 기준 상승 1건; gap→`ReferenceStepPolicy.decide` 연결로 단언).
  fixture 는 기존 `PositionObservationTest`·`HedgeQuoteTest` 의 호가 생성 방식을 따른다. 판정 값 0.49 등은 평단·호가를 역산해 맞춘다(소수 둘째 자리 반올림 주의).
- `OwnerAccountAlertKindTest` 에 `referenceStep*` — AC8 기본 경로.
- `AccountAlertStateTest`(기존 파일, R1 대상 아님)에 `referenceStepWidth` 기본값·양수 검증 추가 가능.
- `OwnerAlertRoutingTest` 의 종류 이름·key 기대 목록에 `REFERENCE_STEP`/`reference-step` 추가(R6, 갱신 허용).

**검증**: `./gradlew :domain:test --offline --no-daemon`
**DoD**: AC1, AC2, AC8, R6(도메인 절반)

---

## Task 2 — Domain: 문구

**파일**
- 신규 `domain/.../accountpnl/ReferenceStepContent.kt` — `object ReferenceStepContent { fun eventKey(alert: AccountAlert.ReferenceStep): String; fun message(alert, zone): OwnerAlertMessage }`
- 수정 `AccountAlertContent.kt` — `eventKey`·`message` 의 `when` 에 `is AccountAlert.ReferenceStep -> ReferenceStepContent...` 위임 한 줄씩
- 수정 `docs/work/alert-message-template/message-spec.md` "계정 알림" 에 세 문구(보유 상승/하락, 무포지션, 직전 진입 수준 도달) 절 추가 — 문구 정본

**결정**
- eventKey `account-pnl-reference-step-{up|down}-{level}-{observation.observedAt ms}`
- 제목: HOLDING `기준 대비 김프 {boundary signed %p}` / FLAT `직전 포지션 대비 김프 {boundary}` / FLAT ∧ level 0 `직전 진입 수준 도달`. Discord 제목 앞 이모지는 상승 📈·하락 📉, 색은 `AccountAlertContent.COLOR_SWING_UP/DOWN` 재사용.
- 칸: 경계, 현재 차이(`premiumGapPercentPoint`), 현재 김프, 기준 매수김프, 환율, 국면(`보유 중` / `포지션 없음 — 직전 포지션 기준`). footer: `기준 ±{w}%p 단계 · 같은 경계는 한 단계 움직인 뒤 다시 알림`.
- 숫자 표기는 `AlertFormat`(percentPoint·percent·krw 류) 기존 함수만 쓴다. author `AccountPnlAlertContent.AUTHOR`, basis `observation.observedAt`.

**테스트**: `ReferenceStepContentTest` — AC7 제목 3종·eventKey 형식·mail subject `[실계정] ` 접두.
**검증**: `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.ReferenceStepContentTest --offline --no-daemon`
**DoD**: AC7

---

## Task 3 — infrastructure:common: V25·상태 저장·부트스트랩 조회

**파일**
- 신규 `infrastructure/common/src/main/resources/db/migration/V25__add_reference_step_state.sql` — `account_pnl_monitor_state` 에 nullable 6열:
  `reference_korea_quantity`, `reference_korea_price`, `reference_foreign_quantity`, `reference_foreign_price` (모두 `VARCHAR(64)`), `reference_step_phase VARCHAR(8)`, `reference_step_level INT`.
  CHECK `ck_account_pnl_monitor_state_reference_step`: 6열 전부 NULL 또는 전부 NOT NULL, phase IN ('HOLDING','FLAT'). V20 헤더 주석 관례(이전 binary 호환) 따름. 파괴 SQL 금지.
- 수정 `JdbcAccountPnlAlertStore.kt` — SELECT·INSERT·UPDATE·`accountArguments`·`toAccountState` 에 6열. 10진 문자열은 V20 처럼 `toPlainString()` 저장, `BigDecimal(String)` 복원. `validateState` 에 길이 검증(기존 legs 검증과 같은 방식).
- 신규 domain port `domain/.../accountpnl/AccountReferencePositionReadPort.kt` — `fun interface AccountReferencePositionReadPort { fun findLatestReference(): ReferenceLegs? }` (KDoc: 열린 건 reference 우선, 없으면 reference 가 있는 최근 닫힌 건, 없으면 null)
- 수정 `JdbcAccountPositionHistoryStore.kt` — 위 port 도 구현. 열린 건 reference 조회(잠금 없음) → null 이면 기존 `latestReferenceEpisode()` 재사용. 트랜잭션 불필요(단일 SELECT 둘).

**테스트**
- `JdbcAccountPnlAlertStoreIntegrationTest` 에 `referenceStep*` — AC4: 저장→새 인스턴스 load 동일, V25 이전 형태 행(새 열 NULL) → INITIAL, 일부 열만 채운 직접 UPDATE 가 제약 위반.
- `JdbcAccountPositionHistoryStoreIntegrationTest` 에 `latestReference*` — AC5: 열린 건 reference 우선, 열린 건 reference 없음(한쪽 leg) → 최근 닫힌 건, 아무것도 없음 → null.
- 신규 `infrastructure/common/src/integrationTest/.../migration/V25ReferenceStepStateMigrationIntegrationTest.kt` — AC6. `V24AccountPositionHistoryMigrationIntegrationTest` 패턴(V24 까지 적용 → 행 삽입 → V25 → 새 열 NULL·기존 값 보존).

**검증**: `./gradlew :infrastructure:common:integrationTest --offline --no-daemon` · `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon`
**DoD**: AC4, AC5, AC6, R5

---

## Task 4 — apps:batch: Job 조합·설정·yml

**파일**
- 수정 `AccountPnlMonitorJob.kt`
  - 생성자 끝 인자 `referencePositions: AccountReferencePositionReadPort? = null`
  - `monitor()` 에서 `AccountAlertPolicy.decide` 호출 전: `observed.history` 가 있으면
    `bootstrap = if (ReferenceGap.needsBootstrap(history, loaded.account.step)) readBootstrap() else null`,
    `gap = ReferenceGap.of(history, loaded.account.step, bootstrap)` 을 만들어 `decide(..., gap)` 에 넘긴다.
  - `readBootstrap()`: port null 이면 null. 예외는 `recordHistory` 와 같은 정책 — 취소면 interrupt 재설정, 그 밖은 `log.warn("Account reference position read failed: error={}", simpleName)` 후 null. 이 경우 gap 이 null 이 되어 단계 상태 불변.
  - `logAccountDecision` 은 그대로(kind·eventKey 로그). 클래스 KDoc 순서 문장에 단계 판정 추가.
- 수정 `config/AccountPnlMonitorConfiguration.kt`
  - `accountAlertSettings` 에 `@Value("\${account-pnl-monitor.account-alerts.reference-step-percent:0.50}") referenceStepWidth: BigDecimal` (scale 2 fallback 주석 관례 유지)
  - `accountPnlMonitorJob` 에 `referencePositions: ObjectProvider<AccountReferencePositionReadPort>` → `if (positionHistoryEnabled) referencePositions.ifAvailable else null`
- 수정 `apps/batch/src/main/resources/application.yml` — `account-alerts.reference-step-percent: ${ACCOUNT_PNL_REFERENCE_STEP_PERCENT:0.5}`, `routes.reference-step` 세 줄(`OWNER_ALERT_REFERENCE_STEP_EMAIL/DISCORD/PUSH`)

**테스트**
- 신규 `AccountPnlMonitorJobReferenceStepTest` — AC3. 기존 `AccountPnlMonitorJobAccountAlertTest`·`AccountPnlMonitorJobPositionHistoryTest` 의 fake(Korea/Foreign/Fx·`FakeOwnerAlertStore`) 구성을 복사·변형해 회차별 호가·leg 를 바꾼다(기존 파일은 수정하지 않는다).
  시나리오: (1) 보유 회차 연속으로 차이 0.1→0.5→0.7→1.0 → `account-pnl-reference-step-up-` 행이 level 1·2 각 1건, Discord PNL 경로. (2) 이어서 전량 정리 회차들로 차이 0.0·−0.5·−1.0 → down 행 각 1건, level 0 행 Discord 제목에 `직전 진입 수준 도달`. (3) 이력 store 가 항상 예외 → (1)(2) 와 같은 행. (4) 부트스트랩 port 예외 → `JobResult`·다른 알림 행 동일, `step` 상태 INITIAL 유지. (5) port null + 상태 기준 없음 FLAT → 단계 행 0.
- 신규 `config/AccountPnlMonitorReferenceStepWiringTest` — AC9. 기존 `AccountPnlMonitorPositionHistoryWiringTest` 패턴(ApplicationContextRunner 류). 미설정 0.50, `1.0` → 1.0, `0` → 기동 실패 메시지 `referenceStepWidth must be positive.`
- `OwnerAlertYamlContractTest` 는 수정 없이 통과해야 한다(yml 추가로) — R6.

**검증**: `./gradlew :apps:batch:test --offline --no-daemon` · `./gradlew architectureTest --offline --no-daemon`
**DoD**: AC3, AC9, R1, R2, R6

---

## Task 5 — 문서

- `docs/runbooks/account-pnl-alert.md` — 환경변수 표에 `ACCOUNT_PNL_REFERENCE_STEP_PERCENT`, 기본 라우팅 표에 `reference-step`, "owner 계정 알림" 아래 `### 기준 포지션 대비 단계` 절(판정 값·실시간 환율 반영·국면·전환·히스테리시스·부트스트랩·at-least-once), V25 배포·롤백, AC10 Discord 체크리스트.
- 나머지(PROJECT_STATUS·ARCHITECTURE_DESIGN 등)는 마무리 단계 tech-docs 서브에이전트가 맡는다.
- **검증**: `bash docs/check-documentation.sh`
- **DoD**: R4, AC10 준비

---

## 순서와 의존
Task 1 → Task 2 → Task 3(1 의 port·상태 타입 필요) → Task 4(1·3 필요) → Task 5. 전체 회귀:
`./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon`
