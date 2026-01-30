# Findings

## 현재 프로젝트 구조

### 기존 모듈

- `apps:api` - Spring Boot API 서버
- `modules:jpa` - JPA 공통 모듈

### 도메인 모델

- **Premium**: 김치 프리미엄 계산 (Korea Ticker, Foreign Ticker, FX Ticker 조합)
- **Ticker**: 거래소 가격 정보 (Exchange, Symbol, Price, Quote)
- **Position**: 포지션 관리 (매수/매도)

### 현재 계층 구조

```
interfaces/api (Controller)
    ↓
application (Facade, DTO)
    ↓
domain (Entity, Service, Repository interface)
    ↓
infrastructure/{domain}/ (Repository 구현체 - 도메인 단위)
```

### infrastructure 패키지 구조 (변경됨)

```
infrastructure/
├── ticker/
│   ├── TickerJpaRepository.kt
│   └── TickerRepositoryImpl.kt
├── premium/
│   ├── PremiumJpaRepository.kt
│   └── PremiumRepositoryImpl.kt
└── position/
    ├── PositionJpaRepository.kt
    └── PositionRepositoryImpl.kt
```

## 외부 API 요구사항

### 빗썸 API

- Endpoint: Public API (인증 불필요 for ticker)
- Rate Limit: **초당 15회**
- 데이터: BTC/KRW 현물 가격

### 바이낸스 선물 API

- Endpoint: fapi.binance.com
- Rate Limit: **분당 1200 요청 (= 초당 20회)**
- 데이터: BTCUSDT 선물 가격

### 환율 API

- Options:
    - 한국수출입은행 API (무료, 1일 1000회)
    - ExchangeRate-API
    - Open Exchange Rates
- 갱신 주기: 10분 (요구사항)

## 갱신 주기 분석 (변경: 1초 간격)

| 데이터       | Rate Limit   | 갱신 주기 | 일일 호출 수 | 여유율  |
|------------|--------------|-------|---------|------|
| 빗썸 BTC    | 15 req/s     | 1초    | 86,400  | 17%  |
| 바이낸스 선물  | 20 req/s     | 1초    | 86,400  | 5%   |
| 환율        | 1000 req/day | 10분   | 144     | 86%  |

> Rate Limit 여유가 충분하여 **1초 간격 갱신** 가능

## 기술 스택 고려사항

### Spring Batch vs Spring Scheduler

- **Spring Scheduler**: 간단한 주기적 작업에 적합 (1초 간격)
- **Spring Batch**: 대용량 배치 처리, 재시작/복구 (필요 없음)
- **결론**: Spring Scheduler + Redisson Lock

### Redis 선택 이유

1. 낮은 지연시간 (~1ms)
2. TTL 네이티브 지원
3. Pub/Sub 지원 (향후 확장)
4. Redisson 분산 락 지원
5. Spring Boot 통합 용이

## 모듈 구조 설계 (변경)

### 루트 모듈 구성

```
premium-spread/
├── apps/           # 실행 가능한 애플리케이션
│   ├── api/        # REST API 서버
│   └── batch/      # 배치 서버
│
├── modules/        # 기술 인프라 모듈 (JPA, Redis, Kafka 등)
│   ├── jpa/        # JPA 공통
│   └── redis/      # Redis + Redisson (신규)
│
└── supports/       # Cross-cutting concerns (신규)
    ├── logging/    # 로깅 설정, 마스킹 필터
    └── monitoring/ # 메트릭, 알람, 헬스체크
```

### 의존성 방향

```
apps:api, apps:batch
    │
    ├── modules:jpa, modules:redis
    │       │
    │       └── (domain 코드는 apps 내부에 위치)
    │
    └── supports:logging, supports:monitoring
```

## Position 기반 캐싱 정책 분석

### 요구사항

- Open Position이 있을 때만 추가 캐싱/계산

### 정책 옵션

1. **Lazy 정책**: Open Position 존재 시에만 Premium 계산
2. **Always-On**: 항상 계산하되, Position 없으면 캐시만
3. **Hybrid**: 기본 데이터는 항상, 복잡한 계산은 Position 시만

### 권장: Hybrid

- 기본 Ticker 데이터는 항상 캐싱 (시장 모니터링)
- Position PnL 계산은 Open Position 시에만

## 보안 고려사항

### API Key 관리

- 환경변수 직접 저장 (위험)
- AWS Secrets Manager / HashiCorp Vault
- Spring Cloud Config + 암호화

### 로깅 마스킹

- API Key, Secret 마스킹 필수
- 요청/응답 로깅 시 민감정보 제외

### 권한 최소화

- API Key에 Read-Only 권한만 부여
- Trade 권한은 V2/V3에서만 (별도 키)
