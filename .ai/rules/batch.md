# Batch Architecture Rules

## 구조

```text
apps:batch/interfaces/scheduling   # @Scheduled thin trigger
apps:batch/application/job         # premium/fx/ticker/aggregation use case
apps:batch/application/notification# durable delivery/retention orchestration
domain                             # Job/market/aggregation/notification ports
infrastructure:batch               # 외부 거래소/FX/WebSocket/cache/lock/metric/email adapter
infrastructure:common              # 공유 JDBC/JPA/Redis adapter와 migration
```

앱 내부 `client`, `cache`, `repository`, `infrastructure` 기술 구현은 금지한다. Scheduler는 Application Job
하나만 호출하고, Job은 Domain port만 주입받는다.

## Job 실행 계약

- 모든 일반 Job은 `JobExecutor`를 통해 typed `JobConfig`의 lock key, lease, execution timeout을 적용한다.
- Redis lock은 owner token과 atomic renew/release를 사용한다. timeout 이후 실제 action이 끝나기 전에 lock을
  해제하지 않는다.
- `JobResult`는 success/skipped/failure를 구분하고 bounded outcome metric과 last-success age를 남긴다.
- `batch.scheduling.enabled=false`이면 Scheduler와 scheduling infrastructure가 모두 비활성화된다.
- cron zone과 aggregation zone이 다르면 startup에서 실패한다.

## Market과 시간

- Batch runtime market은 `batch.market`의 `MarketPair`, 한국/해외 quote, FX base/quote가 하나의 설정 계약이다.
- stream symbol/quote/endpoint가 market 설정과 다르면 startup에서 실패한다.
- 현재 저장 모델은 pair-aware지만 실행 중 동시에 수집하는 pair는 하나다.
- 현재 시각은 주입한 `Clock`을 사용한다.
- minute/hour DB bucket은 UTC, day bucket/cron은 `aggregation.zone`을 사용한다.
- 모든 집계 range는 `[from,to)`다.

## WebSocket ingestion

- 외부 연결/구독/JSON 파싱은 `infrastructure:batch/exchange`, 최신값 buffer는
  `infrastructure:batch/ingestion`이 소유한다.
- Application `TickerFlushJob`은 `LatestMarketTickReadPort`에서 읽어 `TickerTimeSeriesWritePort`로 1초
  down-sample한다.
- exchange timestamp보다 오래된 메시지는 버리고 같은 timestamp는 허용한다.
- 관측값이 10초보다 오래되면 seconds 기록을 중단한다.
- connection은 first-message timeout, idle watchdog, generation fencing, exponential reconnect를 사용한다.
- `test` profile은 실제 stream bean 대신 port fallback을 사용한다. 테스트는 MockWebServer/가짜 stream으로만
  외부 연결을 검증한다.

## FX와 Premium

- FX write는 MySQL 성공 후 Redis를 갱신한다. DB 실패 시 cache-only 값을 발행하지 않는다.
- Premium 계산은 Domain `PremiumPolicy`만 사용하고 `MarketPair`/FX source/observedAt을 보존한다.
- current/seconds는 필수 경로다. history 실패는 현재 계산을 실패시키지 않는 명시적 non-critical 경로다.
- threshold 평가는 premium snapshot 저장 뒤 실행되며 활성 구독 조회와 enqueue는 같은 DB transaction이다.

## Durable notification

- in-memory event, `@Async` listener, Redis cooldown을 전달 보장으로 사용하지 않는다.
- event identity는 subscription revision, MarketPair, direction, threshold, cooldown window를 포함한다.
- worker는 실행 가능한 concurrency만큼만 `FOR UPDATE SKIP LOCKED`로 claim한다.
- row마다 owner와 UUID claim token을 저장하고 모든 상태 전이에 fencing 조건을 적용한다.
- SMTP 전에 PROCESSING claim을 commit한다. 성공 후 mark 실패로 중복될 수 있으므로 at-least-once다.
- retry/max attempts/stale recovery/redrive/PII scrub은
  `docs/runbooks/durable-notification-delivery.md`와 함께 변경한다.

## Readiness와 metric

- liveness에는 외부 dependency를 넣지 않는다.
- Batch readiness는 DB/Redis와 필수 Binance/Bithumb connection/message freshness를 포함한다.
- scheduling disabled test에서는 ingestion만 readiness 대상에서 제외한다.
- exchange/job/outcome처럼 bounded tag만 사용한다. symbol은 configured bounded market만 허용한다.
- owner, claim token, delivery ID, email, exception message는 tag로 기록하지 않는다.
