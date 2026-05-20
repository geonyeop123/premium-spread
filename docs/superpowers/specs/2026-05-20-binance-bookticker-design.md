# Binance ingestion 채널 bookTicker 전환 — 설계 문서

- 작성일: 2026-05-20
- 관련 이슈: #52 (feat, 채널 전환), #51 (docs, miniTicker 주기 정정)
- Epic: #28 / 직전 fix: #46
- 베이스 브랜치: `dev`

## 1. 배경

`apps/batch`의 Binance USD-M Futures 시세 수집은 현재 `<symbol>@miniTicker` WebSocket 채널을 사용한다.
이슈 #46 머지 후 15시간 운영 모니터링(2026-05-18 18:34 ~ 2026-05-19 09:41)에서 두 가지 사실이 확인되었다.

1. **miniTicker 실제 push 주기는 2초** (공식 spec `Update Speed: 2s`). 설계 문서·이슈 본문에 "1초 고정"으로 기재된 것은 오류 — #51.
2. miniTicker는 **2초 고정 스냅샷 통계 채널**이라, **이벤트 기반(가격 변동 시 push)** 인 빗썸과 비대칭이 발생한다. 변동성 큰 시점에 Binance 가격이 최대 2초 지연 → 김치 프리미엄 산정 정밀도 손실.

측정값:
- 빗썸 cache writes: 23,503건 (~0.43 fr/s, 시간대별 642~2119건 — 이벤트 기반)
- 바이낸스 cache writes: 27,092건 (~0.5 fr/s, 시간당 ≈1800건 = 2초 고정 throttle)

## 2. 목표

Binance ingestion을 `<symbol>@bookTicker`(best bid/ask 변동 시 실시간 push) 채널로 전환하여,
Binance 측 가격 갱신을 빗썸과 동일한 "변동 시 push" 모델로 정렬하고 김프 산정 정밀도를 높인다.

`#52`(코드 전환)와 `#51`(문서 정정)을 한 브랜치에서 함께 처리한다.

## 3. 확정된 의사결정 (사용자 승인 2026-05-20)

| 항목 | 결정 | 근거 |
|------|------|------|
| 가격 정책 | `mid = (bestBid + bestAsk) / 2` | bid/ask 한쪽 편향 없음, 일반적 spread 비교 관행 |
| Throttle | 옵션 B — 1초 down-sample | 빗썸 패턴과 정렬, Redis write 부하 안정 |
| Stale 임계값 | 10초 | `BithumbFlushJob.STALE_THRESHOLD`와 동일 — 패턴 일관성 |
| volume 처리 | `null` (집계 미사용으로 무해) | bookTicker payload에 volume 필드 없음 |
| #51 GitHub 이슈 본문 | 수정 안 함 — 리포 문서만 정정 | 외부 가시 변경 최소화, miniTicker 자체 교체로 가치 제한 |

## 4. 접근 방식

빗썸은 이미 동일한 "변동 push + 1초 down-sample" 구조를 갖추고 있다.
빗썸 구현을 **참조 원본**으로 삼아 Binance에 이식한다. miniTicker는 2초 고정 push라 down-sample이
불필요했으나, bookTicker는 초당 수십~수백 건 push되므로 빗썸과 동일한 in-memory 보관 + 1초 flush 구조가 필요하다.

### 빗썸 ↔ Binance 대응

| 빗썸 (참조 원본) | Binance (목표) | 작업 |
|---|---|---|
| `BithumbWebSocketTickerMessage` | `BinanceBookTickerMessage` | 신규 (기존 `BinanceMiniTickerMessage` 대체) |
| `BithumbTickerIngestion` (hash write + `latest()` 보관) | `BinanceTickerIngestion` | 변경 |
| `BithumbFlushJob` (1초 ZSet flush, stale 10s) | `BinanceFlushJob` | 신규 |
| `BithumbFlushScheduler` (`@Scheduled(fixedRate=1000)`) | `BinanceFlushScheduler` | 신규 |

## 5. 컴포넌트 설계

### 5.1 BinanceBookTickerMessage (payload)

`apps/batch/.../client/binance/BinanceWebSocketResponse.kt` 내 `BinanceMiniTickerMessage`를 제거하고 대체.

Binance USD-M Futures `<symbol>@bookTicker` 페이로드:
```json
{"e":"bookTicker","u":400900217,"E":1568014460893,"T":1568014460891,
 "s":"BTCUSDT","b":"25.35","B":"31.21","a":"25.36","A":"40.66"}
```

필드 매핑:
| JSON | 프로퍼티 | 타입 | 비고 |
|------|---------|------|------|
| `e` | `eventType` | `String` | "bookTicker" |
| `u` | `updateId` | `Long` | order book updateId |
| `E` | `eventTime` | `Long` | epoch ms — timestamp로 사용 |
| `T` | `transactTime` | `Long` | epoch ms |
| `s` | `symbol` | `String` | "BTCUSDT" |
| `b` | `bestBid` | `String` | best bid 가격 |
| `B` | `bidQty` | `String?` | best bid 수량 (미사용) |
| `a` | `bestAsk` | `String` | best ask 가격 |
| `A` | `askQty` | `String?` | best ask 수량 (미사용) |

`@JsonIgnoreProperties(ignoreUnknown = true)` 유지.

### 5.2 BinanceWebSocketClient

- `URL`: `wss://fstream.binance.com/market/ws/btcusdt@miniTicker` → `wss://fstream.binance.com/market/ws/btcusdt@bookTicker`
  - **엔트리포인트 `/market`는 유지** — miniTicker의 잔재가 아니라 2026-03-06 Binance "M Futures WebSocket System Upgrade" 이후의 검증된 USD-M Futures WebSocket 엔트리포인트다. 이슈 #46(`fix/issue-46-binance-ws-market-endpoint`, PR #48)이 이 경로로 정정했고, #51이 공식 문서 대조 + 60초 외부 probe + 15시간 라이브 모니터링(27,167건 수신, reconnect 0)으로 실증했다. bookTicker와 miniTicker는 동일 connection 엔트리포인트를 공유하며 stream 이름만 다르다.
- `parse(payload)`:
  - `BinanceBookTickerMessage`로 역직렬화
  - `bestBid`, `bestAsk`를 각각 `BigDecimal`로 파싱 — 둘 중 하나라도 실패 시 `recordParseError` 호출 후 `null` 반환
  - `price = bid.add(ask).divide(BigDecimal(2), MID_PRICE_SCALE, RoundingMode.HALF_UP)` — `MID_PRICE_SCALE = 8`
  - `TickerData(exchange="BINANCE", symbol=extractBaseSymbol(s), currency="USD", price=mid, volume=null, timestamp=Instant.ofEpochMilli(E))`
- KDoc 주석을 bookTicker(실시간 push, mid 산정) 기준으로 갱신
- `handlePayload`, `recordParseError`, `extractBaseSymbol`, alert 정책(5회 연속 parse 실패 → critical)은 변경 없음

### 5.3 BinanceTickerIngestion (빗썸 패턴으로 변경)

현재: 메시지 수신 시 hash + ZSet **즉시** 저장.
변경 후 (`BithumbTickerIngestion`과 동일 구조):
- `LatestTicker(ticker: TickerData, receivedAt: Instant)` data class 추가
- `lastTicker: AtomicReference<LatestTicker?>` 보관
- `onMessage(ticker)`:
  - monotonic check를 **accept-equal**로 완화 — `ticker.timestamp.isBefore(prev.timestamp)` 인 경우만 폐기.
    (현재 Binance는 strict `!isAfter`로 같은 ms도 폐기. bookTicker는 같은 `E` ms에 복수 메시지가 정상이므로 빗썸과 동일하게 완화)

#### 5.3.1 updateId(`u`)를 monotonic 키로 쓰지 않는 이유

bookTicker payload의 `u`(order book updateId)는 단조 증가하지만, monotonic 판정에는 `eventTime`만 사용한다(빗썸과 동일). 근거:

1. **전송 계층이 순서를 보장한다** — 단일 WebSocket connection의 프레임은 TCP 순서 보장 + Reactor Netty inbound flux의 순차 처리로 도착·처리된다. 같은 `E` ms 내 메시지도 `u` 오름차순으로 도착하므로, accept-equal은 결과적으로 최고 `u`가 `latest()`에 남는다. (단위 테스트의 16스레드 동시 호출은 CAS 안전성 검증용 artifact일 뿐, 운영 경로는 순차다.)
2. **1초 down-sample이 sub-ms 차이를 무의미하게 만든다** — `BinanceFlushJob`은 `latest()`를 초당 1회만 ZSet에 샘플링한다. 같은 ms 내 순서 역전이 가상으로 발생하더라도 ~1ms 뒤 다음 메시지가 즉시 교정하며, 1초 OHLC 집계 버킷에는 영향이 없다.
3. **공유 모델 오염 회피** — `u`를 monotonic 키로 쓰려면 `TickerData`(REST/WS/빗썸 공유)에 Binance 전용 필드를 추가하거나 ingestion 경로에 별도 상태를 끼워야 한다. 전송 계층이 이미 보장하는 불변식을 위한 방어 코드는 프로젝트 원칙(CLAUDE.md "과도한 추상화 금지 — 필요할 때만")에 어긋난다.

→ `u`는 payload DTO에는 보존하되(`BinanceBookTickerMessage.updateId`) ingestion 로직에서는 사용하지 않는다.
  - out-of-order 폐기 시 `metrics.recordOutOfOrder(EXCHANGE)`
  - `metrics.recordLag(EXCHANGE, lagMs)` 유지
  - **hash만 즉시 저장** (`tickerCacheService.save(ticker)`) — ZSet 저장은 제거
  - hash 저장 실패 5회 연속 → critical alert (기존 정책 유지)
- `latest(): LatestTicker?` 메서드 추가

### 5.4 BinanceFlushJob (신규)

`apps/batch/.../infrastructure/ingestion/binance/BinanceFlushJob.kt` — `BithumbFlushJob` 미러.
- `run()`:
  - `ingestion.latest()` 가 `null`이면 return
  - `age = now - latest.receivedAt`, `age > STALE_THRESHOLD(10s)` 이면 `metrics.recordStale(EXCHANGE)` 후 return
  - `tickerCacheService.saveToSecondsWithScore(latest.ticker, now)` — score는 flush 시점 `Instant.now(clock)`
  - `redisTemplate.opsForValue().set(LAST_RUN_KEY, now.toEpochMilli().toString(), RedisTtl.BATCH_HEALTH)`
  - `metrics.recordFlush(EXCHANGE)`
  - flush 실패 5회 연속 → critical alert
- 상수: `EXCHANGE = "binance"`, `STALE_THRESHOLD = Duration.ofSeconds(10)`, `FAILURE_ALERT_THRESHOLD = 5`, `LAST_RUN_KEY = "batch:last-run:binance-flush"`
- `@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")`

### 5.5 BinanceFlushScheduler (신규)

`apps/batch/.../scheduler/BinanceFlushScheduler.kt` — `BithumbFlushScheduler` 미러.
- `@ConditionalOnProperty("premium.ingestion.binance.mode", havingValue = "websocket")`
- `@Scheduled(fixedRate = 1000) fun flush() = flushJob.run()` — thin entrypoint
- 단일 인스턴스 전제, 분산 락 불필요 (`JobExecutor` 미사용 — 빗썸과 동일)

## 6. 데이터 흐름

```
bookTicker push (초당 수십~수백 건)
  → BinanceWebSocketClient.handlePayload → parse() : price = (bid + ask) / 2
  → BinanceTickerIngestion.onMessage() : monotonic check → hash 즉시 write + latest() 갱신

BinanceFlushScheduler (@Scheduled fixedRate=1000)
  → BinanceFlushJob.run() : latest() 조회
      → stale(age > 10s) → recordStale + skip
      → 정상 → saveToSecondsWithScore(ticker, now) : ZSet 저장 (down-sample 1Hz)
```

캐시 키(`ticker:binance:btc`, 초ZSet), 집계 로직(`TickerAggregationScheduler`), `TickerData` 계약은
모두 불변 → 분/시/일 집계 및 김프 계산에 영향 없음.

## 7. 에러 처리

| 상황 | 처리 |
|------|------|
| bid/ask 파싱 실패 | `ws.parse.error{exchange=binance}` counter++, 5회 연속 → critical alert |
| hash write 실패 | `metrics.recordFlushError`, 5회 연속 → critical alert |
| flush(ZSet) 실패 | `metrics.recordFlushError`, 5회 연속 → critical alert, 다음 1초 주기 자연 재시도 |
| out-of-order 메시지 | `metrics.recordOutOfOrder`, 폐기 |
| stale (10초+ push 없음) | `metrics.recordStale`, flush skip (stale 데이터 집계 오염 방지) |
| volume 부재 | `volume = null` — `TickerCacheService.save()`가 `?: ""` 처리, 집계 미사용으로 무해 |

## 8. 임계값 점검 (#51)

- `WebSocketConnectionConfig.firstMessageTimeout = 5s` — bookTicker는 BTC 선물 특성상 sub-second push → **변경 불필요**
- `BinanceFlushJob.STALE_THRESHOLD = 10s` — 신규 도입 (빗썸과 동일)
- Grafana `ws_message_received_total{exchange=binance}` — 메시지율이 2초당 1건 → 초당 수십 건으로 급증.
  대시보드 경고선 재점검 필요 → **문서로 기록**, 대시보드 자동 조정은 비목표(운영자 수동).

## 9. 테스트 전략

| 테스트 | 범위 |
|--------|------|
| `BinanceWebSocketClientTest` (변경) | bookTicker payload 파싱, mid 계산 `(bid+ask)/2`, bid/ask 파싱 실패 시 parse error |
| `BinanceTickerIngestionTest` (변경) | `latest()` 보관, monotonic accept-equal (같은 ms 수용 / 이전 ms 폐기), hash write |
| `BinanceFlushJobTest` (신규) | stale skip(>10s), flush 성공, flush 실패 5회 → alert — `BithumbFlushJobTest` 미러 |
| `BinanceWebSocketIntegrationTest` (변경) | (1) mock 서버 payload를 bookTicker 포맷으로 교체 (2) **신규: bookTicker 수신 → `BinanceFlushJob.run()` → Redis 초ZSet + `LAST_RUN_KEY` 갱신 검증** (flush 경로 end-to-end) |

- 도구: AssertJ + MockK (프로젝트 규칙)
- `./gradlew :apps:batch:compileKotlin` + `./gradlew :apps:batch:test` 통과
- flush 경로 통합 테스트는 `@Tag("integration")` — Testcontainers(Redis) 필요. 구현 단계에서 컴파일 검증, 전체 실행은 `./gradlew :apps:batch:integrationTest` (Docker 환경)

## 10. 문서 정정 (#51 — 리포 문서만)

| 파일 | 갱신 내용 |
|------|----------|
| `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md` | Phase 2 섹션: miniTicker "1초 고정" → "2초 update speed"로 정정 + bookTicker 전환(#52) 연혁 노트 추가 |
| `.ai/PROJECT_STATUS.md` | Binance ingestion = bookTicker 실시간 + 1초 flush로 갱신 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | Binance ingestion 채널·주기 명시 부분 갱신 |
| `CLAUDE.md` | batch 설명의 수집 방식 문구 점검 |

GitHub 이슈 본문(#28 Epic, #30 Phase 2)은 수정하지 않는다.

## 11. 수용 기준

- [ ] 부팅 시 신규 URL 로그 (`/market/ws/btcusdt@bookTicker`)
- [ ] bookTicker payload 정상 파싱 (`b`, `a`, `E` 매핑, mid 계산)
- [ ] `./gradlew :apps:batch:compileKotlin` 통과
- [ ] `./gradlew :apps:batch:test` 통과
- [ ] `TickerData` 계약 불변 — 다운스트림(집계·김프) 영향 없음
- [ ] #51 리포 문서 4종 정정 완료

## 12. 비목표

- 빗썸 채널 변경
- REST 폴링 코드 제거 (#32 별도 진행 — 본 전환 후 운영 검증 기간 재산정 필요)
- 다중 심볼 지원 (현재 BTCUSDT 단일)
- bookTicker volume 복원 (집계 미사용)
- Grafana 대시보드 자동 조정 (운영자 수동 — 임계값 변경 필요성만 문서화)
- GitHub 이슈 본문 수정

## 13. 위험 / 주의사항

- bookTicker는 거래량 의존 — 시장 한산 시 push 빈도 ↓ (BTC USD-M 선물은 유동성이 높아 10초 stale은 사실상 발생하지 않음)
- 페이로드 변경 회귀 위험 — 기존 miniTicker 파싱 코드 제거 시 fallback 없음. 롤백은 `premium.ingestion.binance.mode=rest` 또는 git revert
- 운영 적용 후 Redis write 빈도는 1초 down-sample로 miniTicker(2초당 1건)와 유사 수준 유지 — hash write만 메시지율만큼 증가 (모니터링 권장)
