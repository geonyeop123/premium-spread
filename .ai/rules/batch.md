# Batch Architecture Rules

## 구조

- `application/common/`: `JobResult`, `JobConfig`, `JobExecutor` (lock/metrics/last-run 공통)
- `application/job/premium/`: `PremiumRealtimeJob`
- `application/job/fx/`: `FxIngestionJob`
- `application/job/aggregation/`: `AggregationJob<T>` (제네릭, reader/writer 패턴)
- `client/{exchange}/`: `*WebSocketClient` (거래소 WebSocket 구독·파싱)
- `infrastructure/websocket/`: `WebSocketConnectionManager`, `WebSocketMetrics` (공통 WS 인프라)
- `infrastructure/ingestion/{exchange}/`: `*TickerIngestion` (수신 처리), `*FlushJob` (1초 down-sample)
- `scheduler/`: `*FlushScheduler` (thin entrypoint), 집계 scheduler

## 스케줄링 Job 규칙 (집계 등)

- scheduler는 `@Scheduled` + `jobExecutor.execute(config) { job.run() }` 패턴만 담당 (thin entrypoint)
- 비즈니스 로직은 Job 클래스에 위치
- JobExecutor가 lock/metrics/last-run 등 공통 관심사 처리

## WebSocket Ingestion 패턴

시세 수집은 거래소 WebSocket 실시간 스트림으로 처리한다 (REST 폴링은 #32에서 제거).
다음 6개 패턴을 신규 WebSocket 수집 컴포넌트에 동일하게 적용한다.

### 1. thin scheduler + ingestion/flush Job 패턴

- `*WebSocketClient`: 거래소 연결·구독·JSON 파싱. 메시지마다 `*TickerIngestion.onMessage(ticker)`로 위임.
- `*TickerIngestion`: 수신 메시지를 in-memory `AtomicReference`에 보관 + Hash 갱신. ZSet은 건드리지 않음.
- `*FlushJob`: 1초 주기로 최신값을 ZSet에 down-sample flush. bookTicker처럼 초당 수십~수백 건
  push되는 채널을 1Hz로 정규화한다.
- `*FlushScheduler`: `@Scheduled(fixedRate = 1000)` + `flushJob.run()`만 호출하는 thin entrypoint.
  단일 인스턴스 전제이므로 분산 락 불필요 → `JobExecutor` 미사용 (집계 Job과 다름).
- 비즈니스 로직·예외·메트릭·last-run·알람은 모두 Job 클래스 내부에 둔다. scheduler는 trigger만.
- WebSocket 컴포넌트는 `@Profile("!test")`로 가드한다 — `test` 프로파일에서 `@PostConstruct`가
  실제 거래소로 outbound 연결을 여는 것을 막는다 (통합 테스트는 `WebSocketConnectionManager`를
  수동 생성해 Mock WebSocket Server에 붙는다).

### 2. last-run 헬스 모델 (`batch:last-run:{job}` 갱신)

- `*FlushJob`은 flush 성공 시마다 Redis 키 `batch:last-run:{job}`(예: `batch:last-run:bithumb-flush`)에
  현재 epoch millis를 기록한다. TTL은 `RedisTtl.BATCH_HEALTH`.
- 모니터링은 이 키의 존재/신선도로 수집 정상 여부를 판단한다 (`JobExecutor`의 last-run과 동등한 헬스 모델).
- no-op(첫 메시지 미수신)·stale skip 시에는 last-run을 갱신하지 않는다 — 장애가 헬스 키에 가려지지 않도록.

### 3. 연속 N회 실패 시 AlertService 호출 규칙

- Hash 저장(`*TickerIngestion`) 또는 ZSet flush(`*FlushJob`) 실패는 `AtomicInteger`로 연속 실패 횟수를 센다.
- `FAILURE_ALERT_THRESHOLD = 5` 회 연속 실패 시 `AlertService.sendCriticalAlert(...)` 호출 후 카운터 리셋.
- 1회성 실패는 다음 1초 주기에 자연 복구되므로 알람하지 않는다 (메트릭만 증가).

### 4. monotonic check (메시지 reorder/replay 방어)

- `*TickerIngestion.onMessage`는 `AtomicReference.updateAndGet` CAS로 직전 메시지와 exchange timestamp를
  비교한다. 직전보다 **오래된**(`isBefore`) timestamp 메시지는 폐기하고 `ws.out_of_order{exchange}` 증가.
- 동일 timestamp는 수용한다 (Bithumb은 HHmmss 초 정밀도, Binance bookTicker는 동일 eventTime ms에
  복수 push가 정상이므로 strict-less-than만 폐기).

### 5. stale threshold + skip + 메트릭

- `*FlushJob.run()`은 `now - lastReceivedAt > STALE_THRESHOLD`(10초)이면 flush를 skip하고
  `ws.stale.{exchange}` 카운터를 증가시킨다.
- stale 동안 ZSet 기록을 멈춰 오래된 가격이 신선한 데이터로 누적되는 오염을 막는다.
- 재연결 후 첫 메시지가 도착하면 자동으로 flush를 재개한다.

### 6. silent ingestion outage 방어 + idle-timeout watchdog (이슈 #57)

TCP 연결은 살아있으나 메시지가 전혀 들어오지 않는 "silent outage"를 두 레이어에서 처리한다.

**WebSocket 연결 레이어 — `WebSocketConnectionManager`:**
- 연결 후 `firstMessageTimeout`(5초) 내 첫 메시지 미수신 시 `ws.first.message.timeout{exchange}` 증가 +
  `AlertService.sendCriticalAlert` + **강제 재연결**(이슈 #57).
- 핸드셰이크 직후부터 idle-timeout watchdog 동작 — `now - lastMessageAt > idleTimeout`(기본 60초) 시
  현재 연결을 강제 종료하고 backoff 후 재연결(`ws.reconnect.attempt{exchange}` 증가, `AlertService` 호출).
- watchdog 콜백은 boundedElastic 스케줄러에서 alert를 dispatch하므로 alert hang에도 reconnect timer가 막히지 않는다.
- 연결 세대 카운터(`connectionGeneration`)로 stale 콜백이 새 연결을 끊는 race를 방지한다.

**Ingestion 레이어 — `*FlushJob.STALE_THRESHOLD`(10초):**
- `ws.last.message.age{exchange}` Gauge로 마지막 메시지 후 경과 시간을 노출.
- `*FlushJob`이 ZSet flush를 skip하고 `ws.stale.{exchange}` 카운터를 증가 — ticker-specific staleness.

두 레이어는 직교한다: watchdog는 **모든 inbound 프레임**을 fresh로 인정하여 TCP-level 완전 침묵을 검출,
FlushJob staleness는 ticker 데이터 stream 정체를 검출. 운영팀 식별 시그널은 `ws.connection.state == 0`,
`ws.stale.{exchange}` 증가, `ws.last.message.age` 임계값 초과(connected-but-no-message), `ws.reconnect.attempt` 급증.
</content>
