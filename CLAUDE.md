# Premium Spread

> 비즈니스 도메인은 `.ai/context/project-overview.md` 참조

## Tech Stack

- Kotlin 2.0, Java 21, Spring Boot 3.4
- MySQL 8, Redis 7, Testcontainers
- Gradle 멀티모듈

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

## Quick Commands

```bash
./gradlew compileKotlin                  # 빌드
docker compose -f docker/infra-compose.yml up -d  # 인프라 실행
./gradlew :apps:api:bootRun &            # API 서버 (8080)
./gradlew :apps:batch:bootRun &          # Batch 서버 (8081)
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration tests (Docker 필요)
```

## Coding Guidelines

1. **Kotlin 불변 우선** - `val`, `data class`
2. **순수 함수** - 도메인 계산은 부작용 최소화
3. **과도한 추상화 금지** - 필요할 때만 인터페이스
4. **컴파일 가능 + 테스트 통과** 상태 유지

## 관련 문서

| 문서 | 용도 |
|------|------|
| `.ai/PROJECT_STATUS.md` | 현재 상태, TODO, 진행 상황 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | 시스템 아키텍처, 데이터 흐름 |
| `.ai/context/project-overview.md` | 비즈니스 도메인 설명 |
| `.ai/planning/` | 작업별 계획 문서 디렉터리 |
