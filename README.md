# Premium Spread

한국과 해외 거래소 간 암호화폐 가격 차이(프리미엄)를 실시간 모니터링하고 헤지 기반 트레이딩을 지원하는 시스템입니다.

## 개요

암호화폐는 동일한 자산임에도 한국 거래소와 해외 거래소 간 가격 차이가 발생합니다. 이 가격 차이(프리미엄)를 활용하여 차익을 추구하는 헤지 기반 트레이딩 모델을 제공합니다.

- **한국 거래소**: BTC 현물 매수 (Long)
- **해외 거래소**: BTC 선물 매도 (Short)
- **결과**: 가격 방향성 중립(Delta Neutral) + 프리미엄 변화에 따른 수익 실현

## 기술 스택

| 영역 | 기술 |
|------|------|
| Backend | Kotlin 2.0, Java 21, Spring Boot 3.4 |
| Frontend | Next.js 16, React 19, shadcn/ui, TradingView Lightweight Charts |
| Database | MySQL 8 |
| Cache | Redis 7 + Redisson (Master-Replica) |
| Infra | Docker Compose, Nginx (리버스 프록시 + SSL) |
| Build | Gradle 멀티모듈 |
| Testing | JUnit 5, AssertJ, MockK, Testcontainers |
| CI/CD | GitHub Actions |

## 프로젝트 구조

```text
premium-spread/
├── apps/
│   ├── api/          # REST API 서버 (Port 8080)
│   ├── batch/        # 배치 스케줄러 (Port 8081)
│   └── web/          # Next.js 프론트엔드 (Port 3000)
├── modules/
│   ├── jpa/          # JPA 공통 설정, BaseEntity
│   └── redis/        # Redis, Redisson 분산 락
├── supports/
│   ├── logging/      # 구조화 로깅, 민감정보 마스킹
│   └── monitoring/   # Micrometer 메트릭, 헬스체크
└── docker/
    ├── infra-compose.yml   # MySQL, Redis (인프라)
    ├── app-compose.yml     # API + Batch + Web + Nginx (전체)
    ├── api-compose.yml     # API 단독 배포
    ├── batch-compose.yml   # Batch 단독 배포
    └── web-compose.yml     # Web + Nginx 단독 배포
```

### API 서버 레이어 아키텍처

```text
interfaces/api/   → Controller, Request/Response DTO
application/      → Facade, Criteria/Result DTO
domain/           → Service, Command, Entity, Repository(interface)
infrastructure/   → RepositoryImpl (Cache→DB fallback)
```

## 핵심 기능

### 1. 프리미엄 계산

```
premium = ((koreaPrice - foreignPrice × fxRate) / (foreignPrice × fxRate)) × 100
```

| 항목 | 설명 |
|------|------|
| `koreaPrice` | 한국 거래소 현물 가격 (KRW) — 빗썸 |
| `foreignPrice` | 해외 거래소 선물 가격 (USD/USDT) — 바이낸스 |
| `fxRate` | 원/달러 환율 (USD/KRW) |

### 2. 실시간 데이터 수집 및 집계 (Batch)

| 데이터 | 주기 | 소스/처리 |
|--------|------|-----------|
| 한국 시세 | 1초 | 빗썸 API → Redis |
| 해외 시세 | 1초 | 바이낸스 API → Redis |
| 환율 | 30분 | ExchangeRate API → Redis + DB |
| 프리미엄 | 1초 | 계산 → Redis |
| 프리미엄/티커 집계 | 1분/1시간/1일 | Redis ZSet 집계 → DB |

### 3. 포지션 관리

- **진입 (Open)**: 프리미엄 매수 상태 기록
- **청산 (Close)**: 손익 확정
- **PnL 계산**: 진입 프리미엄과 현재 프리미엄 차이로 손익 산출

### 4. 대시보드 (Web)

- 실시간 프리미엄 현황
- 프리미엄 차트 (1분/1시간/1일 인터벌)
- 포지션 관리 (진입/청산/이력)
- 회원 인증 (세션 기반)

## 시작하기

### 사전 요구사항

- JDK 21
- Node.js 20+
- Docker & Docker Compose

### 로컬 개발 환경

```bash
# 1. 인프라 실행
docker compose -f docker/infra-compose.yml up -d

# 2. 백엔드 실행
./gradlew :apps:api:bootRun &
./gradlew :apps:batch:bootRun &

# 3. 프론트엔드 실행
cd apps/web && npm install && npm run dev
```

### Docker 배포

```bash
# 인프라 실행
docker compose -f docker/infra-compose.yml up -d

# 전체 배포
docker compose -f docker/app-compose.yml up -d --build

# 서비스별 개별 배포
docker compose -f docker/api-compose.yml up -d --build
docker compose -f docker/batch-compose.yml up -d --build
docker compose -f docker/web-compose.yml up -d --build
```

### 테스트

```bash
./gradlew test                           # 단위 테스트
./gradlew :apps:api:integrationTest      # 통합 테스트 (Docker 필요)
```

## API 엔드포인트

### 프리미엄

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/premiums/calculate/{symbol}` | 프리미엄 계산 |
| GET | `/api/v1/premiums/current/{symbol}` | 현재 프리미엄 조회 |
| GET | `/api/v1/premiums/history/{symbol}` | 기간별 프리미엄 조회 |
| GET | `/api/v1/premiums/aggregation/{symbol}` | 프리미엄 집계 조회 (1m/1h/1d) |

### 포지션

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/positions` | 포지션 진입 |
| GET | `/api/v1/positions` | 열린 포지션 목록 |
| GET | `/api/v1/positions/summary` | 포지션 요약 |
| GET | `/api/v1/positions/history` | 청산 이력 |
| GET | `/api/v1/positions/{id}` | 포지션 상세 |
| GET | `/api/v1/positions/{id}/pnl` | PnL 조회 |
| POST | `/api/v1/positions/{id}/close` | 포지션 청산 |

### 회원 / 인증

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/members/register` | 회원 가입 |
| POST | `/api/v1/members/login` | 로그인 (세션) |
| POST | `/api/v1/members/logout` | 로그아웃 |
| GET | `/api/v1/members/me` | 내 정보 조회 |

### 티커

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/tickers` | 티커 등록 |

## 손익 구조

- **프리미엄 하락** → 이익
- **프리미엄 상승** → 손실

> 가격 자체의 상승/하락은 현물(Long) ↔ 선물(Short) 구조로 상쇄됨

## 문서

| 문서 | 설명 |
|------|------|
| [Architecture Design](.ai/architecture/ARCHITECTURE_DESIGN.md) | 시스템 아키텍처, 데이터 흐름, Redis 설계 |
| [Development Guide](.ai/instructions.md) | 개발 지침, 코딩 컨벤션 |
| [Project Status](.ai/PROJECT_STATUS.md) | 현재 진행 상황, TODO |

## 라이선스

Private Project
