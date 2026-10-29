# Testing Rules

## Test task와 명령

로컬은 기존 cache만 사용한다. 전체 검증은 Gradle을 한 번만 부른다.

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
```

실패한 task만 다시 돌릴 때:

```bash
./gradlew test architectureTest --parallel --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
```

- 각 모듈 `test`: unit/context/contract test, `integration` tag 제외
- root `architectureTest`: 독립 architecture source set의 모듈 dependency, source/import, bytecode 경계
- `*:integrationTest`: `@Tag("integration")`, Docker/Testcontainers 필요
- `--parallel`이면 서로 다른 모듈의 unit·통합 task가 동시에 돈다. task끼리 컨테이너·DB·포트를 공유한다고 가정하지 않는다.
- `--continue`는 실패한 task가 있어도 나머지를 끝까지 실행해 실패 전체를 한 번에 보여 준다.

## 격리 규칙

- unit은 fake/mock으로 외부 I/O를 차단한다.
- integration은 MySQL/Redis Testcontainers와 deterministic fixture를 사용한다.
- 실제 거래소/FX/SMTP/Slack 주소를 test에 넣지 않는다. HTTP/WebSocket은 local mock server를 사용한다.
- system default timezone/current time에 의존하지 말고 고정 `Clock`과 명시적 zone을 사용한다.
- 테스트 종료 뒤 executor, scheduler, socket, container resource를 정리한다.
- timeout/leaked thread 검출 실패를 retry로 숨기지 않는다.
- `@Disabled`로 계약을 미루지 않는다.
- 같은 테스트 JVM 안의 Testcontainers singleton(모듈 공용 컨테이너)은 허용한다. 정리는 JVM 종료 시 Ryuk가 맡는다(명시적 stop 없음).
  run 사이의 reuse(`testcontainers.reuse.enable`)는 금지다. common 통합 테스트 클래스는 공용 MySQL에서
  `SharedMySqlContainer.database(...)`로 자기 database를 따로 받는다.
- 공용 Spring context를 쓰는 batch 통합 테스트는 매 테스트 전에 base가 공용 상태(DB·Redis·MockWebServer)를 초기화한다.
  상태를 남기는 공용 bean을 새로 추가하면 base 초기화에 넣는다.

## 검증 범위

- Domain 계산/validation은 순수 unit test로 경계값을 검증한다.
- JPA/JDBC/Redis adapter는 실제 MySQL/Redis transaction, TTL, corrupt payload, fallback을 검증한다.
- 인증은 refresh rotation/concurrent loser/reuse/logout 후 Access 유효 계약을 E2E로 검증한다.
- notification은 concurrent enqueue/claim, rollback, retry, stale owner fencing, redrive, PII scrub을 검증한다.
- migration은 빈 DB latest와 이전 version→latest 경로, version uniqueness/destructive SQL gate를 검증한다.
- management endpoint는 별도 port에서 readiness/Prometheus가 열리고 application port에서는 닫히는지 검증한다.

## Coverage와 exclusion

Quality Gate line 기준은 overall 70%, Domain 85%, Application 80%다. exclusion은 generated code,
순수 configuration wiring, field-only DTO로 제한한다. 비즈니스 분기, adapter, error handling을 exclusion에
추가하지 않는다. coverage 결과는 해당 commit SHA의 CI artifact가 최종 증거다.

실제 pattern SSOT는 `config/coverage/exclusions.txt`이며 현재 허용 범위는 generated, application entrypoint,
`config`, `*Configuration`/`*AutoConfiguration`/`*Properties`, DTO/Request/Response다. pattern을 넓힐 때는
제외 대상이 비즈니스 분기를 갖지 않는다는 review 근거와 coverage 전후 수치를 남긴다.

## 실패 처리

- flaky 의심 실패도 clean rerun 근거 없이 무시하지 않는다.
- 실패 원인을 재현하고 단일 가설로 수정한 뒤 관련 task와 상위 gate를 다시 실행한다.
- Docker/network/tool artifact 부재로 실행하지 못한 항목은 green으로 기록하지 않는다.
