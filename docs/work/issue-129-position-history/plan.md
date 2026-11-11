# 실계정 포지션 기록과 매분 관측 이력 저장 — 계획 (#129)

Spec: `docs/work/issue-129-position-history/design.md` · 계약: `dod.md`(FROZEN 2026-11-10)

## Global Constraints

- 작업 위치는 worktree `.worktrees/feat-issue-129-position-history`(branch `feat/issue-129-position-history`)뿐이다.
- 기존 테스트 파일은 수정하지 않는다. 허용 예외는 없다 — 새 동작은 새 테스트 파일로 잰다.
- `AccountPnlMonitorJob` 생성자는 **맨 끝**에만 인자를 붙인다(기본값 `null`). 기존 호출부가 그대로 컴파일돼야 한다.
- 기존 migration(V1~V23)·`account_pnl_latest`·`account_pnl_monitor_state`·`AccountPnlAlertStore` 계약을 바꾸지 않는다.
- 새 Domain 타입은 수량·평단·금액을 `toString` 에 싣지 않는다. 로그에도 싣지 않는다(종류·건 id·시각만).
- Domain 은 순수 Kotlin(허용 의존만). 앱(`apps:batch`)은 infrastructure 타입을 import 하지 않는다.
- 시각은 주입한 `Clock`·회차 `observedAt` 만 쓴다. 테스트는 고정 시각.
- 커밋: `<type>: <subject>` + 한글 bullet 본문, `Co-Authored-By` 금지. 태스크마다 커밋.
- 각 태스크는 테스트를 먼저 쓰고 RED(기대한 단언 실패)를 확인한 뒤 구현한다. RED·GREEN 출력 요지를 보고서에 남긴다.
- `apps/batch` 를 고치기 전에 `.ai/rules/batch.md` 를 읽는다. 엔티티가 아니라 JDBC 를 쓴다(기존 accountpnl 저장소 관례).

## 용어

- **정규화 leg**: `PositionLegs` 에서 바이낸스 롱(`signedQuantity > 0`)을 `ForeignLeg.FLAT` 으로 바꾼 값. 이력은 항상 정규화 leg 만 본다.
- **열림 조건**: 정규화 leg 에서 `!korea.isEmpty || !foreign.isFlat`.
- **헤지 성립(hedged)**: `!korea.isEmpty && !foreign.isFlat`(= `AccountHedgePosition.resolve != null` 과 같은 조건).

---

### Task 1: 김프·VWAP 공유 함수 추출과 포지션 건 정책 (Domain)

**파일**
- 신규 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/HedgeQuote.kt`
- 수정 `domain/.../accountpnl/AccountPnlBreakdown.kt`, `AccountNetPnl.kt` — private `premiumPercent`·`vwap` 본문을 `HedgeQuote` 호출로 교체(동작 불변)
- 신규 `domain/.../accountpnl/PositionEpisode.kt`, `PositionEpisodePolicy.kt`
- 신규 테스트 `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/HedgeQuoteTest.kt`, `PositionEpisodePolicyTest.kt`

**공개 시그니처**
- `object HedgeQuote { fun premiumPercent(korea: BigDecimal, foreign: BigDecimal, fx: BigDecimal): BigDecimal; fun vwap(levels: List<PriceLevel>, quantity: BigDecimal, side: String): BigDecimal }` — 식·반올림·예외(`INSUFFICIENT_DEPTH`)는 기존 private 함수와 바이트 단위로 같은 결과.
- `enum class PositionEventType { OPENED, INCREASED, REDUCED, ADJUSTED, CLOSED }`
- `data class ReferenceLegs(val koreaQuantity, val koreaAveragePrice, val foreignQuantity /* 절댓값 */, val foreignEntryPrice)` — `toString` 은 타입명만.
- `data class OpenEpisode(val id: Long, val legs: PositionLegs, val reference: ReferenceLegs?, val eventCount: Int)` — `toString` 은 id·eventCount 만.
- `data class PositionEvent(val type: PositionEventType, val legs: PositionLegs, val hedged: Boolean, val source: ForeignPerpPositionSource, val occurredAt: Instant)`
- `data class EpisodeDecision(val event: PositionEvent?, val reference: ReferenceLegs? /* 사건 뒤 건의 reference */, val closes: Boolean)`
- `object PositionEpisodePolicy { fun normalize(legs: PositionLegs): PositionLegs; fun decide(open: OpenEpisode?, legs: PositionLegs /* 정규화 전 값을 받아 내부에서 정규화 */, source: ForeignPerpPositionSource, at: Instant): EpisodeDecision }`

**결정**
- 사건 분류는 design D2 표. 수량 비교는 빗썸 `quantity`, 바이낸스 `signedQuantity.abs()`. "바뀜"은 `samePosition`.
- reference: 사건 뒤 헤지 성립이면 그 leg 로 갱신, 아니면 직전 값 유지. `CLOSED` 는 직전 reference 유지.
- leg 가 같으면 `event == null`, reference 는 직전 값.
- `open == null` 이고 열림 조건이 아니면 `event == null`.

**테스트가 단언하는 것 (`PositionEpisodePolicyTest`, dod AC1)**
- 열린 건 없음 + 양쪽 leg → `OPENED`, hedged true, reference = 그 leg
- 열린 건 없음 + 빗썸만 → `OPENED`, hedged false, reference null / 다음 회차 양쪽 → `INCREASED`, reference 채워짐 (선체결)
- 열린 건 없음 + 바이낸스 숏만 → `OPENED` / 바이낸스 롱만 → 사건 없음
- 양쪽 수량 증가 → `INCREASED`, 감소(0 아님) → `REDUCED`, 한쪽 증가·한쪽 감소 → `ADJUSTED`, 수량 같고 평단만 변경 → `ADJUSTED`
- 빗썸 0 + 바이낸스 0 → `CLOSED`, closes true, `CLOSED` 사건의 leg 는 EMPTY/FLAT, reference 는 직전 값
- 빗썸 0 + 바이낸스 롱 → `CLOSED`(롱 0 정규화) / 빗썸 0 + 바이낸스 숏 유지 → `REDUCED`(닫힘 아님)
- leg 동일(scale 만 다름) → 사건 없음
- `HedgeQuoteTest`: 기존 `AccountPnlBreakdown`·`AccountNetPnl` 이 내는 값과 같은 입력에서 같은 값, 깊이 부족 시 `INSUFFICIENT_DEPTH`

**검증**
- `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.PositionEpisodePolicyTest --offline --no-daemon`
- `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.*' --offline --no-daemon` (R3 — 기존 테스트 무수정 통과)

**커밋:** `feat: 포지션 건 판정 정책과 김프·VWAP 공유 함수 추가`

---

### Task 2: 관측 모델·회차 입력·저장 port (Domain)

**파일**
- 신규 `domain/.../accountpnl/PositionObservation.kt`, `AccountPositionHistoryStore.kt`
- 신규 테스트 `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PositionObservationTest.kt`

**공개 시그니처**
- `enum class PositionObservationKind { HOLDING, FLAT }`
- `data class PositionObservation(val kind, val observedAt: Instant, val netPercent: BigDecimal?, val netKrw: BigDecimal?, val currentPremiumPercent, val entryPremiumPercent, val premiumGapPercentPoint, val fxRate, val koreaExitPrice, val foreignExitPrice)` — `init` 에서 `HOLDING` ⇔ net 값 non-null 을 `require`. `toString` 은 kind·observedAt 만.
  - `companion fun holding(pnl: AccountNetPnl): PositionObservation` — 김프 3종은 `AccountPnlBreakdown.of(pnl)` 값 그대로, `netKrw` 는 scale 0 HALF_UP(`AccountPnlLatest.computed` 와 같은 규칙), `observedAt = pnl.calculatedAt`.
  - `companion fun flat(reference: ReferenceLegs, market: MarketQuote, observedAt: Instant): PositionObservation?` — 청산가는 `HedgeQuote.vwap(levels, reference 수량)`, 김프는 `HedgeQuote.premiumPercent`. `INSUFFICIENT_DEPTH` 면 `null`(다른 예외는 전파).
- `data class MarketQuote(val koreaBidLevels: List<PriceLevel>, val foreignAskLevels: List<PriceLevel>, val fxRate: BigDecimal)` — `toString` 은 타입명만.
- `data class PositionHistoryRound(val legs: PositionLegs, val source: ForeignPerpPositionSource, val pnl: AccountNetPnl?, val market: MarketQuote, val observedAt: Instant)` — `pnl` 은 헤지 성립·계산 성공 회차만 non-null. `toString` 은 observedAt 만.
- `fun interface AccountPositionHistoryStore { fun record(round: PositionHistoryRound) }` — KDoc 에 계약: 한 트랜잭션, 열린 건을 잠그고 `PositionEpisodePolicy.decide` → 건·사건 반영 → 관측 0~1행. 같은 `(episode, observedAt)` 재호출은 행을 늘리지 않는다.

**결정 — 관측 행 선택 규칙(adapter 가 따른다, 테스트는 Task 3·4)**
1. 사건 반영 뒤 열린 건이 있고 `round.pnl != null` → `holding(pnl)`, 열린 건 id 에 매단다.
2. 사건 반영 뒤 열린 건이 없고, reference 가 있는 가장 최근 닫힌 건이 있다 → `flat(reference, market, observedAt)`, 그 건 id 에 매단다(`null` 이면 0행).
3. 그 밖(한쪽 leg 만 열림, 기준 건 없음) → 0행.

**테스트가 단언하는 것 (`PositionObservationTest`)**
- `holding`: 김프 3종이 같은 `pnl` 의 `AccountPnlBreakdown.of` 값과 같다, netKrw scale 0
- `flat`: reference 수량 0.3 으로 여러 호가 레벨을 소화한 VWAP 가 청산가다(계정 수량과 무관), 환율만 바꾸면 매수김프·현재김프가 같이 움직여 차이가 ±0.01 이내로 유지된다, 깊이 부족이면 null, net 값 null

**검증:** `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.PositionObservationTest --offline --no-daemon`

**커밋:** `feat: 포지션 관측 모델과 이력 저장 port 추가`

---

### Task 3: V24 migration 과 JDBC adapter (infrastructure:common) — **위험 영역(트랜잭션·멱등성)**

**파일**
- 신규 `infrastructure/common/src/main/resources/db/migration/V24__create_account_position_history.sql`
- 신규 `infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/accountpnl/JdbcAccountPositionHistoryStore.kt`
- 수정 `infrastructure/common/.../CommonInfrastructureAutoConfiguration.kt` — `JdbcAccountPnlLatestStore::class` 옆에 등록
- 신규 테스트 `infrastructure/common/src/integrationTest/kotlin/.../jdbc/accountpnl/JdbcAccountPositionHistoryStoreIntegrationTest.kt`

**DDL (전문)**

```sql
-- 실계정 포지션 한 건(어느 한 leg 라도 있으면 열림 → 둘 다 비면 닫힘)과 그 안의 사건, 매분 관측 이력 (#129).
-- 수량·평단·금액은 실계정 값이다. credential·알림 본문은 저장하지 않는다.
CREATE TABLE account_position_episode (
    id                         BIGINT         NOT NULL AUTO_INCREMENT,
    status                     VARCHAR(8)     NOT NULL,
    opened_at                  DATETIME(6)    NOT NULL,
    closed_at                  DATETIME(6)    NULL,
    korea_quantity             DECIMAL(30,12) NOT NULL,
    korea_average_price        DECIMAL(30,8)  NOT NULL,
    foreign_signed_quantity    DECIMAL(30,12) NOT NULL,
    foreign_entry_price        DECIMAL(30,8)  NOT NULL,
    reference_korea_quantity   DECIMAL(30,12) NULL,
    reference_korea_price      DECIMAL(30,8)  NULL,
    reference_foreign_quantity DECIMAL(30,12) NULL,
    reference_foreign_price    DECIMAL(30,8)  NULL,
    position_source            VARCHAR(16)    NOT NULL,
    event_count                INT            NOT NULL,
    open_marker                TINYINT        AS (CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END) STORED,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_position_episode PRIMARY KEY (id),
    CONSTRAINT uk_account_position_episode_open UNIQUE (open_marker),
    CONSTRAINT ck_account_position_episode_status CHECK (
        (status = 'OPEN' AND closed_at IS NULL) OR (status = 'CLOSED' AND closed_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_position_episode_reference CHECK (
        (reference_korea_quantity IS NULL AND reference_korea_price IS NULL
            AND reference_foreign_quantity IS NULL AND reference_foreign_price IS NULL)
        OR (reference_korea_quantity IS NOT NULL AND reference_korea_price IS NOT NULL
            AND reference_foreign_quantity IS NOT NULL AND reference_foreign_price IS NOT NULL)
    ),
    CONSTRAINT ck_account_position_episode_source CHECK (position_source IN ('EXCHANGE_API', 'MANUAL_ENV'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_account_position_episode_closed ON account_position_episode (status, closed_at);

CREATE TABLE account_position_event (
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    episode_id              BIGINT         NOT NULL,
    seq                     INT            NOT NULL,
    event_type              VARCHAR(16)    NOT NULL,
    occurred_at             DATETIME(6)    NOT NULL,
    korea_quantity          DECIMAL(30,12) NOT NULL,
    korea_average_price     DECIMAL(30,8)  NOT NULL,
    foreign_signed_quantity DECIMAL(30,12) NOT NULL,
    foreign_entry_price     DECIMAL(30,8)  NOT NULL,
    hedged                  BOOLEAN        NOT NULL,
    position_source         VARCHAR(16)    NOT NULL,
    created_at              DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_position_event PRIMARY KEY (id),
    CONSTRAINT uk_account_position_event_seq UNIQUE (episode_id, seq),
    CONSTRAINT fk_account_position_event_episode FOREIGN KEY (episode_id) REFERENCES account_position_episode (id),
    CONSTRAINT ck_account_position_event_type CHECK (
        event_type IN ('OPENED', 'INCREASED', 'REDUCED', 'ADJUSTED', 'CLOSED')
    ),
    CONSTRAINT ck_account_position_event_source CHECK (position_source IN ('EXCHANGE_API', 'MANUAL_ENV'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_pnl_observation (
    id                        BIGINT         NOT NULL AUTO_INCREMENT,
    episode_id                BIGINT         NOT NULL,
    kind                      VARCHAR(8)     NOT NULL,
    observed_at               DATETIME(6)    NOT NULL,
    net_percent               DECIMAL(12,2)  NULL,
    net_krw                   DECIMAL(20,0)  NULL,
    current_premium_percent   DECIMAL(12,2)  NOT NULL,
    entry_premium_percent     DECIMAL(12,2)  NOT NULL,
    premium_gap_percent_point DECIMAL(12,2)  NOT NULL,
    fx_rate                   DECIMAL(20,8)  NOT NULL,
    korea_exit_price          DECIMAL(30,8)  NOT NULL,
    foreign_exit_price        DECIMAL(30,8)  NOT NULL,
    created_at                DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_pnl_observation PRIMARY KEY (id),
    CONSTRAINT uk_account_pnl_observation_round UNIQUE (episode_id, observed_at),
    CONSTRAINT fk_account_pnl_observation_episode FOREIGN KEY (episode_id) REFERENCES account_position_episode (id),
    CONSTRAINT ck_account_pnl_observation_kind CHECK (
        (kind = 'HOLDING' AND net_percent IS NOT NULL AND net_krw IS NOT NULL)
        OR (kind = 'FLAT' AND net_percent IS NULL AND net_krw IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_account_pnl_observation_observed_at ON account_pnl_observation (observed_at);
```

- 기존 migration 의 DECIMAL 정밀도 관례와 다르면 기존 관례를 따르고 보고서에 적는다. `verifyMigrations` 의 파괴 SQL gate 에 걸리는 문장이 없어야 한다.

**adapter 결정**
- `@Repository class JdbcAccountPositionHistoryStore(jdbcTemplate: JdbcTemplate, transactionTemplate: TransactionTemplate 또는 기존 accountpnl 저장소가 쓰는 트랜잭션 수단, clock: Clock) : AccountPositionHistoryStore` — 트랜잭션 수단은 `JdbcAccountPnlAlertStore` 가 쓰는 것과 같은 방식.
- `record` 한 트랜잭션 안의 순서:
  1. `SELECT … FROM account_position_episode WHERE status = 'OPEN' FOR UPDATE` → `OpenEpisode?`
  2. `PositionEpisodePolicy.decide(open, round.legs, round.source, round.observedAt)`
  3. 사건이 있으면: `OPENED` 는 건 INSERT(leg = 정규화 leg, event_count 1) + 사건 seq 1. 그 밖은 건 UPDATE(leg·reference·event_count+1, `CLOSED` 면 status·closed_at) + 사건 INSERT(seq = event_count+1).
  4. 사건이 없어도 reference 는 바뀌지 않으므로 건 UPDATE 없음.
  5. 관측 행 선택 규칙(Task 2) → `INSERT IGNORE` 가 아니라 `INSERT … ON DUPLICATE KEY UPDATE id = id` 로 중복 무시(다른 오류는 삼키지 않는다).
- 가장 최근 기준 건 조회: `WHERE status = 'CLOSED' AND reference_korea_quantity IS NOT NULL ORDER BY closed_at DESC, id DESC LIMIT 1`.
- 동시 `OPENED` 경합은 `uk_account_position_episode_open` 위반 예외로 드러난다 — 잡지 않고 전파한다(Job 이 WARN 으로 삼키고 다음 회차가 따라잡는다).
- 로그: 사건이 있을 때만 INFO `Account position event recorded: type={}, episodeId={}, seq={}`. 수량·가격 없음.

**테스트가 단언하는 것 (dod AC2·AC3)**
- 진입(양쪽) → 추가 진입 → 부분 정리 → 전량 정리: 건 1행 `CLOSED`·event_count 4·closed_at = 마지막 회차 시각, 사건 4행 seq 1~4 종류 순서 `OPENED,INCREASED,REDUCED,CLOSED`
- 이어서 재진입 → 건 2행, 열린 건 1행
- 선체결(빗썸만 → 양쪽): 사건 2행, reference 는 첫 사건 뒤 NULL·둘째 사건 뒤 채워짐
- `idempotent…`: 같은 round 두 번 → 건·사건·관측 행 수 불변 / 새 store 인스턴스로 같은 leg 이어 호출 → 사건 추가 없음, 관측은 observedAt 이 다르면 1행 추가
- `singleOpenEpisode…`: 열린 건이 있을 때 `OPEN` 행 직접 INSERT → `DuplicateKeyException`
- 관측: 보유 회차 `HOLDING` 1행(열린 건 id), 정리 회차와 그 다음 회차 `FLAT` 각 1행(닫힌 건 id, net NULL), 기준 건 없음(헤지 미성립으로 닫힘)·한쪽 leg 열림은 0행
- 트랜잭션: 관측 INSERT 가 실패하도록 만든 round(예: CHECK 위반을 유발하는 값이 불가능하면 `JdbcTemplate` 를 감싼 실패 주입)에서 건·사건도 롤백된다

**검증**
- `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest --offline --no-daemon`
- `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` (AC6)
- `./gradlew architectureTest --offline --no-daemon` (R2)

**커밋:** `feat: 포지션 이력 V24 테이블과 JDBC 저장소 추가`

---

### Task 4: 모니터 Job 연결 (apps:batch) — **위험 영역(실패 격리·취소)**

**파일**
- 수정 `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`
- 신규 테스트 `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobPositionHistoryTest.kt`

**결정**
- 생성자 맨 끝: `private val positionHistory: AccountPositionHistoryStore? = null`.
- private `Observed` 에 `history: PositionHistoryRound?` 를 더한다. 성공 회차(Computed·NoPosition)에서만 채운다: `legs = PositionLegs.of(snapshots)`, `source`, `pnl`(Computed 만), `market = MarketQuote(korea.bidLevels, foreign.askLevels, fxSnapshot.rate)`, `observedAt = completedAt`(Computed 는 `pnl.calculatedAt` 과 같은 값).
- 호출 위치: `recordLatest(observed)` 바로 다음, `deliver()` 전. `save()` 가 false·취소·store 실패로 일찍 반환한 회차는 도달하지 않는다.
- 실패 처리는 `recordLatest` 와 같다: 취소면 `Thread.currentThread().interrupt()`, 아니면 `log.warn("Account position history record failed: error={}", exception.javaClass.simpleName)`. 회차 결과·전달 불변.
- 기존 KDoc 의 순서 설명에 한 줄 추가.

**테스트가 단언하는 것 (dod AC4·AC5)** — fake store 가 받은 `PositionHistoryRound` 를 기록
- Computed 회차: round 1건, `pnl` non-null, `market.fxRate` = 회차 환율, `legs` = 스냅샷 leg
- NoPosition 회차: round 1건, `pnl` null, `market` 채워짐(FLAT 계산 입력)
- 관측 실패 회차: 호출 0회
- `historyFailure…`: store 가 `RuntimeException` → `JobResult`·`FakeStore` 에 저장된 상태·outbox 행·sender 호출이 `positionHistory = null` 인 Job 과 같다, WARN 한 줄(금액·수량 없음)
- `historyFailure…Cancellation`: store 가 `InterruptedException` 을 감싼 예외 → 인터럽트 플래그 복원
- `skipped…`: `save()` false → 호출 0회 / 저장 전 인터럽트 → 호출 0회
- AC4 의 "HOLDING·FLAT 행 수"는 이 테스트가 round 입력으로, Task 3 통합 테스트가 행으로 나눠 잰다

**검증**
- `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest --offline --no-daemon`
- `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.*' --offline --no-daemon` (R1)

**커밋:** `feat: 실계정 모니터가 회차마다 포지션 이력을 기록`

---

### Task 5: 배선·설정·runbook (apps:batch, docs)

**파일**
- 수정 `apps/batch/src/main/kotlin/io/premiumspread/config/AccountPnlMonitorConfiguration.kt` — `@Value("\${account-pnl-monitor.position-history.enabled:true}")` + `ObjectProvider<AccountPositionHistoryStore>`; enabled 일 때만 `ifAvailable` 을 Job 에 넘긴다
- 수정 `apps/batch/src/main/resources/application.yml` — `account-pnl-monitor.position-history.enabled: ${ACCOUNT_PNL_POSITION_HISTORY_ENABLED:true}` (기존 `hourly-report` 항목 옆, 같은 표기)
- 신규 테스트 `apps/batch/src/test/kotlin/io/premiumspread/config/AccountPnlMonitorPositionHistoryWiringTest.kt` — 기존 `AccountPnlMonitorWiringTest` 의 context 구성 방식을 따른다
- 수정 `docs/runbooks/account-pnl-alert.md` — env 표에 행 추가, "포지션 이력" 절(무엇이 쌓이는가·첫 배포 시 `opened_at` 의미·1분 표본 한계·확인 쿼리 3개·끄는 법·V24 배포/롤백)

**테스트가 단언하는 것 (dod AC7)**
- 미설정 → Job 이 이력 store 를 받는다(회차 실행 시 store 호출 1회로 관찰) / `false` → 호출 0회

**검증**
- `./gradlew :apps:batch:test --tests io.premiumspread.config.AccountPnlMonitorPositionHistoryWiringTest --offline --no-daemon`
- `bash docs/check-documentation.sh` (R4)

**커밋:** `feat: 포지션 이력 배선과 설정, runbook 추가`

---

### Task 6: 전체 검증

- `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon`
- `bash docs/check-documentation.sh`
- dod 증거 로그에 AC1~AC7 의 RED·GREEN 과 R1~R4 결과를 붙인다. AC8 은 사람 확인 대기.

## DoD 연결

| AC | 태스크 |
|---|---|
| AC1 | T1 |
| AC2·AC3 | T3 |
| AC4 | T2(계산)·T3(행)·T4(입력) |
| AC5 | T4 |
| AC6 | T3 |
| AC7 | T5 |
| AC8 | 사람 확인 (runbook 쿼리는 T5) |

## 진행 기록

| Task | 커밋 |
|---|---|
| T1 | 5997268 |
| T2 | 63eadb8, e1297e7 |
| T3 | 7f547ed |
| T4 | 24135c0 |
| T5 | b7574dc |
| 리뷰 재작업 | 52ccd4b, b974ac6, 5e2c26d |

참고: T3 의 수량·평단 열은 위 스키마 초안의 `DECIMAL` 대신 `VARCHAR(64)` 로 구현했다(design D7).
