# [Phase 4] REST 폴링 코드 제거 — 구현 계획

- 이슈: #32 (Epic #28의 마지막 Phase)
- 스펙: `docs/superpowers/specs/2026-05-21-issue-32-remove-rest-polling-design.md`
- 브랜치: `refactor/issue-32-remove-rest-polling` (base `dev`)

## 개요

REST 1초 폴링 수집 경로와 `premium.ingestion.*.mode` 피처 플래그를 완전히 제거한다.
순수 삭제 작업이므로 신규 코드 없음 — 끊긴 참조 해소가 핵심.

## Task 1: REST 폴링 코드 파일 삭제

다음 운영 코드 파일을 통째로 삭제한다.

- `apps/batch/src/main/kotlin/io/premiumspread/scheduler/TickerScheduler.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJob.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceClient.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/client/binance/BinanceResponse.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbClient.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/client/bithumb/BithumbResponse.kt`
- `apps/batch/src/main/kotlin/io/premiumspread/config/IngestionModeConfig.kt`

`application/job/ticker/` 디렉터리가 비면 함께 제거.

- [ ] 완료

## Task 2: 피처 플래그 `@ConditionalOnProperty` → `@Profile("!test")` 교체

다음 8개 파일에서 `@ConditionalOnProperty("premium.ingestion.*.mode", havingValue="websocket")`
어노테이션 줄을 제거하고, 대신 `@Profile("!test")`를 추가한다.
import는 `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` 제거 +
`org.springframework.context.annotation.Profile` 추가.

**`@Profile("!test")` 이유 (codex 리뷰 ISSUE-1)**: `@ConditionalOnProperty`를 단순 제거하면
`BinanceWebSocketClient`/`BithumbWebSocketClient` 빈이 모든 `@SpringBootTest`(integration test)
컨텍스트에 등록되어 `@PostConstruct start()`가 실제 거래소로 outbound WS 연결을 연다.
`test` 프로파일은 이미 `scheduling.enabled=false`/`redis.enabled=false`로 운영 빈을
비활성화하므로 `@Profile("!test")`가 일관적이다. WS 통합 테스트는 `WebSocketConnectionManager`를
수동 생성하므로 영향 없음.

- `client/binance/BinanceWebSocketClient.kt`
- `client/bithumb/BithumbWebSocketClient.kt`
- `infrastructure/ingestion/binance/BinanceTickerIngestion.kt`
- `infrastructure/ingestion/binance/BinanceFlushJob.kt`
- `scheduler/BinanceFlushScheduler.kt`
- `infrastructure/ingestion/bithumb/BithumbTickerIngestion.kt`
- `infrastructure/ingestion/bithumb/BithumbFlushJob.kt`
- `scheduler/BithumbFlushScheduler.kt`

`PremiumSpreadBatchApplication.kt`의 `@ConditionalOnProperty("scheduling.enabled")`는 건드리지 않음.

- [ ] 완료

## Task 3: WebClientConfig REST 빈 제거

`client/config/WebClientConfig.kt`에서 `binanceWebClient()`, `bithumbWebClient()` 빈 메서드를 제거한다.
`exchangeRateWebClient()`와 `createWebClient()` 헬퍼는 유지.

- [ ] 완료

## Task 4: Redis 키/TTL 정리

- `modules/redis/.../RedisKeyGenerator.kt`: `lockTickerKey()` 함수 + 주석 제거
- `modules/redis/.../RedisTtl.kt`: `Lock.TICKER_LEASE` 상수 + 주석 제거

`Lock.FX_LEASE`, `Lock.PREMIUM_LEASE`는 유지.

- [ ] 완료

## Task 5: YAML 피처 플래그 키 제거

세 파일에서 `premium.ingestion` 블록(주석 포함)을 제거한다.

- `apps/batch/src/main/resources/application.yml` (라인 27-33 + 라인 27 주석)
- `apps/batch/src/main/resources/application-local.yml` (라인 4-9)
- `apps/batch/src/main/resources/application-prd.yml` (라인 16-23, 주석 포함)

- [ ] 완료

## Task 6: 테스트 파일 삭제

- `scheduler/TickerSchedulerTest.kt`
- `scheduler/TickerSchedulerE2ETest.kt`
- `client/binance/BinanceClientTest.kt`
- `client/bithumb/BithumbClientTest.kt`
- `application/job/ticker/TickerIngestionJobTest.kt`
- `application/job/ticker/TickerIngestionJobModeTest.kt`
- `config/IngestionModeConfigTest.kt`

- [ ] 완료

## Task 7: BatchTestConfig 정리

`apps/batch/src/test/kotlin/io/premiumspread/config/BatchTestConfig.kt`에서 제거:

- `import io.premiumspread.client.binance.BinanceClient`
- `import io.premiumspread.client.bithumb.BithumbClient`
- `bithumbMockServer()`, `binanceMockServer()` 빈
- `@Primary bithumbClient(...)`, `@Primary binanceClient(...)` 빈

유지: `exchangeRateMockServer()`, `@Primary exchangeRateClient(...)`, 그 외 전부.

- [ ] 완료

## Task 8: 빌드/테스트 검증

```bash
./gradlew :apps:batch:compileKotlin :apps:batch:test
./gradlew :apps:batch:integrationTest   # Docker 필요 — @Tag("integration")는 test 태스크에서 제외됨
./gradlew compileKotlin
```

끊긴 참조(컴파일 에러)가 있으면 해소. 모두 통과해야 한다.
integration test는 `@Profile("!test")` 적용으로 실제 WS 연결 없이 컨텍스트가 로드되는지 검증한다.
Docker 미가용 환경이면 PR 본문에 명시하고 unit test + compile로 대체.

- [ ] 완료

## Task 9: 문서 갱신

### `.ai/rules/batch.md` — WebSocket ingestion 패턴 6개 항목 명문화 (필수)

1. thin scheduler + ingestion/flush Job 패턴
2. last-run 헬스 모델 (`batch:last_run:{job}` 갱신)
3. 연속 N회 실패 시 AlertService 호출 규칙
4. monotonic check (메시지 reorder/replay 방어)
5. stale threshold + skip + 메트릭
6. silent ingestion outage 방어 (연결 후 N초 내 첫 메시지 알람)

### `CLAUDE.md`

`apps/batch` 설명의 "REST 1초/30분 수집 또는 WebSocket 실시간 수집" → WebSocket 전용 문구로 갱신.

### `.ai/architecture/ARCHITECTURE_DESIGN.md`

- "1) Ticker 수집" 섹션에서 "1-a) REST 모드" 블록 제거
- "1-b) WebSocket 모드" → "WebSocket 수집"으로 단일화, mode 선택 문구 제거
- 모듈 트리에서 `BinanceClient(REST)`/`BithumbClient(REST)` 설명 제거, `TickerScheduler` 언급 제거

### `.ai/PROJECT_STATUS.md`

- apps/batch 행에서 "1초/30분 수집" → WebSocket 전용 반영
- Recent Changes에 `refactor: REST 폴링 코드 제거 (#32)` 추가
- TODO/Completed 갱신

- [ ] 완료
</content>
