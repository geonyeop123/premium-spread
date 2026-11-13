# 설계 — 레그별 관측 이력 테이블 (#139)

상위: #137 · 선행: #138(포지션 위험·심볼 설정 조회) · 이슈: https://github.com/geonyeop123/premium-spread/issues/139

## 배경

매분 회차의 `account_pnl_observation`(#129, V24)에는 순손익·김프·환율·청산가 VWAP 만 있다.
"그 시점의 바이낸스 청산가·레버리지·누적 비용"과 "빗썸 진입 수수료"는 스냅샷에만 있고 이력으로 남지 않는다.
owner 결정(2026-11-12, 이슈 본문)은 관측과 1:1 로 분리한 레그 테이블 두 개다. 이 문서는 그 결정을 구현 수준으로 고정한다.
브레인스토밍 질의는 이슈의 owner 결정으로 대신했다 — 열린 결정만 계약 승인 때 묻는다.

## 현재 구조 (바꾸지 않는 것)

- `AccountPnlMonitorJob.recordHistory` 는 상태 저장(`store.save`) 뒤 별도 best-effort 호출이다. 예외는 삼키고 회차 결과·알림은 그대로다.
- `JdbcAccountPositionHistoryStore.record` 는 한 `TransactionTemplate` 안에서 열린 건 `FOR UPDATE` → 건·사건 반영 → 관측 0~1행이다.
  관측 INSERT 실패는 이미 같은 회차의 건·사건을 롤백한다.
- 관측 HOLDING 은 반영 뒤 열린 건이 있고 `round.pnl != null`(양 레그 헤지 성립)일 때만, FLAT 은 열린 건이 없을 때만 생긴다.

## 결정

### D1. 테이블 두 개, 키는 관측과 같은 `(episode_id, observed_at)`

- `account_foreign_leg_observation`(바이낸스), `account_korea_leg_observation`(빗썸).
- PK `(episode_id, observed_at)`. FK `(episode_id, observed_at)` → `account_pnl_observation (episode_id, observed_at)`
  (`uk_account_pnl_observation_round` 가 참조 대상 인덱스). 관측 행 없이 레그 행이 남을 수 없다는 것을 DB 가 보장한다.
- 열 (바이낸스): `leverage INT NULL`, `margin_type VARCHAR(8) NULL`, `liquidation_price DECIMAL(30,8) NULL`, `mark_price DECIMAL(30,8) NULL`,
  `isolated_margin DECIMAL(30,8) NULL`, `maint_margin DECIMAL(30,8) NULL`, `entry_commission_usdt DECIMAL(30,8) NULL`,
  `funding_usdt DECIMAL(30,8) NULL`, `position_source VARCHAR(16) NOT NULL`, `created_at DATETIME(6) NOT NULL`.
- 열 (빗썸): `entry_fee_krw DECIMAL(24,4) NULL`, `created_at DATETIME(6) NOT NULL`.
- CHECK: `margin_type IN ('CROSSED','ISOLATED')`, `leverage` 와 `margin_type` 은 함께 NULL/함께 값(같은 `symbolConfig` 출처),
  `mark_price`·`isolated_margin`·`maint_margin` 은 함께 NULL/함께 값(같은 `positionRisk` 출처, 청산가는 위험 정보가 있어도 null 가능),
  `position_source = 'EXCHANGE_API'`(D3 에 따라 수동 행은 만들지 않는다).
- **근거:** 저장 형식은 V26 관례를 따른다. 이 값들은 다음 회차 비교 키가 아니라 조회·계산용 지표라 VARCHAR 정규화(V20·V24) 대신 DECIMAL 이다.
  금액 USDT 는 V26 `foreign_*_usdt DECIMAL(30,8)`, 원화는 V26 `korea_realized_krw DECIMAL(24,4)` 와 같다.
  넘친 소수 자리는 반올림된다(8자리 초과 USDT·4자리 초과 원화) — 지표 용도로 허용한다.

### D2. "없음"은 NULL 이다 (0 아님)

- `entryCommissionUsdt`·`fundingUsdt`(바이낸스), `entryFeeKrw`(빗썸)가 스냅샷에서 null(`미반영`)이면 열도 NULL 이다.
  계산(`AccountNetPnl`)은 0 으로 쓰지만 이력은 구분한다.
- `positionRisk`·`symbolConfig` 가 null(#138 best-effort 조회 실패)이면 해당 열 묶음이 NULL 이다. 레그 행 자체는 만든다.

### D3. 레그 행을 만드는 조건 — 도메인 `PositionLegObservation`

- 새 파일 `domain/.../accountpnl/PositionLegObservation.kt`:
  `ForeignLegObservation`, `KoreaLegObservation`, 그리고 둘을 묶는 `LegObservations(korea, foreign)` 와 팩토리
  `LegObservations.of(KoreaSpotAccountSnapshot, ForeignPerpAccountSnapshot)`.
- 바이낸스 레그: `signedQuantity == 0` 이거나 `positionSource == MANUAL_ENV` 면 null.
- 빗썸 레그: `quantity == 0` 이면 null.
- `PositionHistoryRound` 에 `legObservations: LegObservations = LegObservations.NONE` 을 추가한다(기본값으로 기존 호출부 유지).
- toString 은 값 없이 고정 문자열(실계정 값, #129 관례).

### D4. 저장 위치 — HOLDING 관측 INSERT 직후, 같은 트랜잭션

- `recordObservation` 의 HOLDING 분기에서 `insertObservation` 다음에 레그 행을 INSERT 한다. FLAT 관측에는 레그 행을 만들지 않는다
  (열린 건이 없다 = 보유 레그가 없다). 관측 0행이면 레그 행도 0행이다.
- 중복 `(episode_id, observed_at)` 은 관측과 같이 `ON DUPLICATE KEY UPDATE episode_id = episode_id` 로 무시한다. 다른 오류는 전파한다.
- 레그 INSERT 실패 → 트랜잭션 롤백 → 같은 회차의 관측·건·사건이 남지 않는다. 이는 기존 "관측 INSERT 실패면 건·사건 롤백" 계약의 확장이다.
- Job `recordHistory` 의 best-effort 경계(예외 삼킴, 회차 결과·알림 무관)는 그대로다. Job 은 `history(...)` 에서 `LegObservations.of(korea, foreign)` 를 채우기만 한다.

### D5. migration 과 배포

- `V28__create_account_leg_observation.sql` — CREATE TABLE 두 개만. 기존 테이블·행은 건드리지 않는다(append-only).
- batch 는 Flyway 를 쓰지 않으므로 `apps/batch/src/integrationTest/resources/batch-schema.sql` 에 같은 DDL 을 옮기고
  fixture 스키마 테스트로 열 목록을 고정한다.
- 배포는 API(Flyway) 먼저, batch 나중. 이전 batch binary 는 새 테이블을 쓰지 않으므로 순서가 뒤집혀도 batch 가 쓰기 전까지 무해하다.
  새 batch 가 먼저 뜨면 레그 INSERT 가 실패해 그 회차 이력 전체가 롤백된다(회차·알림은 영향 없음) — 그래서 API 먼저다.

### D6. 로그·개인정보

- 레그 값은 로그에 남기지 않는다. 기존 `Account position event recorded` 로그만 유지한다.

## 범위 밖

- 청산 알림 판정(#137 별도 하위), 조회 API·화면, 보존 기간 정책(레그당 분당 1행, 연 약 52만 행).
- FLAT 회차의 레그 기록, 한쪽 레그만 열린 회차(관측 0행)의 레그 기록.

## 위험

- 레그 DECIMAL 정수부 초과(USDT 22자리 초과)는 INSERT 오류 → 그 회차 이력 전체 롤백. 현실 값 범위에서 발생하지 않는다.
- 행 수 증가: 관측 1행당 최대 2행. 보존 정책은 범위 밖으로 이슈에 기록돼 있다.
