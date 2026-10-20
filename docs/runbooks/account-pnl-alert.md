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
| 바이낸스 (USDS-M 선물) | **Enable Reading 만**. 선물 거래·출금·이체 off | 가능하면 IP 제한 |

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
| `BINANCE_API_KEY` | 예 | 바이낸스 읽기 key | `<binance-api-key>` |
| `BINANCE_SECRET_KEY` | 예 | 바이낸스 읽기 secret | `<binance-secret-key>` |
| `NOTIFICATION_EMAIL_ENABLED` | 예 | 메일 발송 활성. 기본 `false` | `true` |
| `MAIL_HOST` / `MAIL_PORT` | 예 | SMTP 서버 | `smtp.example.com` / `587` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 예 | SMTP 계정 | `<smtp-user>` / `<smtp-password>` |
| `ALERT_EMAIL_FROM` | 예 | 발신 주소 | `alert@example.com` |
| `BATCH_SCHEDULING_ENABLED` | 아니오 | 기본 `true`. `false` 면 Scheduler 가 함께 꺼진다 | `true` |
| `ACCOUNT_PNL_MONITOR_FIXED_RATE` | 아니오 | 실행 주기(ms). 기본 `60000` | `60000` |

- 플래그가 `true` 여도 수신자·key 4개 중 하나라도 비면 Job 은 뜨지 않는다.
- **`NOTIFICATION_EMAIL_ENABLED` 가 꺼져 있으면 모니터가 시작되지 않는다.** 이때 batch 로그에
  `account-pnl-monitor enabled but email disabled; monitor not started` WARN 이 남는다.

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

### 동작 확인

- 기동 로그에 위 `monitor not started` WARN 이 **없어야** 한다.
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
| 재발송 | 에피소드 중·보유 중이고 마지막 발송 후 **60분**, 그 회차 순수익률이 1% 이상일 때만 |
| 재무장 | 순수익률이 **0.9% 미만**으로 내려가면 에피소드 종료. 이후 1% 이상이 되면 즉시 발송 |
| 중단 알림 | 계산 성공이 **5분** 넘게 없으면 1통(원인 범주 포함), 이후 성공하면 복구 1통 |
| 신선도 | 호가 관측 10초 이내, 두 거래소 호가 시각 차 5초 이내, 환율 수집 간격 2시간 이내. 위반은 `시세 지연`/`환율 수집 지연` |
| 미반영 표시 | 이력(진입 수수료·펀딩)을 못 찾으면 0 으로 계산하고 메일에 `미반영(이력 없음)` 표시 |

- 계산 실패 회차는 상태를 바꾸지 않는다(1% 미만으로 취급하지 않는다).
- 발송은 DB outbox 기반 at-least-once 다. 드물게 중복 메일이 올 수 있다.

## 중단 메일 범주별 조치

| 메일의 원인 범주 | 의미 | 조치 |
|---|---|---|
| 빗썸 인증 실패(허용 IP 확인) | 빗썸 401 (`NotAllowIP`, `jwt_verification`, `invalid_query_payload` 등) | 현재 공인 IP 를 확인해 빗썸 key 허용 IP 갱신. key 권한·만료·오타 확인 |
| 바이낸스 인증 실패 | `-1022`/`-2014`/`-2015` 등 | key·secret 확인, 바이낸스 IP 제한이 있으면 현재 IP 반영, Enable Reading 확인 |
| 네트워크·요청 한도 오류 | 타임아웃·연결 실패·HTTP 429 | 인터넷·거래소 상태 확인. 429 가 반복되면 같은 key 를 쓰는 다른 프로세스 점검(바이낸스 418 ban 주의) |
| 시세 지연 | 호가 관측이 10초 초과 또는 두 거래소 시각 차 5초 초과 | batch 의 WebSocket 수집(Binance/Bithumb) 연결 상태·readiness 확인 |
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
- [ ] (b) 빗썸 `/v1/orders` 호출이 `401 invalid_query_payload` 없이 된다(query_hash 인코딩). 로그에
  `Account PnL observation failed: category=BITHUMB_AUTH` 가 없어야 한다.
- [ ] (c) 바이낸스 진입 수수료·누적 펀딩이 거래소 거래·펀딩 기록과 맞는다. 어댑터는 userTrades 를 7일 창 13개(약 91일)로
  거슬러 올라가며 진입을 찾는다. 현재 숏을 연 지 약 91일을 넘었거나 한 창에 체결이 1000건 이상이면 진입을 찾지 못해
  `진입 수수료(바이낸스)`·`누적 펀딩비` 가 `미반영(이력 없음)` 으로 표시된다. **이는 중단 메일이 아니라 수익 메일의 표시다.**
  - 미검증 항목: 바이낸스가 문서상 보존(3개월)을 넘는 가장 오래된 창에 `[]` 대신 4xx 로 답하면 매 회차가
    `기타 오류(로그 확인)` 로 실패하고 5분 뒤 중단 메일이 온다. 그런 경우 로그의
    `Account PnL observation failed: category=UNEXPECTED` 를 확인해 **보고**한다.
  - 포지션이 7일을 넘었으면 `누적 펀딩비` 를 거래소 펀딩 내역 합계와 비교해, income 조회(startTime 만 지정, endTime 없음)가
    전체 범위를 반환했는지 확인한다.
- [ ] (d) 서버 시각 오차(`-1021`)가 없다. 어댑터는 `-1021` 이면 서버 시각을 다시 측정해 한 번 재시도한다. 계속 실패하면
  `네트워크·요청 한도 오류` 중단 메일로 나타난다. `date -u` 로 WSL 시계 동기화를 확인한다.
- [ ] 1% 미만 상태에서 메일이 오지 않고, 1% 이상이면 오는지 확인한다.

## 끄는 법과 데이터 정리

- 끄기: `ACCOUNT_PNL_MONITOR_ENABLED=false`(또는 미설정) 후 batch 재시작. `BATCH_SCHEDULING_ENABLED=false` 면 전체 스케줄러가 꺼진다.
- 상태·outbox 테이블:
  - `account_pnl_monitor_state` — 단일 행(`id=1`). 에피소드·마지막 발송·중단 알림 여부.
  - `account_pnl_mail` — durable 메일 outbox(`PENDING`/`PROCESSING`/`SENT`/`FAILED`). 수신자 주소는 저장하지 않는다.
- 기능을 완전히 접으려면 두 테이블의 행을 지운다(테이블 자체는 migration 소유이므로 drop 하지 않는다).
- **`FAILED` 메일은 redrive 하지 않는다.** 시간이 지난 수익 알림은 보낼 가치가 없다. 필요하면 원인만 확인하고 행을 정리한다.
- 일반 알림 outbox 의 redrive 절차는 [durable-notification-delivery.md](durable-notification-delivery.md)이며 이 기능과 별개다.
