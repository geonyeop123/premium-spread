---
name: api-feature
description: "API 도메인 기능 구현 스킬. 새로운 도메인/엔티티/서비스/Repository/Controller/DTO를 추가하거나, 기존 도메인에 새 API 엔드포인트를 추가할 때 사용한다. '새 도메인 추가', 'API 만들어줘', 'CRUD 구현', '엔드포인트 추가', '컨트롤러 추가' 요청 시 반드시 이 스킬을 사용할 것."
---

# API Feature — 도메인 기능 구현 가이드

premium-spread의 레이어드 아키텍처에 맞춰 API 기능을 구현하는 절차 가이드.

## 레이어 순서

기능 구현은 아래에서 위로 (domain → infrastructure → application → interfaces) 순서로 진행한다. 각 레이어가 컴파일 가능한 상태를 유지하기 위함이다.

```
1. domain/       → Entity, VO, Command, Repository(interface), Service
2. infrastructure/ → JpaRepository, RepositoryImpl, CacheReader(필요 시)
3. application/  → Criteria, Result, Facade
4. interfaces/   → Request, Response, Controller
5. test          → Unit → Integration → E2E
```

## Step 1: Domain 레이어

### Entity
```kotlin
// domain/{name}/{Name}.kt
@Entity
@Table(name = "{names}")
class {Name}(
    @Column(nullable = false)
    val field1: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: {Name}Status,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L,
) : BaseEntity()
```

### Command (DTO)
```kotlin
// domain/{name}/{Name}Command.kt
class {Name}Command private constructor() {
    data class Create(val field1: String, ...)
    data class Update(val id: Long, val field1: String, ...)
}
```

### Repository (interface)
```kotlin
// domain/{name}/{Name}Repository.kt
interface {Name}Repository {
    fun save(entity: {Name}): {Name}
    fun findById(id: Long): {Name}?
    fun findAll(): List<{Name}>
}
```

### Service
```kotlin
// domain/{name}/{Name}Service.kt
@Service
class {Name}Service(
    private val {name}Repository: {Name}Repository,  // 자기 도메인 Repository만
) {
    fun create(command: {Name}Command.Create): {Name} { ... }
    fun findById(id: Long): {Name} { ... }
}
```

## Step 2: Infrastructure 레이어

### JPA Repository
```kotlin
// infrastructure/{name}/Jpa{Name}Repository.kt
interface Jpa{Name}Repository : JpaRepository<{Name}, Long>
```

### Repository 구현체
```kotlin
// infrastructure/{name}/{Name}RepositoryImpl.kt
@Repository
class {Name}RepositoryImpl(
    private val jpa{Name}Repository: Jpa{Name}Repository,
) : {Name}Repository {
    override fun save(entity: {Name}): {Name} = jpa{Name}Repository.save(entity)
    override fun findById(id: Long): {Name}? = jpa{Name}Repository.findByIdOrNull(id)
}
```

**Cache→DB Fallback이 필요한 경우**: RepositoryImpl 내부에서 cache hit/miss를 처리한다. application은 캐시 존재를 모른다.

## Step 3: Application 레이어

### Criteria/Result (DTO)
```kotlin
// application/{name}/{Name}Criteria.kt
class {Name}Criteria private constructor() {
    data class Create(val field1: String, ...) {
        fun toCommand() = {Name}Command.Create(field1 = field1, ...)
    }
}

// application/{name}/{Name}Result.kt
class {Name}Result private constructor() {
    data class Detail(val id: Long, val field1: String, ...) {
        companion object {
            fun from(entity: {Name}) = Detail(id = entity.id, field1 = entity.field1, ...)
        }
    }
}
```

### Facade
```kotlin
// application/{name}/{Name}Facade.kt
@Service
class {Name}Facade(
    private val {name}Service: {Name}Service,  // domain Service만 주입!
) {
    fun create(criteria: {Name}Criteria.Create): {Name}Result.Detail { ... }
}
```

## Step 4: Interfaces 레이어

### Request/Response (DTO)
```kotlin
// interfaces/api/{name}/{Name}Request.kt
class {Name}Request private constructor() {
    data class Create(
        @field:NotBlank val field1: String,
    ) {
        fun toCriteria() = {Name}Criteria.Create(field1 = field1)
    }
}
```

### Controller
```kotlin
// interfaces/api/{name}/{Name}Controller.kt
@RestController
@RequestMapping("/api/v1/{names}")
class {Name}Controller(
    private val {name}Facade: {Name}Facade,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: {Name}Request.Create): ResponseEntity<{Name}Result.Detail> { ... }
}
```

## Step 5: 테스트

테스트 도구는 AssertJ 필수. 기존 테스트 파일의 패턴을 참조한다.

### Unit Test
- Domain: Entity 생성/상태 변경 로직
- Service: Mock Repository로 비즈니스 로직 검증
- Facade: Mock Service로 유스케이스 조합 검증
- Controller: MockMvc로 요청/응답 매핑 검증

### Integration Test
- `@Tag("integration")`, `@SpringBootTest`, `@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)`
- Repository: 실제 DB 연동 검증

### E2E Test
- Controller + 전체 스택 통합 테스트
- JWT 인증 포함

## Step 6: HTTP 샘플

새 엔드포인트 추가 시 `http/api/{도메인}.http` 파일을 갱신한다. `http/README.md` 규칙을 따른다.

## 의존성 방향 위반 금지 사항

| 위반 | 올바른 방향 |
|------|-----------|
| Facade → Repository | Facade → Service → Repository |
| Facade → CacheReader | Facade → Service → Repository(impl이 캐시 처리) |
| domain → infrastructure import | domain은 외부 의존 금지 |
| Service → 타 도메인 Service | Facade에서 여러 Service 조합 |
