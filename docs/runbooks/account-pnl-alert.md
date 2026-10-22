# 실계정 순손익 알림 Runbook

## 목적과 범위

- owner 의 실계정(빗썸 현물 롱 + 바이낸스 선물 숏) 헤지 포지션의 **순수익률·순수익금**을 1분마다 계산해, 1% 이상이면
  메일로 알린다. 모니터링이 5분 넘게 멈추면 중단 메일, 다시 살아나면 복구 메일을 보낸다.
- **조회 전용이다. 주문·출금·이체 API 를 호출하지 않고 구현하지도 않는다.** key 에도 해당 권한을 주지 않는다.
- owner PC 로컬에서 `apps:batch` 로 실행한다. 기본값은 꺼짐이다.

### master spec 이탈 고지

이 기능은 PRIVATE LIVE master spec(`LIVE-1`, `ACT-1`→`ACT-2` 순서)과 의도적으로 어긋난다. owner 가 master spec 을
나중에 이 구현 결과로 고치기로 결정했다. 상세는 [design.md §0.1](../work/live-account-pnl-alert/design.md)을 따른다.

## Key 발급

| 거래소 | 허용할 권한 | 제한 |
|---|---|---|
| 빗썸 | **조회(자산·주문 조회) 권한만**. 주문·출금 권한 off | **허용 IP = 현재 집 공인 IP** |
| 바이낸스 (USDS-M 선물) — **api 모드에서만 필요** | **Enable Reading 만**. 선물 거래·출금·이체 off | 가능하면 IP 제한 |

- 집 공인 IP 가 바뀌면 빗썸이 `401 NotAllowIP` 로 거부한다. 중단 메일 `빗썸 인증 실패(허용 IP 확인)` 의 가장 흔한 원인이다.
- key 는 이 저장소·이슈·PR·채팅·메일 어디에도 붙여넣지 않는다.

## 환경변수

값은 모두 placeholder 다. 실제 값은 문서·source·`application*.yml` 에 쓰지 않는다.

| 이름 | 필수 | 설명 | 예시 |
|---|---|---|---|
| `ACCOUNT_PNL_MONITOR_ENABLED` | 예 | 기능 플래그. 기본 `false`. `true` 일 때만 Job 이 뜬다 | `true` |
| `ACCOUNT_PNL_ALERT_RECIPIENT` | 예 | 알림 수신 주소 | `owner@example.com` |
| `BITHUMB_ACCESS_KEY` | 예 | 빗썸 조회 key | `<bithumb-access-key>` |
| `BITHUMB_SECRET_KEY` | 예 | 빗썸 조회 secret | `<bithumb-secret-key>` |
| `BINANCE_MODE` | 아니오 | 바이낸스 leg 방식. 기본 `api`. `api`\|`manual`(대소문자 무관). 그 밖의 값이면 기동 실패(기능이 켜져 있을 때 — `ACCOUNT_PNL_MONITOR_ENABLED=true` 이고 현재 모드의 필수값·메일·scheduling 이 모두 만족돼 설정이 바인딩될 때) | `api` |
| `BINANCE_API_KEY` | **api 모드에서만** 예 | 바이낸스 읽기 key | `<binance-api-key>` |
| `BINANCE_SECRET_KEY` | **api 모드에서만** 예 | 바이낸스 읽기 secret | `<binance-secret-key>` |
| `BINANCE_MANUAL_*` 5종 | **manual 모드에서만**(수량·진입가) | 수동 입력 포지션. 아래 "바이낸스 수동 입력 모드" 절 | — |
| `NOTIFICATION_EMAIL_ENABLED` | 예 | 메일 발송 활성. 기본 `false` | `true` |
| `MAIL_HOST` / `MAIL_PORT` | 예 | SMTP 서버 | `smtp.example.com` / `587` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 예 | SMTP 계정 | `<smtp-user>` / `<smtp-password>` |
| `ALERT_EMAIL_FROM` | 예 | 발신 주소 | `alert@example.com` |
| `BATCH_SCHEDULING_ENABLED` | 아니오 | 기본 `true`. `false` 면 Scheduler 가 함께 꺼진다 | `true` |
| `ACCOUNT_PNL_HOURLY_REPORT_ENABLED` | 아니오 | 정시 보고 메일. 기본 `true`, `false` 면 정시 보고를 끈다(모니터가 켜져 있을 때만 의미 있다) | `true` |
| `ACCOUNT_PNL_MONITOR_FIXED_RATE` | 아니오 | 실행 주기(ms). 기본 `60000` | `60000` |

- 플래그가 `true` 여도 모드별 필수 env 중 하나라도 비면 Job 은 뜨지 않는다.
  - 공통: `ACCOUNT_PNL_ALERT_RECIPIENT`, `BITHUMB_ACCESS_KEY`, `BITHUMB_SECRET_KEY`
  - `api` 모드: 공통 + `BINANCE_API_KEY`, `BINANCE_SECRET_KEY`
  - `manual` 모드: 공통 + `BINANCE_MANUAL_SHORT_QUANTITY`, `BINANCE_MANUAL_ENTRY_PRICE` (바이낸스 key 불필요)
- 누락 시 batch 로그에 다음 WARN 이 한 줄 남고 Job 은 뜨지 않는다. 비어 있는 **env 이름**만 나오며 설정 값은 나오지 않는다.

  ```text
  account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode=manual, missing=[BINANCE_MANUAL_ENTRY_PRICE]
  ```

  `binanceMode` 는 `api` 또는 `manual`, `missing` 은 비어 있는 env 이름 목록이다. 이 WARN 은 `api` 모드에도 적용된다.
- **`NOTIFICATION_EMAIL_ENABLED` 가 꺼져 있으면 모니터가 시작되지 않는다.** 이때 batch 로그에
  `account-pnl-monitor enabled but email disabled; monitor not started` WARN 이 남는다.

## 바이낸스 수동 입력 모드 (manual)

바이낸스 API key 없이 쓰는 모드다. 바이낸스 포지션·진입 수수료·펀딩을 owner 가 env 로 직접 입력하고, 청산가 계산용 호가와
최근 체결가만 바이낸스 **공개** API(`/fapi/v1/depth`, `/fapi/v2/ticker/price`)에서 읽는다. 서명·key·서버 시각 조회가 없다.
`BINANCE_MODE=manual` 로 켠다. 빗썸 leg 는 그대로 API key 가 필요하다.

| env | 필수 | 단위 | 부호·형식 | 기본값 | 바이낸스 화면에서 읽는 곳 |
|---|---|---|---|---|---|
| `BINANCE_MANUAL_SHORT_QUANTITY` | 예 | BTC | 십진수 `>= 0` (`0` = 포지션 없음) | 없음 | 포지션 Size 의 **절댓값** |
| `BINANCE_MANUAL_ENTRY_PRICE` | 예 | USDT | 십진수 `> 0` | 없음 | 포지션 Entry Price |
| `BINANCE_MANUAL_ENTRY_FEE_USDT` | 아니오 | USDT | 십진수 `>= 0` | 미입력(메일에 `미반영(수동 미입력)`) | 진입 체결 수수료 합. BNB 로 냈으면 **USDT 로 환산**해 입력 |
| `BINANCE_MANUAL_FUNDING_USDT` | 아니오 | USDT | 십진수, **부호 제한 없음** (받은 것 `+`, 낸 것 `-`) | 미입력(메일에 `미반영(수동 미입력)`) | 포지션을 연 뒤 펀딩 합계 |
| `BINANCE_MANUAL_TAKER_FEE_RATE` | 아니오 | 비율 | 십진수 `0 <= x < 1` | `0.0005` | 청산 수수료 계산용 taker 율 |

- 빈 값·공백은 미입력으로 본다.
- 형식 오류, 음수(펀딩 제외), 진입가 `0`, taker 율 1 이상은 **기동 실패**다. 우리 코드의 오류 메시지와 원인 체인에는 설정 이름과
  규칙만 있고 입력 값은 없다. 잔여 노출은 아래 한계 절을 본다.
- manual 입력은 빗썸 쪽 값과 별개이며 `BINANCE_MODE` 를 `manual` 로 두지 않으면 사용되지 않는다.

### 메일 표기

수익 메일에서 바이낸스 출처가 수동 입력이면 바이낸스 관련 줄만 달라진다(제목·빗썸 줄은 api 와 같다). 숫자는 형식 예시다.
메일 본문 순서대로 위치별 표기는 다음과 같다.

| 위치(메일 순서) | api | manual |
|---|---|---|
| `진입 수수료` 하위 `바이낸스` 줄 (입력함) | `  - 바이낸스: -8.9 USDT (-11,824원)` | `  - 바이낸스: -8.9 USDT (-11,824원) 수동 입력` |
| `진입 수수료` 하위 `바이낸스` 줄 (없음) | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(이력 없음)` | `  - 바이낸스: 진입 수수료(바이낸스) 미반영(수동 미입력)` |
| `누적 펀딩비` 줄 (입력함) | `누적 펀딩비: +4,251원 (+3.2 USDT)` | `누적 펀딩비: +4,251원 (+3.2 USDT) 수동 입력` |
| `누적 펀딩비` 줄 (없음) | `누적 펀딩비: 0원  펀딩비 미반영(이력 없음)` | `누적 펀딩비: 0원  펀딩비 미반영(수동 미입력)` |
| `[바이낸스 선물 숏]` 바로 다음 줄 | (없음) | `바이낸스 포지션: 수동 입력(env)` |

### 한계

- **manual 모드에서 펀딩을 갱신하지 않으면 장기 보유 시 순손익이 과대평가돼 1% 알림이 실제보다 일찍 갈 수 있다.**
- 펀딩은 자동 누적되지 않는다. 갱신하려면 `BINANCE_MANUAL_FUNDING_USDT` 를 고치고 batch 를 재시작한다.
- **포지션을 늘리거나 줄이거나 닫으면 반드시 env 수정 + 재시작**한다(닫으면 수량 `0`). 하지 않으면 틀린 수량으로 계산해 틀린 알림이
  갈 수 있다.
- 수량·진입가가 바뀌면 새 에피소드(즉시 판정)로 본다. 수수료·펀딩만 바꾸면 에피소드가 유지된다.
- taker 기본 `0.0005` 는 BNB 할인을 반영하지 않은 값이다(보수적). 진입 수수료를 BNB 로 냈다면 USDT 환산값을 입력한다.
- manual 값은 **모니터가 기동 조건을 모두 만족해 설정이 바인딩될 때**(기능 켜짐·현재 모드의 필수값 전부·메일 켜짐·scheduling
  켜짐) `api` 모드에서도 형식 검증된다. 기동 조건이 안 맞으면 검증도 되지 않는다.
- manual 모드에서는 `바이낸스 인증 실패` 범주가 사실상 나오지 않는다(서명 호출이 없다). 바이낸스 쪽 실패는 `네트워크·요청 한도 오류`,
  `시세 지연`, `기타 오류` 로 나타난다.
- 알림을 받으려고 env 값을 실제와 다르게 바꾸지 않는다(에피소드 상태가 DB 에 남는다).
- 형식 오류로 기동이 실패하면 Spring Boot 기동 실패 리포트(`BindFailureAnalyzer`)가 바인딩 중이던 property 값을 한 줄 출력할
  수 있다(design §3.1 잔여 노출). manual 값은 credential 이 아니고 출력처는 owner 로컬 콘솔이라 수용한다. 로그를 공유하기
  전에 확인한다.

## 로컬 실행 순서

1. 인프라를 올린다.

   ```bash
   docker compose -f docker/infra-compose.yml up -d
   ```

2. **스키마 적용.** batch 는 Flyway 를 실행하지 않는다(API 만 실행). 알림 상태·outbox 테이블(`V18`)은 API 를 한 번
   기동해 적용한다. 이 명령은 foreground 로 실행된다. Flyway `Successfully applied`(또는 `Schema ... is up to date`)
   로그와 `Started` 로그가 나올 때까지 기다린 뒤 Ctrl-C 로 멈추거나 그대로 둔다.

   ```bash
   SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun
   ```

3. 환경변수를 설정한다. 아래 "credential 취급"을 따른다.
4. batch 를 실행한다.

   ```bash
   SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun
   ```

   백그라운드로 띄우고 로그 파일에 남기려면(env 파일 경로는 예시) 다음처럼 한다. `~/logs/account-pnl-batch.log` 는
   "동작 확인"의 `grep 'Account PnL observed'` 가 읽는 파일이다.

   ```bash
   mkdir -p ~/logs
   ( set -a; . ~/.config/premium-spread/account-pnl.env; set +a
     SPRING_PROFILES_ACTIVE=local nohup ./gradlew :apps:batch:bootRun > ~/logs/account-pnl-batch.log 2>&1 & )
   ```

   중지:

   ```bash
   kill $(ss -ltnp | grep ':8081 ' | grep -o 'pid=[0-9]*' | cut -d= -f2)
   ```

### 동작 확인

- 기동 로그에 위 `monitor not started` WARN 이 **없어야** 한다.
- manual 모드면 기동 시 한 번 다음 INFO 가 남는다. 값은 `set`/`unset`/`default` 로만 나오고 수량·가격은 나오지 않는다.

  ```text
  account-pnl-monitor binance leg: mode=manual, entryFeeUsdt=set, fundingUsdt=unset, takerFeeRate=default
  ```
- 순손익이 계산된 회차마다(매분) 다음 INFO 한 줄이 남는다. 숫자는 예시다.
  KRW 금액은 원 단위 반올림이고, `unrecovered` 는 미확보로 0 계산된 항목(`koreaEntryFee`·`foreignEntryFee`·`funding`)이다.
  본인 계정 금액이 들어 있으므로 로컬 로그에만 남기고 공유할 때 주의한다. 최신 값은 nohup 으로 띄운 로그에서 읽는다.

  ```text
  Account PnL observed: netPercent=0.42%, netKrw=4200, netPercentAtLast=0.40%, netKrwAtLast=4000, grossKrw=5000, entryFeeKrw=300, fundingKrw=-100, exitFeeEstimateKrw=400, koreaQty=0.01, koreaExit=102000000, foreignQty=0.01, foreignExit=70000, fxRate=1400, source=MANUAL_ENV, unrecovered=[], calculatedAt=2026-10-20T03:00:00Z
  ```

  ```bash
  grep 'Account PnL observed' ~/logs/account-pnl-batch.log | tail -1
  ```
- 포지션이 없으면 매분 `No account hedge position: ...` INFO 가 남는다(정상).
- 실패 시 `Account PnL observation failed: category=...` WARN 이 남는다.
- Job 메트릭 tag `job=account_pnl_monitor` 의 outcome·last-success age 로 최근 성공을 본다.

### credential 취급

- 셸에 직접 `export KEY=...` 를 치면 history 에 남는다. 아래 중 하나를 쓴다.

  ```bash
  read -rs BITHUMB_SECRET_KEY && export BITHUMB_SECRET_KEY   # 입력이 화면·history 에 남지 않는다
  ```

  또는 저장소 밖(또는 `.gitignore` 된) env 파일을 `chmod 600` 으로 만들어 `set -a; . ./file; set +a` 로 읽는다.
- env 파일을 저장소 안에 둔다면 `git check-ignore -v <파일>` 로 무시되는지 먼저 확인한다(`.env` 는 `.gitignore` 에 있다).
- 로그는 credential 을 마스킹하지만, 로그·스크린샷을 공유하기 전에 한 번 더 확인한다.

## 동작 요약

| 항목 | 값 |
|---|---|
| 실행 주기 | 1분 (lock 50s lease / 40s timeout) |
| 수익 알림 | 순수익률 **1% 이상**이 되면 즉시 1통 |
| 정시 보고 | 매 정시(`Asia/Seoul`) 이후 첫 계산 성공 회차에 1통. 1% 알림과 무관. 실패·포지션 없음이면 생략, 재시작해도 같은 시간대 중복 없음(eventKey `account-pnl-hourly-{정시 epoch ms}`) |
| 재발송 | 에피소드 중·보유 중이고 마지막 발송 후 **60분**, 그 회차 순수익률이 1% 이상일 때만 |
| 재무장 | 순수익률이 **0.9% 미만**으로 내려가면 에피소드 종료. 이후 1% 이상이 되면 즉시 발송 |
| 중단 알림 | 계산 성공이 **5분** 넘게 없으면 1통(원인 범주 포함), 이후 성공하면 복구 1통 |
| 신선도 | 호가 관측 10초 이내, 두 거래소 호가 시각 차 5초 이내, 환율 수집 간격 2시간 이내. 위반은 `시세 지연`/`환율 수집 지연` |
| 미반영 표시 | 이력(진입 수수료·펀딩)을 못 찾으면 0 으로 계산하고 메일에 `미반영(이력 없음)` 표시. manual 모드의 바이낸스 진입 수수료·펀딩은 `미반영(수동 미입력)` |

- 계산 실패 회차는 상태를 바꾸지 않는다(1% 미만으로 취급하지 않는다).
- 발송은 DB outbox 기반 at-least-once 다. 드물게 중복 메일이 올 수 있다.
- 알려진 동작: PC 잠자기로 환율 수집 마지막 성공이 2시간을 넘긴 채 깨어나면, 환율 수집(30분 주기)이 다시 성공하기 전까지 `환율 수집 지연`
  으로 계산이 실패해 `모니터링 중단 — 환율 수집 지연` 메일과 곧이은 `모니터링 복구` 메일이 한 쌍으로 올 수 있다. 환율
  마지막 성공 시각을 프로세스 메모리에서 읽는 설계의 대가이며 조치는 필요 없다.

## 중단 메일 범주별 조치

| 메일의 원인 범주 | 의미 | 조치 |
|---|---|---|
| 빗썸 인증 실패(허용 IP 확인) | 빗썸 401 (`NotAllowIP`, `jwt_verification`, `invalid_query_payload` 등) | 현재 공인 IP 를 확인해 빗썸 key 허용 IP 갱신. key 권한·만료·오타 확인 |
| 바이낸스 인증 실패 | `-1022`/`-2014`/`-2015` 등 | key·secret 확인, 바이낸스 IP 제한이 있으면 현재 IP 반영, Enable Reading 확인 |
| 네트워크·요청 한도 오류 | 타임아웃·연결 실패·HTTP 429 | 인터넷·거래소 상태 확인. 429 가 반복되면 같은 key 를 쓰는 다른 프로세스 점검(바이낸스 418 ban 주의) |
| 시세 지연 | 호가 관측이 10초 초과 또는 두 거래소 시각 차 5초 초과 | 거래소 REST 응답 지연·네트워크 상태 확인(이 기능은 호가를 WebSocket 수집이 아니라 빗썸 `/v1/orderbook`, 바이낸스 `/fapi/v1/depth` 로 직접 읽는다), PC 시계(`date -u`) 확인, 계정 이력 조회가 길어진 회차인지 로그 확인 |
| 환율 수집 지연 | 환율 수집이 2시간 넘게 멈춤 | FX 수집 Job 로그·외부 FX API 확인 |
| 호가 깊이 부족 | 청산 수량을 호가 깊이로 채울 수 없음 | 일시적이면 대기. 지속되면 수량·호가 조회 응답 확인 |
| 기타 오류(로그 확인) | 분류되지 않은 예외 | batch 로그의 `Account PnL observation failed` 스택 확인 |

PC 가 잠자기 상태였다면 깨어난 뒤 지난 시간을 감지해 메일이 늦게 올 수 있다.

## AC13 owner 검산 체크리스트

실 key 로 로컬 실행해 메일을 받은 뒤, 메일의 값을 거래소 화면과 대조한다. 결과는 **PR 코멘트**로 기록한다(AC13).

- [ ] 메일의 빗썸 수량·평단, 바이낸스 수량·진입가가 각 거래소 화면과 같다.
- [ ] 환율, 청산가(호가 VWAP), 체결가 기준 순수익률(대조용)이 사이트 값과 합리적으로 맞는다.
- [ ] (a) **빗썸 평단(`avg_buy_price`)이 수수료를 포함하는가.** 거래소 체결 내역의 Σ체결금액 / Σ체결수량과 비교한다.
  포함하고 있으면 빗썸 진입 수수료가 이중 차감되므로 **보고**한다.
- [ ] (b) 빗썸 `/v1/orders` 호출이 `401 invalid_query_payload` 없이 된다. query_hash 는 전송 URL 의 인코딩 query
  (`states%5B%5D=...`)가 아니라 **디코딩된 query**(`states[]=...`)로 계산해야 한다(2026-10-22 실 key 로 확인·수정). 로그에
  `Account PnL observation failed: category=BITHUMB_AUTH` 가 없어야 한다.
- [ ] (c) 바이낸스 진입 수수료·누적 펀딩이 거래소 거래·펀딩 기록과 맞는다. 어댑터는 userTrades 를 7일 창 13개(약 91일)로
  거슬러 올라가며 진입을 찾는다. 현재 숏을 연 지 약 91일을 넘었거나 한 창에 체결이 1000건 이상이면 진입을 찾지 못해
  `진입 수수료(바이낸스)`·`누적 펀딩비` 가 `미반영(이력 없음)` 으로 표시된다. **이는 중단 메일이 아니라 수익 메일의 표시다.**
  - 바이낸스에 기록된 체결 id 가 없을 때(진입 미발견 캐시) 새 체결은 최근 7일 시각 범위 조회로 감지한다. 새 체결 후
    다음 회차 메일 값이 갱신되는지 확인한다.
  - 미검증 항목: 바이낸스가 문서상 보존(3개월)을 넘는 가장 오래된 창에 `[]` 대신 4xx 로 답하면 매 회차가
    `기타 오류(로그 확인)` 로 실패하고 5분 뒤 중단 메일이 온다. 그런 경우 로그의
    `Account PnL observation failed: category=UNEXPECTED` 를 확인해 **보고**한다.
  - 포지션이 7일을 넘었으면 `누적 펀딩비` 를 거래소 펀딩 내역 합계와 비교해, income 조회(startTime 만 지정, endTime 없음)가
    전체 범위를 반환했는지 확인한다.
- [ ] (d) 서버 시각 오차(`-1021`)가 없다. 어댑터는 `-1021` 이면 서버 시각을 다시 측정해 한 번 재시도한다. 계속 실패하면
  `네트워크·요청 한도 오류` 중단 메일로 나타난다. `date -u` 로 WSL 시계 동기화를 확인한다.
- [ ] 1% 미만 상태에서 메일이 오지 않고, 1% 이상이면 오는지 확인한다.

## manual 모드 검산 체크리스트 (manual-binance AC9)

`BINANCE_MODE=manual` 과 실제 포지션 값으로 로컬 실행한 뒤 확인하고, 결과를 **PR 코멘트**로 기록한다.

- [ ] (a) 기동 로그에 `monitor not started` WARN 이 없고 `account-pnl-monitor binance leg: mode=manual` INFO 가 있다.
- [ ] (b) 첫 수익 메일(순수익률 1% 도달 시)의 바이낸스 수량·진입가가 입력과 같고 `바이낸스 포지션: 수동 입력(env)` 표기가 있다.
  바이낸스 청산가(호가 VWAP)와 최근 체결가가 바이낸스 화면과 합리적으로 맞는다.
- [ ] (c) 알림을 받으려고 env 값을 실제와 다르게 바꾸지 않았다(위 한계 절 참조).

## 끄는 법과 데이터 정리

- 끄기: `ACCOUNT_PNL_MONITOR_ENABLED=false`(또는 미설정) 후 batch 재시작. `BATCH_SCHEDULING_ENABLED=false` 면 전체 스케줄러가 꺼진다.
- 모드 전환(`api` ↔ `manual`)은 env 수정 + batch 재시작이다. 수량·진입가가 같으면 에피소드가 유지된다.
- 상태·outbox 테이블:
  - `account_pnl_monitor_state` — 단일 행(`id=1`). 에피소드·마지막 발송·중단 알림 여부.
  - `account_pnl_mail` — durable 메일 outbox(`PENDING`/`PROCESSING`/`SENT`/`FAILED`). 수신자 주소는 저장하지 않는다.
- 기능을 완전히 접으려면 두 테이블의 행을 지운다(테이블 자체는 migration 소유이므로 drop 하지 않는다).
- **`FAILED` 메일은 redrive 하지 않는다.** 시간이 지난 수익 알림은 보낼 가치가 없다. 필요하면 원인만 확인하고 행을 정리한다.
- 일반 알림 outbox 의 redrive 절차는 [durable-notification-delivery.md](durable-notification-delivery.md)이며 이 기능과 별개다.
