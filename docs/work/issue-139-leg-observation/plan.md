# Plan — 레그별 관측 이력 테이블 (#139)

Spec: `docs/work/issue-139-leg-observation/design.md` · 계약: `docs/work/issue-139-leg-observation/dod.md` (FROZEN)

## Global Constraints

- 테이블명: `account_foreign_leg_observation`(바이낸스), `account_korea_leg_observation`(빗썸). migration 파일: `V28__create_account_leg_observation.sql`.
- 두 테이블 PK `(episode_id, observed_at)`, FK `(episode_id, observed_at)` → `account_pnl_observation (episode_id, observed_at)`.
- 바이낸스 열 순서·타입: `episode_id BIGINT NOT NULL`, `observed_at DATETIME(6) NOT NULL`, `leverage INT NULL`, `margin_type VARCHAR(8) NULL`,
  `liquidation_price DECIMAL(30,8) NULL`, `mark_price DECIMAL(30,8) NULL`, `isolated_margin DECIMAL(30,8) NULL`, `maint_margin DECIMAL(30,8) NULL`,
  `entry_commission_usdt DECIMAL(30,8) NULL`, `funding_usdt DECIMAL(30,8) NULL`, `position_source VARCHAR(16) NOT NULL`, `created_at DATETIME(6) NOT NULL`.
- 빗썸 열 순서·타입: `episode_id BIGINT NOT NULL`, `observed_at DATETIME(6) NOT NULL`, `entry_fee_krw DECIMAL(24,4) NULL`, `created_at DATETIME(6) NOT NULL`.
- 제약 이름(정확히): `pk_account_foreign_leg_observation`, `fk_account_foreign_leg_observation_round`, `ck_account_foreign_leg_observation_config`
  (leverage·margin_type 함께 NULL/함께 값, margin_type IN ('CROSSED','ISOLATED')), `ck_account_foreign_leg_observation_risk`
  (mark_price·isolated_margin·maint_margin 함께 NULL/함께 값), `ck_account_foreign_leg_observation_source` (position_source = 'EXCHANGE_API');
  `pk_account_korea_leg_observation`, `fk_account_korea_leg_observation_round`.
- ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci (V24 와 같음).
- "없음"(스냅샷 null)은 NULL 로 저장한다. 0 으로 바꾸지 않는다.
- 바이낸스 레그는 `positionSource == MANUAL_ENV` 또는 `signedQuantity` 0 이면 없다. 빗썸 레그는 `quantity` 0 이면 없다.
- 레그 행은 HOLDING 관측 INSERT 직후 같은 트랜잭션에서만 쓴다. FLAT 관측·관측 0행이면 레그 행 0행.
- 중복 키는 `ON DUPLICATE KEY UPDATE episode_id = episode_id` 로 무시, 다른 오류는 전파.
- 레그 값(가격·수량·금액)은 toString·로그에 넣지 않는다.

## Task 1 — 도메인 레그 관측 (AC1)

- 신규 `domain/src/main/kotlin/io/premiumspread/domain/accountpnl/PositionLegObservation.kt`
  - `data class ForeignLegObservation(leverage: Int?, marginType: ForeignPerpMarginType?, liquidationPrice: BigDecimal?, markPrice: BigDecimal?, isolatedMargin: BigDecimal?, maintMargin: BigDecimal?, entryCommissionUsdt: BigDecimal?, fundingUsdt: BigDecimal?)` — 값은 `symbolConfig`·`positionRisk`·스냅샷 필드에서 그대로. source 는 항상 EXCHANGE_API 라 필드 없음(저장 시 상수).
  - `data class KoreaLegObservation(entryFeeKrw: BigDecimal?)`
  - `data class LegObservations(korea: KoreaLegObservation?, foreign: ForeignLegObservation?)` + `companion { val NONE; fun of(korea: KoreaSpotAccountSnapshot, foreign: ForeignPerpAccountSnapshot): LegObservations }`
  - 세 타입 toString 은 값 없는 고정 문자열.
- `PositionObservation.kt` 의 `PositionHistoryRound` 에 마지막 파라미터 `val legObservations: LegObservations = LegObservations.NONE` 추가. toString 불변.
- 테스트 `domain/src/test/kotlin/io/premiumspread/domain/accountpnl/PositionLegObservationTest.kt` — AC1 (a)~(e) 각각 하나 이상.
- 검증: `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.PositionLegObservationTest --offline --no-daemon`

## Task 2 — migration V28 + batch fixture (AC5, AC6)

- 신규 `infrastructure/common/src/main/resources/db/migration/V28__create_account_leg_observation.sql` — Global Constraints 그대로. 머리 주석에 #139·NULL=없음·DECIMAL 사유.
- 신규 `infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/migration/V28LegObservationMigrationIntegrationTest.kt`
  (`V26RealizedPnlMigrationIntegrationTest` 패턴: target 27 migrate → episode·event·observation 행 삽입 → 스냅샷 → 최신 migrate → 스냅샷 동일 + 두 테이블 존재).
  위반 테스트: 관측 없는 키 → FK 이름 포함 SQLException; margin_type 'BAD'·leverage 만 값 → `ck_..._config`; mark_price 만 값 → `ck_..._risk`; MANUAL_ENV → `ck_..._source`. 정상 행(전부 NULL 지표, 전부 값) 삽입 성공.
- `apps/batch/src/integrationTest/resources/batch-schema.sql` 에 두 테이블 DDL 추가(`CREATE TABLE IF NOT EXISTS`, account_pnl_observation 뒤).
- `apps/batch/src/integrationTest/kotlin/io/premiumspread/config/AccountPositionHistoryFixtureSchemaIntegrationTest.kt` 에 테스트 추가:
  두 테이블 열 목록(`name:type:nullable`), `information_schema.TABLE_CONSTRAINTS` 의 (이름, 종류) 목록, `KEY_COLUMN_USAGE` 로 FK 참조 열 순서 `episode_id, observed_at`. 클래스 KDoc 에 V28 언급 추가.
  batch 테스트 base 가 매 테스트 전 테이블을 비우는 목록이 있으면 두 테이블을 FK 순서(자식 먼저)로 추가.
- 검증: `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.migration.V28LegObservationMigrationIntegrationTest --offline --no-daemon`,
  `./gradlew :apps:batch:integrationTest --tests io.premiumspread.config.AccountPositionHistoryFixtureSchemaIntegrationTest --offline --no-daemon`, `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon`

## Task 3 — store 저장 (AC2, AC3) — L 위험 영역(트랜잭션)

- `JdbcAccountPositionHistoryStore.recordObservation` HOLDING 분기: `insertObservation` 다음 `insertLegs(openEpisodeId, observation.observedAt, round.legObservations, now)`. FLAT 분기는 그대로.
  `insertForeignLeg`·`insertKoreaLeg` 는 null 이면 건너뜀. position_source 는 `'EXCHANGE_API'` 상수. margin_type 은 `name`.
- 클래스 KDoc 과 `AccountPositionHistoryStore` port KDoc 에 레그 행 규칙 한 줄 추가.
- 통합 테스트 `JdbcAccountPositionHistoryStoreIntegrationTest` 추가 (기존 헬퍼 `hedgedRound`·`round` 를 legObservations 받도록 확장, cleanup 에 레그 테이블 먼저):
  - `레그 HOLDING 회차는 관측 바이낸스 빗썸 레그가 같은 키로 1행씩이고 값이 입력과 같다`
  - `레그 바이낸스 레그가 없으면 바이낸스 행은 0행이고 빗썸 행은 남는다`
  - `레그 미반영 값은 0 이 아니라 NULL 로 저장된다`
  - `레그 FLAT 관측 회차에는 레그 행이 없다`
  - `레그 같은 round 재기록은 레그 행을 늘리지 않는다`
  - `트랜잭션 레그 INSERT 가 실패하면 같은 회차의 관측 건 사건도 롤백된다` (`account_foreign_leg_observation` INSERT 에서 던지는 JdbcTemplate 하위 클래스, 기존 `ObservationFailingJdbcTemplate` 패턴)
  - 테스트 DB 스키마가 Flyway 최신이면 별도 작업 없음; 아니면 V28 포함 확인.
- 검증: `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountPositionHistoryStoreIntegrationTest --offline --no-daemon`

## Task 4 — Job 연결 (AC4)

- `AccountPnlMonitorJob.history(...)` 가 `legObservations = LegObservations.of(koreaSnapshot, foreignSnapshot)` 를 채운다. 다른 동작 불변.
- `AccountPnlMonitorJobPositionHistoryTest` 에 `Computed 회차 round 의 레그 관측은 그 회차 스냅샷에서 만든 값이다` 추가(기존 fake 스냅샷 사용, `LegObservations.of(korea, foreign)` 와 equals). 기존 historyFailure 테스트 유지.
- 검증: `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobPositionHistoryTest --offline --no-daemon`

## 최종 게이트

- `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon`
- `bash docs/check-documentation.sh`
