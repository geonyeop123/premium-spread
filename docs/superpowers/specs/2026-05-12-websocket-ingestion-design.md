# WebSocket 실시간 시세 수집 전환 설계

- 작성일: 2026-05-12
- 작성자: yeop
- 상태: 승인 대기

## 배경

현재 `apps/batch`는 `TickerScheduler`(`@Scheduled(fixedRate = 1000)`)가 1초마다 빗썸/바이낸스 REST API를 폴링해 시세를 수집한다. 이 구조는 다음 한계를 갖는다.

- REST rate limit 압박 (특히 다거래소 확장 시)
- 1초 폴링 간 발생하는 짧은 가격 변동 손실
- 폴링 오버헤드 (매초 HTTP 핸드셰이크/응답 파싱)

해결책으로 양 거래소 공식 WebSocket 스트림을 도입한다. 단, 두 거래소의 push 특성이 다르므로 별도 처리 전략이 필요하다.

| 거래소 | 채널 | Push 방식 |
|--------|------|----------|
| 바이낸스 | `btcusdt@miniTicker` | **1초 고정 주기** |
| 빗썸 | `ticker` | **가격 변동 시 이벤트 기반** |

## 목표 / 비목표

### 목표

- 양 거래소 시세 수집을 WebSocket으로 전환
- 기존 캐시 구조(Redis Hash + 초ZSet) 그대로 재사용 (분/시/일 집계 영향 없음)
- 거래소별 독립 피처 플래그로 점진적 운영 전환
- WebSocket 연결 상태/메시지 lag 모니터링 메트릭 신규 도입

### 비목표

- 업비트 등 신규 거래소 추가 (별도 작업)
- 분/시/일 집계 로직 변경 (`TickerAggregationScheduler` 그대로)
- 다중 batch 인스턴스 운영 (단일 인스턴스 전제)
- REST를 fallback으로 영구 유지 (Phase 4에서 완전 제거)

## 핵심 결정 사항

1. **단일 batch 인스턴스 전제** — leader election 불필요, `BithumbFlushScheduler`에 분산 락 없음
2. **거래소별 독립 피처 플래그** — `premium.ingestion.{binance|bithumb}.mode = rest | websocket`
3. **빗썸은 in-memory `AtomicReference` + 1초 flush use case** — 변동 push와 ZSet 저장 디커플링, scheduler는 thin trigger
4. **Hash 갱신은 실 메시지 도착 시점에만 (수신 시 1회)** — synthetic timestamp가 ticker hash에 들어가지 않음. Hash TTL(`RedisTtl.TICKER = 5s`) 의 freshness 의미 유지
5. **ZSet 저장은 1초 flush 시점에 별도 score로** — `TickerCacheService.saveToSecondsWithScore(ticker, scoreInstant)` 신규 메서드 사용. ticker hash는 건드리지 않음
6. **메시지 수신 시 monotonic check** — 직전 메시지보다 exchange timestamp가 작으면 무시 (reorder/replay 방어)
7. **Stale threshold 10초** — 빗썸 마지막 메시지 수신 후 10초 초과 시 flush skip + `ws.stale.bithumb` 카운터 증가
8. **Connected-but-no-message 알람** — 연결 후 5초 내 첫 메시지 미수신 또는 `ws.last.message.age` Gauge가 10s 초과 시 알람 (silent ingestion outage 방어)
9. **바이낸스는 `@miniTicker` 채널** (1초 고정 push) — 별도 down-sample 불필요
10. **Phase 2/3 운영 검증 후 Phase 4에서 REST 코드 완전 제거**
11. **RatePerSecondLimiter 미도입** — @Scheduled fixedRate=1000 + AtomicReference로 1Hz 자연 보장 (YAGNI)

## 아키텍처

### 모듈 배치 (4-layer 유지)

```
apps/batch/src/main/kotlin/io/premiumspread/
├── client/
│   ├── binance/
│   │   ├── BinanceClient.kt              (REST, Phase 4 제거)
│   │   └── BinanceWebSocketClient.kt     (Phase 2 신규)
│   └── bithumb/
│       ├── BithumbClient.kt              (REST, Phase 4 제거)
│       └── BithumbWebSocketClient.kt     (Phase 3 신규)
├── infrastructure/websocket/             (Phase 1 신규)
│   ├── WebSocketConnectionManager.kt
│   └── WebSocketMetrics.kt
├── application/
│   ├── ingestion/                        (Phase 2/3 신규)
│   │   ├── BinanceTickerIngestion.kt
│   │   ├── BithumbTickerIngestion.kt
│   │   └── BithumbFlushJob.kt            (1초 flush use case, scheduler에서 분리)
│   └── job/ticker/TickerIngestionJob.kt  (Phase 4 제거)
└── scheduler/
    ├── TickerScheduler.kt                (Phase 4 제거)
    └── BithumbFlushScheduler.kt          (Phase 3 신규, thin entrypoint만)
```

### 데이터 흐름

```
[바이낸스 — 1초 고정 push]
Binance WS @miniTicker
  → WebSocketConnectionManager (재연결/하트비트)
  → BinanceWebSocketClient (JSON 파싱)
  → BinanceTickerIngestion (수신 시 즉시 저장)
  → TickerCacheService.save (Hash) + saveToSeconds (초ZSet)

[빗썸 — 변동 push + 1초 flush]
Bithumb WS ticker
  → WebSocketConnectionManager
  → BithumbWebSocketClient (JSON 파싱)
  → BithumbTickerIngestion (AtomicReference 갱신만, exchange timestamp + receivedAt 기록)

BithumbFlushScheduler (@Scheduled fixedRate=1000, thin entrypoint)
  → BithumbFlushJob.run()
       ├─ stale 체크: now - lastReceivedAt > 10s 면 skip + ws.stale 메트릭
       └─ TickerCacheService.saveToSecondsWithScore(latest.ticker, Instant.now())
            ↑ ZSet만 갱신 (score = flush 시점). Hash는 절대 건드리지 않음 — freshness 5s TTL은 메시지 수신 시점에만 갱신.
            ↑ ZSet member 포맷은 `{epochMs}:{price}`로 유일성 보장 → 같은 가격이 연속 flush돼도 5 distinct entries 누적.
```

### 피처 플래그

| 프로퍼티 | 효과 |
|---------|------|
| `premium.ingestion.binance.mode=rest` (기본) | 기존 REST 폴링 |
| `premium.ingestion.binance.mode=websocket` | Phase 2 WebSocket 활성, 해당 거래소 REST 비활성 |
| `premium.ingestion.bithumb.mode=rest \| websocket` | 빗썸 동일 |

거래소별 독립 → 한쪽만 전환 가능, 문제 발생 시 그 거래소만 롤백.

## Phase 분할

```
Phase 1 (인프라)  ──┐
                    ├──► Phase 2 (바이낸스)  ──┐
                    └──► Phase 3 (빗썸)        ├──► Phase 4 (정리)
                                               ┘
```

Phase 2/3은 Phase 1 머지 후 병렬 가능.

### Phase 1: 공통 인프라

**산출물**

- `WebSocketConnectionManager` — Reactor Netty `WebSocketClient` 래퍼
  - lifecycle (`@PostConstruct` 연결, `@PreDestroy` 종료)
  - 재연결: exponential backoff (1s → 2s → 4s → 최대 30s), 무한 재시도
  - 하트비트: 거래소별 정책 주입 가능 (Binance ping 응답 / Bithumb 클라이언트 ping)
  - 메시지 핸들러 등록 (`onMessage: (String) -> Unit`)
- `WebSocketMetrics`
  - `ws.connection.state` (Gauge: 0=disconnected, 1=connected, 태그=exchange)
  - `ws.message.received` (Counter, 태그=exchange)
  - `ws.reconnect.attempt` (Counter, 태그=exchange)
  - `ws.message.lag.ms` (Timer: exchange 메시지 timestamp → 수신 시각, 태그=exchange)
  - `ws.last.message.age` (Gauge: 마지막 메시지 후 경과 초, 태그=exchange) — silent outage 식별
  - `ws.first.message.timeout` (Counter, 태그=exchange) — 연결 후 5초 내 첫 메시지 미수신
  - `ws.out_of_order` (Counter, 태그=exchange) — monotonic check 실패 메시지
  - `ws.stale.{exchange}` (Counter, Phase 3에서 빗썸이 사용)
  - `ticker.flush.{exchange}` (Counter, Phase 3에서 빗썸이 사용)
  - `ticker.flush.error.{exchange}` (Counter, 태그=exception 클래스)

**테스트**

- 단위: `okhttp3.MockWebServer` 기반, 정상 수신/끊김/재연결 시나리오
- AssertJ + MockK

**DoD**

- 빌드 및 단위 테스트 통과
- Phase 2 미리보기 PoC가 `BinanceWebSocketClient`에서 `WebSocketConnectionManager`를 사용해 메시지를 받는다는 것을 PR 본문에 설명

### Phase 2: 바이낸스 WebSocket 통합 (PoC)

**산출물**

- `BinanceWebSocketClient` (`client/binance/`)
  - URL: `wss://fstream.binance.com/ws/btcusdt@miniTicker`
  - JSON 파싱 → `TickerData` 변환 (기존 모델 재사용, exchange="binance", currency="USD", price=miniTicker의 "c" 필드)
- `BinanceTickerIngestion` (`application/ingestion/`)
  - monotonic check (직전 exchange timestamp 비교, 오래된 것 폐기 + `ws.out_of_order` 증가)
  - `onMessage(ticker)` → `tickerCacheService.save(ticker)` + `saveToSeconds(ticker)` (수신 즉시 hash+ZSet 동시 저장)
  - 바이낸스는 1초 고정 push라 down-sample 불필요, 별도 flush job 없음
- 피처 플래그
  - `mode=websocket` 시 `BinanceWebSocketClient` + `BinanceTickerIngestion` 활성 (`@ConditionalOnProperty`)
  - `mode=rest` 시 기존 폴링 동작
  - `TickerIngestionJob`에 거래소별 mode 체크 분기 추가 (의사 코드):

    ```kotlin
    fun run(): JobResult = runBlocking {
        if (binanceMode == "rest") {
            val ticker = binanceClient.getBtcFuturesTicker()
            tickerCacheService.save(ticker); tickerCacheService.saveToSeconds(ticker)
        }
        if (bithumbMode == "rest") {
            val ticker = bithumbClient.getBtcTicker()
            tickerCacheService.save(ticker); tickerCacheService.saveToSeconds(ticker)
        }
        JobResult.Success
    }
    ```

  - 결과: 한쪽만 WebSocket으로 전환해도 다른 쪽 REST는 정상 동작

**테스트**

- 단위:
  - JSON 메시지 → `TickerData` 변환
  - `BinanceTickerIngestion` monotonic check — 직전보다 오래된 timestamp 메시지 폐기
- 통합 (`@Tag("integration")`): Redis TestContainer + Mock WebSocket Server
  - 메시지 1건 push → `TickerCacheService` 캐시 반영 확인
  - 연결 후 5초 동안 메시지 zero → `ws.first.message.timeout` 증가 + AlertService 호출 확인

**DoD**

- `mode=websocket`으로 로컬 1시간 무중단 수집 검증
- 신규 메트릭 노출 확인 (`ws.connection.state`, `ws.message.received`, `ws.reconnect.attempt`, `ws.message.lag.ms`, `ws.last.message.age`, `ws.first.message.timeout`, `ws.out_of_order`)
- 끊김 시뮬레이션 → 30초 내 재연결 + `ws.reconnect.attempt` 증가
- 연결됐지만 메시지 0건 시나리오 → 알람 트리거 확인
- 단위/통합 테스트 통과
- **운영 검증 3일 통과** (Phase 4 진행 조건)

### Phase 3: 빗썸 WebSocket 통합

**산출물**

- `BithumbWebSocketClient` (`client/bithumb/`)
  - URL: `wss://pubwss.bithumb.com/pub/ws`
  - 구독: `{"type":"ticker","symbols":["BTC_KRW"],"tickTypes":["24H"]}`
  - Idle 방지: 클라이언트 ping (빗썸 60초 idle 종료 정책)
  - JSON 파싱 → `TickerData` (exchange="bithumb", currency="KRW")

- `BithumbTickerIngestion` (`application/ingestion/`)

  ```kotlin
  data class LatestTicker(val ticker: TickerData, val receivedAt: Instant)

  @Component
  class BithumbTickerIngestion(
      private val tickerCacheService: TickerCacheService,
      private val metrics: WebSocketMetrics,
      private val clock: Clock,
  ) {
      private val lastTicker = AtomicReference<LatestTicker?>(null)

      fun onMessage(ticker: TickerData) {
          // monotonic check: 직전보다 오래된 exchange timestamp는 폐기
          val prev = lastTicker.get()
          if (prev != null && ticker.timestamp.isBefore(prev.ticker.timestamp)) {
              metrics.recordOutOfOrder("bithumb"); return
          }
          // Hash 갱신은 메시지 수신 시점에만 (exchange timestamp 그대로)
          tickerCacheService.save(ticker)
          lastTicker.set(LatestTicker(ticker, Instant.now(clock)))
      }

      fun latest(): LatestTicker? = lastTicker.get()
  }
  ```

- `BithumbFlushJob` (`application/ingestion/`) — 1초 flush, ZSet만 갱신

  ```kotlin
  @Component
  class BithumbFlushJob(
      private val ingestion: BithumbTickerIngestion,
      private val tickerCacheService: TickerCacheService,
      private val metrics: WebSocketMetrics,
      private val alertService: AlertService,
      private val redisTemplate: StringRedisTemplate,
      private val clock: Clock,
  ) {
      companion object {
          val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
          const val CONSECUTIVE_FAILURE_THRESHOLD = 5  // 5초 연속 실패 시 알람
      }
      private val consecutiveFailures = AtomicInteger(0)

      fun run() {
          val latest = ingestion.latest() ?: return
          val age = Duration.between(latest.receivedAt, Instant.now(clock))
          if (age > STALE_THRESHOLD) {
              metrics.recordStale("bithumb"); return
          }
          try {
              // ZSet만 갱신, score는 flush 시점. ticker hash는 건드리지 않음
              tickerCacheService.saveToSecondsWithScore(latest.ticker, Instant.now(clock))
              metrics.recordFlush("bithumb")
              updateLastSuccessfulRun()
              consecutiveFailures.set(0)
          } catch (e: Exception) {
              metrics.recordFlushError("bithumb", e)
              log.error("Bithumb flush failed", e)
              if (consecutiveFailures.incrementAndGet() >= CONSECUTIVE_FAILURE_THRESHOLD) {
                  alertService.sendCriticalAlert("[bithumb-flush] $CONSECUTIVE_FAILURE_THRESHOLD회 연속 실패")
              }
          }
      }

      private fun updateLastSuccessfulRun() {
          redisTemplate.opsForValue().set(
              RedisKeyGenerator.batchLastRunKey("bithumb-flush"),
              clock.millis().toString(),
              RedisTtl.BATCH_HEALTH,
          )
      }
  }
  ```

  **`TickerCacheService.saveToSecondsWithScore(ticker, scoreInstant)` 신규 메서드** — Phase 3에서 함께 추가:
  - 기존 `saveToSeconds`와 동일하나 ZSet score만 `scoreInstant` 사용
  - Hash는 건드리지 않음
  - 기존 `saveToSeconds`는 `saveToSecondsWithScore(ticker, ticker.timestamp)` 의 별칭으로 유지

- `BithumbFlushScheduler` (`scheduler/`) — thin entrypoint

  ```kotlin
  @Component
  @ConditionalOnProperty("premium.ingestion.bithumb.mode", havingValue = "websocket")
  class BithumbFlushScheduler(private val flushJob: BithumbFlushJob) {
      @Scheduled(fixedRate = 1000)
      fun flush() = flushJob.run()
  }
  ```

  - 분산 락 없음 (단일 인스턴스 전제), `JobExecutor` 미사용 (락/JobResult 의미 없음)
  - Scheduler는 룰에 맞게 trigger만 담당, 비즈니스 로직/예외/메트릭은 `BithumbFlushJob`에 집중

**핵심 동작 규약**

- 메시지 수신 시:
  - monotonic check (exchange timestamp 비교) → 오래된 메시지는 폐기
  - Hash 갱신 (exchange timestamp 그대로) — TTL 5초 freshness 의미 보존
  - `lastTicker` 갱신 + `ws.message.received{exchange=bithumb}` 증가
- 1초 flush 시점:
  - `latest() == null` (연결 후 첫 메시지 미수신) → no-op
  - `age > 10s` → flush skip + `ws.stale.bithumb` 증가
  - 정상: ZSet만 flush 시점 score로 저장 (hash는 갱신하지 않음)
- 연결 후 silent ingestion 방어:
  - `ws.last.message.age{exchange}` Gauge로 마지막 메시지 후 경과 시간 노출
  - 연결 후 5초 내 첫 메시지 없으면 `ws.first.message.timeout` Counter 증가 + 알람
- 운영팀 식별 시그널:
  - `ws.connection.state == 0` (TCP 끊김)
  - `ws.stale.{exchange}` 증가 (메시지 들어오나 timestamp 오래됨)
  - `ws.last.message.age` 임계값 초과 (connected-but-no-message)

**테스트**

- 단위 — `BithumbTickerIngestion`:
  - `AtomicReference` thread-safe 갱신 (동시성 테스트)
  - 정상 메시지 → hash save + lastTicker 갱신
  - 직전보다 오래된 exchange timestamp → 무시 + `ws.out_of_order` 증가
- 단위 — `BithumbFlushJob`:
  - `latest() == null` → no-op
  - `age <= 10s` → `saveToSecondsWithScore` 호출 (hash 호출 없음)
  - `age > 10s` → flush 호출 안 함 + stale 메트릭
  - save 예외 5회 연속 → `AlertService` 호출
  - 성공 시 last-run timestamp 갱신
- 통합:
  - Mock WebSocket 5초간 가격 1건 push → 초ZSet에 **5개의 distinct score** 누적 + hash는 1회만 갱신
  - 메시지 push 중단 11초 → 처음 10초 flush, 이후 stale skip
  - 연결 성공 + 메시지 zero 10초 → `ws.last.message.age` Gauge 증가 확인 (silent outage 시나리오, ISSUE-2 회귀 방지)
  - reverse-order 메시지 시퀀스 → 오래된 것 폐기 확인 (ISSUE-3 회귀 방지)

**DoD**

- `mode=websocket`으로 로컬 1시간 무중단 수집, ZSet 1초 간격(distinct score) 유지 검증
- 끊김 30초 시뮬레이션: 처음 10초 flush, 이후 stale 처리 + 메트릭 증가
- 연결 성공 후 메시지 0건 시나리오에서 알람 트리거 확인
- 재연결 후 첫 메시지 즉시 flush 재개 확인
- `batch:last-run:bithumb-flush` Redis 키가 1초마다 갱신되는지 확인
- 단위/통합 테스트 통과

### Phase 4: REST 폴링 정리

**전제**: Phase 2/3 운영 환경에서 `mode=websocket` 으로 **3일** 무장애 검증 완료

**제거 대상**

- `scheduler/TickerScheduler.kt`
- `application/job/ticker/TickerIngestionJob.kt`
- `client/binance/BinanceClient.kt` (BTC 선물 ticker REST 호출 부분)
- `client/bithumb/BithumbClient.kt` (BTC ticker REST 호출 부분)
- 피처 플래그 `premium.ingestion.*.mode` (Conditional 제거, 항상 활성)
- 관련 테스트: `TickerSchedulerTest`, `TickerSchedulerE2ETest`, `BinanceClientTest`, `BithumbClientTest`

**유지 검토**

- `RedisKeyGenerator.lockTickerKey()`, `RedisTtl.Lock.TICKER_LEASE` — 사용처 없으면 함께 제거
- `JobExecutor` — 집계 Job에서 사용 중이므로 유지

**문서 갱신**

- `.ai/PROJECT_STATUS.md`
- `.ai/architecture/ARCHITECTURE_DESIGN.md`
- `.ai/rules/batch.md` — WebSocket ingestion 패턴 추가 (thin scheduler + flush Job, last-run 헬스 모델, monotonic check, stale threshold, silent outage 알람 — 모두 명문화)
- `CLAUDE.md` — "1초/30분 수집" 문구 갱신
- `apps/batch/src/main/kotlin/io/premiumspread/PremiumSpreadBatchApplication.kt` 주석 (있다면)

**DoD**

- Phase 2/3 운영 검증 기간 통과
- `./gradlew test :apps:batch:integrationTest` 통과
- 메트릭 대시보드에서 1초 폴링 Job 메트릭이 더 이상 발생하지 않음 확인

## 에러 처리 / 운영

- **재연결**: exponential backoff 1s→2s→4s→8s→16s→30s, 30s 도달 후 30s 고정 무한 재시도
- **메시지 파싱 실패**: 해당 메시지 1건만 log warn + 메트릭 (`ws.message.parse.error`), 연결 유지
- **메시지 reorder/replay**: monotonic check로 직전보다 오래된 exchange timestamp 폐기, `ws.out_of_order` Counter 증가
- **빗썸 stale 진입**: 마지막 메시지 후 10초 초과 시 flush skip, `ws.stale.bithumb` Counter 증가. 재연결 후 첫 메시지 도착하면 즉시 flush 재개
- **빗썸 flush 중 ZSet 저장 실패**: log error + 메트릭 (`ticker.flush.error.bithumb`), 다음 1초 주기에 재시도 (자연 복구). **5회 연속 실패 시 `AlertService.sendCriticalAlert`**
- **빗썸 flush 성공 시**: `batch:last-run:bithumb-flush` Redis 키 갱신 (TTL = `RedisTtl.BATCH_HEALTH`) — JobExecutor와 동등한 last-run 헬스 모델
- **연결됐지만 메시지 미수신**: 연결 후 5초 내 첫 메시지 없으면 `ws.first.message.timeout` Counter 증가 + AlertService 호출 (silent ingestion outage 방어)
- **연결 끊김/stale 알림**: `ws.connection.state == 0` 또는 `ws.stale.{exchange}` 또는 `ws.last.message.age{exchange} > threshold` 가 N초 유지 시 알람 (모니터링 대시보드 룰)

## 테스트 전략

- 단위 테스트: AssertJ + MockK
- 통합 테스트: `@Tag("integration")` + `MySqlTestContainersConfig` + `RedisTestContainersConfig` + `okhttp3.MockWebServer` 기반 Mock WebSocket
- 끊김/재연결 시나리오는 통합 테스트로 반드시 커버

## 브랜치 / PR 컨벤션

- `feat/ws-phase1-infra`
- `feat/ws-phase2-binance`
- `feat/ws-phase3-bithumb`
- `chore/ws-phase4-cleanup-rest`

각 phase = 1 PR.

## 롤백 전략

- Phase 2/3 운영 전환 후 문제 발생 시: 해당 거래소 `mode=rest`로 변경 + batch 재시작
- **모드 전환 시 데이터 손실 없음** — 양 모드 모두 동일한 Redis 키 구조 사용 (전환 전후 ZSet 연속성 유지)
- **단, WebSocket 끊김 동안의 실제 가격 변동은 불가역적 손실** — stale threshold(10s) 이후 ZSet 기록 중단으로 stale 오염은 막지만, 손실 자체는 막을 수 없음. Phase 2/3 운영 시 끊김 지속 시간을 알람으로 관리할 것
- Phase 4 머지 후에는 git revert 외에 롤백 경로 없음 → Phase 2/3 검증 기간(각 3일) 통과 후 진행

## 미해결 사항

없음 (모든 결정 사항 확정).
