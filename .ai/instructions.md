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
└── batch/        # 배치 스케줄러 (Port 8081, 1초/30분 수집 + 1분/1시간/1일 집계)

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
domain/           → Service, Command, Entity, Repository(interface), Read Model
infrastructure/   → RepositoryImpl (JPA + Cache), Cache Reader
```

### 의존성 방향

```
interfaces → application → domain ← infrastructure
```

- **domain은 외부 의존 금지** (프레임워크 독립)
- **application은 infrastructure 직접 참조 금지** (domain 인터페이스를 통해서만 접근)
- Repository는 domain에 interface, infrastructure에 구현체

### 계층별 주입 규칙

| 구분    | Facade (application)                       | Service (domain)              | RepositoryImpl (infrastructure)                  |
|-------|--------------------------------------------|-------------------------------|--------------------------------------------------|
| 역할    | 유스케이스 조합, DTO 변환                           | 단일 도메인 로직 위임                  | 데이터 접근 전략 (cache→DB fallback)                    |
| 주입 가능 | **domain Service만**                        | 자기 도메인 Repository만            | Cache Reader, JPA, 타 Repository, QueryRepository |
| 주입 금지 | Repository, CacheReader, infrastructure 전체 | 타 도메인 Service, infrastructure | —                                                |

```kotlin
// Good: Facade → Service → Repository
@Service
class TickerFacade(
    private val tickerService: TickerService,           // domain Service ✅
    private val exchangeRateService: ExchangeRateService, // domain Service ✅
)

// Bad: Facade → infrastructure 직접 참조
@Service
class TickerCacheFacade(
    private val tickerCacheReader: TickerCacheReader,   // infrastructure ❌
    private val fxCacheReader: FxCacheReader,           // infrastructure ❌
    private val tickerAggregationQueryRepository: ...,  // infrastructure ❌
)

// Bad: Facade → Repository 직접 주입
@Service
class SomeFacade(
    private val exchangeRateRepository: ExchangeRateRepository, // Repository 직접 주입 ❌
)
```

### 도메인 분리 규칙

서로 다른 비즈니스 개념은 **별도 domain 패키지**로 분리한다.
하나의 Facade/Service에 여러 도메인 개념을 혼재하지 않는다.

```
domain/
├── ticker/           # 코인 시세 (Ticker, TickerRepository, TickerService, TickerSnapshot)
├── premium/          # 김치 프리미엄 (Premium, PremiumRepository, PremiumService, PremiumSnapshot)
├── position/         # 포지션 (Position, PositionRepository, PositionService)
└── exchangerate/     # 환율 (ExchangeRateRepository, ExchangeRateService, ExchangeRateSnapshot)
```

**분리 기준:**

- 비즈니스 개념이 다르면 분리 (예: 코인 시세 vs 환율)
- 하나의 Facade에서 여러 도메인 Service를 조합하는 것은 허용 (Facade의 역할)

### 신규 도메인 추가 체크리스트

새로운 도메인 개념 추가 시 아래를 확인한다:

- [ ] `domain/{name}/` 패키지에 Entity/Repository(interface)/Service 배치
- [ ] cache→DB fallback이 필요하면 Read Model(`*Snapshot`) + Repository 메서드 추가
- [ ] `infrastructure/{name}/` 패키지에 RepositoryImpl 배치, fallback은 RepositoryImpl 내부
- [ ] application Facade는 **domain Service만 주입** (infrastructure 직접 참조 금지)
- [ ] `rg "^import io\.premiumspread\.infrastructure\." apps/api/src/main/kotlin/io/premiumspread/application/`
  결과 확인

### Cache→DB Fallback 규칙

캐시 우선 조회 + DB fallback 로직은 **infrastructure의 RepositoryImpl 내부**에서 처리한다.
application은 "데이터를 조회한다"만 요청하고, cache hit/miss 여부를 알지 못한다.

```
// Good: infrastructure가 전략 결정
Controller → Facade → Service → Repository(interface)
                                    └→ RepositoryImpl: cache hit → 반환
                                                       cache miss → DB + ticker 조합 → 반환

// Bad: application이 cache/DB를 직접 분기
Controller → CacheFacade → CacheReader (infrastructure 직접 참조)
                         → Service → Repository
```

### Read Model 패턴

조회 시 여러 엔티티를 조합해야 하는 경우 domain에 **Read Model**을 정의한다.

```kotlin
// domain에 정의 — 조회 전용, JPA 엔티티 아님
data class PremiumSnapshot(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal, // cache or ticker에서 조합
    ...
)

// Repository 인터페이스에 메서드 추가
interface PremiumRepository {
    fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot?
}

// RepositoryImpl에서 cache→DB+ticker enrichment로 구현
```

**적용 기준:**

- Entity 단독 반환으로 충분하면 Read Model 불필요
- 캐시 데이터와 DB 데이터의 shape이 다를 때 도입
- DB fallback 시 관련 엔티티를 추가 조회하여 enrichment 필요할 때

---

## Naming Conventions

### DTO (Inner Class Pattern)

모든 레이어의 DTO는 컨테이너 클래스 내 inner class 패턴을 사용:

| Layer       | 컨테이너                     | Inner Class | 예시                                               |
|-------------|--------------------------|-------------|--------------------------------------------------|
| interfaces  | `*Request`, `*Response`  | 동작          | `PositionRequest.Open`                           |
| application | `*Criteria`, `*Result`   | 동작          | `PositionCriteria.Open`, `PositionResult.Detail` |
| domain      | `*Command`               | 동작          | `PositionCommand.Create`                         |
| domain      | `*Snapshot` (Read Model) | —           | `PremiumSnapshot` (조회 전용, 단독 data class)         |

### DTO 파일 구조

```kotlin
// 모든 레이어에 동일 패턴 적용
class PositionCriteria private constructor() {
    data class Open(val symbol: String, ...)
}

class PositionResult private constructor() {
    data class Detail(val id: Long, ...) {
        companion object {
            fun from(entity: Position): Detail = ...
        }
    }
    data class Pnl(val positionId: Long, ...)
}
```

### Entity

- prefix/suffix 없음: `Position`, `Ticker`, `Premium`
- `@Enumerated(EnumType.STRING)` 필수

---

## Testing

### 테스트 실행

```bash
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration (Docker 필요)
```

### 테스트 규칙

- **도구**: AssertJ 필수!!
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

- 새로운 Controller 또는 endpoint 추가 시 `http/api/{도메인}.http` 요청 샘플을 반드시 갱신한다.
- HTTP 상세 작성 규칙/예시는 `http/README.md`를 따른다.

---

## Git Conventions

- 브랜치 규칙: `<type>/<short-description>` (`type`: `feat|fix|refactor|docs|test|chore`)
- 커밋 규칙: `<type>: <subject>` + 한글 bullet 본문
- PR 규칙: 제목은 커밋 첫 줄과 동일, 본문에 `Summary`/`Test plan` 포함
- 상세 Git 정책은 `.ai/skills/codex-claude-flow/references/git-policy.md`를 기준으로 따른다.

---

## 관련 문서

| 문서                                                      | 용도                       |
|---------------------------------------------------------|--------------------------|
| `.ai/PROJECT_STATUS.md`                                 | 현재 상태, TODO, 진행 상황       |
| `.ai/architecture/ARCHITECTURE_DESIGN.md`               | 시스템 아키텍처, 데이터 흐름         |
| `.ai/context/project-overview.md`                       | 비즈니스 도메인 설명              |
| `.ai/skills/codex-claude-flow/references/git-policy.md` | Git 상세 정책(브랜치/커밋/PR/게이트) |
| `.ai/planning/`                                         | 작업별 계획 문서 디렉터리           |
| `http/README.md`                                        | HTTP 샘플 작성 규칙/예시         |

## Planning 문서 운영

- 작업별 계획/진행 문서는 `.ai/planning/` 하위에 수시로 생성/갱신된다.
- 문서 경로/파일명은 작업 성격에 따라 달라질 수 있으므로 최신 문서는 디렉터리에서 확인한다.
