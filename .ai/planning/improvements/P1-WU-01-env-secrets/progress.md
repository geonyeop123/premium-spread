# WU-01 Progress

## 상태: 완료

### 완료 항목
- [x] Task 1: 브랜치 생성 (`fix/wu-01-env-secrets`)
- [x] Task 2: ExchangeRate API 키 환경변수화
  - `application.yml`에서 평문 키 제거, `${EXCHANGE_RATE_API_KEY}` 참조로 교체
  - `application-local.yml` 신규 생성 (dummy 키)
- [x] Task 3: Redis 비밀번호 환경변수화
  - prd 프로필: `${REDIS_PASSWORD}` 환경변수 참조
  - local 프로필: `local-redis-password` 고정값
  - test 프로필: 기존 빈 문자열 유지 (TestContainers)
  - Docker Compose: redis-master/redis-readonly에 requirepass + masterauth 추가
  - api/batch/app compose에 REDIS_PASSWORD 환경변수 추가
- [x] Task 4: `.env.example` 생성, `.gitignore`에 `.env` 추가
- [x] Task 5: Git 히스토리 정리 가이드 문서 작성
- [x] Task 6: 빌드 및 테스트 검증
  - `./gradlew compileKotlin` BUILD SUCCESSFUL
  - `./gradlew test` BUILD SUCCESSFUL
- [x] Task 7: 커밋

### 변경 파일 목록
| 파일 | 변경 유형 |
|------|----------|
| `apps/batch/src/main/resources/application.yml` | 수정 |
| `apps/batch/src/main/resources/application-local.yml` | 신규 |
| `modules/redis/src/main/resources/redis.yml` | 수정 |
| `docker/infra-compose.yml` | 수정 |
| `docker/api-compose.yml` | 수정 |
| `docker/batch-compose.yml` | 수정 |
| `docker/app-compose.yml` | 수정 |
| `.env.example` | 신규 |
| `.gitignore` | 수정 |
| `CLAUDE.md` | 수정 |
| `AGENTS.md` | 수정 |
| `README.md` | 수정 |
| `.ai/planning/improvements/P1-WU-01-env-secrets/git-history-cleanup.md` | 신규 |
