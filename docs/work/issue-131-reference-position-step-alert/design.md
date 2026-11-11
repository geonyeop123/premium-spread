# 기준 포지션 대비 단계 알림 (#131) — design

- 이슈: #131 (상위 #128, 선행 #129 머지됨 `43f8833`)
- 트랙: feature-track **L** — domain·apps:batch·infrastructure:common 3모듈 + 재시작 지속 상태(V25)
- 상태: 계약 승인 대기 (codex 스펙 리뷰 1라운드 + 반박 1회 반영)

## 1. 의도

owner 가 실제로 보는 기준은 "내 (직전) 포지션 대비 김프가 얼마나 움직였나"다. 지금은 포지션을 정리하면 계정 알림이
멈추고, 시장 김프 알림은 환율 변동에도 울린다. 이 작업은 **기준 포지션 매수김프 대비 현재 김프 차이(%p)** 가
단계 폭 경계를 넘을 때마다 한 번 알린다. 포지션이 없을 때도 직전 포지션을 기준으로 계속 판정해 재진입 시점을 알려 준다.

### 목표
- G1 보유 중: 차이가 +0.5·+1.0… / −0.5… 경계를 넘을 때마다 1회
- G2 무포지션: 가장 최근 정리 포지션 기준으로 계속 판정, 0 도달은 "직전 진입 수준 도달" 문구
- G3 같은 경계 부근 진동에 반복 판정하지 않는다
- G4 재시작 뒤 단계 상태가 이어진다
- G5 새 포지션 진입 시 기준 전환·단계 초기화

### 비목표
- 수수료·펀딩을 반영한 "실현 가능 손익" 판정 (판정 값은 수수료 제외 김프 차이다)
- 여러 경계를 한 회차에 건너뛸 때 경계별 다건 발송 (한 통만, D4)
- 기존 알림(수익·정시 급변·정시 보고·포지션 변경·리마인더)의 동작 변경
- 정확히 한 번 전달 — 전달 보장은 기존 outbox 와 같은 at-least-once 다(D7)

### 반목표 (기술적으로 돌아가도 실패인 것)
- 배포 직후·기준 전환 직후 현재 위치에서 곧바로 알림이 터진다
- 환율만 움직였는데 시장 김프 알림처럼 울린다
- 재시작마다 같은 단계 알림이 다시 판정된다

## 2. 현재 상태 (근거)

- #129 `PositionObservation` 이 매 성공 회차의 차이를 이미 계산한다.
  - HOLDING: `holdingOrNull(pnl)` → `AccountPnlBreakdown` 의 `premiumGapPercentPoint`
  - FLAT: `flat(reference, market, observedAt)` — 기준 수량으로 현재 호가 VWAP
  - 두 경로 모두 `HedgeQuote.premiumPercent(korea, foreign, fx)` 같은 식(소수 둘째 자리), 같은 현재 환율, 수수료 제외
- 무포지션 회차도 `observe()` 가 두 계정 스냅샷(호가 포함)과 환율을 읽고 `MarketQuote` 를 만든다(이슈 위험 1 확인 완료).
- 계정 알림 상태 `AccountAlertState` 는 `account_pnl_monitor_state` 한 행에 버전 조건부로 outbox 행과 **같은 트랜잭션**에 저장된다.
- 이력 저장(`recordHistory`)은 상태 저장 **뒤**의 별도 트랜잭션이고 실패를 삼킨다 → 단계 판정의 정본으로 쓰지 않는다(D3).

## 3. 결정

### D1 판정 값 — **열린 결정 (계약 승인에서 선택)**
표시 값은 이슈 정의 그대로 `PositionObservation.premiumGapPercentPoint`(같은 현재 환율, 수수료 제외)다. 판정 값은 둘 중 하나다.

- **D1-A 정의 그대로:** 판정 = 표시 값. 차이 = `fx·(F/K − f/k)·100` 이라 환율 단독 영향은 `차이 × 환율 변화율` 로 줄지만 0 은 아니다.
  경계 바로 안쪽(예: 0.49, 환율 +7.7% → 0.53)에서는 환율만으로 **첫** 통과 알림이 날 수 있다. 히스테리시스로 재발송은 없다. 이 잔여는 한계로 문서화한다.
- **D1-B 환율 고정 판정 (권장):** 기준 전환 회차의 환율 `fxRef` 를 상태에 저장하고, 판정은 반올림 없이 `fxRef·(F/K − f/k)·100`
  (F·K = 기준 평단, f·k = 현재 청산 VWAP)으로 한다. 가격이 안 바뀌면 판정 값이 정확히 불변이라 "환율만 변한 입력에서는 알림이 없다"가
  경계 직전에서도 성립한다. 알림 본문의 표시 값은 현재 환율 기준(D1-A 값)이라 경계와 0.0x%p 어긋날 수 있다.

어느 쪽이든 관측 계산이 null(평단 0·호가 깊이 부족)이면 이번 회차는 판정하지 않고 상태를 유지한다.

### D2 국면과 기준
| 회차 | 국면 | 기준 | 판정 |
|---|---|---|---|
| `pnl != null` (헤지 성립) | HOLDING | 현재 leg (`pnl.position`) — 상태의 기준 leg 를 이 값으로 갱신 | 함 |
| 정규화 leg 가 완전 flat (빗썸 dust 미만 + 바이낸스 flat/롱) | FLAT | 상태에 저장된 기준 leg | 기준 있으면 함 |
| 한쪽 leg 만 있음(진입·정리 중간) | — | — | 안 함, 상태 유지 |
| 실패 회차 | — | — | 안 함, 상태 유지 |

### D3 기준의 정본 = 알림 상태 (codex ISSUE-2 반영)
기준 leg 4값(빗썸 수량·평단, 바이낸스 수량·진입가)을 단계 상태에 저장해 outbox 와 같은 트랜잭션으로 갱신한다.
이력 저장이 실패해도 FLAT 기준이 사라지거나 옛 포지션으로 바뀌지 않는다.

**부트스트랩:** 상태에 기준이 없을 때(V25 배포 직후, 또는 포지션을 한 번도 관측하지 못함) FLAT 회차만 새 read port
`AccountReferencePositionReadPort.findLatestReference(): ReferenceLegs?` 로 #129 이력에서 한 번 채운다
(열린 건 우선, 없으면 reference 가 있는 가장 최근 닫힌 건). null 이면 판정하지 않는다(이슈 위험 2 "직전 포지션이 없으면 판정 안 함").
port 는 `JdbcAccountPositionHistoryStore` 가 함께 구현한다. 이력 기록이 꺼져 있으면 port 를 넘기지 않아 부트스트랩만 꺼진다.
조회 예외는 경고 로그 후 이번 회차 판정 생략(회차 결과·다른 알림 불변, 취소는 interrupt 보존).

### D4 기준 전환 (codex ISSUE-1 반영)
아래 중 하나면 기준 전환이다. 전환 회차는 단계를 초기화하고 알림을 내지 않는다.
1. 직전 판정 국면이 FLAT 이고 이번이 HOLDING — 완전 정리 뒤 재진입은 평단이 같아도 새 포지션이다
2. 이번이 HOLDING 이고 기준 평단(빗썸 평단, 바이낸스 진입가) 중 하나라도 compareTo 로 다르다 — 추가 진입으로 평단이 바뀜
3. 상태에 기준이 없음

수량만 바뀐 HOLDING(부분 정리)은 전환이 아니다 — 기준 수량만 갱신하고 단계를 잇는다. HOLDING → FLAT 은 전환이 아니다 — 보유 중 단계가
무포지션으로 이어진다.

### D5 단계 판정 (히스테리시스)
상태 `level: Int` = 마지막으로 알린(또는 초기화한) 경계 번호, 경계값 = `level × w` (w = 단계 폭, 기본 0.50%p). 판정 값 g:
- 기준 전환: `level = trunc(g / w)` (0 쪽으로 버림), **알림 없음**
- `g ≥ (level+1)·w` → `level = floor(g / w)`, 상승 알림 1건
- `g ≤ (level−1)·w` → `level = ceil(g / w)`, 하락 알림 1건
- 그 밖 → 변화 없음

같은 경계 위아래 진동은 한 단계 폭을 다 움직여야 다시 알린다(G3). 한 회차에 여러 경계를 건너뛰면 가장 먼 경계 한 건. 비교는 compareTo, 경계 포함.

### D6 문구와 라우팅
- 새 `AccountAlert.ReferenceStep(direction, level, stepWidth, phase, observation)` — `kind = OwnerAlertKind.REFERENCE_STEP`.
- 제목: 보유 `기준 대비 김프 +1.00%p`, 무포지션 `직전 포지션 대비 김프 −0.50%p`, 무포지션 + level 0 은 `직전 진입 수준 도달`.
- 본문 칸: 경계, 현재 차이, 현재 김프, 기준 매수김프, 환율, 국면. 정확한 문구는 `message-spec.md` "계정 알림" 에 추가하는 것이 정본.
- eventKey: `account-pnl-reference-step-{up|down}-{level}-{observedAt ms}`.
- `OwnerAlertKind.REFERENCE_STEP(email=false, discord=PNL, SOUND)`. 기존 override env(`owner-alert.routes.reference-step.*`)로 바꾸거나 끈다.

### D7 상태 저장 (V25, append-only)과 전달 보장
`account_pnl_monitor_state` 에 nullable 열: 기준 leg 4열 `VARCHAR(64)`(V20 처럼 정규화 10진 문자열), `reference_step_level INT`,
`reference_step_phase VARCHAR(8)`(HOLDING|FLAT), D1-B 를 고르면 `reference_step_fx VARCHAR(64)`. CHECK: 전부 NULL 또는 전부 NOT NULL.
- 도메인: `AccountAlertState.step: ReferenceStepState = INITIAL` (기본값으로 3인자 생성·기존 동결 테스트 보존).
- 이전 binary 는 새 열을 쓰지 않으므로 롤백해도 값이 보존된다.
- **보장 수준 (codex ISSUE-4 반영):** 상태와 outbox 행의 원자 저장은 "한 경계 통과 = 판정 1회·outbox 행 1개"를 보장한다(동시 회차·재시작 포함).
  전달은 기존 dispatcher 그대로 at-least-once 다 — Discord 전송 성공 뒤 `markSent` 전에 프로세스가 죽으면 stale 복구 뒤 같은 행이 다시 갈 수 있다.
  runbook 에 명시한다.

### D8 조합 위치
- 순수 함수 `ReferenceStepPolicy.decide(state, gap: ReferenceGap?, stepWidth)` 를 `AccountAlertPolicy.decide` 의 네 번째 정책으로 넣고,
  `decide` 에 `gap: ReferenceGap? = null` 인자를 추가한다(기존 호출·테스트 무수정). alerts 순서는 포지션 → 급변 → 리마인더 → 단계.
- `ReferenceGap(phase, reference: ReferenceLegs, observation, fxRate)` 은 도메인 팩토리 `ReferenceGap.of(round, state.step, bootstrapReference)` 로 만든다
  (국면 판별·관측 계산은 도메인). Job 은 "FLAT 이고 상태 기준이 없을 때만" port 를 조회해 넘긴다.
- 설정 `AccountAlertSettings.referenceStepWidth = 0.50` (양수 검증), env `ACCOUNT_PNL_REFERENCE_STEP_PERCENT` → `account-pnl-monitor.account-alerts.reference-step-percent`.

## 4. 위험

| 위험 | 대응 |
|---|---|
| 기존 계정 상태 equality 테스트가 새 필드로 깨짐 | 기본값 INITIAL, gap null 이면 step 상태 불변 |
| 부트스트랩 조회 실패가 회차를 실패시킴 | Job 에서 잡아 판정만 생략 |
| 이력 기록 실패로 FLAT 기준 유실 | D3 — 기준 정본을 알림 상태로 |
| 같은 평단 재진입이 기준 전환으로 안 잡힘 | D4-1 국면 전이 규칙 |
| 배포 첫 회차 폭주 | D5 초기화는 알림 없음 |
| 환율 단독 첫 통과 | D1 열린 결정. A 면 한계 문서화, B 면 판정 값이 환율 불변 |
| 전달 중복 | D7 — at-least-once 명시 (기존 알림과 같음) |

## 5. 빌더 몫
- 문구 세부 표기(이모지·색)는 기존 급변 알림 관례를 따른다.
- `ReferenceGap`·`ReferenceStepState` 의 toString 은 실계정 값을 숨긴다(#129 관례).

## 6. 스펙 리뷰 기록 (codex adversarial-review, 2026-11-11)
| 이슈 | 판정 | 반영 |
|---|---|---|
| 1 같은 평단 재진입 미초기화 | ACCEPT | D4-1 |
| 2 이력 기록 실패 시 FLAT 기준 유실 | ACCEPT | D3 기준 정본을 상태로, 이력은 부트스트랩만 |
| 3 환율 단독 경계 통과 | REBUT → MAINTAINED | D1 열린 결정으로 사용자에게 |
| 4 원자 저장 ≠ 전달 중복 방지 | ACCEPT | D7 보장 수준 명시 |
| 5 AC 하나에 명령 둘 | ACCEPT | dod AC 분리 |
