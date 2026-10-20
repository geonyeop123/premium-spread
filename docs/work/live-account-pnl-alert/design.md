# 실계정 순손익 알림 (Live Account PnL Alert) — 스펙

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ④ 스펙 문서 |
| slug | `live-account-pnl-alert` |
| base branch | `dev` (`df15e79`) |
| branch | `feat/live-account-pnl-alert` |
| 상태 | `DRAFT` — ⑥ 스펙 리뷰 · ⑦ 사용자 승인 전 |

## 0. 이 단위의 성격

owner가 지금 잡고 있는 김프 포지션(빗썸 BTC 현물 매수 + 바이낸스 BTCUSDT 선물 숏)의 **지금 청산하면 남는
순수익률**을 계정에서 직접 읽어 계산하고, 1% 이상이면 이메일로 알린다.

지금은 owner가 개인 사이트(`geonyeop123.github.io/PremiumTrading`)에 수량·평단을 손으로 넣고 화면을 지켜본다.
그 사이트의 계산식은 이미 Domain `Tracking.grossPnl()`(`domain/.../tracking/Tracking.kt:143-179`)로 옮겨져
있다. 이 단위는 그 위에 두 가지를 더한다.

1. **입력 자동화** — 수량·평단을 사람이 넣지 않고 계정에서 읽는다.
2. **gross → net** — 사이트와 도메인이 모두 빠뜨리고 있는 수수료·펀딩비를 반영한다.
   (`TrackingGrossPnl` 주석이 스스로 "계정 손익이 아니다(SEM-4)"라고 선을 그어 둔 바로 그 공백이다.)

이 단위는 **주문을 제출하지 않는다.** 조회와 알림뿐이다.

### 0.1 PRIVATE LIVE master spec 과의 관계 (owner 결정)

master spec(`../private-live-autotrader/design.md`, `FROZEN`)과 아래가 어긋난다. **owner는 master spec 을 지금
고치지 않고, 이 단위의 구현 결과를 근거로 나중에 고치기로 결정했다** (2026-10-20 대화).

| master spec | 어긋나는 점 |
|---|---|
| `LIVE-1` credential은 private execution/reconciliation 경계만 읽는다 | 이 단위는 `apps:batch` 안의 모니터링 Job 이 read-only credential 을 읽는다 |
| §변경 통제 "credential 을 private execution/reconciliation 경계 밖에서 사용" | 위와 같음 |
| `ACT-1` → `ACT-2` 순서 (production credential 은 ACT-1 이후) | ACT-1 평가 전에 read-only production credential 을 쓴다 |

이 단위가 지키는 master spec 불변식: `LIVE-2`(credential 을 source·DB·로그·metric·알림 payload 에 남기지
않음), `LIVE-3`(출금·이체 호출 없음, key 에 해당 권한 없음), `LIVE-4`(주문 불가 — 주문 API 자체를 구현하지
않음), 테스트에서 실 credential·실 거래소 미사용.

후속 master spec 변경 요청의 근거로 쓸 수 있도록 `understanding.md` 에 실측(실 API shape, 실패 양상, 운영
불편)을 남긴다.

### 0.2 거래 준비 단위와의 관계

거래 준비(`../private-live-autotrader-trade-preparation/`, `FROZEN`)의 잔고 포트
`BalanceSnapshotReadPort`·`VerifiedBalanceReadPort` 는 **구현하지 않는다.** 그 단위의 `AC20`
(`TradePreparationWiringContractTest`)이 production 잔고 포트 구현을 `DeclaredBalanceAdapter` 하나로 고정하고
있고, 거기에 실계정 구현을 꽂으면 거래 준비의 `ARMED` 전이 의미가 바뀐다. 이 단위는 **별도 Domain port** 를
만든다. 두 경로의 통합은 master spec 변경 때 함께 결정한다.

## 1. 목표와 범위

### 1.1 목표

- 1분마다 두 계정을 읽어 **순수익률**을 계산한다.
- 순수익률이 1% 이상이 되면 이메일을 보내고, 유지되는 동안 1시간마다 다시 보낸다.
- 조회가 계속 실패하면 "모니터링 중단" 메일을, 회복되면 "복구" 메일을 보낸다.

### 1.2 포함

| | |
|---|---|
| 빗썸 private REST 조회 adapter (잔고·평균매수가·체결 수수료·수수료율) | `infrastructure:batch` |
| 바이낸스 USDⓈ-M signed REST 조회 adapter (포지션·진입가·수수료 이력·펀딩 이력·수수료율) | `infrastructure:batch` |
| 계정 포지션 조회 Domain port 와 Snapshot | `domain` |
| 순손익 계산 Domain 순수함수 (기존 `grossPnl` 재사용) | `domain` |
| 알림 상태(무장·에피소드·마지막 발송) durable 저장 | `domain` port + `infrastructure:common` + Flyway |
| 모니터링 Job + Scheduler | `apps:batch` |
| 이메일 발송 (기존 SMTP 경로 재사용) | 기존 `supports:email` |
| credential 취급 runbook | `docs/runbooks/` |

### 1.3 제외

| 제외 | 이유 |
|---|---|
| 주문·취소·출금·이체 API | 조회 전용 (§0) |
| 웹 화면·REST endpoint | owner 는 메일만으로 충분하다고 했다 *(추정, §6)* |
| master spec·거래 준비 산출물 수정 | §0.1·§0.2 |
| 기존 `Tracking`·`TrackingGrossPnl`·`grossPnl` 변경 | 사람이 등록한 tracking 의 gross 계약은 그대로 둔다 |
| BTC 외 심볼, 빗썸·바이낸스 외 거래소, 다중 계정 | owner 의 실제 포지션 범위가 이것뿐 |
| 진입 판단·청산 자동화 | 알림만 |

## 2. 포지션 정의 (owner 결정)

owner 의 두 계정에는 **김프 포지션 외 BTC 보유나 BTCUSDT 거래가 없다.** 따라서 포지션은 계정 상태에서 바로
만든다. 사용자가 등록한 `Tracking` 과 연결하지 않는다.

| leg | 원천 | 값 |
|---|---|---|
| 한국 | 빗썸 KRW-BTC 계정 | 보유 수량(가용 + 주문 중 잠김 포함), 평균매수가 |
| 해외 | 바이낸스 USDⓈ-M BTCUSDT 포지션 | 숏 수량(절댓값), 진입가 |

- 두 leg 수량이 조금 달라도 각자 수량으로 계산한다 (기존 `grossPnl` 계산식이 이미 leg 별 수량을 받는다).
  *(builder 판단 위임: 아래 §3 의 gross 는 기존 계산식과 같은 공식을 쓰되 `Tracking` entity 를 만들지 않는다.
  공식을 공유하는 방법 — 순수함수 추출 또는 동일 공식 재구현 + 동치 테스트 — 은 builder 가 정한다.
  다만 `Tracking.grossPnl` 의 공개 계약과 결과는 바뀌면 안 된다.)*
- **포지션 없음**: 어느 한 leg 라도 수량이 0 이면 포지션 없음이다. 알림을 보내지 않고 알림 상태를 초기화한다.
- 바이낸스 포지션이 **롱**이면(김프 포지션이 아님) 포지션 없음으로 보고 경고 로그를 남긴다. *(내 판단)*
- 빗썸 BTC 중 dust(거래 불가 최소 수량 미만)는 무시해도 된다 — 임계값은 builder 판단.

## 3. 순수익률 정의 (owner 결정)

owner 의 기준은 **"지금 양쪽을 청산하면 실제로 남는 돈"** 이다.

```
gross          = (빗썸 청산가 − 빗썸 평단) × 빗썸 수량
               + (바이낸스 진입가 − 바이낸스 청산가) × 바이낸스 수량 × 환율        // 기존 grossPnl 과 같은 공식
entryFee       = 현재 보유분을 만든 빗썸 매수 체결 수수료(KRW)
               + 현재 포지션을 만든 바이낸스 진입 체결 수수료(USDT) × 환율
funding        = 현재 포지션을 연 뒤 바이낸스에서 받거나 낸 펀딩비 합계(USDT, 받은 것 +) × 환율
exitFeeEst     = 빗썸 매도 수수료율 × 빗썸 청산가 × 빗썸 수량
               + 바이낸스 taker 수수료율 × 바이낸스 청산가 × 바이낸스 수량 × 환율
net            = gross − entryFee + funding − exitFeeEst
netPercent     = net ÷ (빗썸 청산가 × 빗썸 수량) × 100                            // 분모는 기존 grossPnl 과 같은 구조
```

- **알림 판정은 `netPercent ≥ 1.00`** 이다.
- **청산가 = 호가 (owner 결정)**: 빗썸은 매수호가(bid), 바이낸스 숏 청산은 매도호가(ask). "지금 청산하면 남는 돈"에
  맞춘 값이다. 판정은 이 값으로만 한다.
- **호가 깊이 VWAP (⑥ 리뷰 반영)**: 최우선 호가 잔량이 보유 수량보다 작으면 뒤 가격대까지 체결된다. 따라서 청산가는
  빗썸 매수호가를 위에서부터 빗썸 수량만큼, 바이낸스 매도호가를 아래에서부터 바이낸스 수량만큼 소화한 **수량가중 평균가
  (VWAP)** 다. 조회한 호가 깊이로 수량을 다 소화하지 못하면 그 회차는 계산 실패(`호가 깊이 부족`)다. owner 의 "호가 기준"
  결정을 전량 청산 의도에 맞게 구체화한 것이다.
- **체결가 기준 병기 (owner 결정)**: 같은 공식을 최근 체결가로 계산한 `netPercentLast` 도 함께 계산해 메일에 적는다.
  owner 가 사이트(최근 체결가 사용) 숫자와 대조하기 위한 값이며 판정에 쓰지 않는다.
- 분모는 사이트·도메인과 같은 "빗썸 평가금액" 구조다 — owner 가 확인했다.
- **분할 매매는 다루지 않는다 (owner 결정)**: 포지션은 한 번에 진입해 한 번에 전량 청산하는 것으로 본다.
  `exitFeeEst` 는 전량 청산 기준이고, 부분 청산 실현손익·수수료 배분(FIFO 등)은 계산하지 않는다.
- **환율**: 프로젝트가 30분마다 수집하는 USD/KRW(`FxIngestionJob` → FX cache/DB) 를 쓴다. 사이트의 네이버 환율이
  아니다. *(내 판단 — 기존 premium 계산과 같은 원천)*
- **가격 원천**: 호가·체결가를 batch 가 이미 수집하는 WebSocket 최신값에서 읽을지, 조회 시 REST 로 읽을지는 builder
  판단 (기존 바이낸스 stream 은 선물 bookTicker 라 bid/ask 가 있다. 빗썸 호가 원천은 확인 필요). 사용한 가격·환율과 그
  관측 시각을 계산 결과에 보존한다.
- **시세 신선도**: 호가 관측이 10초보다 오래되거나, **두 거래소 호가 관측 시각 차이가 5초를 넘으면** 그 회차는 계산
  실패다 (10초는 기존 ingestion 기준, 5초 skew 는 ⑥ 리뷰 반영·내 판단). 최근 체결가는 표시용이라
  신선도를 보지 않는다.
- **환율 신선도 (내 판단)**: 환율 값 자체의 나이가 아니라 **환율 수집이 마지막으로 성공한 지 2시간이 넘으면** 계산
  실패다. 주말·공휴일엔 원천 값이 바뀌지 않으므로 값의 나이로 판정하면 주말 내내 중단 메일이 나간다.
- **USDT ≈ USD 가정**: 바이낸스 손익·수수료·펀딩은 USDT 이고 환율은 USD/KRW 다. 기존 `grossPnl` 도 같은 가정을
  쓴다. 이 단위도 따른다.
- 금액 계산은 `BigDecimal`. 반올림은 표시 시점에만 (`netPercent` 는 소수 둘째 자리, 금액은 원 단위).
- **수수료 자산 (⑥ 리뷰 반영)**: 바이낸스 진입 수수료가 BNB 로 나갔으면 조회 시점의 BNBUSDT 가격으로 USDT 환산해
  반영한다 (알려진 비용을 0 으로 빼면 1% 를 잘못 넘긴다). 환산은 매 회차 다시 한다. USDT·BNB 외 자산이 섞여 있으면 그 회차는 계산 실패(`기타
  오류`)다. 0 처리(§3.1)는 **이력을 찾지 못한 경우에만** 쓴다.

### 3.1 "현재 포지션을 연 뒤"의 경계

`entryFee`·`funding` 은 **현재 포지션에 귀속되는 것만** 합산한다. 이전에 열었다 닫은 포지션의 수수료·펀딩을
섞으면 순손익이 틀린다.

- 바이낸스: 현재 숏을 만든 시점(포지션 수량이 마지막으로 0 에서 벗어난 시점)부터의 `COMMISSION`·`FUNDING_FEE`.
- 빗썸: 현재 보유분을 만든 매수 체결들의 수수료.
- **owner 의 현재 포지션은 이 기능이 생기기 전에 이미 열려 있다.** 따라서 경계는 Job 이 처음 관측한 시점이
  아니라 계정 이력으로 거슬러 찾아야 한다.
- **이력에서 찾지 못하면 (owner 결정)**: 이력 API 조회 기간 한도 등으로 진입 수수료나 펀딩비를 확정할 수 없으면 해당
  항목을 **0 으로 두고 알림은 그대로 보낸다.** 단 계산 결과에 항목별 `미반영` 플래그를 남기고, 메일에 "진입 수수료
  미반영(이력 없음)"처럼 명시한다. 이력을 찾을 수 있는 범위를 어떻게 판정하는지는 §7 조사 후 builder 가 정한다.

## 4. 알림 규칙 (owner 결정)

### 4.1 수익 알림

```
상태: DISARMED(1% 미만 대기)  ⇄  ARMED(1% 이상 에피소드 진행 중)
```

포지션이 바뀌면(바이낸스 수량·진입가 또는 빗썸 수량·평균매수가 변경) 이전 에피소드를 끝내고 새 포지션으로 판정한다.

| 상황 | 동작 |
|---|---|
| `netPercent` 가 1% 미만이다가 **1% 이상**이 됨 | 즉시 1통. 에피소드 시작 |
| 에피소드 중이고 포지션 보유 중이며 마지막 발송 후 **1시간 경과** | 1통 재발송 |
| 에피소드 중 `netPercent` 가 **0.9% 미만**으로 떨어짐 | 에피소드 종료(재발송 중단). 다음에 1% 이상이 되면 1시간을 기다리지 않고 즉시 발송 |
| 에피소드 중 0.9% 이상 1% 미만 구간 | 에피소드 유지(재무장하지 않음). 재발송 시점이 와도 이 구간이면 보내지 않는다 (아래) |
| 포지션 없음 (§2) | 에피소드 종료, 발송 없음 |

- 0.9% 재무장 기준(hysteresis)은 **내 판단**이다. owner 의 요구는 "내려갔다 올라가면 또 보내라"이고, 1% 근처에서
  1분마다 오르내릴 때 메일이 연달아 오는 것을 막기 위한 장치다.
- 0.9%~1% 구간에 1시간 재발송 시점이 오면 **보내지 않는다** — owner 는 "1% 가 넘고 보유 중이면 1시간마다"라고
  했다. 재발송은 그 회차 `netPercent ≥ 1%` 일 때만 한다. *(owner 표현에서 도출)*
- 계산 실패 회차(조회 실패·신선도 실패)는 상태를 바꾸지 않는다. 실패를 1% 미만으로 취급하지 않는다.

### 4.2 모니터링 중단·복구 알림

| 상황 | 동작 |
|---|---|
| 계산 성공이 **5분 넘게** 없음 (조회 실패·신선도 실패·Job 실패 포함) | "모니터링 중단" 1통. 원인 범주(빗썸 인증 실패 / 바이낸스 인증 실패 / 네트워크·요청 한도 / 시세 지연 / 환율 수집 지연 / 호가 깊이 부족 / 기타 오류)를 포함 |
| 중단 알림 후 계산이 다시 성공 | "복구" 1통 |

- 5분과 복구 메일은 **내 판단**이다.
- **모든 실패가 이 정책을 거친다 (⑥ 리뷰 반영)**: 조회·응답 해석·신선도·계산 단계의 예외는 종류와 무관하게 위 범주 중
  하나(분류되지 않으면 `기타 오류`)로 정규화해 중단 정책에 입력한다. HTTP 429 는 `네트워크·요청 한도` 다. 상태 저장소
  자체가 실패하면 중단 메일도 저장할 수 없으므로 Job 실패로 끝내고 기존 운영 알림(`OperatorAlert`) 경로에 맡긴다.
- 빗썸 key 는 허용 IP 에 묶이므로 집 공인 IP 가 바뀌면 인증 실패가 난다. 이 경우가 가장 흔한 중단 원인일 것으로
  본다 — 메일에 "허용 IP 확인"을 안내한다.
- PC 가 잠자기 상태면 메일을 보낼 수 없다. 깨어난 뒤 지난 시간을 감지해 보내는 것으로 충분하다.

### 4.3 메일 내용

- 제목에 **순수익률(%)·순수익금(원)** 을 넣는다 (owner 필수 요구).
- 본문 *(내 판단 — 메일만 보고 숫자를 검증할 수 있게)*:
  - 순수익률, 순수익금
  - gross, 진입 수수료, 누적 펀딩비, 예상 청산 수수료
  - 체결가 기준 순수익률(`netPercentLast`) — 사이트 대조용
  - 양 leg 수량·평단/진입가·청산가(호가)·최근 체결가, 환율, 각 관측 시각, 계산 시각 (KST 표기 + ISO-8601 Instant)
  - §3.1 에서 0 으로 처리한 항목의 `미반영` 표시
- API key, 계정 ID, 이메일 주소 외 개인 식별 정보는 넣지 않는다.

### 4.4 내구성

- 알림 상태(DISARMED/ARMED, 에피소드 시작, 마지막 발송 시각, 중단 알림 발송 여부)는 **DB 에 저장**한다. 재시작
  후에도 같은 에피소드에 즉시 중복 발송하지 않아야 한다.
- 프로젝트 규칙(`.ai/rules/batch.md` Durable notification): in-memory event·`@Async`·Redis cooldown 을 전달
  보장으로 쓰지 않는다. 발송은 at-least-once 로 충분하다(owner 1 인, 드문 중복 허용).
- 기존 `notification_delivery`(V14) 는 `subscription_id NOT NULL + FK`(V14:30,56) 와 premium 전용 event key 에 묶여 있어
  **재사용하지 않고 이 기능 전용 outbox 를 둔다** (builder 판단, plan 에서 확정).
- **전용 outbox 도 프로젝트 durable 발송 계약을 따른다 (⑥ 리뷰 반영)**: SMTP 전에 `PROCESSING` claim 을 commit 하고
  (`FOR UPDATE SKIP LOCKED`), 행마다 owner·UUID claim token 을 저장해 모든 상태 전이(`SENT`·재시도·실패)에 token
  fencing 을 건다. 일정 시간 넘게 `PROCESSING` 인 행은 `PENDING` 으로 복구한다. 최대 시도 횟수를 넘으면 `FAILED` 로 끝내고
  redrive 하지 않는다 — 시간이 지난 수익 알림은 다시 보낼 가치가 없다.
- **상태 갱신은 버전 조건부다**: 두 실행이 겹쳐도 늦은 쪽이 상태를 덮어쓰지 못하게 한다(낙관적 버전).
- migration 은 append-only, owner 는 `infrastructure:common`.

## 5. 실행 환경 (owner 결정: 로컬)

- owner PC(WSL)에서 기존 방식대로 실행한다: `docker compose -f docker/infra-compose.yml up -d` +
  `SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun`.
- 기능 on/off 플래그 기본값 **꺼짐**. 꺼져 있거나 key 가 없으면 Job·adapter bean 이 뜨지 않는다.
  `batch.scheduling.enabled=false` 이면 함께 꺼진다.
- **credential**: 환경변수로 주입한다 (빗썸 access/secret, 바이낸스 api/secret). source·`application*.yml` 기본값·DB·
  로그·metric tag·메일 본문·예외 메시지에 남기지 않는다. 로깅 마스킹(`supports:logging`) 대상에 추가한다.
- **key 권한 (owner 가 발급 시 설정)**: 빗썸 — 조회 권한만, 허용 IP = 현재 집 공인 IP. 바이낸스 — Enable Reading
  만, 선물 거래·출금 권한 없음, 가능하면 IP 제한.
- **메일**: 기존 SMTP 설정(`NOTIFICATION_EMAIL_ENABLED`, `MAIL_*`, `ALERT_EMAIL_FROM`)을 쓴다. 받는 주소는 이 기능
  전용 환경변수 하나. *(내 판단)*
- 실행 주기 1분. `JobExecutor` + `JobConfig`(lock·lease·timeout) 기존 패턴을 따른다. 거래소 rate limit 안에서
  돌아야 한다 — 이력 조회(수수료·펀딩)는 매분 전체를 다시 읽지 않도록 builder 가 설계한다.

## 6. 결정 기록

| 결정 | 누가 |
|---|---|
| 알림 기준 = 수수료·펀딩비 반영 순수익률, 분모 현재 빗썸 평가금액 | owner |
| 순수익 = 지금 청산 기준 (예상 청산 수수료 포함) | owner |
| 포지션 = 계정 상태 그대로 (계정에 김프 포지션만 있음) | owner |
| 1% 돌파 즉시 1통, 보유·1% 이상 유지 시 1시간마다, 내려갔다 올라가면 즉시 | owner |
| 메일에 현재 수익률·수익금 명시 | owner |
| 로컬 실행 | owner |
| 판정 가격 = 호가(빗썸 bid, 바이낸스 ask), 메일에 체결가 기준 수익률 병기 | owner |
| 진입 수수료·펀딩 이력 미확보 시 0 처리 + 메일에 미반영 명시 후 알림 발송 | owner |
| 분할 매매 미고려 — 전량 진입·전량 청산 가정 | owner |
| 조회 실패 지속 시 중단 알림 | owner |
| master spec 은 지금 고치지 않고 이 구현 결과로 나중에 고친다 | owner |
| 재무장 기준 0.9% (hysteresis) | 내 판단 |
| 중단 판정 5분, 복구 메일 발송 | 내 판단 |
| 메일 본문에 계산 근거 전부 표기 | 내 판단 |
| 환율 = 프로젝트 수집 USD/KRW | 내 판단 |
| 환율 신선도 = 수집 마지막 성공 후 2시간, 시세 신선도 = 10초 | 내 판단 |
| 청산가 = 호가 깊이 VWAP, 깊이 부족은 계산 실패 | ⑥ 리뷰 반영 (owner 호가 결정의 구체화) |
| 모든 실패를 범주로 정규화해 중단 정책 입력 | ⑥ 리뷰 반영 |
| 전용 outbox 에 claim·fencing·stale 복구·최대 시도, 상태 낙관적 버전 | ⑥ 리뷰 반영 (`.ai/rules/batch.md`) |
| 웹 화면·endpoint 없음 | 내 판단 (owner 는 메일만 언급) |
| 거래 준비 잔고 포트를 구현하지 않고 별도 port | 내 판단 (§0.2 근거) |
| 바이낸스 롱 포지션은 포지션 없음 처리 | 내 판단 |
| 받는 주소 = 전용 환경변수 | 내 판단 |

## 7. builder 에게 남기는 것 / 구현 전 조사

외부 API shape 는 **구현 전에 공식 문서로 확인**하고 `understanding.md` 에 기록한다. 거래 준비의 미해결 항목
`TP-OPEN-1`(빗썸 private API 한도와 잔고 endpoint shape 미확인)이 이 조사로 해소된다.

| 확인할 것 | 왜 중요한가 |
|---|---|
| 빗썸 v1 인증(JWT, query hash) 과 잔고 응답의 평균매수가 필드 | 한국 leg 평단 원천 |
| 빗썸 평균매수가가 수수료를 포함하는가 | 포함하면 `entryFee` 의 빗썸 몫을 따로 빼면 이중 차감 |
| 빗썸 체결 이력에서 수수료 필드와 조회 가능 기간 | §3.1 경계 |
| 빗썸 매도 수수료율 조회 방법 (주문 가능 정보 endpoint 등) | `exitFeeEst` |
| 바이낸스 positionRisk 의 수량·진입가 필드 (one-way / hedge mode) | 해외 leg 원천. hedge mode 면 SHORT side 를 읽는다 |
| 바이낸스 income 이력(`COMMISSION`, `FUNDING_FEE`) 조회 기간·페이지 한도 | §3.1 경계와 매분 비용 |
| 바이낸스 taker 수수료율 endpoint, BNB 수수료 할인 여부 | `exitFeeEst`, 수수료 자산 환산 |
| 두 거래소 rate limit 과 서버 시각 오차 허용(recvWindow 등) | 1분 주기 안정성 |

builder 판단 위임: gross 공식 공유 방식(§2), 가격 원천(§3), 이력 증분 조회 설계(§5),
dust 임계값(§2), port·adapter·Snapshot 이름(프로젝트 네이밍 규칙 준수).

## 8. 지켜야 할 프로젝트 규칙

- 모듈 경계: 거래소 private client 는 `infrastructure:batch/exchange`, DB adapter·migration 은
  `infrastructure:common`, Job 은 `apps:batch/application/job`, Scheduler 는 `apps:batch/interfaces/scheduling`.
  앱 내부에 기술 구현 패키지 금지. Domain 은 HTTP/Jackson/JWT 구현에 의존하지 않는다.
- Domain 은 계정 조회 결과를 `*Snapshot` 으로 받는다. 순손익 계산은 Domain 순수함수.
- 현재 시각은 주입한 `Clock`. 시간 값은 `Instant`.
- 테스트는 실 거래소·실 SMTP·실 credential 을 쓰지 않는다. 거래소는 MockWebServer, 서명은 공식 문서 예제 벡터로
  검증한다.
- metric tag 는 bounded 만 (exchange, outcome). 금액·수익률은 tag 가 아니라 gauge 값으로.
