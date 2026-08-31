---
name: test-strategy
description: "테스트 전략 스킬. unit/architectureTest/integrationTest 태스크 구분, 계층별 테스트 작성, Testcontainers·고정 Clock·격리 규칙, 커버리지 기준을 가이드한다. 테스트 작성, TDD 구현, 테스트 실패 분석, 새 테스트 태스크 추가 시 반드시 이 스킬을 사용할 것."
---

# 테스트 전략

## 태스크 구분

| 태스크 | 대상 |
|--------|------|
| 각 모듈 `test` | unit / context / contract 테스트. `integration` 태그 제외 |
| 루트 `architectureTest` | 독립 source set의 모듈 의존, source/import, 바이트코드 경계 |
| `*:integrationTest` | `@Tag("integration")`. Docker/Testcontainers 필요 |

로컬은 기존 캐시만 쓴다.

```bash
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
```

**이 저장소에 `lint` 태스크는 없다.** 정적 경계 검증은 `architectureTest`가 담당한다.

## 계층별 작성

| 계층 | 무엇을 | 방식 |
|------|-------|------|
| Domain | 계산·validation의 경계값 | 순수 단위 테스트. 외부 I/O 없음 |
| JPA/JDBC/Redis adapter | 실제 트랜잭션·TTL·손상 payload·fallback | Testcontainers(MySQL/Redis) |
| Facade/Controller | 유스케이스 조합과 status 매핑 | slice/contract 테스트 |
| 인증 | refresh rotation, 동시 요청 loser, reuse 탐지, logout 후 Access 유효 계약 | E2E |
| notification | 동시 enqueue/claim, rollback, retry, stale owner fencing, redrive, PII scrub | integration |
| migration | 빈 DB latest 경로와 이전 version→latest 경로, version 유일성, 파괴적 SQL gate | `verifyMigrations` |
| management endpoint | 별도 port에서 readiness/Prometheus가 열리고 application port에서는 닫히는지 | contract |

## 격리 규칙

- unit은 fake/mock으로 외부 I/O를 차단한다.
- 실제 거래소/FX/SMTP/Slack 주소를 테스트에 넣지 않는다. HTTP/WebSocket은 local mock server를 쓴다.
- system default timezone·현재 시각에 의존하지 않는다. **고정 `Clock`과 명시적 zone**을 쓴다.
- 테스트가 끝나면 executor·scheduler·socket·container 자원을 정리한다.
- timeout·leaked thread 실패를 retry로 숨기지 않는다.
- `@Disabled`로 계약을 미루지 않는다.

## TDD 순서

Domain 불변식 → 포트·Service → infrastructure adapter → Criteria/Result/Facade →
Controller(또는 Scheduler→Job) → integration/E2E → `architectureTest`.

각 단계에서 **실패하는 테스트를 먼저** 만들고, 통과시키는 최소 구현을 넣고, 그 다음 정리한다.
테스트를 고쳐서 통과시키는 것은 요구사항이 바뀐 경우에만 허용한다.

## 커버리지

line 기준 overall 70%, Domain 85%, Application 80%. exclusion은 generated code, 순수 configuration
wiring, field-only DTO로 제한한다. 비즈니스 분기·adapter·error handling을 exclusion에 넣지 않는다.
패턴의 정본은 `config/coverage/exclusions.txt`이며, 넓힐 때는 review 근거와 전후 수치를 남긴다.

## 실패를 다루는 법

- flaky로 의심돼도 clean rerun 근거 없이 무시하지 않는다.
- 원인을 재현하고 **단일 가설**로 고친 뒤 관련 태스크와 상위 gate를 다시 돌린다.
- Docker·네트워크·도구 부재로 실행하지 못한 항목을 green으로 기록하지 않는다.

## 읽을 것

- `.ai/rules/testing.md` — 테스트 규칙의 정본
- `config/coverage/exclusions.txt` — exclusion 패턴 SSOT
