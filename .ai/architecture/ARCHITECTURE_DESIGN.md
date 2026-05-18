# Premium Spread System Architecture

> 구현 기준(As-Is) 아키텍처 문서 (갱신: 2026-05-18, 이슈 #44 프론트엔드 AUTO/MANUAL 폼 분리 + PnL KRW 표시 반영)

## System Overview

- 목적: 한국/해외 거래소 가격과 환율을 수집해 프리미엄을 계산하고, Redis + DB 집계 데이터를 API 조회 경로에 제공
- 실행 주기:
  - Ticker 수집: 1초
  - Premium 계산: 1초
  - FX 수집: 30분 (+ 앱 시작 후 1회)
  - 집계: 1분 / 1시간 / 1일

## Module Structure

```text
premium-spread/
├── apps/
│   ├── api/              # REST API 서버 (Port 8080)
│   └── batch/            # 배치 스케줄러 (Port 8081)
│       ├── scheduler/    # @Scheduled 작업 (TickerScheduler, BithumbFlushScheduler, ...)
│       ├── client/       # External API Client
│       │   ├── binance/  # BinanceClient(REST) + BinanceWebSocketClient (Phase 2, #30)
│       │   └── bithumb/  # BithumbClient(REST) + BithumbWebSocketClient (Phase 3, #31)
│       ├── cache/        # Redis Cache Writer (TickerCacheService.saveToSecondsWithScore 추가)
│       ├── repository/   # DB Writer (JdbcTemplate)
│       └── infrastructure/
│           ├── websocket/  # WebSocket 공통 인프라 (Phase 1, #29)
│           │   ├── WebSocketConnectionManager   # Reactor Netty 연결·재연결·하트비트
│           │   ├── WebSocketMetrics             # 9종 Micrometer 메트릭
│           │   ├── HeartbeatPolicy              # sealed interface (Ping/Text/None)
│           │   └── WebSocketConnectionConfig    # 연결 파라미터 data class
│           └── ingestion/  # WebSocket 수신 → 캐시 반영 (Phase 2/3)
│               ├── binance/BinanceTickerIngestion   # CAS strict monotonic + lag 측정
│               ├── bithumb/BithumbTickerIngestion   # AtomicReference 최신값 유지 (same-second 수용)
│               └── bithumb/BithumbFlushJob          # 1Hz down-sample → saveToSecondsWithScore
├── modules/
│   ├── jpa/              # JPA 공통 설정
│   └── redis/            # Redis/Redisson 설정, 분산 락
└── supports/
    ├── logging/          # 구조화 로깅
    ├── monitoring/       # 메트릭, 헬스체크
    └── email/            # JavaMail 기반 이메일 발송 (Gmail SMTP)
```

## Batch Data Flow (As-Is)

### 1) Ticker 수집

거래소별로 REST 폴링과 WebSocket 수신 중 하나를 선택할 수 있다 (`premium.ingestion.{binance,bithumb}.mode = rest | websocket`, 기본값 `rest`). 양 모드 모두 동일한 캐시 키(Hash + 초ZSet)에 저장하므로 다운스트림(집계/조회)은 무영향.

#### 1-a) REST 모드 (기본, Phase 4에서 제거 예정)

1. `TickerScheduler`가 1초마다 실행 (`lock:ticker:all`)
2. `TickerIngestionJob`가 mode 분기 후, 활성 거래소만 병렬 호출 (`async { client.getBtcTicker() }`)
3. **양쪽 await 완료 후** `TickerCacheService` 저장 (한쪽 실패 시 다른쪽도 캐시에 쓰지 않음 — atomic await)
   - 현재값 Hash: `ticker:{exchange}:{symbol}`
   - 초당 ZSet: `ticker:seconds:{exchange}:{symbol}`

#### 1-b) WebSocket 모드 (Phase 2/3, #30/#31)

**바이낸스 — 1초 고정 push (`@miniTicker` 채널)**

```
Binance WS  → WebSocketConnectionManager (재연결/하트비트)
            → BinanceWebSocketClient (JSON 파싱, parse-error 카운터)
            → BinanceTickerIngestion (AtomicReference CAS, strict monotonic)
            → TickerCacheService.save + saveToSeconds
```

**빗썸 — 변동 push + 1Hz down-sample (`ticker` 채널)**

```
Bithumb WS  → WebSocketConnectionManager
            → BithumbWebSocketClient (HHmmss timestamp 파싱)
            → BithumbTickerIngestion (AtomicReference 최신값 유지, isBefore로 same-second 수용)

BithumbFlushScheduler (@Scheduled fixedRate=1000, thin entrypoint)
            → BithumbFlushJob (last-run 갱신, 5회 연속 실패 시 AlertService)
            → TickerCacheService.saveToSecondsWithScore (ticker, Instant.now())
              · ZSet member 포맷: `{epochMs}:{price}` — 동일 가격 반복 flush 누적 보장 (flat-price collision 방지)
              · ticker hash는 갱신하지 않음 (synthetic timestamp가 freshness 의미 오염 방지)
```

**공통 안전장치**

- monotonic check (exchange timestamp 기준, reorder/replay 방어)
- stale threshold 10초 (빗썸 마지막 메시지 후 10초 초과 시 flush skip + `ws.stale.bithumb` 카운터)
- 5회 연속 parse/flush 실패 시 ERROR 로그 + 메트릭
- 연결 후 5초 내 첫 메시지 미수신 또는 `ws.last.message.age > 10s` 시 알람 (silent ingestion outage 방어)

### 2) Premium 계산

1. `PremiumScheduler`가 1초마다 실행 (`lock:premium`)
2. 캐시에서 ticker/fx 조회 후 `PremiumCalculator` 계산
3. `PremiumCacheService` 저장
   - 현재값 Hash: `premium:{symbol}`
   - 초당 ZSet: `premium:seconds:{symbol}`
   - 포지션 open 시 history ZSet: `premium:{symbol}:history`

### 3) FX 수집

1. `ExchangeRateScheduler`가 30분마다 실행 (`lock:fx`)
2. `ExchangeRateClient`로 USD/KRW 조회
3. Redis(`fx:usd:krw`) + MySQL(`exchange_rate`) 저장
4. 앱 시작 5초 후 1회 즉시 실행

### 4) 집계 파이프라인

- `PremiumAggregationScheduler`
  - 10초: summary 캐시 갱신 (`summary:{interval}:{symbol}`)
  - 1분: `premium:seconds:*` -> `premium:minutes:*` + `premium_minute`
  - 1시간: `premium:minutes:*` -> `premium:hours:*` + `premium_hour`
  - 1일: `premium:hours:*` -> `premium_day`
- `TickerAggregationScheduler`
  - 1분: `ticker:seconds:*` -> `ticker:minutes:*` + `ticker_minute`
  - 1시간: `ticker:minutes:*` -> `ticker:hours:*` + `ticker_hour`
  - 1일: `ticker:hours:*` -> `ticker_day`

## Redis Key Patterns

| Key | Example | Type | TTL |
|-----|---------|------|-----|
| `ticker:{exchange}:{symbol}` | `ticker:bithumb:btc` | Hash | 5초 |
| `fx:{base}:{quote}` | `fx:usd:krw` | Hash | 31분 |
| `premium:{symbol}` | `premium:btc` | Hash | 5초 |
| `premium:{symbol}:history` | `premium:btc:history` | ZSet | 1시간 |
| `ticker:seconds:{exchange}:{symbol}` | `ticker:seconds:binance:btc` | ZSet | 5분 (member: epochMs 또는 `{epochMs}:{price}`) |
| `ticker:minutes:{exchange}:{symbol}` | `ticker:minutes:binance:btc` | ZSet | 2시간 |
| `ticker:hours:{exchange}:{symbol}` | `ticker:hours:binance:btc` | ZSet | 25시간 |
| `premium:seconds:{symbol}` | `premium:seconds:btc` | ZSet | 5분 |
| `premium:minutes:{symbol}` | `premium:minutes:btc` | ZSet | 2시간 |
| `premium:hours:{symbol}` | `premium:hours:btc` | ZSet | 25시간 |
| `summary:{interval}:{symbol}` | `summary:1h:btc` | Hash | interval별 상이 |
| `lock:*` | `lock:premium` | String | lease 2~120초 |
| `batch:last_run:{job}` | `batch:last_run:premium` | String | 5분 |

### Summary TTL

- `summary:1m:*` -> 10초
- `summary:10m:*` -> 30초
- `summary:1h:*` -> 1분
- `summary:1d:*` -> 5분

## Distributed Lock Strategy

| Lock | Wait | Lease | Job |
|------|------|-------|-----|
| `lock:ticker:all` | 0초 | 2초 | ticker 수집 |
| `lock:premium` | 0초 | 2초 | premium 계산 |
| `lock:fx` | 0초 | 30초 | FX 수집 |
| `lock:aggregation:*` | 0초 | 30/60/120초 | premium 집계 |
| `lock:ticker:aggregation:*` | 0초 | 30/60/120초 | ticker 집계 |

## Authentication (JWT Stateless)

세션 기반 인증에서 JWT Stateless 인증으로 전환 (WU-08).

### 흐름

1. **로그인**: `POST /api/v1/members/login` (JSON Body: email, password)
   - 응답 Body: `{ accessToken, id, email, nickname }`
   - 응답 Cookie: `refresh_token` (HttpOnly, Secure, SameSite=Strict)
2. **인증된 API 호출**: `Authorization: Bearer {accessToken}` 헤더
3. **토큰 갱신**: `POST /api/v1/auth/refresh` (refresh_token 쿠키 자동 전송)
   - 응답 Body: `{ accessToken }` + 새 refresh_token 쿠키
4. **로그아웃**: `POST /api/v1/auth/logout` (refresh_token 쿠키 삭제)

### 구성 요소

| 클래스 | 위치 | 역할 |
|--------|------|------|
| `SecurityConfig` | infrastructure/security | Stateless 세션, 필터 체인, 공개/인증 경로 분리 |
| `JwtTokenProvider` | infrastructure/security | Access/Refresh Token 생성·검증 (HMAC-SHA) |
| `JwtAuthenticationFilter` | infrastructure/security | Bearer 토큰 파싱 → SecurityContext 설정 |
| `JsonLoginFilter` | infrastructure/security | JSON 로그인 요청 처리 |
| `LoginSuccessHandler` | infrastructure/security | 로그인 성공 시 JWT 발급 + 쿠키 설정 |
| `AuthController` | interfaces/api/auth | /refresh, /logout 엔드포인트 |

### 토큰 설정

- Access Token 만료: `jwt.access-token-expiry-ms` (기본 30분)
- Refresh Token 만료: `jwt.refresh-token-expiry-ms` (기본 7일)
- Secret Key: `jwt.secret-key` (환경변수)

### 공개 엔드포인트 (인증 불필요)

- `POST /api/v1/members/register`
- `POST /api/v1/members/login`
- `GET /api/v1/premiums/**`
- `GET /api/v1/tickers/**`
- `GET /actuator/**`

## Alert Service

`supports/monitoring` 모듈에 알림 서비스 인터페이스 + 구현체 (WU-07). **운영자/개발자용 모니터링 알람**.

| 클래스 | 조건 | 역할 |
|--------|------|------|
| `AlertService` | — | 인터페이스 (sendAlert, sendCriticalAlert) |
| `SlackAlertService` | `alert.slack.webhook-url` 설정 시 | Slack Webhook 기반 알림 (severity별 아이콘) |
| `LogAlertService` | Slack 미설정 시 기본 | 로그 기반 알림 (local, test 환경) |

`MonitoringAutoConfiguration`에서 `@ConditionalOnProperty`로 자동 전환.

## User Notification (이슈 #27)

회원이 등록한 프리미엄 임계값 도달 시 이메일 발송. 운영용 `AlertService`와 분리된 도메인.

### 흐름

```
[회원] POST /api/v1/notifications/subscriptions
  → API: domain/notification (NotificationSubscription CRUD)
  → MySQL: notification_subscription
                       ▲ (활성 구독 + member.email JOIN, JdbcTemplate)
                       │
[Batch] PremiumRealtimeJob ──1초──► PremiumUpdatedEvent (Spring ApplicationEvent)
                                          ▼ @Async (별도 쓰레드, 잡과 격리)
                              PremiumThresholdNotificationListener
                                          ▼
                              PremiumThresholdNotificationService
                                          ├─ matches(rate)? → no면 skip
                                          ├─ tryAcquireCooldown(NX, 60min)? → false면 skip
                                          ▼
                                    EmailSender.send()  (supports/email)
                                          ├─ 성공 → cooldown 유지
                                          └─ 실패 → release(취소) 후 로그
```

### 구성 요소

| 모듈 | 클래스 | 역할 |
|------|--------|------|
| apps/api | `domain/notification/NotificationSubscription` | Entity, 변경 컬럼은 `protected set` + `change*()` |
| apps/api | `application/notification/NotificationSubscriptionFacade` | CRUD 유스케이스 |
| apps/api | `interfaces/api/notification/NotificationSubscriptionController` | `/api/v1/notifications/subscriptions` |
| apps/batch | `repository/ActiveSubscriptionReadRepository` | JdbcTemplate, member JOIN 활성 구독 조회 |
| apps/batch | `application/notification/PremiumThresholdNotificationListener` | `@Async @EventListener`, ConditionalOnProperty 가드 |
| apps/batch | `application/notification/PremiumThresholdNotificationService` | 매칭, cooldown, 발송 위임 |
| apps/batch | `cache/NotificationCooldownStore` | Redis `SET NX EX 3600` 원자 reservation + release |
| supports/email | `EmailSender` / `JavaMailEmailSender` | SMTP 발송, 실패는 `EmailDeliveryException`으로 전파 |

### 활성화 조건

- `alert.email.from` 환경변수가 설정되면 Service/Listener 등록 (`@ConditionalOnProperty`)
- `JavaMailSender` 빈은 `spring.mail.host` 자동 설정에 따라 등록 (Spring Boot)
- 미설정 시 Service/Listener 빈 미등록 → 부팅 영향 없음 (local/test 안전)

## Position Pair Model (이슈 #41)

회원이 등록하는 포지션은 **한국 거래소 long + 해외 거래소 short 페어 1쌍**을 단일 행으로 보유한다.

### 컬럼 구조 (position 테이블, V12 이후)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `korea_exchange` | VARCHAR(50) | 한국 거래소 — `Exchange.region == KOREA` |
| `korea_quantity` | DECIMAL(30,10) | 한국 long 수량 ( > 0 ) |
| `korea_entry_price` | DECIMAL(30,10) | 한국 진입 가격 (KRW, > 0) |
| `foreign_exchange` | VARCHAR(50) | 해외 거래소 — `Exchange.region == FOREIGN` **AND** `FX_PROVIDER` 거절 |
| `foreign_quantity` | DECIMAL(30,10) | 해외 short 수량 ( > 0 ) |
| `foreign_entry_price` | DECIMAL(30,10) | 해외 진입 가격 (USD, > 0) |
| `foreign_leverage` | INT | 해외 레버리지 (1 ~ 125) |
| `entry_fx_rate` | DECIMAL | 진입 시점 환율 ( > 0 ) |
| `entry_premium_rate` | DECIMAL | 서버 계산값 (입력값 무시) |

### 도메인 검증 (Position 엔티티)

- `koreaExchange.region == KOREA` 강제
- `foreignExchange.region == FOREIGN` 강제 + `FX_PROVIDER` 거절
- 수량/가격/환율 모두 `> 0`
- `foreignLeverage ∈ [1, 125]`
- `entryPremiumRate`는 서버가 `Premium.calculatePremiumRate`와 동일 정밀도(`DIVISION_SCALE=10`, scale=2)로 계산. 클라이언트 입력값은 신뢰하지 않음.

### API 엔드포인트 (이슈 #42 이후)

오픈 엔드포인트는 AUTO/MANUAL 두 경로로 분기된다. 루트 `POST /api/v1/positions`는 제거되어 호출 시 405 `METHOD_NOT_ALLOWED` 응답을 반환한다.

| 메서드 | 경로 | 본문 필드 | 진입가/환율/관측시각 처리 |
|--------|------|----------|--------------------------|
| POST | `/api/v1/positions/auto` | `symbol`, `koreaExchange`, `koreaQuantity`, `foreignExchange`, `foreignQuantity`, `foreignLeverage` | 서버가 `PremiumService.findLatestSnapshotBySymbol`로 자동 채움 (60초 신선도 검증) |
| POST | `/api/v1/positions/manual` | AUTO 필드 + `koreaEntryPrice`, `foreignEntryPrice`, `entryFxRate`, `entryObservedAt` | 사용자가 직접 입력 |

AUTO 경로는 `PositionFacade.openAutoPosition`이 처리하며, 스냅샷이 존재하지 않으면 `PremiumSnapshotNotAvailableException`, 스냅샷의 `observedAt`이 현재 시각 기준 60초를 초과하면 `StalePremiumSnapshotException`을 던진다. MANUAL 경로는 기존 페어 검증 로직(`openManualPosition`)을 그대로 수행한다.

### 예외 매핑

| 예외 | 코드 | HTTP | 의미 |
|------|------|------|------|
| `PremiumSnapshotNotAvailableException` | `PREMIUM_SNAPSHOT_NOT_AVAILABLE` | 409 | AUTO 요청 시 symbol의 최신 스냅샷이 없음 |
| `StalePremiumSnapshotException` | `STALE_PREMIUM_SNAPSHOT` | 409 | AUTO 요청 시 스냅샷 `observedAt`이 60초 초과 |
| `HttpRequestMethodNotSupportedException` | `METHOD_NOT_ALLOWED` | 405 | 제거된 루트 `POST /api/v1/positions` 등 미지원 메서드 호출 |

세 예외 모두 `GlobalExceptionHandler`에 핸들러가 정의되어 있으며, 메시지는 `ERROR_MESSAGES` 한국어 매핑을 따른다.

### DTO 분리 (이슈 #42)

| 레이어 | 변경 |
|--------|------|
| interfaces | `PositionRequest.Open` → `PositionRequest.OpenAuto` + `PositionRequest.OpenManual` |
| application | `PositionCriteria.Open` → `PositionCriteria.OpenAuto` + `PositionCriteria.OpenManual` |
| interfaces | `PositionResponse.Detail` 변경 없음 |

### PnL 계산 (이슈 #43)

`GET /api/v1/positions/{id}/pnl`은 페어 기반 KRW 손익을 반환한다. `Position.calculatePnl`은 `PremiumSnapshot`에 직접 의존하지 않고 4개의 `BigDecimal` 인자를 받는다 (Position 도메인이 read model에 결합되지 않도록 분리).

```kotlin
fun Position.calculatePnl(
    currentKoreaPrice: BigDecimal,   // 현재 한국 가격 (KRW)
    currentForeignPrice: BigDecimal, // 현재 해외 가격 (USD)
    currentFxRate: BigDecimal,       // 현재 환율 (USD→KRW)
    currentPremiumRate: BigDecimal,  // 현재 프리미엄율 (참고값)
): PositionPnl
```

#### 수식

| 값 | 계산식 |
|----|-------|
| `koreaPnl` | `(currentKoreaPrice - koreaEntryPrice) × koreaQuantity` (KRW) |
| `foreignPnlUsd` (중간값) | `(foreignEntryPrice - currentForeignPrice) × foreignQuantity` (USD, short 포지션) |
| `foreignPnlKrw` | `foreignPnlUsd × currentFxRate` (KRW) |
| `totalPnlKrw` | `koreaPnl + foreignPnlKrw` (KRW) |
| `koreaCurrentValue` | `currentKoreaPrice × koreaQuantity` (KRW) — % 기준 |
| `totalPnlPercent` | `totalPnlKrw / koreaCurrentValue × 100` (scale=2, HALF_UP, 분자 division scale=10) |
| `premiumDiff` | `currentPremiumRate - entryPremiumRate` (참고값) |

#### 도메인 가드

- `currentKoreaPrice`, `currentForeignPrice`, `currentFxRate`는 모두 `> 0`을 `require`로 강제 — 위반 시 `IllegalArgumentException` → 400. 0 이하 snapshot이 PnL을 0.00%로 마스킹하는 케이스를 차단한다.
- Position 도메인은 외부 read model에 의존하지 않는다. snapshot 분해/검증은 `PositionFacade.calculatePnl`이 담당한다.

#### `isProfit()` 시맨틱 변경 (Breaking)

| 시점 | 기준 |
|------|------|
| ~#42 | `premiumDiff < BigDecimal.ZERO` |
| #43~ | `totalPnlKrw > BigDecimal.ZERO` |

실제 KRW 이익 여부를 직접 표현하도록 자연화. 같은 입력에서 부호가 달라질 수 있는 케이스가 존재하며, 회귀 테스트가 부호 불일치 케이스를 단언한다.

#### Read 흐름

```
Controller → PositionFacade.calculatePnl
              ├─ PremiumService.findLatestSnapshotBySymbol(symbol)
              │     (없거나 stale 시 예외 — AUTO 오픈과 동일 경계 정책)
              └─ Position.calculatePnl(snapshot.koreaPrice, snapshot.foreignPrice,
                                       snapshot.fxRate, snapshot.premiumRate)
```

#### DTO 확장

| 레이어 | 추가 필드 |
|--------|-----------|
| domain (`PositionPnl`) | `koreaPnl`, `foreignPnlKrw`, `totalPnlKrw`, `koreaCurrentValue`, `totalPnlPercent` |
| application (`PositionResult.Pnl`) | 동일 5필드 |
| interfaces (`PositionResponse.Pnl`) | 동일 5필드 |

기존 `premiumDiff`, `entryPremiumRate`, `currentPremiumRate`, `isProfit`, `calculatedAt`은 유지된다 (응답 호환).

### 알려진 한계 (이슈 #41/#42/#43 시점)

- **PnL 정확성 — 부분 해소 (이슈 #43)**: 페어 인지 4-인자 수식 + 시세 양수 검증으로 KRW 손익 계산 자체는 정확해졌다. 다만 `PremiumSnapshot`은 여전히 symbol 단일 기준이므로 Position의 `koreaExchange`/`foreignExchange`와 시세가 매칭되는지 보장하지 못한다. dev 환경(거래소 1쌍 운용)에서는 정확. premium 도메인이 다중 거래소 지원으로 확장될 때 완전 해소.
- **`isProfit()` 시맨틱 변경 (Breaking, 이슈 #43)**: `premiumDiff < 0` → `totalPnlKrw > 0`. 같은 데이터에 결과 부호가 달라질 수 있다. 프론트엔드는 #41 시점부터 PnL 표시가 동기화되지 않았으므로 사용자 영향은 없으며, 이슈 #44에서 신규 필드와 함께 갱신 예정.
- **PremiumSnapshot 거래소 매칭 미지원**: AUTO 엔드포인트와 PnL 계산이 사용하는 `PremiumSnapshot`은 symbol 기반이라 요청/Position의 `koreaExchange`/`foreignExchange` 일치 여부를 검증하지 못한다 (이슈 #42/#43). premium 도메인이 다중 거래소 지원으로 확장될 때 해소.
- **운영 배포 차단**: V12는 `TRUNCATE`를 포함하므로 staging/prod 적용 전 별도 backfill 마이그레이션 필요.
- ~~**프론트엔드 미동기화**~~: 이슈 #44에서 해소 (2026-05-18). 아래 "프론트엔드 Position 흐름 (이슈 #44)" 섹션 참고.

### 프론트엔드 Position 흐름 (이슈 #44)

`apps/web` 컴포넌트가 페어 모델 + AUTO/MANUAL 엔드포인트 + KRW 기반 PnL과 정합한다.

| 컴포넌트 | 역할 |
|----------|------|
| `OpenPositionForm.tsx` | AUTO/MANUAL 모드 토글 (기본 AUTO). 한국(롱) / 해외(숏) 페어 필드 그룹화. AUTO → `POST /api/v1/positions/auto` (symbol, koreaExchange, koreaQuantity, foreignExchange, foreignQuantity, foreignLeverage), MANUAL → `POST /api/v1/positions/manual` (+ koreaEntryPrice, foreignEntryPrice, entryFxRate, entryObservedAt). 409 (`PREMIUM_SNAPSHOT_NOT_AVAILABLE`/`STALE_PREMIUM_SNAPSHOT`)는 친화적 한국어 메시지로 매핑한다. "현재 데이터 채우기"는 MANUAL 전용으로 `GET /api/v1/premiums/current/{symbol}` 응답을 폼에 채운다. |
| `PositionList.tsx` | `Position` 타입을 페어 모델로 사용. 현재 PnL 셀은 `premiumDiff(%p)` + `totalPnlKrw원(totalPnlPercent%)` 2줄, 색상 기준은 `totalPnlKrw >= 0`. |
| `positions/[id]/page.tsx` | 한국/해외 페어를 분리 카드로 렌더링. PnL 카드 헤드라인은 `totalPnlKrw` (KRW 액수) + `totalPnlPercent` (%). 한국 PnL (`koreaPnl`) / 해외 PnL KRW 환산 (`foreignPnlKrw`)을 분리 표시. |

```
[AUTO 흐름]
사용자 입력(symbol/exchanges/quantities/leverage)
  → POST /positions/auto
  → PositionFacade.openAutoPosition
       (PremiumService.findLatestSnapshotBySymbol, 60초 신선도)
  → 409 시 OpenPositionForm이 한국어 메시지로 매핑

[MANUAL 흐름]
사용자 입력(+ entry prices, fxRate, observedAt)
  → POST /positions/manual
  → PositionFacade.openManualPosition (페어 검증)

[PnL 표시]
GET /positions/{id}/pnl
  → PositionResponse.Pnl { koreaPnl, foreignPnlKrw, totalPnlKrw,
                          koreaCurrentValue, totalPnlPercent, premiumDiff, ... }
  → 목록: totalPnlKrw원 + totalPnlPercent%
  → 상세: 헤드라인 KRW + %, 한국/해외 분리 카드
```

## Persistence (MySQL)

- 원시/조회용
  - `exchange_rate`
- 집계용
  - `premium_minute`, `premium_hour`, `premium_day`
  - `ticker_minute`, `ticker_hour`, `ticker_day`

### Flyway 마이그레이션

| 버전 | 파일명 | 내용 |
|------|--------|------|
| V1 | `create_ticker_table` | ticker 테이블 |
| V2 | `create_premium_table` | premium 테이블 |
| V3 | `create_position_table` | position 테이블 |
| V4 | `create_premium_snapshot_table` | premium_snapshot |
| V5 | `create_premium_aggregation_tables` | premium 집계 테이블 |
| V6 | `create_ticker_and_exchange_rate_tables` | ticker 집계 + exchange_rate |
| V7 | `create_member_table` | member 테이블 |
| V8 | `add_member_id_to_position` | position.member_id FK 추가 |
| V9 | `add_indexes_and_currency_column` | 성능 인덱스 + ticker 집계 currency 컬럼 |
| V10 | `add_fx_rate_to_premium_aggregation_tables` | premium 집계 테이블에 fx_rate 컬럼 |
| V11 | `create_notification_subscription` | notification_subscription 테이블 (이슈 #27) |
| V12 | `restructure_position_to_pair` | position 단일 거래소 컬럼 → 한국/해외 페어 컬럼 재구조화 (이슈 #41). `exchange/quantity/entry_price`를 `korea_*`로 rename 후 `foreign_exchange/foreign_quantity/foreign_entry_price/foreign_leverage` 신규 추가. **dev/local 한정 TRUNCATE 포함 — 운영 적용 전 별도 backfill 필요.** |

## Observability (As-Is)

- 주요 카운터/게이지 예시:
  - `scheduler.ticker.success|error|skipped`
  - `scheduler.premium.success|error|skipped`
  - `scheduler.fx.success|error|skipped`
  - `scheduler.aggregation.*`, `scheduler.ticker.aggregation.*`
  - `premium.rate.current`, `fx.rate.current`
- 외부 호출 타이머:
  - `ticker.fetch.latency`
  - `fx.fetch.latency`
- WebSocket 메트릭 (Phase 1, `WebSocketMetrics`):
  - `ws.connection.state` — 연결 상태 게이지 (exchange 태그)
  - `ws.message.received` — 수신 메시지 카운터 (exchange 태그)
  - `ws.reconnect.attempt` — 재연결 시도 카운터 (exchange 태그)
  - `ws.message.lag.ms` — 메시지 처리 지연 타이머 (exchange 태그)
  - `ws.last.message.age` — 마지막 메시지 수신 후 경과 시간 게이지 (exchange 태그)
  - `ws.first.message.timeout` — 첫 메시지 타임아웃 카운터 (exchange 태그)
  - `ws.out_of_order` — 순서 역전 메시지 카운터 (exchange 태그)
  - `ws.stale.{exchange}` — stale 감지 카운터
  - `ticker.flush.{exchange}` / `ticker.flush.error.{exchange}` — flush 성공/오류 카운터 (Phase 2/3 적용)
  - `ws.parse.error` — 메시지 파싱 실패 카운터 (Phase 2/3)

## Notes

- 문서의 수치/키/플로우는 현재 코드 기준으로 유지한다.
- 설계 변경 시 문서와 코드(스케줄 주기, TTL, key pattern)를 같은 커밋에서 함께 수정한다.
