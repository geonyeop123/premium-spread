# Project Status

> Last Updated: 2026-02-02
> Branch: `refactor/redis-testcontainers`

## Current State

| 모듈                  | 상태 | 비고                   |
|---------------------|----|----------------------|
| apps/api            | ✅  | 프리미엄 캐시 우선 조회 추가     |
| apps/batch          | ✅  | DB 저장 추가, JPA 의존성 추가 |
| modules/jpa         | ✅  | -                    |
| modules/redis       | ✅  | Testcontainers 전환 완료 |
| supports/logging    | ✅  | -                    |
| supports/monitoring | ✅  | -                    |

## Recent Changes

```
5b56e2e feat: 프리미엄 API Redis 캐시 조회 및 DB 저장 추가
212740b fix: 배치 서버 구동 오류 수정
396cdf4 refactor: Redis 테스트를 Testcontainers로 전환
```

## TODO

GPT 5.2 / Opus 4.5 Sonet 4.5 5.0

### High Priority

- [ ] E2E 테스트 (API + Batch 연동)
- [ ] Production 설정 (application-prod.yml, 환경변수)

### Medium Priority

- [ ] Docker 설정 (Dockerfile, docker-compose.yml)
- [ ] CI/CD 파이프라인 (GitHub Actions)

### Low Priority

- [ ] 성능 테스트 (부하 테스트)
- [ ] 멀티 코인 지원 (ETH, SOL)

## Known Issues

- (현재 없음)

## Test Status

```bash
./gradlew test                        # Unit tests
./gradlew :apps:api:integrationTest   # Integration (Docker 필요)
```

- Unit Tests: 통과
- Integration Tests: 28개 통과

## Quick Commands

```bash
# 인프라 실행
docker compose -f docker/infra-compose.yml up -d

# 서버 실행
./gradlew :apps:api:bootRun      # Port 8080
./gradlew :apps:batch:bootRun    # Port 8081

# API 테스트
curl http://localhost:8080/api/v1/premiums/current/BTC
```
