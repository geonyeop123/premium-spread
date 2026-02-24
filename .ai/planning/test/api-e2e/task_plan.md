# Task Plan: API E2E 테스트 구축

## Goal

HTTP 요청 → Controller → Facade → Service → Repository → DB/Cache 전체 흐름을 검증하는
통합(E2E) 테스트 20건을 구축하여 현재 레이어별 단위 테스트로 커버되지 않는
실제 시스템 동작을 보증한다.

## 배경 (batch E2E 대비)

| 항목 | batch E2E (완료) | API E2E (이번 작업) |
|------|-----------------|-------------------|
| 진입점 | 스케줄러 직접 호출 | MockMvc HTTP 요청 |
| 검증 | 캐시/DB 저장 결과 | HTTP 응답 + DB/Cache 부수효과 |
| mocking | 외부 API 허용 | 없음 (완전 no-mock) |
| 케이스 수 | 20건 | 20건 (목표) |

## 구현할 파일

```
src/test/kotlin/io/premiumspread/interfaces/api/
├── ticker/
│   └── TickerControllerE2ETest.kt    (2건)
├── premium/
│   └── PremiumControllerE2ETest.kt   (7건)
└── position/
    └── PositionControllerE2ETest.kt  (11건)
```

## 공통 테스트 어노테이션 패턴

```kotlin
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class XxxControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
    // 데이터 준비용 Repository 직접 주입
)
```

## Phases

### Phase 1: 인프라 점검 및 세팅 확인
- Status: pending
- Tasks:
  - [ ] `@AutoConfigureMockMvc` + `@SpringBootTest` 조합 동작 확인
  - [ ] Redis flush 헬퍼 추가 필요 여부 확인 (`DatabaseCleanUp`에 Redis 포함 or 별도)
  - [ ] Redis 캐시 준비용 유틸 방식 결정 (RedisTemplate 직접 vs 헬퍼 클래스)

### Phase 2: TickerControllerE2ETest (2건)
- Status: pending
- Tasks:
  - [ ] `POST /api/v1/tickers` 성공: DB 저장 확인
  - [ ] `POST /api/v1/tickers` 잘못된 exchange → 400

### Phase 3: PremiumControllerE2ETest (7건)
- Status: pending
- Tasks:
  - [ ] `POST /api/v1/premiums/calculate/BTC` 정상 (DB ticker 3건 준비 → premium 계산 + 저장)
  - [ ] `POST /api/v1/premiums/calculate/BTC` Korea ticker 없음 → 404
  - [ ] `POST /api/v1/premiums/calculate/BTC` Foreign ticker 없음 → 404
  - [ ] `POST /api/v1/premiums/calculate/BTC` FX ticker 없음 → 404
  - [ ] `GET /api/v1/premiums/current/BTC` 캐시 hit → Redis 데이터 반환
  - [ ] `GET /api/v1/premiums/current/BTC` 캐시 miss + DB hit → DB fallback 반환
  - [ ] `GET /api/v1/premiums/current/BTC` 캐시 miss + DB miss → 404
  - [ ] `GET /api/v1/premiums/history/BTC` 기간 필터 + 정렬 검증 (2건)

### Phase 4: PositionControllerE2ETest (11건)
- Status: pending
- Tasks:
  - [ ] `POST /api/v1/positions` 오픈: DB 저장 + Redis exists/count 갱신
  - [ ] `POST /api/v1/positions` 잘못된 exchange → 400
  - [ ] `GET /api/v1/positions/{id}` 존재
  - [ ] `GET /api/v1/positions/{id}` 미존재 → 404
  - [ ] `GET /api/v1/positions` OPEN 포지션만 필터
  - [ ] `GET /api/v1/positions` 빈 결과 → []
  - [ ] `GET /api/v1/positions/{id}/pnl` 캐시 hit으로 PnL 계산
  - [ ] `GET /api/v1/positions/{id}/pnl` DB fallback으로 PnL 계산
  - [ ] `GET /api/v1/positions/{id}/pnl` 프리미엄 없음 → 404
  - [ ] `POST /api/v1/positions/{id}/close` 청산: DB CLOSED + Redis exists=false/count=0
  - [ ] `POST /api/v1/positions/{id}/close` 없는 포지션 → 404

### Phase 5: 검증 및 커밋
- Status: pending
- Tasks:
  - [ ] `./gradlew :apps:api:integrationTest` 20건 전체 GREEN
  - [ ] 커밋 + PR

## 설계 결정

| 결정 | 근거 |
|------|------|
| `@AutoConfigureMockMvc` 사용 | `@SpringBootTest` + MockMvc 자동 설정, 실제 서블릿 필터 체인 포함 |
| Redis 직접 조작 (RedisTemplate) | 배치의 캐시 Writer 의존 없이 테스트 자체 격리 |
| DB 준비 시 Repository 직접 사용 | 테스트용 SQL 없이 도메인 모델 재사용 |
| Redis 초기화: `redisTemplate.connectionFactory.connection.flushAll()` | DB truncate와 동일 수준 격리 |
| Cache hit 케이스: Redis에 직접 hash set | `PremiumCacheWriter`(batch)에 의존하지 않음 |

## 참고 문서

- `e2e_testcases.md` — 케이스별 Given/When/Then 상세
- `findings.md` — 현재 구조 분석, 재활용 인프라
- `.ai/planning/refactoring/batch/testcase/scheduler_e2e_testcases.md` — batch E2E 참고

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| (none yet) | - | - |
