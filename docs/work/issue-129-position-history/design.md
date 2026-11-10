# 실계정 포지션 기록과 매분 관측 이력 저장 — 설계 (#129)

상위 #128 · 후속 #130(확정 손익), #131(단계 알림). 2026-11-10 owner 와 합의한 모양을 저장 계층으로 옮긴다.

## 1. 문제와 목표

- 실계정 모니터(`AccountPnlMonitorJob`)는 매분 순손익을 계산하지만 DB 에는 최신 한 건(`account_pnl_latest`)만 남는다.
- 목표: (a) 포지션 한 건(수량 0 → 다시 0)과 그 안의 사건을 기록하고, (b) 매분 관측을 이력으로 쌓는다. 포지션이 없는 기간도 직전 포지션을 기준으로 계속 쌓는다.
- 이 이슈는 **저장만** 한다. 알림 규칙·문구·기존 상태 전이는 바꾸지 않는다. #130·#131 이 이 데이터를 읽는다.

## 2. 결정

### D1. 포지션 한 건의 경계 — "어느 한 leg 라도 있으면 열림, 둘 다 비면 닫힘"

- 열림 조건: 빗썸 leg 가 dust 이상(`KoreaLeg` 비어 있지 않음) **또는** 바이낸스 leg 가 숏.
- 닫힘 조건: 빗썸 leg 가 비고 바이낸스 leg 가 숏이 아님.
- 바이낸스 롱은 "없음"으로 본다(기존 `AccountHedgePosition.resolve` 와 같은 취급).
- 근거: owner 는 나눠 들어가고 나눠 정리한다. 한쪽 leg 를 먼저 체결하는 1~2분의 틈을 "닫힘"으로 보면 한 번의 진입·정리가 여러 건으로 쪼개지고, #130 이 체결을 귀속시킬 구간도 깨진다.
- 버린 대안: 헤지가 성립(`resolve != null`)할 때만 열림 — 한쪽 leg 선체결 구간에서 건이 끊긴다.

### D2. 사건 — leg 가 바뀐 회차마다 한 건

| 종류 | 조건 |
|---|---|
| `OPENED` | 열린 건이 없는데 열림 조건 성립 |
| `INCREASED` | 바뀐 leg 의 수량(절댓값)이 모두 늘었거나 같고, 하나 이상 늘었다 |
| `REDUCED` | 바뀐 leg 의 수량이 모두 줄었거나 같고, 하나 이상 줄었다(닫힘 아님) |
| `ADJUSTED` | 그 밖 — 한쪽은 늘고 한쪽은 줄었거나, 수량은 같고 평단만 바뀜 |
| `CLOSED` | 열린 건이 있는데 닫힘 조건 성립 |

- "바뀜"은 기존 `KoreaLeg.samePosition`·`ForeignLeg.samePosition`(수량·평단 `compareTo`)을 그대로 쓴다.
- 사건 행은 변경 **뒤**의 양쪽 leg(수량·평단), 출처(`EXCHANGE_API`/`MANUAL_ENV`), 감지 시각(회차 `observedAt`)을 담는다.
- 한쪽 leg 선체결로 진입하면 `OPENED` + `INCREASED` 두 건이 남는다. 정상이다.
- 바이낸스 수동 모드에서는 env 값 변경이 그대로 사건이 된다.

### D3. 기준 평단(reference) — 건마다 "마지막으로 헤지가 성립했던 leg"

- 건 행에 `reference_*`(양쪽 수량·평단) 를 둔다. 헤지가 성립(`AccountHedgePosition.resolve != null`)한 회차의 사건에서만 갱신한다.
- 닫힌 건의 `reference_*` 가 포지션 없는 기간의 기준이다. 김프(%)가 아니라 평단을 저장하는 이유: 매수김프는 매 회차 **현재 환율**로 다시 계산해야 환율만 움직인 변동이 걸러진다(합의).
- 헤지가 한 번도 성립하지 않고 닫힌 건(한쪽 leg 만 잡았다 정리)은 reference 가 없다. 기준으로 쓰지 않는다 — 기준은 "reference 가 있는 가장 최근 닫힌 건".

### D4. 관측 행 — 성공 회차마다 최대 한 건

| kind | 언제 | 채우는 값 |
|---|---|---|
| `HOLDING` | 헤지 성립, 순손익 계산 성공 | 순수익률·순손익(KRW)·현재 김프·매수김프·차이·환율·양쪽 청산가 |
| `FLAT` | 열린 건 없음 + 기준 건 있음 | 현재 김프·매수김프(기준 평단)·차이·환율·양쪽 청산가. 순수익률·순손익은 NULL |

- 적재하지 않는 회차: 관측 실패(`Failed`), 한쪽 leg 만 있는 열린 건(순손익이 정의되지 않음), 기준 건이 없는 포지션 없음.
- 김프·차이는 `AccountPnlBreakdown` 의 식과 **같은 함수**를 쓴다(`(korea − foreign × fx) / korea × 100`, 소수 둘째 자리). `FLAT` 의 청산가는 기준 수량으로 같은 VWAP 규칙을 적용한다 — #131 이 보유 중·없음 구간에서 같은 숫자를 이어 쓰기 위해서다.
- `FLAT` 계산이 실패(호가 깊이 부족 등)하면 그 회차는 행을 남기지 않는다. 회차 결과에 영향 없음.
- `FLAT` 행은 기준 건의 id 에 매단다. `HOLDING` 행은 열린 건의 id.

### D5. 쓰는 위치와 트랜잭션 — 기존 저장 **뒤**, 별도 트랜잭션, 자기 상태로 판정

- `store.save()`(상태+outbox)가 성공한 회차에서만, `recordLatest` 다음에 호출한다. 실패는 WARN 한 줄로 삼키고 회차 결과·전달을 바꾸지 않는다(`recordLatest` 와 같은 규칙, 취소는 인터럽트 복원).
- 사건 판정은 모니터 상태(`PositionWatchState`)가 아니라 **이력 저장소 자신의 열린 건 leg** 와 현재 leg 를 비교한다.
  - 저장 성공 뒤 이력 기록 전에 죽어도, 다음 회차가 같은 비교로 변경을 다시 감지한다. 사건이 유실되지 않고 감지 시각만 늦어진다.
  - 기존 상태 테이블·`save()` 계약·V20 열을 건드리지 않는다.
- 한 회차의 이력 쓰기(건 갱신 + 사건 + 관측)는 한 트랜잭션이다. 열린 건 행을 `SELECT … FOR UPDATE` 로 잡고 판정·기록한다.
- 중복 방지: 열린 건은 최대 1개(생성 열 `open_marker` UNIQUE), 사건은 `(episode_id, seq)` UNIQUE, 관측은 `(episode_id, observed_at)` UNIQUE(중복은 무시).
- 버린 대안: `save()` 트랜잭션에 합치기 — 이력 실패가 알림·중단 판정을 막는다(수용 기준 위반).

### D6. 판정은 Domain 순수 함수, 저장은 port 하나

- `PositionEpisodePolicy.decide(open: OpenEpisode?, legs, hedged: Boolean, source, at): EpisodeDecision` — 사건 0~1건과 다음 건 상태.
- `AccountPositionHistoryStore`(Domain port): `record(round: PositionHistoryRound)` 하나. adapter 가 트랜잭션 안에서 열린 건 조회 → policy 호출 → 기록한다. Job 은 port 만 안다.
- Job 생성자는 맨 끝에 `positionHistory: AccountPositionHistoryStore? = null` 을 붙인다(기존 관례). production 배선은 `account-pnl-monitor.position-history.enabled`(env `ACCOUNT_PNL_POSITION_HISTORY_ENABLED`, 기본 `true`)일 때 넘긴다.

### D7. 스키마 (V24, 추가만)

- `account_position_episode`: id, status(`OPEN`/`CLOSED`), opened_at, closed_at, 현재 leg 4열, reference 4열(NULL 가능), position_source, event_count, `open_marker`(생성 열), created_at, updated_at.
- `account_position_event`: id, episode_id(FK), seq, event_type, occurred_at, 변경 뒤 leg 4열, position_source, hedged.
- `account_pnl_observation`: id, episode_id(FK), kind, observed_at, net_percent·net_krw(NULL 가능), current_premium_percent, entry_premium_percent, premium_gap_percent_point, fx_rate, korea_exit_price, foreign_exit_price. `observed_at` 인덱스.
- 보존: 1일 최대 1,440행(연 약 53만 행). 이번에는 삭제 작업을 두지 않는다. 용량이 문제가 되면 별도 이슈.
- 수량·평단·금액이 실계정 값이다. 새 Domain 타입의 `toString` 과 로그에 싣지 않는다(기존 규칙).

## 3. 첫 배포 시 동작

- 포지션 보유 중이면 첫 회차에 `OPENED` 가 남는다. `opened_at` 은 실제 진입 시각이 아니라 첫 관측 시각이다(runbook 에 명시).
- 포지션이 없고 과거 건도 없으면 아무것도 쌓이지 않는다. 첫 포지션을 정리한 뒤부터 `FLAT` 관측이 시작된다.

## 4. 범위 밖

- 확정 손익(#130), 단계 알림(#131), 조회 API·화면.
- 기존 알림·`account_pnl_latest`·`account_pnl_monitor_state` 의 동작 변경.
- 과거 로그에서 이력 역이행.

## 5. 위험

| 위험 | 대응 |
|---|---|
| 이력 쓰기가 회차 시간(1분 주기, Job timeout)을 잡아먹음 | 단일 트랜잭션 3문장 이내. 통합 테스트로 회차당 쿼리 수 고정 |
| 모니터 상태와 이력의 leg 가 어긋남(이력 기록만 실패) | 이력은 자기 상태로 판정하므로 다음 회차에 따라잡는다. 감지 시각이 늦어질 뿐 |
| 버전 충돌로 건너뛴 회차 | `save()` 가 false 면 이력도 쓰지 않는다 — 다른 실행이 그 회차를 기록한다 |
| 수동 모드에서 빗썸만 정리하고 env 미갱신 | 바이낸스 leg 가 남아 건이 닫히지 않는다. 기존 "헤지 불일치" 알림이 이미 알린다 |
