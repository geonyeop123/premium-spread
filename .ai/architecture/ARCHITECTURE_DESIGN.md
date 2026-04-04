# Premium Spread System Architecture

> 구현 기준(As-Is) 아키텍처 문서 (갱신: 2026-04-04)

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
│       ├── scheduler/    # @Scheduled 작업
│       ├── client/       # External API Client (WebClient)
│       ├── cache/        # Redis Cache Writer
│       └── repository/   # DB Writer (JdbcTemplate)
├── modules/
│   ├── jpa/              # JPA 공통 설정
│   └── redis/            # Redis/Redisson 설정, 분산 락
└── supports/
    ├── logging/          # 구조화 로깅
    └── monitoring/       # 메트릭, 헬스체크
```

## Batch Data Flow (As-Is)

### 1) Ticker 수집

1. `TickerScheduler`가 1초마다 실행 (`lock:ticker:all`)
2. `BithumbClient`, `BinanceClient` 병렬 호출
3. `TickerCacheService` 저장
   - 현재값 Hash: `ticker:{exchange}:{symbol}`
   - 초당 ZSet: `ticker:seconds:{exchange}:{symbol}`

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
| `ticker:seconds:{exchange}:{symbol}` | `ticker:seconds:binance:btc` | ZSet | 5분 |
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

`supports/monitoring` 모듈에 알림 서비스 인터페이스 + 구현체 (WU-07).

| 클래스 | 조건 | 역할 |
|--------|------|------|
| `AlertService` | — | 인터페이스 (sendAlert, sendCriticalAlert) |
| `SlackAlertService` | `alert.slack.webhook-url` 설정 시 | Slack Webhook 기반 알림 (severity별 아이콘) |
| `LogAlertService` | Slack 미설정 시 기본 | 로그 기반 알림 (local, test 환경) |

`MonitoringAutoConfiguration`에서 `@ConditionalOnProperty`로 자동 전환.

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

## Notes

- 문서의 수치/키/플로우는 현재 코드 기준으로 유지한다.
- 설계 변경 시 문서와 코드(스케줄 주기, TTL, key pattern)를 같은 커밋에서 함께 수정한다.
