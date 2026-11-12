# 청산 근접 단계 알림 — 실행 계획 (issue #141)

Spec: [design.md](design.md) · 완료 기준: [dod.md](dod.md)

## Global Constraints

- 모든 코드는 Kotlin, 불변 우선. 도메인은 순수 함수이며 framework 의존을 추가하지 않는다.
- 실계정 값(가격·수량)은 `toString`·로그에 싣지 않는다. 기존 타입(`ReferenceStepState`, `ForeignPerpPositionRisk`)과 같다.
- `OwnerAlertKind` 전체에 대한 `when` 을 두지 않는다 — 분기는 `AccountAlert` sealed 타입으로만 한다.
- migration 은 append-only. V27 만 추가한다. 기존 열 의미를 바꾸지 않는다.
- detekt: 클래스 함수 20개 미만, `ReturnCount` 4 이하. ktlint: 인자가 여러 줄이면 한 줄에 하나.
- 테스트 이름은 한글 backtick. 고정 `Clock`, 외부 I/O 없음.
- 커밋: `<type>: #141 <subject>` + 한글 bullet, Co-Authored-By 없음.

## Task 1 — 도메인: 정책·상태·알림 종류·문구

**파일**
- 신규 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/LiquidationProximity.kt`
  - `enum class PerpSide { SHORT, LONG }`
  - `data class LiquidationProximityState(val side: PerpSide?, val entryPrice: BigDecimal?, val level: Int?)` — 셋 다 null 이거나
    셋 다 non-null, level ∈ {0,50,60,70,80,90}. `INITIAL`. `toString` 은 side·level 만(진입가 없음).
  - `data class LiquidationProximityDecision(val next: LiquidationProximityState, val alert: AccountAlert?)` — 상승(`LiquidationProximity`) 또는 완화(`LiquidationEased`) 중 하나 또는 null
  - `object LiquidationProximityPolicy { fun decide(state, round: AccountRound?): LiquidationProximityDecision; fun progress(entry, mark, liq): BigDecimal? }`
    - design D1(식·scale 4 DOWN), D2(제외 표), D3(재무장 알고리즘), D5(flat 초기화·방향 변경·진입가 변경 재무장) 그대로.
    - `BOUNDARIES = listOf(50, 60, 70, 80, 90)`, `REARM_WIDTH = 10`.
- 수정 `AccountAlertState.kt`
  - `AccountRound` 에 `foreignRisk: ForeignPerpPositionRisk? = null`, `foreignConfig: ForeignPerpSymbolConfig? = null` (마지막, 기본 null).
  - `AccountAlertState` 에 `liquidation: LiquidationProximityState = LiquidationProximityState.INITIAL` (마지막).
- 수정 `AccountAlert.kt` — `data class LiquidationProximity(val level: Int, val progressPercent: BigDecimal, val side: PerpSide,
  val entryPrice: BigDecimal, val markPrice: BigDecimal, val liquidationPrice: BigDecimal, val config: ForeignPerpSymbolConfig?,
  val observedAt: Instant)`, `kind = LIQUIDATION_PROXIMITY`, `toString` 은 level·side·observedAt 만.
  같은 필드 + `previousLevel`·`armedLevel` 을 가진 `LiquidationEased`(`kind = LIQUIDATION_EASED`, design D4).
- 수정 `AccountAlertPolicy.kt` — 단계 판정 다음에 `LiquidationProximityPolicy.decide(state.liquidation, round)`. 알림 순서 마지막.
- 수정 `OwnerAlertRouting.kt` — `LIQUIDATION_PROXIMITY(email = true, Discord PNL·SOUND)`, `LIQUIDATION_EASED(email = false, Discord PNL·SILENT)` 를 `REFERENCE_STEP` 바로 뒤에.
- 신규 `LiquidationProximityContent.kt` — `eventKey`, `message(alert, zone)` (design D8). `AccountAlertContent.eventKey/message`
  의 sealed `when` 에 위임 분기 추가.

**테스트**
- 신규 `LiquidationProximityPolicyTest` — AC1~AC5·AC10 의 테스트 이름(dod 표 그대로).
- 신규 `LiquidationProximityContentTest` — AC9.
- 수정 `OwnerAccountAlertKindTest`(`청산 근접 기본 경로는 메일과 PNL 소리다` 추가, 계정 종류 목록 갱신), `OwnerAlertRoutingTest`(이름·key 순서 갱신).
- 수정 `AccountAlertPolicyTest` — 청산 근접 알림이 기준 단계 뒤에 온다는 단언 1개.

**DoD:** AC1·AC2·AC3·AC4·AC5·AC8(도메인 부분)·AC9·AC10
**검증:** `./gradlew :domain:test --offline`

## Task 2 — 저장: migration V27 · JdbcAccountPnlAlertStore

**파일**
- 신규 `infrastructure/common/src/main/resources/db/migration/V27__add_liquidation_proximity_state.sql`

```sql
ALTER TABLE account_pnl_monitor_state
    ADD COLUMN liquidation_side        VARCHAR(8)  NULL,
    ADD COLUMN liquidation_entry_price VARCHAR(64) NULL,
    ADD COLUMN liquidation_level       INT         NULL,
    ADD CONSTRAINT ck_account_pnl_monitor_state_liquidation CHECK (
        (liquidation_side IS NULL AND liquidation_entry_price IS NULL AND liquidation_level IS NULL)
        OR (liquidation_side IS NOT NULL AND liquidation_entry_price IS NOT NULL AND liquidation_level IS NOT NULL
            AND liquidation_side IN ('SHORT', 'LONG') AND liquidation_level IN (0, 50, 60, 70, 80, 90))
    );
```

- 수정 `JdbcAccountPnlAlertStore.kt` — load SELECT·INSERT·UPDATE 에 세 열, 진입가는 `toPlainString` 저장·길이 검증(V25 reference 와 같은 `MAX_DECIMAL_TEXT_LENGTH`), `toLiquidation()` 복원(열 NULL → INITIAL).

**테스트**
- `JdbcAccountPnlAlertStoreIntegrationTest` 에 `청산 근접 단계 상태를 저장하고 복원한다` — 저장·복원, NULL 행 → INITIAL,
  side 만·level 만 있는 행 직접 UPDATE 시 각각 CHECK 위반 예외.

**DoD:** AC6
**검증:** `./gradlew :infrastructure:common:integrationTest --tests ...JdbcAccountPnlAlertStoreIntegrationTest --offline`,
`./gradlew :infrastructure:common:verifyMigrations --offline`

## Task 3 — Job 조립 · yml · 문서

**파일**
- 수정 `AccountPnlMonitorJob.round()` — `foreignSnapshot.positionRisk`, `foreignSnapshot.symbolConfig` 를 `AccountRound` 에 넘긴다.
  다른 변경 없음.
- 수정 `apps/batch/src/main/resources/application.yml` — `owner-alert.routes.liquidation-proximity`·`liquidation-eased` 블록(email·discord·push → env).
- 문서: `docs/work/alert-message-template/message-spec.md` "청산 근접"·"청산 위험 완화" 절, `docs/runbooks/account-pnl-alert.md`
  (기본 라우팅 표 행, override env, 동작·한계, 운영 체크리스트), `.ai/PROJECT_STATUS.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md` 한 줄.

**테스트**
- 신규 `AccountPnlMonitorJobLiquidationTest` — 숏 진행률 경계 통과 시 `save` 한 번에 EMAIL·DISCORD(PNL,SOUND) 행과 level,
  수동 모드·청산가 null 회차 행 0개. #131 Job 테스트의 Fake* 를 재사용한다.

**DoD:** AC7, AC8(yml)
**검증:** `./gradlew :apps:batch:test --offline`, `docs/check-documentation.sh`

## 최종

`./gradlew test architectureTest --parallel --offline --no-daemon` → `:infrastructure:common:integrationTest`·`:apps:batch:integrationTest`·`verifyMigrations` → DoD Gate 2.
