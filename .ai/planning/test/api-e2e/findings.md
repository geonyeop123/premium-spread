# Findings: API E2E 테스트 설계 조사

## 현재 테스트 레이어 구성

| 레이어 | 파일 | 방식 | 한계 |
|--------|------|------|------|
| Domain | `*Test.kt` | 단위 (no mock) | - |
| Service | `*ServiceTest.kt` | 단위 (mockk Repository) | - |
| Facade | `*FacadeTest.kt` | 단위 (mockk Service) | - |
| Controller | `*ControllerTest.kt` | `@WebMvcTest` + MockkBean(Facade) | Facade mocking → 실제 흐름 미검증 |
| Repository | `*RepositoryTest.kt` | `@SpringBootTest` + TestContainers | Repository 레이어만 검증 |
| **E2E** | **없음** | **-** | **HTTP → DB/Cache 전체 흐름 미검증** |

## 현재 통합 테스트 인프라 (재활용 가능)

- `MySqlTestContainersConfig` — `@Tag("integration")`으로 MySQL 컨테이너 실행
- `RedisTestContainersConfig` — Redis 컨테이너 실행
- `DatabaseCleanUp` — `@BeforeEach`에서 truncate 사용
- `TestConfig` — `@TestConfiguration` 빈 (현재 비어 있음)
- `@SpringBootTest` + `@ActiveProfiles("test")` + `@Import(MySql..., Redis..., TestConfig::class)` 패턴 확립됨

## API 엔드포인트 목록 (PremiumController, PositionController, TickerController)

### TickerController
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/tickers` | 티커 수동 등록 (DB 저장) |

### PremiumController
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/premiums/calculate/{symbol}` | DB의 최신 티커 기반 프리미엄 계산 + DB 저장 |
| GET | `/api/v1/premiums/current/{symbol}` | 최신 프리미엄 조회 (캐시 우선 → DB fallback) |
| GET | `/api/v1/premiums/history/{symbol}` | 기간별 프리미엄 히스토리 조회 (DB) |

### PositionController
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/positions` | 포지션 오픈 (DB 저장 + Redis 캐시 갱신) |
| GET | `/api/v1/positions/{id}` | 포지션 단건 조회 (DB) |
| GET | `/api/v1/positions` | 열린 포지션 목록 조회 (DB) |
| GET | `/api/v1/positions/{id}/pnl` | PnL 계산 (DB 포지션 + cache/DB 프리미엄) |
| POST | `/api/v1/positions/{id}/close` | 포지션 청산 (DB 상태 변경 + Redis 캐시 갱신) |

## Cache 관련 흐름 (E2E에서 검증할 핵심)

### PremiumRepositoryImpl.findLatestSnapshotBySymbol()
```
1. Redis에서 premium:{symbol} 조회
   → hit: 캐시 데이터로 PremiumSnapshot 반환
   → miss: DB(premium 테이블) + 연관 3개 ticker 조회 → PremiumSnapshot 조합 후 반환
```

### PositionRepositoryImpl.save()
```
DB 저장 후 syncOpenPositionCache() 자동 호출:
→ OPEN 상태 포지션 수 조회
→ Redis position:open:exists, position:open:count 갱신
```

## batch E2E 테스트와의 차이점

| 항목 | batch E2E | API E2E |
|------|-----------|---------|
| 진입점 | 스케줄러 메서드 직접 호출 | MockMvc HTTP 요청 |
| 외부 의존 | 거래소 API mocking 허용 | 없음 (모든 데이터 사전 준비) |
| 검증 대상 | 캐시/DB 저장 결과 | HTTP 응답 body + 상태코드 + DB/Cache 부수효과 |
| 테스트 클래스 구성 | 스케줄러 1개당 1 파일 | 컨트롤러 1개당 1 파일 |

## 테스트 파일 배치 계획

```
src/test/kotlin/io/premiumspread/
└── interfaces/
    └── api/
        ├── ticker/
        │   └── TickerControllerE2ETest.kt   (신규)
        ├── premium/
        │   └── PremiumControllerE2ETest.kt  (신규)
        └── position/
            └── PositionControllerE2ETest.kt (신규)
```

## MockMvc 세팅 방식

```kotlin
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class PremiumControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
    ...
)
```

## Redis 캐시 데이터 준비 방식

- `PremiumCacheWriter`(batch 측 존재) 대신 `StringRedisTemplate`으로 직접 Redis 키 설정
- 또는 batch에서 쓰는 Redis key 포맷을 `RedisKeyGenerator`로 생성 후 직접 `opsForHash().putAll()` 호출
