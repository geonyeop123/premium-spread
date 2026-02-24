# Progress Log: API E2E 테스트 구축

## Session: 2026-02-24 (Session 2)

### Completed

- [x] batch E2E 테스트 구조 분석 (`scheduler_e2e_testcases.md` 참고)
- [x] API 모듈 엔드포인트 전수 조사 (3 Controller, 9 endpoint)
- [x] 현재 테스트 레이어 갭 분석 (Controller는 WebMvcTest, Repository는 통합 — E2E 없음)
- [x] 재활용 가능 인프라 확인 (MySqlTestContainersConfig, RedisTestContainersConfig, DatabaseCleanUp)
- [x] E2E 테스트케이스 20건 설계 완료 (TickerController 2 + PremiumController 7 + PositionController 11)
- [x] 설계 결정 사항 정리 (Redis 초기화 방식, MockMvc 세팅 방식, Cache 준비 방식)
- [x] 3개 E2E 테스트 파일 작성 완료 (컴파일 성공 확인)
  - `TickerControllerE2ETest.kt` (2건)
  - `PremiumControllerE2ETest.kt` (9건 — 기간 필터 2건 포함)
  - `PositionControllerE2ETest.kt` (11건)

### In Progress (blocked)

- [ ] `./gradlew :apps:api:integrationTest` 전체 GREEN 확인 — **실패 중 (인프라 문제)**

### 현재 실패 원인 (조사 필요)

**증상**: `./gradlew :apps:api:integrationTest` 실행 시 `RedisAuthRequiredException` 발생으로 전체 50건 실패
```
Caused by: org.redisson.client.RedisAuthRequiredException at CommandDecoder.java:395
```

**진단 결과**:
- `ss -tlnp | grep 6379` → `LISTEN 0 4096 *:6379 *:*` — 뭔가 6379 포트에서 수신 중
- `docker ps -a | grep redis` → `premium-spread-redis` (Exited 12일 전, `0.0.0.0:6379->6379/tcp` 매핑)
- TestContainers Redis 컨테이너가 시작 안 됨 (docker ps에서 미확인)
- 기존 RepositoryTest들도 동일하게 실패 → 새 E2E 파일이 원인이 아닌 **기존 인프라 문제**

**가능한 원인**:
1. WSL/Windows에서 Redis가 localhost:6379로 실행 중이며 비밀번호 있음
2. Redisson이 `@ServiceConnection` 오버라이드 대신 `localhost:6379`를 사용 중
3. `withReuse(true)` 플래그가 중지된 compose Redis 재사용 시도

### 다음 세션 작업

1. **인프라 문제 해결**: 기존 RepositoryTest부터 통과시킨 후 E2E 진행
   - 옵션A: `RedisTestContainersConfig`에서 `withReuse(true)` 제거
   - 옵션B: `redis.yml`의 test 프로파일 password 확인
   - 옵션C: docker compose up으로 로컬 Redis 먼저 실행 후 테스트

2. **E2E 테스트 파일 검증**: 컴파일 성공 확인됨, 실행 검증 필요
3. **커밋 + PR**: 테스트 GREEN 후 `/finalize`

### 작성된 파일

| 파일 | 경로 | 상태 |
|------|------|------|
| TickerControllerE2ETest.kt | `apps/api/src/test/.../interfaces/api/ticker/` | 작성 완료, 미실행 |
| PremiumControllerE2ETest.kt | `apps/api/src/test/.../interfaces/api/premium/` | 작성 완료, 미실행 |
| PositionControllerE2ETest.kt | `apps/api/src/test/.../interfaces/api/position/` | 작성 완료, 미실행 |

### Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| `RedisAuthRequiredException` | 전체 integrationTest 실행 | 기존 테스트도 동일 실패 — 인프라 환경 문제로 추정 |
