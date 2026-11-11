# 기준 포지션 대비 단계 알림 (#131) — design

- 이슈: #131 (상위 #128, 선행 #129 머지됨 `43f8833`)
- 트랙: feature-track **L** — domain·apps:batch·infrastructure:common 3모듈 + 재시작 지속 상태(V25)
- 상태: 초안 (계약 승인 전)

## 1. 의도

owner 가 실제로 보는 기준은 "내 (직전) 포지션 대비 김프가 얼마나 움직였나"다. 지금은 포지션을 정리하면 계정 알림이
멈추고, 시장 김프 알림은 환율 변동에도 울린다. 이 작업은 **기준 포지션 매수김프 대비 현재 김프 차이(%p)** 가
단계 폭 경계를 넘을 때마다 한 번 알린다. 포지션이 없을 때도 직전 포지션을 기준으로 계속 판정해 재진입 시점을 알려 준다.

### 목표
- G1 보유 중: 차이가 +0.5·+1.0… / −0.5… 경계를 넘을 때마다 1회
- G2 무포지션: 가장 최근 정리 포지션 기준으로 계속 판정, 0 도달은 "직전 진입 수준 도달" 문구
- G3 같은 경계 부근 진동에 반복 발송하지 않는다
- G4 재시작 뒤 단계 상태가 이어진다
- G5 새 포지션(기준 평단 변경) 시 기준 전환·단계 초기화

### 비목표
- 수수료·펀딩을 반영한 "실현 가능 손익" 판정 (판정 값은 수수료 제외 김프 차이다)
- 여러 경계를 한 회차에 건너뛸 때 경계별 다건 발송 (한 통만, D4)
- 기존 알림(수익·정시 급변·정시 보고·포지션 변경·리마인더)의 동작 변경

### 반목표 (기술적으로 돌아가도 실패인 것)
- 배포 직후·기준 전환 직후 현재 위치에서 곧바로 알림이 터진다
- 환율만 움직였는데 시장 김프 알림처럼 울린다
- 재시작마다 같은 단계 알림이 다시 온다

## 2. 현재 상태 (근거)

- #129 `PositionObservation` 이 매 성공 회차의 차이를 이미 계산한다.
  - HOLDING: `holdingOrNull(pnl)` → `AccountPnlBreakdown` 의 `premiumGapPercentPoint`
  - FLAT: `flat(reference, market, observedAt)` — 최근 닫힌 건 reference 수량으로 현재 호가 VWAP
  - 두 경로 모두 `HedgeQuote.premiumPercent(korea, foreign, fx)` 같은 식, 같은 현재 환율, 수수료 제외 → 이슈의 판정 값 정의와 일치
- 무포지션 회차도 `observe()` 가 두 계정 스냅샷(호가 포함)과 환율을 읽고 `MarketQuote` 를 만든다(이슈 위험 1 확인 완료).
  신선도 검사도 같다.
- 계정 알림 상태 `AccountAlertState` 는 `account_pnl_monitor_state` 한 행에 버전 조건부로 outbox 행과 **같은 트랜잭션**에
  저장된다 → 동시 회차·재시작에서 중복 발송이 없다.
- 이력 저장(`recordHistory`)은 상태 저장 **뒤**의 별도 트랜잭션이다.

## 3. 결정

### D1 판정 값 = `PositionObservation.premiumGapPercentPoint` 재사용
새 식을 만들지 않는다. 보유 회차는 `holdingOrNull(pnl)`, 무포지션 회차는 `flat(reference, round.market, observedAt)`.
null(평단 0·호가 깊이 부족)이면 이번 회차는 판정하지 않고 상태를 유지한다.

환율 민감도: 차이 = `fx·(F/K − f/k)·100` 이라 환율만 바뀌면 차이가 0 이면 0 그대로, 0 이 아니면 환율 비율만큼만 비례한다
(차이 1%p·환율 1% 변동 → 0.01%p). 시장 김프(환율 1% → 약 1%p)와 달리 환율 단독으로는 경계를 사실상 넘지 않는다. 이를 수용 기준에 고정한다.

### D2 기준과 국면
| 회차 | 국면 | 기준 | 판정 |
|---|---|---|---|
| `pnl != null` (헤지 성립) | HOLDING | 현재 leg 평단 (`pnl.position`) | 함 |
| 정규화 leg 가 완전 flat (빗썸 dust 미만 + 바이낸스 flat/롱) | FLAT | 저장소의 최근 기준 건 reference | 기준 있으면 함 |
| 한쪽 leg 만 있음(진입·정리 중간) | — | — | 안 함, 상태 유지 |
| 실패 회차 | — | — | 안 함, 상태 유지 |

FLAT 기준 조회는 새 read port `AccountReferencePositionReadPort.findLatestReference(): ReferenceLegs?` 다.
의미: **열린 건이 있으면 그 건의 reference, 없으면 reference 가 있는 가장 최근 닫힌 건의 reference**.
상태 저장 전에 읽으므로(이력 기록은 그 뒤) 정리 직후 첫 회차는 아직 OPEN 인 건을 보게 되는데, 그 건의 reference 가 곧 닫힐 건의
reference 와 같아서 결과가 같다. 직전 포지션이 한 번도 없으면 null → 판정 안 함(이슈 위험 2).
구현은 `JdbcAccountPositionHistoryStore` 가 함께 구현한다(같은 테이블, 단순 SELECT). 이력 기록이 꺼져 있으면(`position-history.enabled=false`)
port 를 넘기지 않아 FLAT 판정이 꺼지고 HOLDING 판정만 남는다. 조회 예외는 경고 로그 후 이번 회차 판정 생략(회차 결과 불변).

### D3 기준 식별 = (빗썸 평단, 바이낸스 진입가)
상태에 기준 평단 두 값을 저장하고, 이번 회차 기준 평단과 compareTo 로 다르면 **기준 전환**으로 본다.
- 수량은 식별에 넣지 않는다 — 부분 정리(평단 불변)는 같은 기준으로 단계를 이어 간다. 추가 매수로 평단이 바뀌면 전환이다.
- 보유 → 정리: 마지막 헤지 leg = 건 reference 라 평단이 같아 전환이 아니다 → 보유 중 단계가 무포지션으로 이어진다.
- 신규 진입: 평단이 달라 전환.

### D4 단계 판정 (히스테리시스)
상태 `level: Int` = 마지막으로 알린(또는 초기화한) 경계 번호, 경계값 = `level × w` (w = 단계 폭, 기본 0.50%p).
차이 g, 이번 회차:
- 기준 전환 또는 상태 없음: `level = trunc(g / w)` (0 쪽으로 버림), **알림 없음** (반목표 1)
- `g ≥ (level+1)·w` → `level = floor(g / w)`, 상승 알림 1통
- `g ≤ (level−1)·w` → `level = ceil(g / w)`, 하락 알림 1통
- 그 밖 → 변화 없음

성질: 같은 경계 위아래 진동은 한 단계 폭을 다 움직여야 다시 알린다(G3). 한 회차에 여러 경계를 건너뛰면 가장 먼 경계 한 통.
비교는 compareTo, 경계 포함.

### D5 문구
- 새 `AccountAlert.ReferenceStep(direction, level, stepWidth, phase, observation)` — `kind = OwnerAlertKind.REFERENCE_STEP`.
- 제목: 보유 `기준 대비 김프 +1.00%p` 류, 무포지션 `직전 포지션 대비 김프 −0.50%p`, 무포지션 + level 0 은 `직전 진입 수준 도달`.
- 본문 칸: 경계, 현재 차이, 현재 김프, 기준 매수김프, 환율, 국면. 정확한 문구는 `message-spec.md` "계정 알림" 에 추가하는 것이 정본.
- eventKey: `account-pnl-reference-step-{up|down}-{level}-{observedAt ms}` (상태가 중복을 막고, key 는 같은 회차 재저장만 막는다).

### D6 라우팅
`OwnerAlertKind.REFERENCE_STEP(email=false, discord=PNL, SOUND)`. 기존 override env(`owner-alert.routes.reference-step.*`)로 바꾸거나 끈다.

### D7 상태 저장 (V25, append-only)
`account_pnl_monitor_state` 에 nullable 3열: `reference_step_korea_price VARCHAR(64)`, `reference_step_foreign_price VARCHAR(64)`,
`reference_step_level INT`, CHECK(셋 다 NULL 또는 셋 다 NOT NULL). 가격은 V20 처럼 정규화 10진 문자열.
- 도메인: `AccountAlertState.step: ReferenceStepState = INITIAL` (기본값으로 3인자 생성·기존 동결 테스트 보존).
- 이전 binary 는 새 열을 쓰지 않으므로 롤백해도 값이 보존된다.

### D8 조합 위치
- 순수 함수 `ReferenceStepPolicy.decide(state, gap: ReferenceGap?, stepWidth)` 를 `AccountAlertPolicy.decide` 의 네 번째 정책으로 넣고,
  `decide` 에 `gap: ReferenceGap? = null` 인자를 추가한다(기존 호출·테스트 무수정). alerts 순서는 포지션 → 급변 → 리마인더 → 단계.
- `ReferenceGap(phase, koreaPrice, foreignPrice, observation)` 은 Job 이 도메인 팩토리 `ReferenceGap.of(round, pnl, latestReference)` 로 만든다
  (국면 판별·관측 계산은 도메인, port 조회만 Job).
- 설정 `AccountAlertSettings.referenceStepWidth = 0.50` (양수 검증), env `ACCOUNT_PNL_REFERENCE_STEP_PERCENT` → `account-pnl-monitor.account-alerts.reference-step-percent`.

## 4. 위험

| 위험 | 대응 |
|---|---|
| 기존 계정 상태 equality 테스트가 새 필드로 깨짐 | 기본값 INITIAL, gap null 이면 step 상태 불변 |
| FLAT 기준 조회 실패가 회차를 실패시킴 | Job 에서 잡아 판정만 생략 (recordHistory 와 같은 정책, 취소는 interrupt 보존) |
| 정리 직후 회차의 기준이 다른 건으로 바뀜 | D2 "열린 건 우선" 으로 같은 reference 를 본다. 통합 테스트로 고정 |
| 배포 첫 회차 폭주 | D4 초기화는 알림 없음 |
| 환율 단독 변동 오발송 | D1 근거 + 수용 기준 테스트 |

## 5. 빌더 몫
- 문구 세부 표기(이모지·색)는 기존 급변 알림 관례를 따른다.
- `ReferenceGap`·`ReferenceStepState` 의 toString 은 실계정 값을 숨긴다(#129 관례).
