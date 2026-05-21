# [Phase 4] REST 폴링 코드 제거 설계

- 작성일: 2026-05-21
- 작성자: yeop
- 이슈: #32 (Epic #28의 마지막 Phase)
- 상태: 승인 대기

## 배경

WebSocket 실시간 시세 수집 전환(Epic #28)의 Phase 1/2/3이 완료되어 dev에 머지되었다.
Binance(`@bookTicker`)·Bithumb(`ticker`) WebSocket 수집이 동작하므로, 이제 dead code가 된
REST 1초 폴링 경로와 `rest | websocket` 피처 플래그를 완전히 제거한다.

상세 설계는 `docs/superpowers/specs/2026-05-12-websocket-ingestion-design.md`의 Phase 4 섹션을 따른다.

> ⚠️ #32 본문 DoD는 "Phase 2/3 운영 환경 3일 무장애 검증 통과 후 진행"을 요구한다.
> bookTicker 전환(#52)이 막 머지되어 운영 검증 기간이 미충족이나, 사용자가 명시적으로
> "전체 수행 + 일반 PR"을 승인했으므로 선진행한다. 머지 전 운영 검증을 권장한다.

## 목표 / 비목표

### 목표

- REST 1초 폴링 수집 경로(scheduler/job/client) 완전 제거
- `premium.ingestion.{binance,bithumb}.mode` 피처 플래그 제거 → WebSocket 컴포넌트 항상 활성
- 끊긴 참조(컴파일 에러) 모두 해소, 빌드·테스트 통과
- `.ai/rules/batch.md`에 WebSocket ingestion 패턴 6개 항목 명문화

### 비목표

- WebSocket 수집 로직 자체의 변경 (동작 동일, `@ConditionalOnProperty`만 제거)
- 집계 Job(`AggregationJob`, `TickerAggregationScheduler` 등) 변경
- 신규 거래소 추가

## 현재 상태 조사 결과 (코드 검증 완료)

### 제거 대상 — 코드

| 파일 | 제거 범위 | 근거 |
|------|----------|------|
| `scheduler/TickerScheduler.kt` | 파일 전체 | REST 1초 폴링 스케줄러 |
| `application/job/ticker/TickerIngestionJob.kt` | 파일 전체 | REST 수집 Job |
| `client/binance/BinanceClient.kt` | 파일 전체 | `BinanceClient`/`BinanceApiException` — `TickerIngestionJob` + 자기 테스트 외 사용처 없음 |
| `client/binance/BinanceResponse.kt` | 파일 전체 | `BinancePriceResponse`/`Binance24hrTickerResponse` — REST client 전용 |
| `client/bithumb/BithumbClient.kt` | 파일 전체 | `BithumbClient`/`BithumbApiException` — `TickerIngestionJob` + 자기 테스트 외 사용처 없음 |
| `client/bithumb/BithumbResponse.kt` | 파일 전체 | `BithumbTickerResponse`/`BithumbTickerData` — REST client 전용 |
| `config/IngestionModeConfig.kt` | 파일 전체 | 피처 플래그 검증 전용 — 플래그 제거 시 존재 이유 소멸 |
| `client/config/WebClientConfig.kt` | `binanceWebClient`/`bithumbWebClient` 빈 제거 | REST client 전용. `exchangeRateWebClient`는 유지 |
| `modules/redis/.../RedisKeyGenerator.kt` | `lockTickerKey()` 제거 | `TickerScheduler` 외 사용처 없음 |
| `modules/redis/.../RedisTtl.kt` | `Lock.TICKER_LEASE` 제거 | `TickerScheduler` 외 사용처 없음 |

> `getFuturesTickerWithVolume`(24hr ticker)도 `BinanceClient`에 있으나 운영 코드 사용처가
> 없으므로(테스트만) `BinanceClient.kt` 통째 제거에 포함된다.

### 피처 플래그 제거 — `@ConditionalOnProperty` 제거 대상

다음 8개 컴포넌트의 `@ConditionalOnProperty("premium.ingestion.*.mode", havingValue="websocket")`
어노테이션과 unused import를 제거하여 항상 활성화한다.

- `client/binance/BinanceWebSocketClient.kt`
- `client/bithumb/BithumbWebSocketClient.kt`
- `infrastructure/ingestion/binance/BinanceTickerIngestion.kt`
- `infrastructure/ingestion/binance/BinanceFlushJob.kt`
- `infrastructure/ingestion/binance/BinanceFlushScheduler.kt` (`scheduler/`)
- `infrastructure/ingestion/bithumb/BithumbTickerIngestion.kt`
- `infrastructure/ingestion/bithumb/BithumbFlushJob.kt`
- `scheduler/BithumbFlushScheduler.kt`

`PremiumSpreadBatchApplication.kt`의 `@ConditionalOnProperty("scheduling.enabled")`는 **무관 — 유지**.

### YAML 키 제거

- `application.yml`: `premium.ingestion` 블록 제거 (라인 27-33)
- `application-local.yml`: `premium.ingestion` 블록 제거 (라인 4-9)
- `application-prd.yml`: `premium.ingestion` 블록 제거 (라인 17-23, #54에서 추가됨)

### 제거 대상 — 테스트

| 파일 | 근거 |
|------|------|
| `scheduler/TickerSchedulerTest.kt` | `TickerScheduler` 제거 |
| `scheduler/TickerSchedulerE2ETest.kt` | `TickerScheduler` 제거 |
| `client/binance/BinanceClientTest.kt` | `BinanceClient` 제거 |
| `client/bithumb/BithumbClientTest.kt` | `BithumbClient` 제거 |
| `application/job/ticker/TickerIngestionJobTest.kt` | `TickerIngestionJob` 제거 |
| `application/job/ticker/TickerIngestionJobModeTest.kt` | `TickerIngestionJob` 제거 |
| `config/IngestionModeConfigTest.kt` | `IngestionModeConfig` 제거 |

### 테스트 인프라 정리 — `config/BatchTestConfig.kt`

- `bithumbMockServer`/`binanceMockServer` 빈 제거 — `TickerSchedulerE2ETest` 제거 후 사용처 없음
- `@Primary bithumbClient`/`@Primary binanceClient` 빈 제거 — `BinanceClient`/`BithumbClient` 삭제로 컴파일 불가
- `exchangeRateMockServer`/`exchangeRateClient`는 유지 (`ExchangeRateSchedulerE2ETest`가 사용)

## 영향 분석

- WebSocket 통합 테스트(`BinanceWebSocketIntegrationTest`, `BithumbWebSocketIntegrationTest`)는
  자체 Mock WebSocket을 사용하므로 영향 없음. 단 `@ConditionalOnProperty` 제거 후 `test` 프로파일에서도
  WS 컴포넌트가 항상 빈으로 등록되므로, 통합 테스트 컨텍스트 로딩에 문제 없는지 확인 필요.
- `JobExecutor`는 집계 Job에서 계속 사용 → 유지.
- `WebClientConfig` 자체는 `exchangeRateWebClient` 때문에 유지.

## 문서 갱신

- `.ai/PROJECT_STATUS.md` — apps/batch 설명에서 "1초/30분 수집" → WebSocket 전용 반영, Recent Changes 추가
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — REST 폴링 데이터 흐름 제거
- `.ai/rules/batch.md` — WebSocket ingestion 패턴 6개 항목 명문화 (아래)
- `CLAUDE.md` — "REST 1초/30분 수집 또는 WebSocket 실시간 수집" → WebSocket 전용 문구

### `.ai/rules/batch.md` 명문화 — 6개 항목

1. thin scheduler + ingestion/flush Job 패턴
2. last-run 헬스 모델 (`batch:last_run:{job}` 갱신)
3. 연속 N회 실패 시 AlertService 호출 규칙
4. monotonic check (메시지 reorder/replay 방어)
5. stale threshold + skip + 메트릭
6. silent ingestion outage 방어 (연결 후 N초 내 첫 메시지 알람)

## DoD

- `./gradlew :apps:batch:compileKotlin :apps:batch:test` 통과
- `./gradlew compileKotlin` 통과 (멀티모듈 끊긴 참조 없음)
- `premium.ingestion.*.mode` 키가 3개 yml 모두에서 제거됨
- `.ai/rules/batch.md`에 WebSocket ingestion 패턴 6개 항목 명문화 완료
- `grep "premium.ingestion\|TickerScheduler\|TickerIngestionJob\|BinanceClient\|BithumbClient"` 결과 0건 (테스트/문서 제외)

## 롤백 주의

- Phase 4 머지 후에는 git revert 외 롤백 경로 없음
- #32 DoD의 3일 운영 검증 게이트는 미충족 상태 — 머지 전 운영 검증 권장
</content>
</invoke>
