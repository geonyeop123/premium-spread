# Premium Spread

한국과 해외 거래소 간 암호화폐 가격 차이(프리미엄)를 실시간 모니터링하고 헤지 기반 트레이딩을 지원하는 시스템입니다.

## 개요

암호화폐는 동일한 자산임에도 한국 거래소와 해외 거래소 간 가격 차이가 발생합니다. 이 가격 차이(프리미엄)를 활용하여 차익을 추구하는 헤지 기반 트레이딩 모델을 제공합니다.

- **한국 거래소**: BTC 현물 매수 (Long)
- **해외 거래소**: BTC 선물 매도 (Short)
- **결과**: 가격 방향성 중립(Delta Neutral) + 프리미엄 변화에 따른 수익 실현

## 기술 스택

- **Language**: Kotlin 2.0, Java 21
- **Framework**: Spring Boot 3.4
- **Database**: MySQL 8
- **Cache**: Redis 7 + Redisson
- **Build**: Gradle (멀티모듈)
- **Testing**: JUnit 5, AssertJ, Testcontainers

## 문서

| 문서 | 설명 |
|------|------|
| [Architecture Design](.ai/architecture/ARCHITECTURE_DESIGN.md) | 구현 기준 시스템 아키텍처, 데이터 흐름, Redis 설계 |
| [Development Guide](.ai/instructions.md) | 개발 지침, 코딩 컨벤션 |
| [Project Status](.ai/PROJECT_STATUS.md) | 현재 진행 상황, TODO |

## 프로젝트 구조

```text
premium-spread/
├── apps/
│   ├── api/                    # REST API 서버 (Port 8080)
│   └── batch/                  # 배치 스케줄러 (Port 8081)
├── modules/
│   ├── jpa/                    # JPA 공통 설정
│   └── redis/                  # Redis, 분산 락
└── supports/
    ├── logging/                # 구조화 로깅
    └── monitoring/             # 메트릭, 헬스체크
```

### API 서버 아키텍처

```text
interfaces/api/   → Controller, Request/Response DTO
application/      → Facade, Criteria/Result DTO
domain/           → Service, Command, Entity
infrastructure/   → Repository 구현체
```

## 핵심 기능

### 1. 프리미엄 계산

```kotlin
premium = ((koreaPrice - foreignPrice * fxRate) / (foreignPrice * fxRate)) * 100
```

| 항목 | 설명 |
|------|------|
| `koreaPrice` | 한국 거래소 현물 가격 (KRW) |
| `foreignPrice` | 해외 거래소 선물 가격 (USD/USDT) |
| `fxRate` | 원/달러 환율 (USD/KRW) |

### 2. 포지션 관리

- 포지션 진입 (Open): 프리미엄 매수 상태 기록
- 포지션 청산 (Close): 손익 확정
- PnL 계산: 진입 프리미엄과 현재 프리미엄 차이로 손익 산출

### 3. 실시간 데이터 수집 및 집계 (Batch)

| 데이터 | 주기 | 소스/처리 |
|--------|------|-----------|
| 한국 시세 | 1초 | 빗썸 API 수집 후 Redis 저장 |
| 해외 시세 | 1초 | 바이낸스 API 수집 후 Redis 저장 |
| 환율 | 30분 | ExchangeRate API 수집 후 Redis + DB 저장 |
| 프리미엄 | 1초 | 계산 후 Redis 저장 |
| 프리미엄/티커 집계 | 1분/1시간/1일 | Redis ZSet 집계 후 DB 저장 |

## 시작하기

### 사전 요구사항

- JDK 21
- Docker & Docker Compose

### 인프라 실행

```bash
docker compose -f docker/infra-compose.yml up -d
```

### 빌드 및 실행

```bash
# 빌드
./gradlew compileKotlin

# API 서버 실행
./gradlew :apps:api:bootRun

# 배치 서버 실행
./gradlew :apps:batch:bootRun
```

### 테스트

```bash
# 단위 테스트
./gradlew test

# 통합 테스트 (Docker 필요)
./gradlew :apps:api:integrationTest
```

## API 엔드포인트

### 프리미엄

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/premiums/calculate/{symbol}` | 프리미엄 계산 |
| GET | `/api/v1/premiums/current/{symbol}` | 현재 프리미엄 조회 |
| GET | `/api/v1/premiums/history/{symbol}` | 기간별 프리미엄 조회 |

### 포지션

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/positions` | 포지션 진입 |
| GET | `/api/v1/positions` | 열린 포지션 목록 |
| GET | `/api/v1/positions/{id}` | 포지션 상세 조회 |
| GET | `/api/v1/positions/{id}/pnl` | PnL 조회 |
| POST | `/api/v1/positions/{id}/close` | 포지션 청산 |

### 티커

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/tickers` | 티커 등록 |

## 환경 설정

### 애플리케이션 프로파일

| Profile | 용도 |
|---------|------|
| `local` | 로컬 개발 환경 |
| `test` | 테스트 환경 |
| `prod` | 운영 환경 |

### 환경 변수

```yaml
# MySQL
MYSQL_URL: jdbc:mysql://localhost:3306/premium_spread
MYSQL_USERNAME: root
MYSQL_PASSWORD: password

# Redis
REDIS_HOST: localhost
REDIS_PORT: 6379
```

## 손익 구조

- **프리미엄 하락** → 이익
- **프리미엄 상승** → 손실

> 가격 자체의 상승/하락은 현물(Long) ↔ 선물(Short) 구조로 상쇄됨

## 향후 계획

- [ ] E2E 테스트
- [ ] Production 설정
- [ ] Docker 이미지 빌드
- [ ] CI/CD 파이프라인
- [ ] 다중 코인 지원 (ETH, SOL 등)
- [ ] 거래소 다각화 (코인원, OKX 등)
- [ ] 자동 매매 기능

## 라이선스

Private Project
