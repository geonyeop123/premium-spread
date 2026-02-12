# Architecture Rules

## Layer 구조 (apps/api)

```
interfaces/api/   → Controller, Request/Response DTO
application/      → Facade, Criteria/Result DTO
domain/           → Service, Command, Entity, Repository(interface), Read Model
infrastructure/   → RepositoryImpl (JPA + Cache), Cache Reader
```

## 의존성 방향

```
interfaces → application → domain ← infrastructure
```

- **domain은 외부 의존 금지** (프레임워크 독립)
- **application은 infrastructure 직접 참조 금지** (domain 인터페이스를 통해서만 접근)
- Repository는 domain에 interface, infrastructure에 구현체

## 계층별 주입 규칙

| 구분 | Facade (application) | Service (domain) | RepositoryImpl (infrastructure) |
|------|---------------------|-------------------|--------------------------------|
| 역할 | 유스케이스 조합, DTO 변환 | 단일 도메인 로직 위임 | 데이터 접근 전략 (cache→DB fallback) |
| 주입 가능 | **domain Service만** | 자기 도메인 Repository만 | Cache Reader, JPA, 타 Repository, QueryRepository |
| 주입 금지 | Repository, CacheReader, infrastructure 전체 | 타 도메인 Service, infrastructure | — |

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
)

// Bad: Facade → Repository 직접 주입
@Service
class SomeFacade(
    private val exchangeRateRepository: ExchangeRateRepository, // ❌
)
```

## 도메인 분리 규칙

서로 다른 비즈니스 개념은 **별도 domain 패키지**로 분리한다.

```
domain/
├── ticker/           # 코인 시세
├── premium/          # 김치 프리미엄
├── position/         # 포지션
└── exchangerate/     # 환율
```

- 비즈니스 개념이 다르면 분리 (예: 코인 시세 vs 환율)
- 하나의 Facade에서 여러 도메인 Service를 조합하는 것은 허용

## 신규 도메인 추가 체크리스트

- [ ] `domain/{name}/` 패키지에 Entity/Repository(interface)/Service 배치
- [ ] cache→DB fallback이 필요하면 Read Model(`*Snapshot`) + Repository 메서드 추가
- [ ] `infrastructure/{name}/` 패키지에 RepositoryImpl 배치, fallback은 RepositoryImpl 내부
- [ ] application Facade는 **domain Service만 주입** (infrastructure 직접 참조 금지)

## Cache→DB Fallback 규칙

캐시 우선 조회 + DB fallback 로직은 **infrastructure의 RepositoryImpl 내부**에서 처리한다.
application은 "데이터를 조회한다"만 요청하고, cache hit/miss 여부를 알지 못한다.

```
// Good: infrastructure가 전략 결정
Controller → Facade → Service → Repository(interface)
                                    └→ RepositoryImpl: cache hit → 반환
                                                       cache miss → DB + ticker 조합 → 반환

// Bad: application이 cache/DB를 직접 분기
Controller → CacheFacade → CacheReader (infrastructure 직접 참조)
```

## Read Model 패턴

조회 시 여러 엔티티를 조합해야 하는 경우 domain에 **Read Model**을 정의한다.

```kotlin
data class PremiumSnapshot(
    val symbol: String,
    val premiumRate: BigDecimal,
    val koreaPrice: BigDecimal,
    ...
)

interface PremiumRepository {
    fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot?
}
```

- Entity 단독 반환으로 충분하면 Read Model 불필요
- 캐시 데이터와 DB 데이터의 shape이 다를 때 도입
- DB fallback 시 관련 엔티티를 추가 조회하여 enrichment 필요할 때
