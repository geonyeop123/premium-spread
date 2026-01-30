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
└── batch/        # 배치 스케줄러 (1초/10분 주기)

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

### DTO

| Layer | 패턴 | 예시 |
|-------|------|------|
| interfaces | `*Request`, `*Response` | `OpenPositionRequest` |
| application | `*Criteria`, `*Result` | `PremiumCriteria` |
| domain | `*Command` | `PositionCommand.Create` |

### DTO 파일 구조

```kotlin
// domain/position/PositionCommand.kt
class PositionCommand private constructor() {
    data class Create(val symbol: String, ...)
    data class Close(val positionId: Long)
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
@Import(MySqlTestContainersConfig::class, TestConfig::class)
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

# 실행 (Docker infra 필요)
docker compose -f docker/infra-compose.yml up -d
./gradlew :apps:api:bootRun
./gradlew :apps:batch:bootRun

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

## 관련 문서

- 비즈니스 도메인: `.ai/context/project-overview.md`
- 구현 상세: `claudedocs/IMPLEMENTATION.md`
- 아키텍처 설계: `claudedocs/ARCHITECTURE_DESIGN.md`
