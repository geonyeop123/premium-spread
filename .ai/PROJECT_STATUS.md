# Project Status

> Last updated: 2026-05-18 (이슈 #44 — 프론트엔드 Position AUTO/MANUAL 폼 분리 + PnL KRW 표시 반영)

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | MVP 1 백엔드 완료, JWT Stateless 인증, DB 쿼리 최적화 (N+1·인덱스·currency 컬럼), 회원 알림 구독 CRUD (이슈 #27), Position 도메인 한국/해외 페어 모델로 재구조화 (이슈 #41), Position 오픈 AUTO/MANUAL 엔드포인트 분기 (이슈 #42), Position PnL 페어 기반 KRW 손익 확장 (이슈 #43) |
| apps/batch | Active | 1초/30분 수집 + 1분/1시간/1일 집계, PremiumUpdatedEvent + 이메일 알림 리스너 (이슈 #27), WebSocket 공통 인프라 + Binance/Bithumb WS 수집 (Phase 1/2/3) |
| apps/web | Active | Next.js 16 + shadcn/ui + TradingView Charts, 대시보드/포지션/인증 UI, Position 오픈 AUTO/MANUAL 폼 분리 + PnL KRW 표시 (이슈 #44) |
| modules/redis | Active | ZSet 중복 제거 + 캐시 워밍, AggregationTimeUnit(DAYS 추가), TTL 확장 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Active | Slack AlertService (운영자 알람) |
| supports/email | Active | JavaMail 기반 이메일 발송 (Gmail SMTP) — 이슈 #27 |

## Recent Changes

```text
feat: Position 프론트엔드 AUTO/MANUAL 폼 분리 + PnL KRW 표시 (#44)
feat: Position PnL 수식 페어 기반 KRW 손익으로 확장 (#43)
feat: Position 오픈 AUTO/MANUAL 엔드포인트 분기 (#42)
feat: Position 도메인 한국/해외 페어 모델로 재구조화 (#41)
feat: Phase 3 Bithumb WebSocket 1Hz 다운샘플 수집 (#31)
feat: Phase 2 Binance Futures WebSocket 실시간 수집 (#30)
feat: Phase 1 WebSocket 공통 인프라 — ConnectionManager + Metrics (#29)
feat: 회원 프리미엄 임계값 도달 이메일 알림 (이슈 #27)
chore: 하네스 구성 — 에이전트 8개 + 스킬 9개 + 오케스트레이터
fix: PremiumControllerE2ETest 에러 메시지 한국어 매핑 반영
fix: E2E 테스트 JWT 인증 방식으로 마이그레이션
fix: codex 리뷰 반영 (WU-03 음수 프리미엄 + domain 동등성 테스트)
fix: origin merge 충돌 해소 + 마이그레이션 V8→V9 재번호
feat: JWT 인증 전환 — 세션 → Stateless (WU-08)
fix: DB 쿼리 최적화 — N+1 제거·인덱스·통화 (WU-04)
feat: 설정 강화 + Slack AlertService 구현 (WU-07)
fix: 캐시 개선 — ZSet 중복 제거 + 캐시 워밍 (WU-05)
fix: 인증·예외 보안 강화 (WU-02)
fix: Batch 계층 정리 — 포지션 의존 제거·락 로그 조정 (WU-06)
fix: 프리미엄 계산 로직 일원화 (WU-03)
fix: 환경변수 보안 강화 — API키·Redis 비밀번호 (WU-01)
```

## TODO

### Completed
- [x] 차트 무한스크롤 (드래그로 과거 데이터 조회, 최대 범위: 1m=24h, 1h=30d, 1d=365d)
- [x] Redis 집계 캐싱 (cache→DB fallback, AggregationTimeUnit.DAYS 추가)
- [x] WU-01: 환경변수 보안 강화 (API키·Redis 비밀번호 외부화)
- [x] WU-02: 인증·예외 보안 강화
- [x] WU-03: 프리미엄 계산 로직 일원화 (batch-domain 불일치 해소, 음수 프리미엄 수정)
- [x] WU-04: DB 쿼리 최적화 (N+1 제거, 복합 인덱스 추가, ticker 집계 currency 컬럼)
- [x] WU-05: 캐시 개선 (ZSet 중복 제거 + 캐시 워밍)
- [x] WU-06: Batch 계층 정리 (포지션 의존 제거·락 로그 조정)
- [x] WU-07: 설정 강화 + Slack AlertService 구현 (Webhook 기반 알림)
- [x] WU-08: JWT 인증 전환 (세션 → Stateless, Access/Refresh Token)
- [x] E2E 테스트 JWT 인증 방식 마이그레이션
- [x] Flyway 마이그레이션 V8 (position.member_id), V9 (인덱스·currency 컬럼)
- [x] 이슈 #27: 회원 프리미엄 임계값 도달 이메일 알림 (NotificationSubscription CRUD + 비동기 이벤트 리스너 + supports/email)

- [x] Phase 1 (#29): WebSocket 공통 인프라 — Reactor Netty 기반 `WebSocketConnectionManager`, `WebSocketMetrics` (9종), `HeartbeatPolicy`, `WebSocketConnectionConfig`, 단위 테스트 18개
- [x] Phase 2 (#30): 바이낸스 Futures WebSocket 실시간 수집 — `BinanceWebSocketClient` + `BinanceTickerIngestion` (CAS strict monotonic + lag 측정), `premium.ingestion.binance.mode` 토글, `TickerIngestionJob` mode 분기 + atomic await
- [x] Phase 3 (#31): 빗썸 WebSocket 1Hz down-sample 수집 — `BithumbWebSocketClient` + `BithumbTickerIngestion` (AtomicReference 최신값 유지, same-second 수용), `BithumbFlushJob/Scheduler` (thin entrypoint), `TickerCacheService.saveToSecondsWithScore` (`{epochMs}:{price}` ZSet member 포맷)
- [x] 이슈 #41: Position 도메인 한국/해외 페어 모델로 재구조화 — Flyway V12 (단일 거래소 컬럼 → korea_* rename + foreign_* 4개 컬럼 신규), `Position` 엔티티에 한국 long + 해외 short 페어 필드 + `foreignLeverage` (1~125), 도메인 검증 (`koreaExchange.region == KOREA`, `foreignExchange.region == FOREIGN` 및 `FX_PROVIDER` 거절, 수량/가격/환율 양수), `entryPremiumRate` 서버 계산 (`Premium.calculatePremiumRate`와 동일 `DIVISION_SCALE=10`, scale=2), Command/Criteria/Result/Request/Response/Controller 페어 필드로 변환, `POST /api/v1/positions` 페어 본문으로 교체
- [x] 이슈 #42: Position 오픈 AUTO/MANUAL 엔드포인트 분기 — `POST /api/v1/positions/auto` (서버가 `PremiumService.findLatestSnapshotBySymbol`로 진입가/환율/관측시각 자동 채움, 60초 신선도 검증) + `POST /api/v1/positions/manual` (진입가/환율/관측시각 사용자 입력) 신설, 루트 `POST /api/v1/positions` 제거 (405 응답). `PremiumSnapshotNotAvailableException`/`StalePremiumSnapshotException` 신규 + GlobalExceptionHandler 409 매핑, `HttpRequestMethodNotSupportedException` 405 매핑. DTO `PositionCriteria.Open`/`PositionRequest.Open` → `OpenAuto`+`OpenManual` 분리, Controller 두 엔드포인트로 분기.
- [x] 이슈 #43: Position PnL 페어 기반 KRW 손익 확장 — `Position.calculatePremiumDiff(currentPremiumRate)` → `Position.calculatePnl(currentKoreaPrice, currentForeignPrice, currentFxRate, currentPremiumRate)` 4-인자 시그니처로 변경 (Position 도메인이 `PremiumSnapshot`에 직접 의존하지 않음). 시세 양수 검증(`require` koreaPrice/foreignPrice/fxRate > 0) 추가로 0 이하 snapshot이 0.00% PnL로 마스킹되는 케이스 차단. `PositionPnl`에 5개 필드 추가 (`koreaPnl`, `foreignPnlKrw`, `totalPnlKrw`, `koreaCurrentValue`, `totalPnlPercent`). `isProfit()` 시맨틱 `premiumDiff < 0` → `totalPnlKrw > 0` 으로 변경 (실제 KRW 이익 여부로 자연화). `PositionFacade.calculatePnl`이 `findLatestSnapshotBySymbol`을 사용하여 snapshot 분해 후 4-인자 전달, `PositionResult.Pnl`/`PositionResponse.Pnl`에 동일 필드 추가. 회귀 테스트 추가 (사용자 예시 0.157/0.15 → +1,808,138원 ≈ 9.73%, 양쪽 손실, isProfit/premiumDiff 부호 불일치, 시세 양수 검증).
- [x] 이슈 #44: Position 프론트엔드 AUTO/MANUAL 폼 분리 + PnL KRW 표시 — `OpenPositionForm`에 AUTO/MANUAL 모드 토글 추가 (기본 AUTO). AUTO는 `symbol`/`koreaExchange`/`koreaQuantity`/`foreignExchange`/`foreignQuantity`/`foreignLeverage`만 전송 후 `POST /positions/auto`, MANUAL은 + `koreaEntryPrice`/`foreignEntryPrice`/`entryFxRate`/`entryObservedAt` 전송 후 `POST /positions/manual`. 한국(롱) / 해외(숏) 페어 필드를 시각적으로 그룹화. "현재 데이터 채우기"는 MANUAL 전용 (`premium API`에서 `koreaPrice`/`foreignPrice`/`fxRate`/`observedAt` 자동 입력). 409 응답 (`PREMIUM_SNAPSHOT_NOT_AVAILABLE`/`STALE_PREMIUM_SNAPSHOT`)을 친화적 한국어 메시지("현재 가격/환율 정보가 없거나 오래되었습니다…")로 매핑. `PositionList`의 `Position` 인터페이스를 페어 모델로 교체 (`koreaExchange`/`koreaQuantity`/`koreaEntryPrice`/`foreignExchange`/`foreignQuantity`/`foreignEntryPrice`/`foreignLeverage`), `PnlData`에 `koreaPnl`/`foreignPnlKrw`/`totalPnlKrw`/`koreaCurrentValue`/`totalPnlPercent` 추가. 목록 현재 PnL 칸은 `premiumDiff(%p)` + `totalPnlKrw원(totalPnlPercent%)` 2줄로 표시, 색상 기준은 `totalPnlKrw >= 0`. 상세 페이지(`positions/[id]/page.tsx`)는 한국/해외 분리 카드로 재구성, PnL 카드 헤드라인 = KRW 액수 + 총 PnL%, 한국 PnL / 해외 PnL KRW 환산을 분리 표시.

### Epic #28 — WebSocket 실시간 수집 전환

| Phase | 내용 | 상태 |
|-------|------|------|
| Phase 1 (#29) | WebSocket 공통 인프라 (ConnectionManager + Metrics) | ✅ 완료 |
| Phase 2 (#30) | 바이낸스 WebSocket 클라이언트 + REST/WS 모드 토글 | ✅ 완료 |
| Phase 3 (#31) | 빗썸 WebSocket 클라이언트 + 1Hz 다운샘플 + ZSet 저장 | ✅ 완료 |
| Phase 4 | REST 폴링 클라이언트 제거 + 규칙 문서화 | 예정 |

### Epic #40 — Position 도메인 페어 모델 + AUTO/MANUAL 분기 + 프론트엔드

| Child | 내용 | 상태 |
|-------|------|------|
| #41 | Position 도메인 한국/해외 페어 모델 재구조화 (V12, 엔티티/검증/API) | ✅ 완료 |
| #42 | 포지션 입력 AUTO/MANUAL 분기 (`/positions/auto` + `/positions/manual`, 60초 신선도 검증, 루트 POST 제거) | ✅ 완료 |
| #43 | KRW 기반 PnL 확장 (Position.calculatePnl 4-인자 시그니처, PositionPnl 5필드 추가, isProfit 시맨틱 변경) | ✅ 완료 |
| #44 | 페어 모델 프론트엔드 반영 (AUTO/MANUAL 폼 분리 + PnL 카드 KRW 손익 표시 포함) | ✅ 완료 |

**Epic #40 — 전체 완료 (2026-05-18)**. 자식 이슈 #41 ~ #44가 모두 완료되어 Position 도메인 페어 모델, AUTO/MANUAL 오픈 분기, KRW 기반 PnL, 프론트엔드 동기화가 모두 정상화됨.

### Pending
(없음)

## Known Issues

- Batch Docker 컨테이너는 `ZoneId.systemDefault()` = UTC로 동작 (로컬 개발 시 KST와 차이 주의)
- Docker app-compose로 띄운 컨테이너와 bootRun이 동시 실행되면 포트 충돌 발생
- **V12 마이그레이션은 dev/local 전용** — `position` 테이블 `TRUNCATE` 포함. staging/prod 배포 시 기존 행을 페어 컬럼으로 채우는 별도 backfill 마이그레이션이 선행되어야 한다 (이슈 #41).
- **`GET /api/v1/positions/{id}/pnl` 정확성 — 부분 해소 (이슈 #43)** — 이슈 #43에서 `Position.calculatePnl`을 페어 인지 4-인자 시그니처로 교체하고 시세 양수 검증을 추가하여 KRW 기반 손익 수식을 정확히 계산하도록 했다. 다만 `PremiumSnapshot`은 여전히 symbol 단일 기준이므로 Position의 `koreaExchange`/`foreignExchange`와 매칭되는 시세를 보장하지 못한다. dev 환경(거래소 1쌍 운용)에서는 정확하지만, premium 도메인이 다중 거래소 지원으로 확장될 때 완전히 해소된다 (별도 후속 작업).
- **`PositionPnl.isProfit()` 시맨틱 변경 (이슈 #43)** — `premiumDiff < 0` 기준에서 `totalPnlKrw > 0` 기준으로 변경되었다. 같은 입력 데이터에서 결과 부호가 달라질 수 있는 케이스가 존재한다 (회귀 테스트로 부호 불일치 케이스 단언). 프론트엔드는 이슈 #44에서 신규 필드(`totalPnlKrw`/`totalPnlPercent`)와 함께 동기화되어, 사용자가 보는 손익 부호는 KRW 기준으로 일관된다.
- **PremiumSnapshot 거래소 매칭 미지원** — AUTO 엔드포인트와 PnL 계산이 사용하는 `PremiumSnapshot`은 symbol 기반이라 요청/Position의 `koreaExchange`/`foreignExchange`와 거래소 일치 여부를 검증하지 못한다 (이슈 #42/#43). premium 도메인이 다중 거래소 지원으로 확장될 때 해소 (별도 후속 작업).
- ~~**프론트엔드 포지션 오픈/PnL 흐름 미동기화**~~ — 이슈 #44에서 해소 완료 (2026-05-18). `OpenPositionForm`이 AUTO/MANUAL 페어 본문을 전송하고, `PositionList`/상세 페이지가 `koreaPnl`/`foreignPnlKrw`/`totalPnlKrw`/`koreaCurrentValue`/`totalPnlPercent` 신규 필드를 KRW 액수 + 총 PnL% 형태로 표시한다.

## Notes

- FX 캐시 TTL: 31분 (30분 스케줄 + 1분 버퍼)
- 배치 수집 주기: ticker/premium 1초, FX 30분
- 집계 주기: 분/시간/일
- 프론트엔드 API 프록시: `next.config.ts` rewrites (`/api/*` → `localhost:8080`)
- Docker Compose 분리: `api-compose.yml`, `batch-compose.yml`, `web-compose.yml` (개별 배포 가능)
- 설계 문서: `.ai/architecture/ARCHITECTURE_DESIGN.md`
- MVP 1 계획서: `docs/plans/2026-02-26-mvp1-implementation.md`
