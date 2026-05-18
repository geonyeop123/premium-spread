# Project Status

> Last updated: 2026-05-18

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | MVP 1 백엔드 완료, JWT Stateless 인증, DB 쿼리 최적화 (N+1·인덱스·currency 컬럼), 회원 알림 구독 CRUD (이슈 #27), Position 도메인 한국/해외 페어 모델로 재구조화 (이슈 #41) |
| apps/batch | Active | 1초/30분 수집 + 1분/1시간/1일 집계, PremiumUpdatedEvent + 이메일 알림 리스너 (이슈 #27), WebSocket 공통 인프라 + Binance/Bithumb WS 수집 (Phase 1/2/3) |
| apps/web | Active | Next.js 16 + shadcn/ui + TradingView Charts, 대시보드/포지션/인증 UI |
| modules/redis | Active | ZSet 중복 제거 + 캐시 워밍, AggregationTimeUnit(DAYS 추가), TTL 확장 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Active | Slack AlertService (운영자 알람) |
| supports/email | Active | JavaMail 기반 이메일 발송 (Gmail SMTP) — 이슈 #27 |

## Recent Changes

```text
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
| #42 | 포지션 입력 AUTO/MANUAL 분기 | 예정 |
| #43 | KRW 기반 PnL 확장 (페어 인지 쿼리로 `PremiumService.findLatestBySymbol` 교체) | 예정 |
| #44 | 페어 모델 프론트엔드 반영 | 예정 |

### Pending
(없음)

## Known Issues

- Batch Docker 컨테이너는 `ZoneId.systemDefault()` = UTC로 동작 (로컬 개발 시 KST와 차이 주의)
- Docker app-compose로 띄운 컨테이너와 bootRun이 동시 실행되면 포트 충돌 발생
- **V12 마이그레이션은 dev/local 전용** — `position` 테이블 `TRUNCATE` 포함. staging/prod 배포 시 기존 행을 페어 컬럼으로 채우는 별도 backfill 마이그레이션이 선행되어야 한다 (이슈 #41).
- **`GET /api/v1/positions/{id}/pnl` 정확성 미보장** — 이슈 #41만 머지된 상태에서는 `PremiumService.findLatestBySymbol`이 페어 정보를 모르므로 PnL 결과가 부정확할 수 있다 (dev only). 이슈 #43에서 페어 인지 쿼리로 교체 예정.

## Notes

- FX 캐시 TTL: 31분 (30분 스케줄 + 1분 버퍼)
- 배치 수집 주기: ticker/premium 1초, FX 30분
- 집계 주기: 분/시간/일
- 프론트엔드 API 프록시: `next.config.ts` rewrites (`/api/*` → `localhost:8080`)
- Docker Compose 분리: `api-compose.yml`, `batch-compose.yml`, `web-compose.yml` (개별 배포 가능)
- 설계 문서: `.ai/architecture/ARCHITECTURE_DESIGN.md`
- MVP 1 계획서: `docs/plans/2026-02-26-mvp1-implementation.md`
