# 청산 근접 단계 알림 — 설계 (issue #141)

상위 #137 · 선행 #138(위험 지표 조회, merge e49c2b6)

## 왜

바이낸스 선물 숏 leg 는 김프 헤지의 한쪽이지만, 가격이 오르면 청산가에 가까워진다. 지금은 청산가에 다가가도
아무 알림이 없다. #138 로 실계정 스냅샷에 청산가·마크가·레버리지·마진 모드가 실렸으니, 이 값으로 "진입가에서
청산가까지 얼마나 갔는가"를 단계적으로 알린다. 조회 전용이다 — 증거금 추가·포지션 축소 같은 대응은 하지 않는다.

## 목표 · 비목표 · 반목표

- **목표:** 진행률이 50·60·70·80·90% 경계를 위로 처음 넘을 때 1회씩 메일 + Discord PNL(소리)로 알린다.
  상태는 재시작 후에도 유지된다.
- **비목표:** 회복(하락) 방향 알림, 대응 동작, 수동 모드 판정, 청산가 자체 계산(거래소 값을 그대로 쓴다).
- **반목표:** 진동(55↔49↔55)으로 같은 경계가 반복 발송되는 것. 청산가를 못 읽은 회차가 상태를 바꾸는 것.
  이 판정이 다른 알림·상태 저장을 실패시키는 것.

## 결정

### D1. 진행률 (owner 결정, 이슈 원문)

`progress = (mark − entry) / (liq − entry) × 100`

- 숏·롱 모두 이 식 하나로 된다. 숏은 liq > entry 라 mark 가 오르면 양수, 롱은 liq < entry 라 mark 가 내리면 양수다.
  이슈의 "롱은 부호를 반대로"와 같은 값이다.
- **판정 대상은 #138 어댑터가 관측하는 포지션뿐이다** (codex 스펙 리뷰 ISSUE-3). 어댑터는 hedge mode 에서 SHORT 행을
  우선하고 없으면 BOTH 행을 읽는다. 따라서 숏(SHORT 행 또는 BOTH 음수)과 one-way 모드 롱(BOTH 양수)만 판정한다.
  hedge mode 의 LONG 행만 열린 경우는 어댑터가 flat 으로 보므로 판정하지 않는다(범위 밖, 한계에 적는다).
- 유리한 쪽(숏에서 mark < entry)이면 0 이하다.
- 입력: `entry` = `round.legs.foreign.entryPrice`, `mark`·`liq` = #138 `ForeignPerpPositionRisk.markPrice`·`liquidationPrice`.
- 계산은 `BigDecimal` 로 하고 scale 4, `RoundingMode.DOWN`(0 쪽 절사)으로 나눈다 — 49.99999 가 50 경계로 올라가지 않는다.

### D2. 판정하지 않는 회차 (이슈 범위 + 추론)

아래 회차는 판정하지 않고 상태를 바꾸지 않는다(D5 초기화 제외).

| 조건 | 근거 |
|---|---|
| 관측 실패 회차(`round == null`) | 이슈 "조회 실패". 기존 계정 알림과 같다 |
| `round.source == MANUAL_ENV` | 이슈 "수동 모드" |
| `foreignRisk == null` 또는 `liquidationPrice == null` | 이슈 "청산가 없음(0·조회 실패)" — #138 이 0 을 null 로 만든다 |
| `liq == entry`, 또는 청산가가 진입가의 반대쪽(숏인데 liq < entry, 롱인데 liq > entry) | 추론 — 나눗셈이 0 이거나 진행률 부호가 뒤집혀 유리한 쪽에서 오발송한다 |

바이낸스 leg 가 flat 인 실계정 회차는 판정하지 않되 상태를 초기화한다(D5).

### D3. 경계와 재무장 (owner 결정 + 계약 결정)

`lvl(x)` = `x` 이하인 가장 높은 경계(50·60·70·80·90), 50 미만이면 0. 상태 `level` = 마지막으로 알린 경계(0 = 없음).

한 회차:

1. 재무장: `armed = min(level, lvl(progress + 10))` — 진행률이 알린 경계보다 한 단계(10%p) 아래 경계 밑으로 내려가야
   그 경계를 다시 알릴 수 있다. #131 단계 알림과 같은 히스테리시스다.
2. 상승: `lvl(progress) > armed` 이면 `lvl(progress)` 하나만 알리고 `level = lvl(progress)`. 아니면 `level = armed`.

예시(이슈 수용 기준): 45→55→65→95 는 50·60·90 세 건(95 에서 70·80 을 건너뛰고 가장 높은 90 하나).
55→49→55 는 49 가 40 밑이 아니라 50 이 재무장되지 않아 추가 발송 없음. 55→39→55 는 50 을 다시 알린다.

### D4. 회복 알림 없음 (계약 결정)

진행률이 내려가도 알리지 않는다. 내려가면 D3 재무장만 일어난다.

### D5. 상태 초기화 (계약 결정)

상태는 `(side, entry, level)` 이다. `side` 는 판정한 포지션의 방향(SHORT/LONG), `entry` 는 마지막 판정 회차의 진입가.

- 실계정(`EXCHANGE_API`) 성공 회차에서 바이낸스 leg 가 flat 이면 상태를 비운다(INITIAL). 그 포지션이 끝났다.
- 판정 회차의 방향이 저장된 `side` 와 다르면 `level = 0` 에서 판정한다(분 사이에 숏→롱으로 뒤집힌 경우).
- **진입가가 저장된 `entry` 와 다르면** D3 1단계 전에 `level = min(level, lvl(progress) − 10)`(0 미만이면 0)으로 내려
  현재 경계를 한 번 다시 알릴 수 있게 한다 (codex 스펙 리뷰 ISSUE-2).
  - 이유: 바이낸스 진입가는 추가 진입과 새 포지션에서만 바뀐다(부분 정리·펀딩·증거금 변화는 진입가를 바꾸지 않는다).
    회차 사이에 같은 방향으로 닫고 다시 열면 flat 을 못 보고 진입가만 바뀐다. 이전 level 을 물려받으면 새 포지션의
    첫 경계 알림이 빠진다(예: 이전 90 → 새 포지션 85% 면 90 까지 무알림).
  - 대가: 추가 진입 때마다 현재 경계가 1회 더 올 수 있다. 사용자 행동 횟수로 한정되고 가격 진동으로는 생기지 않는다.
- 청산가만 바뀌면(증거금 변화·교차 마진 지갑 변동·펀딩) 상태를 유지하고 현재 값으로 다시 계산한다(이슈 제안).
- #129 episode 를 쓰지 않는 이유: episode 는 빗썸·바이낸스 두 leg 가 모두 비어야 닫힌다. 바이낸스만 닫고 다시
  열면 같은 episode 라서 새 포지션이 이전 단계를 물려받는다. 청산 위험은 바이낸스 leg 의 것이므로 바이낸스 flat 을
  경계로 쓴다. 또 Job 이 판정 시점에 episode id 를 알려면 저장소 조회가 하나 더 필요하다.

### D6. 저장 (migration V27, nullable 열 추가)

`account_pnl_monitor_state` 에 `liquidation_side VARCHAR(8) NULL`, `liquidation_entry_price VARCHAR(64) NULL`,
`liquidation_level INT NULL` 을 더한다. 진입가는 V25 처럼 정규화한 10진 문자열이다(scale 무관 비교).
CHECK: 셋 다 NULL 이거나, 셋 다 `IS NOT NULL` 이고 `side IN ('SHORT','LONG') AND level IN (0,50,60,70,80,90)`.
`IS NOT NULL` 을 명시해야 한다 — `level IN (...)` 은 level 이 NULL 이면 UNKNOWN 이고 MySQL CHECK 는 UNKNOWN 을
통과시킨다 (codex 스펙 리뷰 ISSUE-1, V25 와 같은 방식).

- V25 와 같은 방식이다. 새 열이 nullable 이라 이전 binary 의 INSERT·UPDATE 가 그대로 돌고 값을 보존한다.
- 상태는 기존 `AccountPnlMonitorState.account` 안(`AccountAlertState.liquidation`)에 있어 기존 버전 조건부 `save`
  한 트랜잭션에 상태와 outbox 행이 함께 들어간다. 새 트랜잭션 경계는 없다.

### D7. 알림 종류와 라우팅

- `OwnerAlertKind.LIQUIDATION_PROXIMITY` — 기본 `email = true`, Discord `PNL`·`SOUND`(이슈 결정).
- 덮어쓰기: `apps/batch` `application.yml` `owner-alert.routes.liquidation-proximity` 블록과 env
  `OWNER_ALERT_LIQUIDATION_PROXIMITY_EMAIL`·`_DISCORD`·`_PUSH`. 기존 종류와 같은 형식이고,
  `OwnerAlertYamlContractTest` 가 종류 집합·env 연결을 이미 강제한다.
- eventKey: `account-pnl-liquidation-{level}-{observedAt ms}`.

### D8. 문구 (정본 `docs/work/alert-message-template/message-spec.md` "계정 알림 — 청산 근접")

- 제목: Discord `🚨 청산 근접 {level}%` / 메일 `[실계정] 청산 근접 {level}%`.
- 칸: 진행률(소수 1자리 %) · 현재 마크가 · 청산가 · 진입가 · 레버리지(`{n}x`) · 마진 모드(교차/격리).
  `symbolConfig == null` 이면 레버리지·마진 모드는 `확인 불가`.
- footer: `진입가→청산가 진행률 · 50%부터 10% 단위 · 같은 경계는 한 단계 내려간 뒤 다시 알림`.
- 실계정 가격을 담는다(이슈 결정). 수량·증거금 금액은 담지 않는다.

### D9. 조립 위치

- `AccountRound` 에 `foreignRisk: ForeignPerpPositionRisk? = null`, `foreignConfig: ForeignPerpSymbolConfig? = null`
  를 더한다(기본 null — 기존 생성 코드 무변경). Job 의 `round()` 가 #138 스냅샷 값을 넘긴다.
- 순수 정책 `LiquidationProximityPolicy.decide(state, round) → (next, alert?)` 를 `AccountAlertPolicy.decide` 가
  기준 포지션 단계 다음에 부른다. 알림 순서도 그 뒤다.
- Job 에 새 분기·새 예외 경로가 없다. 판정이 순수 함수라 실패 흡수 코드가 필요 없다.
- `accountAlerts == null`(계정 알림 꺼짐) 이면 기존처럼 판정하지 않는다.

## 한계 (runbook 에 적는다)

- 1분 회차라 그 사이의 급등은 회차 시점 값으로만 본다. 여러 경계를 한 번에 넘으면 가장 높은 것 하나만 온다.
- 관측 실패 회차(호가 지연·환율 지연 등)에는 판정하지 않는다. 청산가는 거래소 추정값이다.
- 교차 마진은 지갑 잔고가 청산가를 움직이므로 같은 포지션에서도 진행률이 바뀔 수 있다.
- hedge mode 의 LONG 행은 #138 어댑터가 읽지 않아 판정하지 않는다.
- 추가 진입으로 진입가가 바뀌면 현재 경계가 1회 다시 올 수 있다(D5).

## codex 스펙 리뷰 기록

| # | 지적 | 판정 | 반영 |
|---|---|---|---|
| ISSUE-1 | CHECK 가 NULL 때문에 side 만 있는 행을 허용 | 수용 | D6 `IS NOT NULL` 명시, AC6 부분 행 거부 |
| ISSUE-2 | 회차 사이 같은 방향 재진입 시 새 포지션이 이전 level 을 물려받아 첫 경계 누락 | 반박 → codex 고수 → 수용 | D5 진입가 변경 시 현재 경계 재무장, AC5 |
| ISSUE-3 | hedge mode LONG 은 어댑터가 관측하지 않음 | 수용 | D1 판정 대상 한정, 한계 |

## builder 에게 남기는 것

- 테스트 픽스처 구성, 진행률 표시 포맷 함수 위치(`AlertFormat` 재사용 여부), 로그 문구.
