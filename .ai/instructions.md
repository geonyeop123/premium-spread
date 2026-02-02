# Premium Spread - Development Instructions

> 비즈니스 도메인은 `.ai/context/project-overview.md` 참조

## Tech Stack

- Kotlin 2.0, Java 21, Spring Boot 3.4
- MySQL 8, Redis 7, Testcontainers
- Gradle 멀티모듈

---

## Module Structure

```
apps/
├── api/          # REST API 서버 (Port 8080)
└── batch/        # 배치 스케줄러 (Port 8081, 1초/30분 주기)

modules/
├── jpa/          # JPA 공통 설정, BaseEntity
└── redis/        # Redis, Redisson 분산 락

supports/
├── logging/      # 구조화 로깅, 민감정보 마스킹
└── monitoring/   # Micrometer 메트릭, 헬스체크
```

---

## Architecture Rules

### Layer 구조 (apps/api)

```
interfaces/api/   → Controller, Request/Response DTO
application/      → Facade, Criteria/Result DTO
domain/           → Service, Command, Entity, Repository(interface)
infrastructure/   → RepositoryImpl (JPA)
```

### 의존성 방향

```
interfaces → application → domain ← infrastructure
```

- **domain은 외부 의존 금지** (프레임워크 독립)
- Repository는 domain에 interface, infrastructure에 구현체

### Facade vs Service

| 구분 | Facade | Service |
|------|--------|---------|
| 위치 | application | domain |
| 역할 | 유스케이스 조합, 트랜잭션 | 단일 도메인 로직 |
| 의존 | 여러 Service 조합 가능 | 타 도메인 Service 주입 금지 |

---

## Naming Conventions

### DTO (Inner Class Pattern)

모든 레이어의 DTO는 컨테이너 클래스 내 inner class 패턴을 사용:

| Layer | 컨테이너 | Inner Class | 예시 |
|-------|----------|-------------|------|
| interfaces | `*Request`, `*Response` | 동작 | `PositionRequest.Open` |
| application | `*Criteria`, `*Result` | 동작 | `PositionCriteria.Open`, `PositionResult.Detail` |
| domain | `*Command` | 동작 | `PositionCommand.Create` |

### DTO 파일 구조

```kotlin
// 모든 레이어에 동일 패턴 적용
class PositionCriteria private constructor() {
    data class Open(val symbol: String, ...)
}

class PositionResult private constructor() {
    data class Detail(val id: Long, ...) {
        companion object { fun from(entity: Position): Detail = ... }
    }
    data class Pnl(val positionId: Long, ...)
}
```

### Entity

- prefix/suffix 없음: `Position`, `Ticker`, `Premium`
- `@Enumerated(EnumType.STRING)` 필수

---

## Redis Keys (apps/batch)

| Key | TTL | 용도 |
|-----|-----|------|
| `ticker:{exchange}:{symbol}` | 5s | 시세 |
| `fx:{base}:{quote}` | 15m | 환율 |
| `premium:{symbol}` | 5s | 프리미엄 |
| `lock:*` | 2-30s | 분산 락 |

---

## Testing

### 테스트 실행

```bash
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration (Docker 필요)
```

### 테스트 규칙

- **도구**: AssertJ 필수
- **Unit Test**: Mock Repository 사용
- **Integration Test**: `@Tag("integration")`, TestConfig Import

```kotlin
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class RepositoryTest { ... }
```

---

## Implementation Status (92%)

### Completed

- [x] **apps/api**: Domain, Infrastructure, Application, API Layer
- [x] **apps/batch**: Scheduler, Client, CacheService, PremiumCalculator
- [x] **modules/redis**: RedisConfig, DistributedLockManager
- [x] **supports/logging**: StructuredLogger, LogMaskingFilter
- [x] **supports/monitoring**: PremiumMetrics, HealthIndicators
- [x] **Integration Tests**: 28개 통과

### Pending

- [ ] E2E Tests (API + Batch 연동)
- [ ] Production 설정 (application-prod.yml)
- [ ] Docker 설정 (Dockerfile, docker-compose)
- [ ] CI/CD 파이프라인

---

## Quick Commands

```bash
# 빌드
./gradlew compileKotlin

# 인프라 실행 (Docker)
docker compose -f docker/infra-compose.yml up -d

# 서버 실행 (동시 실행)
./gradlew :apps:api:bootRun &      # Port 8080
./gradlew :apps:batch:bootRun &    # Port 8081

# 테스트
./gradlew test
./gradlew :apps:api:integrationTest
```

---

## Coding Guidelines

1. **Kotlin 불변 우선** - `val`, `data class`
2. **순수 함수** - 도메인 계산은 부작용 최소화
3. **과도한 추상화 금지** - 필요할 때만 인터페이스
4. **컴파일 가능 + 테스트 통과** 상태 유지

---

## HTTP 파일 작성 규칙

### 파일 위치
```
http/
├── http-client.env.json   # 환경 변수 정의
└── api/
    ├── premiums.http      # Premium API
    ├── positions.http     # Position API
    └── tickers.http       # Ticker API
```

### API 추가 시 필수 작업

**새로운 Controller 생성 시:**
1. `http/api/{도메인}.http` 파일 생성
2. 모든 endpoint에 대한 요청 샘플 작성

**기존 Controller에 endpoint 추가 시:**
1. 해당 `http/api/{도메인}.http` 파일에 요청 추가

### HTTP 파일 작성 형식

```http
### {API 설명}
{METHOD} {{commerce-api}}/api/v1/{path}
Content-Type: application/json  # POST/PUT 요청 시 필수

{
  "field": "value"  # RequestBody 있을 경우
}
```

### 예시

```http
### 포지션 오픈
POST {{commerce-api}}/api/v1/positions
Content-Type: application/json

{
  "symbol": "BTC",
  "exchange": "UPBIT",
  "quantity": 0.1
}

### 포지션 조회
GET {{commerce-api}}/api/v1/positions/1
```

---

## Git Conventions

### Commit Message

```
<type>: <subject>

- 변경 사항 1
- 변경 사항 2
```

**Type 종류:**

| Type | 용도 |
|------|------|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 코드 리팩토링 (기능 변경 없음) |
| `docs` | 문서 수정 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 설정 변경 |

**규칙:**
- 한글로 작성
- subject는 명령형 (`추가`, `수정`, `개선`)
- Co-Author 라인 추가하지 않음
- 본문은 bullet points로 변경 사항 나열

**예시:**
```
refactor: Redis 테스트를 Testcontainers로 전환

- 통합 테스트에서 Redis Mock 대신 실제 Redis 컨테이너 사용
- TestConfig에서 Redis Mock Bean 제거
- redis.yml에 spring.data.redis 설정 추가
```

### Branch Naming

```
<type>/<short-description>
```

**예시:**
- `feature/position-api`
- `fix/redis-connection`
- `refactor/redis-testcontainers`

### Pull Request

**제목:** 커밋 메시지 첫 줄과 동일

**본문 템플릿:**
```markdown
## Summary
- 변경 사항 요약 (bullet points)

## Test plan
- [ ] 테스트 항목 1
- [ ] 테스트 항목 2
```

**규칙:**
- base branch: `feature/premium` (현재 메인 개발 브랜치)
- 불필요한 파일 커밋 금지 (`.claude/settings.local.json`, `logs/` 등)

---

## 관련 문서

- 비즈니스 도메인: `.ai/context/project-overview.md`
- 구현 상세: `claudedocs/IMPLEMENTATION.md`
- 아키텍처 설계: `claudedocs/ARCHITECTURE_DESIGN.md`
