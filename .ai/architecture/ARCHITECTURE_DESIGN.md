# Premium Spread System Architecture

> 김치 프리미엄 실시간 모니터링 시스템 아키텍처

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            EXTERNAL APIs                                 │
│   Bithumb (1s)        Binance Futures (1s)        ExchangeRate (10m)    │
└───────────┬───────────────────┬──────────────────────────┬──────────────┘
            │                   │                          │
            ▼                   ▼                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         BATCH SERVER (apps:batch)                        │
│                                                                          │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────────────────┐    │
│   │  Scheduler  │───▶│   Client    │───▶│   Cache Writer (Redis)  │    │
│   │  (1s/10m)   │    │ (WebClient) │    │   + DB Writer (JPA)     │    │
│   └─────────────┘    └─────────────┘    └─────────────────────────┘    │
│                                                   │                      │
│                    ┌──────────────────────────────┘                      │
│                    ▼                                                     │
│   ┌──────────────────────────┐                                          │
│   │  Distributed Lock        │                                          │
│   │  (Redisson)              │                                          │
│   └──────────────────────────┘                                          │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                              REDIS                                       │
│                                                                          │
│   ticker:bithumb:btc (5s)  │  fx:usd:krw (15m)  │  premium:btc (5s)     │
│   ticker:binance:btc (5s)  │  lock:* (2-30s)    │  premium:btc:history  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         API SERVER (apps:api)                            │
│                                                                          │
│   ┌─────────────┐    ┌─────────────┐    ┌───────────────────────┐      │
│   │ Controller  │───▶│   Facade    │───▶│  RepositoryImpl       │      │
│   │             │    │   Service   │    │  (Cache→DB Fallback)  │      │
│   └─────────────┘    └─────────────┘    └───────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         DATABASE (MySQL)                                 │
│                                                                          │
│   ticker  │  premium  │  position                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

```
premium-spread/
├── apps/
│   ├── api/              # REST API 서버 (Port 8080)
│   │   ├── interfaces/   # Controller, Request/Response DTO
│   │   ├── application/  # Facade (UseCase 조합)
│   │   ├── domain/       # Entity, Service, Repository Interface
│   │   └── infrastructure/
│   │       ├── cache/    # Redis Cache Reader
│   │       └── {domain}/ # JPA Repository 구현체
│   │
│   └── batch/            # 배치 스케줄러 (Port 8081)
│       ├── scheduler/    # @Scheduled 작업 (1s/10m)
│       ├── client/       # External API Client (WebClient)
│       ├── cache/        # Redis Cache Writer
│       └── repository/   # DB Writer (JdbcTemplate)
│
├── modules/
│   ├── jpa/              # JPA 공통 설정, BaseEntity
│   └── redis/            # Redis/Redisson 설정, 분산 락
│
└── supports/
    ├── logging/          # 구조화 로깅, 민감정보 마스킹
    └── monitoring/       # Micrometer 메트릭, 헬스체크
```

---

## Data Flow

### 1. 시세 수집 (Batch → Redis)

| 단계 | 컴포넌트 | 설명 |
|------|----------|------|
| 1 | `TickerScheduler` | 1초마다 실행, 분산 락 획득 |
| 2 | `BithumbClient`, `BinanceClient` | 외부 API 호출 (병렬) |
| 3 | `TickerCacheService` | Redis Hash에 저장 (TTL 5초) |

### 2. 프리미엄 계산 (Batch → Redis/DB)

| 단계 | 컴포넌트 | 설명 |
|------|----------|------|
| 1 | `PremiumScheduler` | 1초마다 실행 |
| 2 | `PremiumCalculator` | 캐시에서 시세 조회, 프리미엄 계산 |
| 3 | `PremiumCacheService` | Redis에 저장 |
| 4 | `PremiumSnapshotRepository` | DB에 히스토리 저장 |

### 3. 프리미엄 조회 (Client → API → Redis/DB)

| 단계 | 컴포넌트 | 설명 |
|------|----------|------|
| 1 | `PremiumController` | REST API 요청 수신 |
| 2 | `PremiumFacade` → `PremiumService` | 유스케이스 위임 (cache/DB 전략 무관) |
| 3 | `PremiumRepositoryImpl` | 캐시 우선 조회 (cache hit → `PremiumSnapshot` 반환) |
| 4 | (cache miss) DB + Ticker 조합 | `Premium` + 3개 Ticker 가격으로 `PremiumSnapshot` enrichment |

---

## Redis Cache Design

### Key Patterns

| Key | 예시 | TTL | 용도 |
|-----|------|-----|------|
| `ticker:{exchange}:{symbol}` | `ticker:bithumb:btc` | 5초 | 거래소 시세 |
| `fx:{base}:{quote}` | `fx:usd:krw` | 15분 | 환율 |
| `premium:{symbol}` | `premium:btc` | 5초 | 현재 프리미엄 |
| `premium:{symbol}:history` | `premium:btc:history` | 1시간 | 프리미엄 히스토리 (SortedSet) |
| `lock:*` | `lock:premium` | 2-30초 | 분산 락 |

### Cache Strategy

- **Cache-Aside (Write)**: Batch가 외부 API 조회 후 Redis에 저장
- **Cache-First (Read)**: API가 Redis 먼저 조회, 미스 시 DB Fallback
- **Position-Aware**: 열린 포지션 있을 때만 히스토리 저장

---

## Distributed Lock Strategy

| 락 | Wait | Lease | 용도 |
|----|------|-------|------|
| `lock:ticker:all` | 0초 | 2초 | 티커 갱신 독점 |
| `lock:fx` | 0초 | 30초 | 환율 갱신 독점 |
| `lock:premium` | 0초 | 2초 | 프리미엄 계산 독점 |

- **tryLock(0, leaseTime)**: 즉시 시도, 실패 시 skip
- **Failover**: 서버 다운 시 Lease Time 후 자동 해제

---

## Key Design Decisions

| 결정 | 선택 | 이유 |
|------|------|------|
| 캐시 | Redis | 분산 환경, TTL, 분산 락 지원 |
| 분산 락 | Redisson | 검증된 Redis 분산 락 구현체 |
| 외부 API 호출 | WebClient | 비동기, Reactor 기반 |
| 갱신 주기 | 1초/10분 | Rate Limit 여유, 실시간성 |
| 배치 다중화 | Active-Passive | 분산 락으로 단일 실행 보장 |

---

## Observability

### Metrics (Micrometer)

| 메트릭 | 타입 | 용도 |
|--------|------|------|
| `ticker.fetch.latency` | Timer | API 응답 시간 |
| `premium.rate.current` | Gauge | 현재 프리미엄율 |
| `batch.last_run.epoch_seconds` | Gauge | 배치 헬스 체크 |
| `cache.hit/miss.total` | Counter | 캐시 효율 |

### Alerts

- 배치 작업 지연 (5초 이상)
- 외부 API 에러율 (10% 이상)
- 캐시 미스율 (30% 이상)
- 프리미엄 급변 (5분간 2% 이상 변동)

---

## Future Scalability

| Phase | 확장 |
|-------|------|
| 현재 | BTC, 빗썸-바이낸스, 1초 폴링 |
| Phase 2 | ETH, SOL 추가 |
| Phase 3 | 업비트, OKX 추가 |
| Phase 4 | WebSocket 실시간 스트리밍 |
