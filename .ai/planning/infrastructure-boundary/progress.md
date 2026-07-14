# Infrastructure Boundary Refactoring Progress

> 갱신: 2026-07-14 KST

| Phase | 상태 | Commit | Push | 검증 |
|---|---|---|---|---|
| 0. 기준선/운영 데이터 점검 | COMPLETE | `cc7030e` | `origin/refactor/infrastructure-boundary` | unit 443 green, Batch integration 64개 중 25개 known failures 승인 |
| 1. 긴급 운영 안전장치 | COMPLETE | `e1f1487` | `origin/refactor/infrastructure-boundary` | unit 473 green, API integration 91개 중 90 green/1 approved disabled |
| 2. Gradle 모듈 경계/Architecture Test | COMPLETE | 이번 Phase commit | `origin/refactor/infrastructure-boundary` | unit 505 green, architecture 11 green, Batch integration은 Phase 3 retention 2건만 실패 |
| 3~10 | NOT_STARTED | 없음 | 없음 | Phase 3 공통 Domain 추출 대기 |

## Phase 0 실행 기록

- `origin/dev` `a0a59ee`에서 전용 worktree와 `refactor/infrastructure-boundary` 브랜치를 생성했다.
- 원본 workspace의 사용자 변경 `.ai/instructions.md`, `.claude/worktrees/`는 수정하지 않았다.
- 최초 계획 문서를 동일 SHA-256으로 worktree에 복제한 뒤, 스펙 리뷰와 사용자 승인 내용을 실행본에 반영했다.
- default test 443개는 성공했다.
- API integration은 Docker API 1.44 보정 후 성공했다.
- Batch integration 전체 64개를 Docker API 보정 후 재실행해 25개 실패를 확인했다: retention 2건은
  Phase 3, test fixture schema drift 23건은 Phase 2 owner가 해결한다.
- Web production build는 성공했지만 lint 1건은 실패했다.
- 로컬 V12는 APPLIED/position 0건이다.
- 운영/스테이징 환경은 없으며 `NOT_DEPLOYED`로 분류했다.
- 의미가 불명확한 로컬 timestamp는 변환하지 않고 volume을 보존한 뒤 UTC 기반 새 volume/fixture를 사용한다.
- offline cache 부재로 coverage/ktlint/detekt/OWASP 결과를 만들 수 없다.
- 사용자가 Batch retention 2건(Phase 3), schema fixture drift 23건(Phase 2), Web lint 1건(Phase 5), quality tool 부재(Phase 9 CI)를
  `baseline-known-failures`로 승인했다.

## Phase 1 실행 기록

- refresh 공개 matcher를 exact POST path로 제한하고 실제 로그인 refresh cookie만으로 재발급되는 E2E를 추가했다.
- JWT secret/issuer/audience/access TTL/refresh TTL/clock skew는 local/test 외 환경에서 필수이며, 값과 서명 claim을 검증한다.
- API 공통 JPA는 `validate`, local/test만 `create-drop`이고 Batch Flyway는 비활성화했다.
- V12 외부 preflight와 API Flyway callback이 APPLIED/PENDING_EMPTY/PENDING_WITH_DATA를 같은 정책으로 차단한다.
- V11 MySQL에 position 데이터가 있는 통합 테스트에서 V12 실행 전 차단, 기존 row 보존, V12 이력 미생성을 검증했다.
- 이메일 비활성 시 SMTP/EmailSender/listener/service bean이 없고, 활성화 시 발신자와 SMTP 설정을 fail-fast 검증한다.
- 공개 Actuator는 liveness/readiness GET으로 제한하고 Batch management port는 내부 9081로 분리했다.
- Phase 1 전체 단위 테스트는 API 290 + Batch 176 + Email 7 = 473개가 모두 성공했다.
- API integration은 91개 중 90개 성공, 기존 JWT blacklist 미구현 테스트 1개는 계획된 Phase 5 항목으로 disabled 상태다.
- shell syntax, V12 분류 script, `git diff --check`가 성공했다.
- 스펙 리뷰와 코드 리뷰는 BLOCKER/MAJOR 없이 PASS했다. 리뷰 중 발견한 workflow fail-stop, 안전하지 않은
  JWT template placeholder, Boot 기본 SMTP auto-configuration 우회 경로를 수정하고 재검증했다.

## Phase 2 실행 기록

- offline cache에 없던 Kotlin DSL convention artifact를 다운로드하지 않고, `java-gradle-plugin` binary convention
  3종(`kotlin-library`, `spring-library`, `spring-boot-application`)으로 build-logic을 구성했다.
- `domain`, `infrastructure:{common,api,batch}`, `architecture-tests` 모듈을 추가하고 API/Batch는 Domain을
  compile 의존, 해당 Infrastructure와 logging을 runtime 의존으로 연결했다.
- root 전역 Spring/Jackson/Testcontainers/KAPT 의존과 container task-disable hack을 제거하고 각 모듈이 실제
  사용하는 의존성을 직접 선언했다. 미사용 QueryDSL/KAPT/`QueryDslConfig`도 제거했다.
- Kotlin 2.0/JVM 21, Docker API 1.44, JaCoCo 0.8.15와 공통 Test 정책을 convention의 단일 설정으로 통합했다.
- Domain JPA no-arg/all-open, Infrastructure `AutoConfiguration.imports`, API/Batch thin JAR 소비를 테스트했다.
- Architecture Test 11개가 Domain forbidden, Infrastructure -> apps 금지, 실제 main class-count guard,
  API bytecode 36개/Batch bytecode 31개 exact debt edge와 Gradle dependency snapshot을 검증한다.
- Kotlin const/inline으로 bytecode에서 사라지는 의존도 놓치지 않도록 실제 source root를 함께 검사하며,
  API source 27개/Batch source 20개 debt를 exact 고정하고 Domain source debt는 0개로 강제한다.
- forbidden 기술군은 JDBC/Redis/Redisson/HTTP/WebSocket/Security/JWT/SMTP/Micrometer/Jackson/Hibernate를
  계층별로 구분하고, Domain direct external dependency는 허용된 JPA/Context/Tx/Data Commons 4종만 exact 허용한다.
- logging MVC interceptor 소유권을 supports auto-configuration으로 옮기고 no-MVC Batch backoff를 검증했다.
- Boot JAR 이름을 `app.jar`로 고정하고 Docker가 exact artifact만 복사하도록 변경했다. `.dockerignore`와 신규
  모듈 build metadata cache layer를 추가해 thin JAR/host 산출물 충돌 및 dependency cache fail-open을 제거했다.
- `batch-schema.sql`의 currency/fx_rate drift를 수정했다. Repository 32개와 scheduler E2E 24개가 성공했고,
  Batch integration 64개 중 Phase 3 owner인 retention 2개만 실패했다.
- shell 환경 보정 없이 API integration 91개 중 90개 성공/기존 승인 disabled 1개, Batch Testcontainers 기동에 성공했다.
- 최신 root `compileKotlin test`가 성공했고 unit/architecture 합계 505개가 모두 성공했다. API/Batch `bootJar`와
  Dockerfile 정적 검사도 성공했으며 두 `app.jar`에서 Boot main class를 확인했다.
- local JaCoCo line baseline은 API 93.54%, Batch 92.94%, 논리 Domain 96.52%, 논리 Application 91.58%,
  API+Batch+Domain overall 93.27%로 현재 threshold deficit은 0이다. Domain 코드는 Phase 3 이동 전 API package를
  포함해 계산했으며, required CI workflow가 아직 없으므로 Phase 9 CI의 후보 SHA 결과를 최종 권위값으로 사용한다.
- 기존 Domain data class copy visibility 경고 4건은 계획대로 Phase 3 owner가 제거한다.
- 스펙 리뷰와 코드 리뷰에서 발견한 edge 단위 allowlist, source inline 우회, forbidden 기술군 누락, Domain 외부
  의존 누락, Docker dual-JAR 문제를 모두 수정했고 최종 리뷰는 BLOCKER/MAJOR/MINOR 없이 PASS했다.

## 승인 및 재개 상태

2026-07-14 사용자 결정: 운영/스테이징 없음, 불명확한 로컬 timestamp는 변환하지 않는 추천안 채택,
스펙 리뷰 보정안 전체 승인. Phase 0~1 commit/push를 완료했고 Phase 2 검증·리뷰를 완료해 commit/push한다.
