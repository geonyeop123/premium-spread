# Premium Spread

> 비즈니스 도메인은 `.ai/context/project-overview.md` 참조

## Tech Stack

- Kotlin 2.x, Java 21, Spring Boot 3.x
- MySQL 8, Redis 7, Testcontainers
- Gradle 멀티모듈 (정확한 버전은 `gradle.properties`/`build.gradle.kts` 참조)

## Module Structure

```
apps/
├── api/          # REST API 서버 (Port 8080)
└── batch/        # 배치 스케줄러 (Port 8081, WebSocket 실시간 시세 수집 + FX 30분 수집 + 1분/1시간/1일 집계)

modules/
├── jpa/          # JPA 공통 설정, BaseEntity
└── redis/        # Redis, Redisson 분산 락

supports/
├── logging/      # 구조화 로깅, 민감정보 마스킹
├── monitoring/   # Micrometer 메트릭, 헬스체크
└── email/        # JavaMail 기반 이메일 발송 (이슈 #27)
```

## Quick Commands

```bash
./gradlew compileKotlin                  # 빌드
docker compose -f docker/infra-compose.yml up -d  # 인프라 실행
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun &   # API 서버 (8080)
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun & # Batch 서버 (8081)
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration tests (Docker 필요)
```

## Coding Guidelines

1. **Kotlin 불변 우선** - `val`, `data class`
2. **순수 함수** - 도메인 계산은 부작용 최소화
3. **과도한 추상화 금지** - 필요할 때만 인터페이스
4. **컴파일 가능 + 테스트 통과** 상태 유지

## 하네스

`.claude/` 아래에 에이전트와 스킬 하네스를 둔다. **목록과 디렉터리 구조는 파일 시스템에서 직접
확인한다 — 여기에 옮겨 적지 않는다.** 옮겨 적으면 두 곳이 갈린다.

| 항목 | 내용 |
|------|------|
| 목표 | 설계·구현·리뷰·검증·문서화를 고정된 단계와 검증 가능한 완료 기준으로 수행한다 |
| 코드 변경 작업 | `orchestrator` 스킬 (`.claude/skills/orchestrator/`) |
| 하네스 파일 변경 작업 | `orchestrator` 스킬 — 하네스 파일(`.claude/**`, `CLAUDE.md`) 변경도 코드 변경 작업이다. 의미를 바꾸지 않는 소규모 문구 수정의 스킵 경로는 그 스킬의 스킵 규칙 표가 소유한다 |
| 산출물 위치 | `docs/work/{slug}/` — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
| 정합성 검사 | `./gradlew harnessCheck` — 하네스 파일을 고쳤으면 커밋 전에 직접 한 번 돌리고 `docs/harness-changelog.md`에 한 행을 남긴다 |
| 변경 이력 | `docs/harness-changelog.md` — 하네스를 바꾼 작업마다 `날짜 · 변경 내용 · 대상 · 사유` 한 행. 매 세션 로드되는 이 파일에는 두지 않는다 |

`harnessCheck`(`docs/check-harness.sh`)는 하네스 파일 **본문**(펜스 코드블록과 frontmatter 제외)이
언급하는 경로와 `@import` 대상이 실재하는지, agent/skill frontmatter가 유효한지, 그리고 이 저장소가
정한 하네스 계약(필수 절·단계 순서·스킬 디렉터리 형태·`docs/harness-changelog.md`의 존재와 표 헤더)을
검사한다. 변경 이력 파일은 과거 경로를 담는 기록이라 경로 검사 대상이 아니다. 게이트가 빨간데 죽은 참조가 없으면
계약 단정 쪽을 본다. `harnessCheckTest`(`docs/test-check-harness.sh`)는 그 검사기가 위반을 실제로
검출하는지 픽스처로 확인한다 — 검사기만 있고 자가검증이 없으면 조용히 무력해진 게이트를 알아챌 수 없다.
둘 다 `check` 태스크와 CI `quality-gate.yml`에 걸려 있다.

`docs/check-documentation.sh`(같은 워크플로에서 실행)는 정본 문서(`AGENTS.md`·`.ai/*`·
`docs/runbooks/*`)만 검사한다. 두 검사는 대상이 겹치지 않는다.

## 관련 문서

| 문서 | 용도 |
|------|------|
| `.ai/PROJECT_STATUS.md` | 현재 상태, TODO, 진행 상황 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | 시스템 아키텍처, 데이터 흐름 |
| `.ai/context/project-overview.md` | 비즈니스 도메인 설명 |
| `.ai/planning/` | 작업별 계획 문서 디렉터리 (진행 기록과 완료된 작업의 동결 산출물) |
| `docs/work/{slug}/` | `feature-workflow` 산출물 — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
