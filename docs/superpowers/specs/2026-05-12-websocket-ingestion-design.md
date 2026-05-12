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
4. **ZSet score는 flush 시점의 `Instant.now()`** — exchange 메시지 timestamp는 lag 메트릭 전용. 동일 가격 반복 flush가 1초 단위로 누적되도록 score 분리
5. **Stale threshold 10초** — 빗썸 마지막 메시지 수신 후 10초 초과 시 flush skip + `ws.stale.bithumb` 카운터 증가
6. **바이낸스는 `@miniTicker` 채널** (1초 고정 push) — 별도 down-sample 불필요
7. **Phase 2/3 운영 검증 후 Phase 4에서 REST 코드 완전 제거**

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
│   ├── WebSocketMetrics.kt
│   └── RatePerSecondLimiter.kt
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
       ├─ ticker.copy(timestamp = Instant.now())  ← ZSet score 1초 단위 누적용
       └─ TickerCacheService.save + saveToSeconds
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
  - `ws.stale.{exchange}` (Counter, Phase 3에서 빗썸이 사용)
  - `ticker.flush.{exchange}` (Counter, Phase 3에서 빗썸이 사용)
  - `ticker.flush.error.{exchange}` (Counter, 태그=exception 클래스)
- `RatePerSecondLimiter` — Phase 3에서 사용, 인프라 phase에 미리 둠

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
  - `onMessage(ticker)` → `tickerCacheService.save(ticker)` + `saveToSeconds(ticker)`
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

- 단위: JSON 메시지 → `TickerData` 변환
- 통합: `@Tag("integration")` + Redis TestContainer + Mock WebSocket Server
  - 메시지 1건 push → `TickerCacheService` 캐시에 반영 확인

**DoD**

- `mode=websocket`으로 로컬 1시간 무중단 수집 검증
- 메트릭 4종 노출 확인 (actuator/prometheus)
- 끊김 시뮬레이션 → 30초 내 재연결 + `ws.reconnect.attempt` 증가 확인
- 단위/통합 테스트 통과

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
  class BithumbTickerIngestion(private val clock: Clock) {
      private val lastTicker = AtomicReference<LatestTicker?>(null)
      fun onMessage(ticker: TickerData) {
          lastTicker.set(LatestTicker(ticker, Instant.now(clock)))
      }
      fun latest(): LatestTicker? = lastTicker.get()
  }
  ```

- `BithumbFlushJob` (`application/ingestion/`) — 1초 flush 비즈니스 로직

  ```kotlin
  @Component
  class BithumbFlushJob(
      private val ingestion: BithumbTickerIngestion,
      private val tickerCacheService: TickerCacheService,
      private val metrics: WebSocketMetrics,
      private val clock: Clock,
  ) {
      companion object {
          val STALE_THRESHOLD: Duration = Duration.ofSeconds(10)
      }
      fun run() {
          val latest = ingestion.latest() ?: return
          val age = Duration.between(latest.receivedAt, Instant.now(clock))
          if (age > STALE_THRESHOLD) {
              metrics.recordStale("bithumb")
              return
          }
          try {
              // ZSet score를 flush 시점으로 분리 (exchange ts 재사용 금지)
              val sample = latest.ticker.copy(timestamp = Instant.now(clock))
              tickerCacheService.save(sample)
              tickerCacheService.saveToSeconds(sample)
              metrics.recordFlush("bithumb")
          } catch (e: Exception) {
              metrics.recordFlushError("bithumb", e)
              log.error("Bithumb flush failed", e)
          }
      }
  }
  ```

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

- WebSocket 연결 후 첫 메시지 수신 전: `latest() == null` → flush no-op
- 정상 운영 중 (`age <= 10s`): 동일 가격이라도 flush 시점의 `Instant.now()`를 ZSet score로 사용 → 1초 단위로 누적 (ISSUE-1 해결)
- Stale 진입 (`age > 10s`): flush skip + `ws.stale.bithumb` Counter 증가
  - 효과: 끊김 동안 stale 가격이 ZSet에 누적되지 않음 → 집계가 stale 데이터로 오염되지 않음
  - 재연결 후 첫 메시지 도착 즉시 flush 재개
- 운영팀은 `ws.connection.state{exchange=bithumb} == 0` 또는 `ws.stale.bithumb` 증가로 끊김 식별

**테스트**

- 단위: `BithumbTickerIngestion`의 `AtomicReference` thread-safe 갱신
- 단위: `BithumbFlushJob`
  - `latest() == null` → no-op
  - age <= 10s → save 호출, `sample.timestamp == clock.now`
  - age > 10s → save 호출 안 함, stale 메트릭 증가
  - save 예외 → error 메트릭 증가, exception swallow (다음 주기 재시도)
- 통합: Mock WebSocket 5초간 가격 1건 push → 초ZSet에 **5개의 distinct score** 누적 (ISSUE-1 회귀 방지)
- 통합: 메시지 push 중단 11초 → 처음 10초는 flush 발생, 이후는 stale로 skip

**DoD**

- `mode=websocket`으로 로컬 1시간 무중단 수집, ZSet 1초 간격(distinct score) 유지 검증
- 끊김 30초 시뮬레이션: 처음 10초 flush, 이후 stale 처리 + 메트릭 증가 확인
- 재연결 후 첫 메시지 즉시 flush 재개 확인
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
- `.ai/rules/batch.md` — WebSocket ingestion 패턴 추가
- `CLAUDE.md` — "1초/30분 수집" 문구 갱신
- `apps/batch/src/main/kotlin/io/premiumspread/PremiumSpreadBatchApplication.kt` 주석 (있다면)

**DoD**

- Phase 2/3 운영 검증 기간 통과
- `./gradlew test :apps:batch:integrationTest` 통과
- 메트릭 대시보드에서 1초 폴링 Job 메트릭이 더 이상 발생하지 않음 확인

## 에러 처리 / 운영

- **재연결**: exponential backoff 1s→2s→4s→8s→16s→30s, 30s 도달 후 30s 고정 무한 재시도
- **메시지 파싱 실패**: 해당 메시지 1건만 log warn + 메트릭 (`ws.message.parse.error`), 연결 유지
- **빗썸 stale 진입**: 마지막 메시지 후 10초 초과 시 flush skip, `ws.stale.bithumb` Counter 증가. 재연결 후 첫 메시지 도착하면 즉시 flush 재개
- **빗썸 flush 중 ZSet 저장 실패**: log error + 메트릭 (`ticker.flush.error.bithumb`), 다음 1초 주기에 재시도 (자연 복구)
- **연결 끊김 알림**: `ws.connection.state == 0` 또는 `ws.stale.{exchange}` 증가가 N초 이상 유지되면 알람 (모니터링 대시보드 룰, 코드 변경 아님)

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
