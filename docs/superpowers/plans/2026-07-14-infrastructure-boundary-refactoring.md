# Premium Spread Infrastructure Boundary & Reliability Refactoring Plan

> 작성일: 2026-07-14
> 기준 브랜치: `dev`
> 작업 브랜치: `refactor/infrastructure-boundary`
> 참조 모델: backend `refactor/infrastructure-boundary` 계획이 완료된 아키텍처
> 실행 방식: Phase별 검증 → commit → push, 이전 Phase가 green일 때만 다음 Phase 진행

## 0. 문서 목적

이 문서는 `premium-spread`의 API/Batch 내부에 혼재한 Domain, Application, Persistence, Cache,
외부 연동, Security 책임을 명시적인 Gradle 모듈 경계로 재구성하고, 기존 분석에서 확인한
보안·데이터·알림·시간·관측성·CI 문제를 함께 해소하기 위한 end-to-end 실행 계획이다.

최종 결과는 다음을 동시에 만족해야 한다.

1. API와 Batch가 하나의 Domain 모델과 계산 정책을 공유한다.
2. HTTP Controller와 Batch Scheduler는 각각 하나의 Application 진입점만 호출한다.
3. Redis/JPA/JDBC/WebClient/WebSocket/JWT/SMTP 구현은 Infrastructure에 격리된다.
4. 운영 환경은 기본 secret, `ddl-auto=update`, 공개 관리 엔드포인트 등으로 기동할 수 없다.
5. 사용자 이메일 알림은 프로세스 메모리 이벤트가 아니라 durable delivery로 처리된다.
6. 기존 REST 계약, WebSocket 수집, 집계, Position PnL 기능은 의도된 변경 외에 회귀하지 않는다.
7. 최종 push 시 이 문서의 완료 조건이 자동 테스트 또는 명시적 운영 검증으로 증명된다.

이 문서는 단순 방향 문서가 아니다. 각 Phase의 파일 소유권, 구현 순서, 검증 명령,
commit/push 경계를 정의한 실행 문서다.

### 0.1 제외 범위

- Kafka 신규 도입과 범용 Inbox/Outbox 플랫폼 구축
- JPA Entity와 순수 Domain Entity의 이중 모델 도입
- Trading V2 실계좌 연동과 Trading V3 자동매매 기능
- Redis/MySQL/WebSocket 기술 스택 교체
- Web UI 디자인 개편

Web UI 자체 리팩터링은 제외하지만 API 계약, Cookie, 상태 코드가 변경되면 같은 Phase에서
`apps/web`의 client/type/error handling을 갱신하고 `npm run lint`, `npm run build`를 통과시킨다.

---

## 1. 현재 기준선

### 1.1 저장소 상태

- 현재 관찰 브랜치: `chore/issue-25-oci-launch-retry`
- 실제 작업은 현재 브랜치가 아니라 최신 `origin/dev`에서 별도 worktree로 시작한다.
- 기존 사용자 변경 `.ai/instructions.md`와 `.claude/worktrees/`는 건드리지 않는다.
- 본 계획 파일 이외의 제품 코드는 계획 작성 단계에서 변경하지 않는다.

### 1.2 검증 기준선

2026-07-13 기준 다음을 확인했다.

- `bash gradlew test --rerun-tasks --offline --no-daemon`: 성공
- 기본 테스트: 443개, failure/error 0
- 소요 시간: 5분 39초
- 소스상 `@Tag("integration")`: 13개 (`test` 기본 실행에서는 제외)
- 소스상 `@Disabled`: 1개
- `ktlintCheck`: 오프라인 캐시에 ktlint artifact가 없어 미검증
- Kotlin 2.0 + KAPT 1.9 fallback 경고 존재
- Kotlin 2.1에서 오류가 될 private constructor data class `copy()` 경고 존재
- 테스트 코드에 nullable Java type mismatch 경고 존재
- CI 검증 workflow 없이 main push 후 배포 workflow만 존재

### 1.3 확인된 구조 문제

- Domain이 `apps/api` 내부에 있어 Batch가 같은 Domain 계산을 직접 사용할 수 없다.
- Batch Application이 Redis/JDBC/WebClient/Micrometer/Alert 구현에 직접 의존한다.
- `MemberController`, `TickerController`, `AuthController`가 Facade 경계를 따르지 않는다.
- API/Batch에 cache/repository/client 구현이 분산되어 동일 데이터의 표현과 정책이 중복된다.
- Root Gradle이 모든 subproject에 Spring Boot, starter, Jackson, KAPT를 일괄 적용한다.
- QueryDSL APT가 설정되어 있지만 생성 Q 타입을 실제 코드에서 사용하지 않는다.
- Soft delete 필터가 Repository별 수작업이라 `findById`에서 누락된다.
- Position 저장 시 전체 OPEN Position을 조회하여 Redis count를 갱신한다.
- DB commit 전 Redis를 갱신하여 DB와 캐시의 원자성이 보장되지 않는다.

### 1.4 확인된 운영·보안 문제

- `/api/v1/auth/refresh`가 Security public matcher에 없다.
- 운영에서도 사용할 수 있는 기본 JWT secret이 존재한다.
- Refresh Token 서버 측 저장/회전/재사용 탐지가 없다.
- `modules/jpa/jpa.yml` 기본 `ddl-auto`가 `update`이며 Batch prd가 덮어쓰지 않는다.
- V12 migration이 `position`을 `TRUNCATE`한다.
- `alert.email.from` 빈 기본값이 있어 이메일 기능이 의도치 않게 활성화될 수 있다.
- `/actuator/**`가 공개되고 Batch health detail이 항상 노출된다.
- Flyway migration resource가 API에만 있어 스키마 소유권과 Batch 독립 배포 규칙이 불명확하다.
- `Instant`, `ZonedDateTime`, `LocalDateTime`, `ZoneId.systemDefault()`가 혼재한다.
- Spring in-memory event + `@Async` 이메일은 프로세스 종료 시 유실될 수 있다.
- 배포 workflow에 선행 build/test/security gate와 자동 rollback이 없다.

---

## 2. 고정 아키텍처 결정

이 절의 결정은 구현 중 임의 변경하지 않는다. 변경이 필요하면 먼저 이 문서를 수정하고
별도 설계 commit으로 합의한다.

### AD-01. Pragmatic JPA Domain

- Domain Entity와 JPA Entity를 이중 모델로 분리하지 않는다.
- Domain에는 JPA annotation, Spring Context, Spring Transaction, Spring Data auditing annotation
  의존만 허용한다.
- Domain에는 Redis, JDBC, WebClient, WebSocket, Jackson, Servlet, Security, JWT, SMTP,
  Micrometer 구현 의존을 허용하지 않는다.
- Domain 계산은 가능한 순수 함수로 유지하며 현재 시각은 인자로 받는다.

### AD-02. 단일 논리 Infrastructure 계층, 실행 앱별 물리 분리

backend 참조 모델의 “단일 Infrastructure 경계” 원칙은 유지하되 하나의 거대한 jar로 만들지 않는다.
API와 Batch의 runtime classpath가 다르므로 다음 세 모듈로 분리한다.

- `infrastructure:common`: JPA/JDBC/Redis/Flyway 등 공유 Adapter
- `infrastructure:api`: JWT/Spring Security/Refresh Session 등 API 전용 Adapter
- `infrastructure:batch`: WebSocket/WebClient/SMTP/Batch 실행 지원 Adapter

### AD-03. Application 진입점 강제

- HTTP: `Controller -> Facade -> Domain Service/Port`
- Batch: `Scheduler -> JobFacade(or Job) -> Domain Port`
- WebSocket: `Batch Lifecycle -> MarketTickerStream Port -> Stream Adapter -> TickerSink callback`
- Controller/Scheduler는 Repository, Cache, Client 구현을 직접 호출하지 않는다.
- Controller의 public method는 Request를 Criteria로 변환하고 Result를 Response로 변환한다.
- Facade public contract에는 Domain Entity를 노출하지 않는다.

### AD-04. Port 위치

- 비즈니스 의미가 있는 outbound contract는 `domain`에 둔다.
- 실행 흐름 조합은 각 앱의 `application`에 둔다.
- 기술 구현체는 `infrastructure:*`에 둔다.
- Infrastructure는 `apps:*`에 의존하지 않는다.
- 앱 전용 callback이 필요하면 Domain의 최소 contract로 정의하고 기술 타입을 노출하지 않는다.
- `MarketTickerStream.start(TickerSink)` contract는 Domain에 두고 Batch Application이 start를 호출한다.
  Infrastructure 구현은 전달받은 `TickerSink`만 호출하므로 앱에 compile 의존하지 않는다.

### AD-05. 메시징·전달 보장

- 현재 Kafka가 없으므로 Kafka/Inbox를 신규 도입하지 않는다.
- WebSocket 시세는 latest-value 성격이며 일부 중간 이벤트 유실을 허용한다.
- Premium은 원천 시세와 환율로 재계산 가능하므로 매초 Outbox에 적재하지 않는다.
- 사용자 이메일 알림만 MySQL durable delivery/outbox를 사용한다.
- 이메일은 SMTP 특성상 exactly-once를 주장하지 않는다.
- 목표 보장은 “DB에 등록된 delivery의 at-least-once 시도 + 유실 없는 재시도”이며,
  SMTP 성공 후 DB mark 실패 시 중복 가능성을 문서화한다.

### AD-06. 시간 정책

- 저장과 Domain 교환 타입은 `Instant`를 기본으로 한다.
- DB `DATETIME(6)`은 UTC 의미로 저장한다.
- Hibernate JDBC timezone은 UTC로 고정한다.
- JDBC URL/session timezone도 UTC로 고정하고 `Instant -> DATETIME(6) -> Instant` 왕복 통합 테스트로
  9시간 offset이 없음을 검증한다.
- 일/시간 집계의 업무 시간대는 `aggregation.zone`으로 명시하며 기본값은 `Asia/Seoul`이다.
- `ZoneId.systemDefault()`, 직접 `Instant.now()`, `LocalDateTime.now()` 사용을 제거한다.
- 현재 시각은 `Clock` 또는 호출자가 전달한 `Instant`로 얻는다.
- Entity의 created/updated audit은 Spring Data Auditing과 Clock 기반 `DateTimeProvider`로 처리한다.

### AD-07. Migration 소유권

- migration SQL은 `infrastructure:common`이 소유한다.
- API가 배포 시 Flyway 실행의 유일한 애플리케이션 owner가 된다.
- Batch는 모든 프로필에서 `spring.flyway.enabled=false`다.
- 배포 순서는 migration/API readiness 확인 후 Batch 기동이다.
- 이미 적용된 migration 파일은 checksum 보호를 위해 수정하지 않는다.

### AD-08. Cache 정합성

- cache-aside/fallback 정책은 Infrastructure Adapter 내부에 둔다.
- DB write transaction 안에서 Redis 성공을 DB commit 조건으로 사용하지 않는다.
- 캐시 갱신은 after-commit 또는 durable invalidation/delivery로 처리한다.
- cache miss, parse failure, stale data의 의미를 Port 반환 타입으로 구분한다.

---

## 3. 최종 모듈 및 패키지 구조

```text
premium-spread/
├── domain/
│   └── src/main/kotlin/io/premiumspread/domain/
│       ├── common/
│       │   ├── BaseEntity.kt
│       │   ├── DomainException.kt
│       │   └── time/
│       ├── market/
│       │   ├── MarketPair.kt
│       │   ├── ticker/
│       │   └── exchangerate/
│       ├── premium/
│       │   ├── Premium.kt
│       │   ├── PremiumPolicy.kt
│       │   ├── PremiumRepository.kt
│       │   └── PremiumSnapshot.kt
│       ├── position/
│       ├── member/
│       ├── notification/
│       │   ├── NotificationSubscription.kt
│       │   ├── NotificationDelivery.kt
│       │   └── NotificationDeliveryRepository.kt
│       └── auth/
│           ├── TokenIssuer.kt
│           └── RefreshSessionStore.kt
│
├── infrastructure/
│   ├── common/
│   │   └── src/main/kotlin/io/premiumspread/infrastructure/
│   │       ├── persistence/jpa/{member,position,premium,ticker,notification}
│   │       ├── persistence/jdbc/{aggregation,exchangerate,notification}
│   │       ├── cache/redis/{ticker,premium,fx}
│   │       ├── migration/
│   │       └── config/CommonInfrastructureAutoConfiguration.kt
│   ├── api/
│   │   └── src/main/kotlin/io/premiumspread/infrastructure/api/
│   │       ├── security/
│   │       ├── auth/RedisRefreshSessionStore.kt
│   │       └── config/ApiInfrastructureAutoConfiguration.kt
│   └── batch/
│       └── src/main/kotlin/io/premiumspread/infrastructure/batch/
│           ├── exchange/{binance,bithumb,exchangerate}
│           ├── websocket/
│           ├── notification/email/
│           ├── job/{lock,metrics,alert}
│           └── config/BatchInfrastructureAutoConfiguration.kt
│
├── apps/
│   ├── api/src/main/kotlin/io/premiumspread/
│   │   ├── interfaces/api/{auth,member,notification,position,premium,ticker}
│   │   ├── application/{auth,member,notification,position,premium,ticker}
│   │   └── config/
│   ├── batch/src/main/kotlin/io/premiumspread/
│       ├── interfaces/scheduling/
│       ├── application/job/{aggregation,fx,premium,ticker,notification}
│       └── config/
│   └── web/                       # UI 구조는 유지, API 계약 변경만 동기화
│
├── architecture-tests/            # 전체 main output을 읽는 전역 ArchUnit/Gradle 경계 검증
├── modules/{jpa,redis}/
└── supports/{logging,monitoring,email}/
```

### 3.1 최종 Gradle 의존성

```text
apps:api
  implementation -> domain
  runtimeOnly     -> infrastructure:common, infrastructure:api
  runtimeOnly     -> supports:logging, supports:monitoring

apps:batch
  implementation -> domain
  runtimeOnly     -> infrastructure:common, infrastructure:batch
  runtimeOnly     -> supports:logging, supports:monitoring

infrastructure:common
  implementation -> domain, modules:jpa, modules:redis

infrastructure:api
  implementation -> domain, infrastructure:common, modules:redis

infrastructure:batch
  implementation -> domain, infrastructure:common, modules:redis, supports:email

domain
  implementation -> jakarta.persistence-api, spring-context, spring-tx, spring-data-commons(auditing only)
  plugins        -> kotlin-jvm, kotlin-spring, kotlin-jpa

architecture-tests
  testImplementation -> domain, infrastructure:common, infrastructure:api, infrastructure:batch,
                        apps:api, apps:batch
```

예외적으로 앱의 interfaces가 사용하는 Spring MVC/Web starter와 Scheduler annotation은 앱 compile
dependency로 유지한다. 앱은 Infrastructure 구현 클래스를 compile 시점에 참조하지 않는다.
`supports:logging`의 MVC interceptor 등록은 supports auto-configuration이 소유해 앱이 runtimeOnly 상태에서
구현 타입을 직접 import하지 않게 한다. Architecture test는 각 대상 package에서 검사 class 수가 0보다
큰지 먼저 단언해 빈 classpath의 거짓 통과를 막는다.

---

## 4. 최종 완료 조건

최종 완료 판정은 아래 조건이 모두 체크되기 전에는 수행하지 않는다. CI 실행을 위해 후보 commit을
먼저 push할 수 있으며, 최종 SHA의 required CI가 green인 것을 확인한 시점에만 완료로 판정한다.

### 4.1 Architecture

- [ ] **A-01** `domain`이 독립 Gradle 모듈이며 API와 Batch가 동일 모듈을 참조한다.
- [ ] **A-02** `apps/api` 아래 `domain`, `infrastructure` 패키지가 존재하지 않는다.
- [ ] **A-03** `apps/batch` 아래 `cache`, `client`, `repository`, `infrastructure` 구현 패키지가 존재하지 않는다.
- [ ] **A-04** Domain에는 JPA/Spring Context/Transaction/Data auditing 외 기술 framework import가 없다.
- [ ] **A-05** API Application에는 Infrastructure/Redis/JDBC/WebClient/Security 구현 import가 없다.
- [ ] **A-06** Batch Application에는 Redis/JDBC/WebClient/WebSocket/Micrometer/SMTP 구현 import가 없다.
- [ ] **A-07** 모든 REST Controller는 Application Facade 하나만 주입한다.
- [ ] **A-08** 모든 Scheduler는 Application Job 진입점 하나만 주입한다.
- [ ] **A-09** Facade public 입출력은 Criteria/Result이며 Domain Entity를 노출하지 않는다.
- [ ] **A-10** Infrastructure는 `apps:api`, `apps:batch`에 compile 의존하지 않는다.
- [ ] **A-11** 앱의 Infrastructure 의존은 `runtimeOnly`이며 각 실행 앱에 필요한 Adapter만 포함된다.
- [ ] **A-12** Cache fallback/write 정책은 Infrastructure 내부에만 있다.
- [ ] **A-13** 위 규칙이 ArchUnit과 Gradle dependency test로 자동 검증된다.

### 4.2 Domain 및 데이터

- [ ] **D-01** Premium 계산식과 정밀도 정책이 Domain의 한 구현으로 통합된다.
- [ ] **D-02** API/Batch 동등성 복제 테스트 대신 동일 Domain 정책을 직접 테스트한다.
- [ ] **D-03** `MarketPair`가 symbol + 한국 거래소 + 해외 거래소를 표현한다.
- [ ] **D-04** AUTO Position/PnL 조회가 Position의 MarketPair와 일치하는 snapshot만 사용한다.
- [ ] **D-05** Entity는 JPA에 부적합한 data class가 아니며 영속 identity equality 규칙을 가진다.
- [ ] **D-06** Value Object의 private constructor/copy 경고가 없고 Kotlin 2.0 compiler warning이 0건이다.
  Kotlin 2.1 호환성은 별도 CI matrix가 실행 가능한 경우 추가 증거로 기록하되 로컬 완료 조건과 혼동하지 않는다.
- [ ] **D-07** 모든 active 조회가 soft-deleted row를 제외한다.
- [ ] **D-08** 미사용 global Position Redis cache가 제거되고 회원 Position summary는 DB count query를 사용한다.
- [ ] **D-09** DB rollback 시 Redis에 commit되지 않은 상태가 확정값으로 남지 않는다.
- [ ] **D-10** migration SQL은 Infrastructure common에 있고 API만 Flyway를 실행한다.
- [ ] **D-11** Batch prd가 schema를 자동 수정할 수 없다.
- [ ] **D-12** V12가 데이터 있는 운영 DB에 자동 적용되지 않도록 preflight가 차단한다.
- [ ] **D-13** 이미 적용된 Flyway migration checksum을 변경하지 않는다.
- [ ] **D-14** Premium cache/aggregation key와 DB unique key가 MarketPair를 포함해 거래소 쌍 간 충돌이 없다.

### 4.3 Security 및 설정

- [ ] **S-01** `/api/v1/auth/refresh`는 Refresh Cookie만으로 접근 가능하고 E2E 테스트가 있다.
- [ ] **S-02** 운영에서 기본 JWT secret 또는 빈 secret으로 기동할 수 없다.
- [ ] **S-03** JWT issuer/audience/Access TTL/Refresh TTL이 검증된다.
- [ ] **S-04** Refresh Token 원문을 저장하지 않고 hash/jti만 저장한다.
- [ ] **S-05** Refresh 회전은 Redis Lua/CAS로 원자적이며 이전 Token 재사용을 거부한다.
- [ ] **S-06** 한 회원의 Refresh Session 정책이 명시되고 로그인/회전/로그아웃에 일관되게 적용된다.
- [ ] **S-07** 로그아웃 후 Refresh는 거부되며 Access Token은 만료까지 유효하다는 계약을 테스트/문서화한다.
- [ ] **S-08** local/prd Cookie secure, SameSite, domain/path 정책이 설정으로 분리된다.
- [ ] **S-09** public endpoint 목록은 하나의 SSOT를 Security와 테스트가 공유한다.
- [ ] **S-10** CORS는 명시된 origin/method/header만 허용한다.
- [ ] **S-11** prd Swagger/API docs가 모두 비활성화된다.
- [ ] **S-12** Actuator public 노출은 liveness/readiness로 제한된다.

### 4.4 Notification delivery

- [ ] **N-01** Premium 조건 충족 결과가 durable `notification_delivery` row로 기록된다.
- [ ] **N-02** 같은 subscription/cooldown window는 DB unique key로 한 번만 enqueue된다.
- [ ] **N-03** claim은 MySQL 8 `SELECT ... FOR UPDATE SKIP LOCKED`를 사용하는 짧은 DB transaction이다.
- [ ] **N-04** claim마다 고유 `claimToken`을 발급하고 완료/재시도 update가 fencing 조건을 사용한다.
- [ ] **N-05** stale PROCESSING delivery가 복구된다.
- [ ] **N-06** retry/backoff/max-attempt/FAILED 상태가 설정 가능하고 테스트된다.
- [ ] **N-07** SMTP 전송에는 stable `Message-ID`/deliveryId가 포함된다.
- [ ] **N-08** 운영자가 FAILED를 조회하고 redrive할 수 있는 절차가 있다.
- [ ] **N-09** 기존 in-memory `ApplicationEvent + @Async` 전달 경로가 제거된다.
- [ ] **N-10** 이메일 비활성화 시 subscription 조회와 SMTP bean이 동작하지 않는다.
- [ ] **N-11** 이메일 exactly-once를 주장하지 않고 중복 가능성과 보장 수준을 문서화한다.

### 4.5 Time, 운영 및 관측성

- [ ] **O-01** business code에서 `ZoneId.systemDefault()`와 직접 now 호출이 제거된다.
- [ ] **O-02** Entity audit와 soft delete 시간이 `Instant`/UTC로 일관된다.
- [ ] **O-03** aggregation zone이 설정과 메트릭에 명시된다.
- [ ] **O-04** 운영/스테이징 기존 DB timestamp의 의미가 확인되거나, 환경이 `NOT_DEPLOYED`이면
  비운영 로컬 데이터를 변환하지 않고 UTC 전환 후 재생성하는 guard/runbook이 있다.
- [ ] **O-05** API/Batch readiness가 실제 필수 dependency 상태를 반영한다.
- [ ] **O-06** Slack/외부 Alert 전송 timeout이 Scheduler lock을 장시간 점유하지 않는다.
- [ ] **O-07** HTTP correlation ID가 응답·로그에 전파된다.
- [ ] **O-08** Job, WebSocket, cache, notification delivery 핵심 메트릭이 고정 이름 + bounded tag를 사용한다.
- [ ] **O-09** 설정값은 `@ConfigurationProperties + @Validated`로 fail-fast한다.
- [ ] **O-10** Hikari pool 크기와 timeout을 환경변수로 제어하고 안전한 기본값을 가진다.
- [ ] **O-11** Docker Compose가 dependency healthcheck와 migration/API/Batch 기동 순서를 가진다.
- [ ] **O-12** 배포 실패 시 이전 이미지 또는 이전 commit으로 복구하는 runbook이 있다.

### 4.6 Quality, CI, 문서

- [ ] **Q-01** 사용되지 않는 QueryDSL/KAPT 설정이 제거되고 KAPT fallback 경고가 없다.
- [ ] **Q-02** Kotlin compiler warning과 테스트 nullable mismatch 경고가 0건이다.
- [ ] **Q-03** `@Disabled` 테스트가 0건이거나 승인된 사유/만료일을 가진 allowlist에 있다.
- [ ] **Q-04** unit/architecture/integration test task가 분리되고 각각 timeout을 가진다.
- [ ] **Q-05** `test`, API integration, Batch integration, lint, architecture, build가 모두 통과한다.
- [ ] **Q-06** JaCoCo aggregate line coverage 70%, Domain 85%, Application 80% 이상이다.
- [ ] **Q-07** DTO/config/generated code exclusion 목록이 문서화되고 임의 확대되지 않는다.
- [ ] **Q-08** ktlint와 detekt가 CI required gate다.
- [ ] **Q-09** CVSS 7 이상 미해결 dependency 취약점이 없고, false positive suppression은 owner/근거/만료일을 가진다.
- [ ] **Q-10** dependency locking/verification과 Gradle Wrapper checksum 검증이 적용된다.
- [ ] **Q-11** main 배포 job은 CI 성공과 environment approval 없이는 실행되지 않는다.
- [ ] **Q-12** 배포는 branch의 정확한 commit SHA/image tag를 사용한다.
- [ ] **Q-13** `.ai/architecture`, `.ai/PROJECT_STATUS.md`, AGENTS/개발 지침, 운영 runbook이 코드와 일치한다.
- [ ] **Q-14** 최종 worktree가 계획된 파일 외 변경 없이 clean하다.
- [ ] **Q-15** API 계약 변경이 반영된 Web 앱의 lint와 production build가 통과한다.

---

## 5. 공통 실행 프로토콜

### 5.1 Worktree와 브랜치

```bash
git fetch origin
git worktree add ../premium-spread-infrastructure-boundary \
  -b refactor/infrastructure-boundary origin/dev
cd ../premium-spread-infrastructure-boundary
```

- 원본 dirty worktree에서 구현하지 않는다.
- 계획 문서가 아직 `origin/dev`에 없다면 원본 workspace의 동일 파일을 새 worktree에 추가하고
  SHA-256이 같은지 확인한 뒤 Phase 0 commit에 포함한다.
- remote branch가 이미 있으면 새로 만들지 말고 상태를 확인한 뒤 resume한다.
- `git reset --hard`, 강제 checkout, force push를 사용하지 않는다.
- 다른 작업의 migration/version 변경이 들어오면 최신 `origin/dev`를 반영하고 migration 번호를 재계산한다.

### 5.2 Phase gate

각 Phase는 다음 순서를 고정한다.

1. Phase 범위 테스트를 RED 또는 characterization으로 먼저 작성한다.
2. 구현한다.
3. 범위 테스트와 전체 기본 테스트를 실행한다.
4. `git diff --check`, `git status --short`, 변경 파일 목록을 검토한다.
5. 해당 Phase 파일만 stage한다.
6. 한국어 commit message와 변경 요약·검증 결과를 담은 한글 bullet 본문으로 commit한다.
7. 검증 결과를 progress 문서에 기록한다. commit SHA는 자기참조를 피하기 위해 다음 Phase 첫 commit이나
   별도 progress commit에서 이전 Phase SHA로 기록한다.
8. 동일 feature branch에 push한다.
9. push 성공 후에만 다음 Phase로 이동한다.

Phase gate 실패 시 commit/push하지 않는다. 단, Phase 0에서 재현한 기존 baseline failure는 사용자가
진행을 승인하고 `baseline-known-failures`에 원인, 해결 Phase, owner가 기록된 경우에만 Phase 0 문서
commit을 허용한다. 이후 해당 해결 Phase gate는 반드시 green이어야 한다. 이미 commit 후 push 전에
실패를 발견하면 수정 commit을 같은 Phase에 추가하고 검증 후 함께 push한다. 공개된 commit을
amend/force-push하지 않는다.

### 5.3 공통 검증 명령

```bash
bash gradlew compileKotlin --offline --no-daemon
bash gradlew test --offline --no-daemon
git diff --check
```

이 문서의 Gradle 명령은 `CI 격리 runner`로 표시된 블록 외에는 모두 `--offline` 실행을 원칙으로 한다.
새 dependency가 필요한 Phase는 dependency 및 checksum 변경을 명시적으로 review한다. 구현 실행 환경은
Gradle 신규 다운로드를 금지하므로 로컬 cache에 없는 ktlint engine, detekt, JaCoCo ant, OWASP 도구는
다운로드하지 않는다. 해당 검증은 GitHub CI의 격리 runner에서 실행하고 결과 URL/SHA를 완료 증거로
기록한다. 로컬에서는 compiler/test, ArchUnit, source/dependency scan을 대체 gate로 사용한다.

---

## 6. Phase 0 — 기준선 고정 및 운영 데이터 사전 점검

### 목표

구조 변경 전 동작, API 계약, DB migration 적용 상태를 재현 가능한 자료로 남긴다.

### 작업

- [ ] 최신 `origin/dev` 기준 별도 worktree/branch 생성
- [ ] `docs/superpowers/plans/2026-07-14-infrastructure-boundary-refactoring.md` 포함 여부 확인
- [ ] `.ai/planning/infrastructure-boundary/baseline.md` 생성
- [ ] `.ai/planning/infrastructure-boundary/progress.md` 생성(Phase/commit/push/test 결과 누적)
- [ ] `.ai/planning/infrastructure-boundary/findings.md` 생성(중단 조건과 추가 발견사항 기록)
- [ ] 전체 default test 수/시간/경고 기록
- [ ] API/Batch integration test를 Docker 환경에서 실행하고 결과 기록
- [ ] REST OpenAPI 또는 MockMvc contract snapshot 기록
- [ ] route/status/body type과 실행 test 경로를 `.ai/planning/infrastructure-boundary/rest-contract.md`에
  snapshot하고 SHA-256 기록
- [ ] Web `npm ci`, lint, production build 기준선 기록
- [ ] Redis key/TTL/sample payload와 실행 test 경로를
  `.ai/planning/infrastructure-boundary/redis-fixtures.md`에 기록하고 SHA-256 고정
- [ ] Flyway `flyway_schema_history`와 최고 version 기록
- [ ] 비밀값 없는 read-only V12/timestamp 조회를 `.ai/planning/infrastructure-boundary/v12-audit.sql`로
  제공하고 container/volume 식별·실행 방법과 SHA-256 기록
- [ ] 운영/스테이징별 V12 적용 여부와 `position` row 수 확인
- [ ] V12 상태를 아래 넷 중 하나로 기록
  - `APPLIED`: 이미 적용, checksum 변경 금지
  - `PENDING_EMPTY`: 미적용 + position 0건, 명시적 승인 후만 실행
  - `PENDING_WITH_DATA`: 자동 배포 금지, backup/backfill/cutover 계획 필수
  - `NOT_DEPLOYED`: 해당 운영/스테이징 환경 자체가 없음을 사용자/운영자가 확인
- [ ] 기존 timestamp sample을 UTC/KST 양쪽으로 해석한 audit 결과 기록
- [ ] 운영/스테이징이 `NOT_DEPLOYED`이고 로컬 timestamp 의미가 불명확하면 기존 로컬 volume을 자동
  변환하지 않고 보존한 뒤, UTC 전환 후 새 volume/fixture로 재생성한다.
- [ ] 현재 JaCoCo module별 baseline 산출. 단, offline cache에 JaCoCo ant가 없으면 명령/누락 artifact를
  기록하고 Phase 9 CI coverage baseline을 권위 있는 수치로 사용한다.
- [ ] 443개 기준선과 차이가 있으면 원인을 먼저 해결
- [ ] `baseline-known-failures`에 Batch retention 2건, Batch schema fixture drift 23건, Web lint 1건,
  offline quality tool 부재와 npm 취약점을 기록하고 각각 owner/해결 Phase/완료 gate를 지정

### 검증

```bash
bash gradlew test --rerun-tasks --offline --no-daemon
bash gradlew :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run build
git diff --check
```

### Commit / Push

```text
commit: docs: 인프라 경계 리팩터링 기준선 기록
push:   git push -u origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- baseline 문서만 보고 현재 기능/테스트/migration 상태를 재현할 수 있다.
- 운영/스테이징은 사용자 확인으로 `NOT_DEPLOYED`, 로컬은 `APPLIED`로 분류한다.
- 사용자 승인된 baseline-known-failures 외 default test 443개가 green이다.

---

## 7. Phase 1 — 긴급 운영 안전장치

### 목표

대규모 파일 이동 전에 현재 구조에서 즉시 위험한 설정과 인증 결함을 제거한다.

### 대상 파일

- `apps/api/.../SecurityConfig.kt`
- `apps/api/.../AuthController.kt`
- `apps/api/.../JwtTokenProvider.kt`
- `apps/api/src/main/resources/application*.yml`
- `apps/batch/src/main/resources/application*.yml`
- `modules/jpa/src/main/resources/jpa.yml`
- `supports/email/.../EmailAutoConfiguration.kt`
- `apps/batch/.../PremiumThresholdNotification*`
- `docker/*-compose.yml`
- 신규 deployment preflight script/test

### 작업

#### 인증

- [ ] `/api/v1/auth/refresh`를 exact method/path public matcher에 추가
- [ ] Refresh Cookie만으로 Controller 도달하는 E2E test 추가
- [ ] 잘못된/만료된/access-type token으로 refresh 시 401 검증
- [ ] 기본 JWT secret을 local/test profile로만 이동
- [ ] prd secret/issuer/audience/TTL 누락 시 context startup 실패 테스트
- [ ] Swagger UI와 API docs를 prd에서 모두 비활성화

#### Schema

- [ ] 공통 `ddl-auto`를 `validate` 또는 `none`으로 변경
- [ ] local/test만 명시적으로 `create-drop` 또는 Flyway 사용
- [ ] prd 전체에서 `update/create/create-drop` 문자열이 없도록 테스트
- [ ] Batch에 `spring.flyway.enabled=false` 명시
- [ ] API/Batch 컨테이너 시작 전 배포 workflow의 외부 DB preflight를 실행하고, V12 pending + non-empty
  position이면 둘 다 기동하지 않음
- [ ] 수동 API 기동도 Flyway 전용 callback/별도 migration runner가 V12 전에 동일 조건을 검사
- [ ] `PENDING_EMPTY`는 일회성 승인 플래그 없이는 V12를 실행하지 않음
- [ ] `PENDING_WITH_DATA`는 backup -> 안전 변환/복원 -> row/checksum 검증 -> V12 이력 처리 runbook 없이 실행 금지
- [ ] V12 SQL 자체는 수정하지 않음

#### 이메일 활성화

- [ ] `notification.email.enabled=false` 기본값 추가
- [ ] `@ConditionalOnProperty(... havingValue="true")`로 전환
- [ ] enabled=true일 때 from/SMTP 설정 `@Validated` fail-fast
- [ ] disabled일 때 listener/service/EmailSender bean 미등록 context test

#### 관리 엔드포인트

- [ ] public actuator를 `/actuator/health/liveness`, `/readiness`로 제한
- [ ] health detail prd `never` 또는 authorized-only
- [ ] Prometheus endpoint는 내부 network/별도 management port 정책 문서화

### 검증

```bash
bash gradlew :apps:api:test :apps:batch:test :supports:email:test --offline --no-daemon
bash gradlew :apps:api:integrationTest --offline --no-daemon
rg -n 'ddl-auto:\s*(update|create|create-drop)' --glob 'application*.yml' --glob 'jpa.yml'
rg -n 'default-local-secret' apps/api/src/main/resources
```

두 `rg`는 허용된 local/test 위치 외 결과가 없어야 한다.

### Commit / Push

```text
commit: fix: 운영 인증과 스키마 안전장치 강화
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **S-01, S-02, S-03, S-11, S-12, D-11, D-12, D-13, N-10** 충족

---

## 8. Phase 2 — Gradle 모듈 경계와 Architecture Test 기반 생성

### 목표

제품 동작을 바꾸지 않고 최종 모듈을 만들고, 기술 의존성을 모듈 타입별로 분리한다.

### 신규/수정

- `settings.gradle.kts`
- `build.gradle.kts`
- `build-logic/` included build convention plugin 구성
- `domain/build.gradle.kts`
- `infrastructure/common/build.gradle.kts`
- `infrastructure/api/build.gradle.kts`
- `infrastructure/batch/build.gradle.kts`
- `architecture-tests/build.gradle.kts`
- 각 모듈 marker/auto-configuration resources
- 전체 대상 모듈 main output에 의존하는 독립 Architecture test 모듈

### 작업

- [ ] `:domain`, `:infrastructure:common`, `:infrastructure:api`, `:infrastructure:batch` include
- [ ] convention을 `kotlin-library`, `spring-library`, `spring-boot-application`으로 분리
- [ ] library 모듈 BootJar 비활성, app 모듈만 BootJar 활성
- [ ] app의 thin `jar`는 Architecture test용 consumable variant로 활성화하고, `architecture-tests`가
  API/Batch `sourceSets.main.output`을 실제 소비했는지 class-count guard로 검증
- [ ] Jackson/Web/Testcontainers를 모든 subproject에 강제하지 않도록 정리
- [ ] 실제 Q type 사용이 없음을 다시 확인한 후 QueryDSL/KAPT 제거
- [ ] Domain은 최소 JPA/Context/Tx/Data auditing dependency만 선언
- [ ] Domain에 `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`를 적용하고 JPA proxy/no-arg 정책 테스트
- [ ] 앱에 `implementation(domain)` 추가
- [ ] Infrastructure auto-configuration marker와 `AutoConfiguration.imports` 추가
- [ ] `supports:logging`의 MVC interceptor 등록을 auto-configuration으로 이동해 앱의 직접 구현 import 제거
- [ ] 임시 이동 기간 dependency는 TODO와 제거 Phase를 명시
- [ ] `architecture-tests`가 모든 대상 모듈 main output을 읽고 package별 검사 class 수가 1개 이상인지 선행 단언
- [ ] Gradle dependency graph snapshot test 추가
- [ ] root container project의 task-disable hack 제거 또는 convention으로 대체
- [ ] Kotlin 2.0 compiler flag와 JVM 21을 convention에서 단일 관리하고 source warning 0건을 목표로 설정
- [ ] Testcontainers가 Docker Engine 최소 API와 호환되도록 test JVM의 Docker API version을 단일 설정하고
  별도 shell 환경변수 없이 API/Batch integration test가 컨테이너를 시작하는지 검증
- [ ] Phase 2 candidate SHA부터 CI JaCoCo baseline을 산출해 Domain/Application/overall deficit과 보정 owner/Phase를
  progress에 기록하고, 이후 각 소유 Phase gate에서 deficit을 줄임
- [ ] `batch-schema.sql`을 현재 Repository SQL의 `currency`/`fx_rate` 컬럼과 동기화하고 Repository 16건 및
  하위 E2E 7건의 Phase 0 schema fixture drift를 해소

### Architecture test 초안

- Domain forbidden dependency 규칙
- Infrastructure -> apps 금지
- API interfaces -> infrastructure/domain 직접 의존 금지(현재 debt는 명시적 allowlist)
- Batch application -> technical package 직접 의존 금지(현재 debt는 명시적 allowlist)
- allowlist는 이후 Phase마다 감소하며 최종 0건이어야 한다.

### 검증

```bash
bash gradlew projects --offline --no-daemon
bash gradlew compileKotlin test --offline --no-daemon
bash gradlew :domain:dependencies --configuration compileClasspath --offline --no-daemon
bash gradlew :apps:batch:integrationTest --rerun-tasks --offline --no-daemon
```

### Commit / Push

```text
commit: build: 도메인과 인프라 모듈 경계 생성
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- 신규 모듈이 빌드되고 기존 기능 테스트가 통과한다.
- Root 공통 dependency가 모듈 경계를 무력화하지 않는다.
- Batch integration에서 Phase 3 owner인 retention 2건 외 실패가 없다.

---

## 9. Phase 3 — 공통 Domain 추출과 계산·시간 정책 통합

### 목표

API 내부 Domain을 독립 모듈로 옮기고 Batch의 복제 계산/데이터 타입을 동일 Domain 모델로 통합한다.

### 이동 대상

- `apps/api/src/main/kotlin/io/premiumspread/domain/**` -> `domain/...`
- `modules/jpa/.../BaseEntity.kt` -> `domain/common/BaseEntity.kt`
- `apps/batch/.../calculator/PremiumCalculator.kt` -> Domain `PremiumPolicy`로 통합 후 삭제
- Batch `TickerData`, `FxRateData`, cache payload와 Domain snapshot 매핑 정리

### 작업

#### Entity/Value Object

- [ ] Entity data class를 일반 class로 변경
- [ ] 영속 Entity equality/hashCode 정책 정의 및 테스트
- [ ] `Symbol`, `Quote`, `MarketPair` Value Object 생성자/copy 가시성 경고 제거
- [ ] `BaseEntity` 시간을 `Instant`로 통일
- [ ] `delete(now)`, `restore()` 등 시간 의존 동작을 명시적 인자로 변경
- [ ] Spring Data Auditing과 Clock 기반 `DateTimeProvider`로 created/updated 시간을 설정

#### Premium

- [ ] 계산식을 `PremiumPolicy.calculate` 한 곳으로 통합
- [ ] 계산 중간 scale, 저장 scale, API display scale을 각각 명명
- [ ] Batch cache 4자리와 Domain entity 2자리의 의미를 명시적으로 분리
- [ ] 기존 equivalence 복제 테스트 제거, 같은 policy를 API/Batch fixture에서 직접 검증
- [ ] 음수 premium, 0/음수 입력, 극단 FX, rounding boundary test

#### MarketPair와 Position

- [ ] `MarketPair(symbol, koreaExchange, foreignExchange)` 도입
- [ ] `PremiumSnapshot`에 pair와 FX source/observedAt 포함
- [ ] Repository 조회 contract를 symbol 단독에서 pair 기준으로 변경
- [ ] AUTO Position과 PnL이 Position pair와 같은 snapshot을 사용
- [ ] 기존 단일 BTC/Bithumb/Binance API 계약은 default pair mapping으로 호환
- [ ] Premium/Ticker/Fx cache와 aggregation payload의 MarketPair 식별 규칙 정의

#### Port

- [ ] Ticker/Fx/Premium read/write Port 정리
- [ ] Aggregation read/write Port 정리
- [ ] JobLock, JobRunRecorder, OperatorAlert Port 생성
- [ ] TokenIssuer, RefreshSessionStore Port 생성
- [ ] NotificationDelivery Port 생성
- [ ] Domain Repository contract에 Spring Data `Page/Pageable`을 노출하지 않음

#### 시간

- [ ] Facade/Job에 `Clock` 주입
- [ ] Domain 순수 계산에는 `calculatedAt` 전달
- [ ] `aggregation.zone` Value Object/설정 contract 정의
- [ ] system default timezone 의존 테스트를 UTC/KST 양쪽으로 실행
- [ ] `TickerCacheService` retention 기준시각에 `Clock` 또는 명시적 `Instant`를 사용해 Phase 0의
  `TickerCacheServiceScoreTest` 2건 회귀를 수정하고 Batch integration 전체를 green으로 전환
- [ ] JDBC URL/session/Hibernate timezone을 UTC로 고정하고 MySQL `DATETIME(6)` Instant 왕복 통합 테스트 추가

### 검증

```bash
bash gradlew :domain:test :apps:api:test :apps:batch:test --offline --no-daemon
bash gradlew :apps:batch:integrationTest --rerun-tasks --offline --no-daemon
rg -n '^import (com\.fasterxml|org\.springframework\.data\.redis|org\.springframework\.jdbc|org\.springframework\.web|org\.redisson|io\.micrometer)' domain/src/main
rg -n 'ZoneId\.systemDefault|Instant\.now\(|LocalDateTime\.now\(|ZonedDateTime\.now\(' domain/src/main apps/api/src/main apps/batch/src/main
```

허용된 Clock configuration 외 결과가 없어야 한다.
Phase 0의 Batch retention 회귀를 포함한 Batch integration 전체가 green이 아니면 Phase 3를 commit/push하지 않는다.

### Commit / Push

```text
commit: refactor: 공통 도메인과 계산 정책 추출
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **A-01, A-04, D-01~D-06, O-01~O-04** 충족
- **D-14 contract ready**: canonical `MarketPair`, pair 기준 Port/snapshot/payload 식별 규칙과 default pair
  mapping을 완성한다. 실제 Redis v2 write/legacy dual-read cutover와 production DB pair column/backfill/unique
  migration은 migration·cache adapter owner인 Phase 4에서 수행한 뒤 D-14를 최종 완료한다. Phase 3에서
  운영 schema/key cutover를 중복 구현하거나 test fixture만 변경하고 D-14 완료로 표시하지 않는다.

---

## 10. Phase 4 — 공통 Persistence/Redis Infrastructure 통합

### 목표

API와 Batch에 흩어진 JPA/JDBC/Redis 구현과 migration을 `infrastructure:common`으로 모은다.

### 이동/통합

- API `infrastructure/{member,notification,position,premium,ticker,exchangerate}`
- Batch `cache/**`, `repository/**` 중 공유 persistence/cache 구현
- API `db/migration/**`
- `modules/jpa`의 business scan 설정

### 작업

#### JPA/JDBC

- [ ] Domain Repository마다 `Jpa*RepositoryAdapter` 또는 팀 명명 규칙의 구현체 배치
- [ ] Spring Data repository와 Domain port 구현 이름을 구분
- [ ] `JpaConfig`의 잘못된 `com.loopers` package 제거
- [ ] EntityScan/RepositoryScan을 marker class 기반으로 구성
- [ ] native SQL은 Infrastructure에만 존재하도록 이동
- [ ] active-only `findById`, list, exists query 통일
- [ ] 소스상 consumer가 없는 `PositionCacheWriter`, `PositionCacheService`, 관련 Redis key/TTL/test 제거
- [ ] 회원 Position summary에 `countByMemberIdAndStatus` query를 사용해 전체 Entity loading 제거
- [ ] 알려진 unique constraint를 Domain conflict로 매핑하고 알 수 없는 DB 오류는 500으로 전파

#### Redis

- [ ] Ticker/Fx/Premium cache reader/writer 중복 통합
- [ ] Redis key와 TTL SSOT는 `modules:redis` foundation에 유지
- [ ] business payload serializer/version은 Infrastructure가 소유
- [ ] parse failure 시 `Instant.now()` 대체값 사용 금지; miss/corrupt metric 후 명시적 실패
- [ ] cache write는 DB after-commit 또는 별도 invalidation 경로 사용
- [ ] 실제 사용처가 없는 `RedisTemplate<String, Any>`와 generic Jackson serializer 제거
- [ ] pair-aware v2 key(`premium:{korea}:{foreign}:{symbol}:...`) 도입
- [ ] 기존 symbol-only key는 짧은 TTL 동안 dual-read하고 v2 write만 수행한 뒤 제거
- [ ] Redis key cutover 전후 cache miss/fallback 회귀 테스트

#### Migration

- [ ] migration resources를 Infrastructure common으로 이동
- [ ] API만 Flyway enabled, Batch disabled integration test
- [ ] migration 번호 충돌 검사 task 추가
- [ ] destructive SQL (`TRUNCATE`, table drop, no-WHERE delete) 검출 gate 추가
- [ ] V12는 immutable historical exception allowlist + 운영 preflight로만 관리
- [ ] timestamp audit 결과에 따라 신규 forward migration 작성
- [ ] 기존 data를 시간대 변환할 경우 backup/row-count/checksum 검증 포함
- [ ] premium snapshot/aggregation 테이블에 korea/foreign exchange 식별 컬럼 추가
- [ ] 기존 row는 현재 default pair(BITHUMB/BINANCE)로 backfill
- [ ] pair를 포함하도록 unique/index/query를 변경

### 테스트

- JPA adapter Testcontainers MySQL
- Redis adapter Testcontainers Redis
- soft delete 모든 Repository contract test
- transaction rollback + cache consistency integration test
- API Flyway enabled/Batch disabled context test
- Batch integration test는 production Flyway auto-config가 아니라 test fixture로 schema 초기화
- migration from empty DB와 현재 schema upgrade test

### 검증

```bash
bash gradlew :infrastructure:common:test --offline --no-daemon
bash gradlew :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
rg -n 'JdbcTemplate|StringRedisTemplate|RedisTemplate|EntityManager' apps domain --glob '*.kt'
```

최종적으로 앱/domain 결과는 0건이어야 한다. Phase 6까지 Batch 임시 adapter가 남는다면 allowlist와 제거
commit을 progress에 명시한다.

### Commit / Push

```text
commit: refactor: 공통 영속성과 캐시 어댑터 통합
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **A-12, D-07~D-14** 충족

---

## 11. Phase 5 — API Facade 경계와 인증 세션 완성

### 목표

모든 HTTP 진입점을 Facade로 통일하고 Security/JWT/Refresh 구현을 `infrastructure:api`로 격리한다.

### 작업

#### Controller/Facade

- [ ] `MemberFacade`, `TickerFacade`, `AuthFacade` 추가
- [ ] 기존 Position/Premium/Notification Facade contract를 Criteria/Result로 정리
- [ ] Controller의 Domain import 0건
- [ ] Controller당 Facade 주입 1개
- [ ] Request -> Criteria, Result -> Response mapping을 interfaces에 둠
- [ ] Domain Entity를 HTTP Response mapper가 직접 참조하지 않음
- [ ] 201/204/400/404/409/422 상태 contract 명시
- [ ] M0 contract snapshot을 기본 유지하며, body 없는 delete/logout을 204로 바꾸는 경우
  같은 commit에서 `apps/web` client와 contract test를 갱신
- [ ] Auth Cookie/응답/상태 변경을 `apps/web` API client와 error mapping에 동기화
- [ ] Phase 0의 `PositionList.tsx` effect 내 동기 state update lint 실패를 수정하고 Web lint/build를 green으로 전환
- [ ] Facade가 Domain exception을 안정된 Application error로 변환하고 ExceptionHandler는 Application error만 매핑

#### Security Infrastructure

- [ ] JWT issuer를 `TokenIssuer` Adapter로 이동
- [ ] `JwtProperties`, `CookieProperties`, `CorsProperties` validated 설정
- [ ] public endpoint SSOT 생성
- [ ] SecurityConfig와 contract test가 같은 SSOT 사용
- [ ] Premium/Ticker public matcher는 GET만 허용하고 Ticker ingest POST는 인증/내부 경로로 제한
- [ ] refresh/logout 같은 Cookie 기반 endpoint의 CSRF/Origin 검증 정책 추가
- [ ] local cookie secure=false, prd=true 정책 테스트
- [ ] issuer/audience/clock skew 검증
- [ ] API app의 JPA/Redis/Security 구현 compile dependency를 제거하고 Infrastructure를 `runtimeOnly`로 연결

#### Refresh Session

- [ ] Redis에는 refresh 원문이 아닌 HMAC-SHA-256 hash + jti + memberId + expiry + familyId + generation 저장
- [ ] 한 회원당 active session 1개 정책 적용(다중 기기 요구 변경 시 문서 우선 수정)
- [ ] login 시 기존 session 교체
- [ ] refresh 시 Lua/CAS로 old hash 확인 + new hash 교체를 원자 처리하고 previousHash/rotatedAt을 짧게 보존
- [ ] 같은 family의 동시 refresh loser는 grace window 안에서 401로 거부하되 승자의 새 session은 revoke하지 않음
- [ ] grace window 이후 같은 family old token 재사용은 family revoke + 401
- [ ] 이전 login family의 token은 401로 거부하되 현재 login family를 revoke하지 않음
- [ ] logout 시 refresh session revoke + cookie expire
- [ ] Access Token은 blacklist하지 않고 짧은 TTL까지 유효함을 명시
- [ ] Refresh hash 전용 운영 key 누락 시 fail-fast
- [ ] Authentication principal 해석은 interfaces가 Infrastructure principal class를 import하지 않도록 구성

### 필수 테스트

- 회원가입/로그인/내 정보
- cookie-only refresh 성공
- access token 없이 refresh 성공
- access token을 refresh로 제출 시 실패
- 이전 refresh 재사용 실패
- 두 동시 refresh 중 하나만 성공
- 동시 refresh 승자가 받은 새 refresh는 후속 refresh에 성공
- 이전 login family token 재사용이 현재 family를 revoke하지 않음
- logout 후 refresh 실패
- access token은 logout 직후에도 만료까지 유효
- prd secret/cookie/CORS fail-fast
- 모든 Controller architecture rule

### 검증

```bash
bash gradlew :infrastructure:api:test :apps:api:test :apps:api:integrationTest --offline --no-daemon
npm --prefix apps/web run lint
npm --prefix apps/web run build
rg -n '^import io\.premiumspread\.(domain|infrastructure)' apps/api/src/main/kotlin/io/premiumspread/interfaces
rg -n '^import io\.premiumspread\.infrastructure' apps/api/src/main/kotlin/io/premiumspread/application
```

둘 다 0건이어야 한다.

### Commit / Push

```text
commit: refactor: API 파사드 경계와 토큰 회전 완성
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **A-02, A-05, A-07, A-09, S-01~S-10** 충족

---

## 12. Phase 6 — Batch Port 경계와 외부 Adapter 이동

### 목표

Batch Scheduler/Application에서 기술 구현 의존을 제거하고 WebSocket/외부 API/Redis/JDBC를
Infrastructure Adapter로 이동한다.

### 이동 대상

- `apps/batch/client/**` -> `infrastructure:batch/exchange/**`
- `apps/batch/infrastructure/websocket/**` -> `infrastructure:batch/websocket/**`
- 공유 cache reader/writer와 JDBC repository는 `infrastructure:common/**`에 두고, Batch 전용
  time-series/aggregation orchestration adapter는 `infrastructure:batch/**`에 둔다.
  - common 소유: `FxCacheReader/Writer`, `TickerCacheReader/Writer`, `PremiumCacheReader/Writer`,
    `PremiumAggregationCacheReader`, JPA/JDBC repository
  - batch 소유: seconds sampling/retention, minute/hour/day aggregation과 DB-first/cache-second 조합,
    WebSocket ingestion buffer를 구현하는 adapter. 이 구현은 이름에 `CacheService`가 남아 있어도 공유
    CRUD adapter가 아니라 Batch job Port의 기술 구현이므로 `infrastructure:batch`에 둔다.
- `apps/batch/scheduler/**` -> `apps/batch/interfaces/scheduling/**`

### 작업

#### Job Application

- [ ] `FxIngestionJob`은 ExchangeRateProvider/CacheWriter/Repository Port만 의존
- [ ] `PremiumRealtimeJob`은 SnapshotReader/PremiumWriter/ThresholdEvaluator Port만 의존
- [ ] Aggregation Job은 TimeSeriesReader/AggregationWriter Port만 의존
- [ ] `JobExecutor`에서 RedisTemplate/MeterRegistry/AlertService 직접 의존 제거
- [ ] JobLock, JobRunRecorder, OperatorAlert Adapter로 위임
- [ ] job name은 metric name이 아니라 bounded tag로 사용

#### Scheduler

- [ ] Scheduler는 Job 하나와 JobConfig만 주입
- [ ] lock/metric/cache/repository 조합을 Scheduler에서 제거
- [ ] fixedRate/cron/zone/enabled를 ConfigurationProperties로 외부화
- [ ] test profile뿐 아니라 `scheduling.enabled=false`에서 모든 scheduler가 확실히 비활성화
- [ ] 중복 인스턴스 lock/lease/timeout 관계 검증

#### WebSocket/외부 API

- [ ] 기존 idle watchdog, generation fencing, reconnect, metric 동작 보존
- [ ] WebSocket 구현은 Domain `TickerStream`/ingestion callback contract 구현
- [ ] API response DTO와 WebClient를 Infrastructure 밖으로 노출하지 않음
- [ ] Exchange endpoint, symbol, pair 목록을 validated properties로 이동
- [ ] hard-coded BTC/Bithumb/Binance는 default configured pair로 대체
- [ ] FX client timeout/retry/backoff와 retryable status를 명시
- [ ] 외부 호출 중 Scheduler thread를 무기한 block하는 `.get()` 제거
- [ ] Batch app의 JPA/Redis/WebFlux/SMTP 구현 compile dependency를 제거하고 Infrastructure를 `runtimeOnly`로 연결

#### Alert

- [ ] Slack/외부 alert에 connect/read timeout 설정
- [ ] alert 실패가 Job lock lease를 장시간 점유하지 않도록 bounded executor 사용
- [ ] alert queue saturation/drop metric과 fallback log 제공

### 필수 테스트

- Scheduler thin-entry architecture test
- Job unit test는 Port fake만 사용
- WebSocket 기존 reconnect/watchdog 전체 회귀
- Redis/MySQL adapter integration
- duplicate scheduler instance lock test
- external API timeout/retry MockWebServer test
- scheduling disabled context test

### 검증

```bash
bash gradlew :infrastructure:batch:test :apps:batch:test :apps:batch:integrationTest --offline --no-daemon
rg -n 'StringRedisTemplate|JdbcTemplate|WebClient|MeterRegistry|io\.premiumspread\.infrastructure' apps/batch/src/main/kotlin/io/premiumspread/application
rg -n 'cache\.|repository\.|client\.' apps/batch/src/main/kotlin/io/premiumspread/interfaces
```

결과는 0건이어야 한다.

### Commit / Push

```text
commit: refactor: 배치 포트 경계와 외부 어댑터 분리
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **A-03, A-06, A-08, A-10, A-11, O-06** 충족

---

## 13. Phase 7 — Durable Notification Delivery

### 목표

Spring in-memory event와 Redis cooldown에 의존한 이메일 발송을 DB 기반 durable delivery로 교체한다.

### Schema

실행 시점 최고 migration 다음 번호를 사용한다. 현재 기준 예상 번호는 V13이지만 고정하지 않는다.

```text
notification_subscription 변경
- korea_exchange VARCHAR(...) NOT NULL
- foreign_exchange VARCHAR(...) NOT NULL
- revision BIGINT NOT NULL DEFAULT 1

notification_delivery
- id BIGINT PK
- delivery_id CHAR(36) UNIQUE
- subscription_id BIGINT
- event_key VARCHAR(...) UNIQUE
- recipient_email VARCHAR(...) NULL
- subject VARCHAR(...) NULL
- payload TEXT NULL
- status PENDING|PROCESSING|SENT|FAILED
- attempt_count INT
- next_attempt_at DATETIME(6)
- locked_at DATETIME(6) NULL
- locked_by VARCHAR(...) NULL
- claim_token CHAR(36) NULL
- sent_at DATETIME(6) NULL
- last_error VARCHAR(...) NULL
- scrubbed_at DATETIME(6) NULL
- created_at/updated_at DATETIME(6)
```

subscription 생성/수정 시 symbol과 한국/해외 거래소를 canonical `MarketPair`로 저장한다. 기존 subscription은
현재 기본 pair(BITHUMB/BINANCE)로 migration/backfill한다. `event_key`는 최소
`subscriptionId + subscriptionRevision + canonical MarketPair + normalized threshold direction/value + cooldown window start`를
포함해 설정 변경 전후 이벤트와 거래소 쌍이 충돌하지 않게 한다.
threshold/direction/cooldown/MarketPair 중 이벤트 의미에 영향을 주는 필드가 변경될 때마다 revision을 원자 증가시키며,
활성/비활성만 변경하는 경우에도 재활성화 후 과거 window와 충돌하지 않도록 revision을 증가시킨다.

### 상태 전이

```text
PENDING --claim--> PROCESSING --SMTP success--> SENT
   ^                    |
   |                    +--retryable failure--> PENDING(next_attempt_at)
   |                    +--max attempts-------> FAILED
   +--stale recovery----+
```

### 구현 규칙

- [ ] threshold evaluation과 delivery insert는 명시적 DB transaction
- [ ] unique event_key 충돌은 이미 enqueue된 정상 상황으로 처리
- [ ] `NotificationDeliveryTransactionService`를 외부 호출 가능한 별도 bean으로 구성
- [ ] claim/markSent/scheduleRetry/markFailed/recoverStale은 각각 public `REQUIRES_NEW` transactional method
- [ ] poller 내부 self-invocation으로 transaction proxy를 우회하지 않음
- [ ] claim은 MySQL 8 `SELECT ... FOR UPDATE SKIP LOCKED` 사용
- [ ] claim transaction은 외부 SMTP 호출 전에 commit
- [ ] claim마다 UUID `claimToken` 생성
- [ ] markSent/scheduleRetry/markFailed 조건:
  `id + status=PROCESSING + lockedBy + claimToken`
- [ ] update row 0건은 stale ownership metric 후 현재 worker가 더 이상 처리하지 않음
- [ ] `(ceil(batchSize / concurrency) * hardSendDeadline) + dbQueueSafetyMargin < staleThreshold` 검증
- [ ] stale recovery는 timeout 지난 PROCESSING만 PENDING으로 이동
- [ ] 기본값: poll 5초, batch 10건, SMTP timeout 10초, stale 5분, max attempt 5회
- [ ] retry 기본 지연 1분/5분/30분/2시간 + jitter, properties로 override 가능
- [ ] 기본 batch/concurrency/hard deadline/DB margin 조합이 stale threshold보다 작은지 startup validation
- [ ] max attempt 후 FAILED, operator 확인 전 자동 삭제하지 않음
- [ ] stable `Message-ID`에 deliveryId 사용
- [ ] SMTP 성공 후 DB mark 실패 시 중복 가능함을 runbook에 기록
- [ ] FAILED 조회/수동 redrive는 외부 HTTP endpoint가 아닌 인증된 offline CLI/SQL runbook으로만 제공
- [ ] redrive는 새 claimToken을 사용하고 actor/reason/redrivenAt audit 기록
- [ ] 기존 `PremiumUpdatedEvent`, `@Async` listener, Redis cooldown 제거
- [ ] symbol/status/direction을 포함한 index로 active subscription만 조회하고 full table scan 방지
- [ ] SENT의 recipient/subject/payload PII는 30일 후 scrub하되 deliveryId/eventKey/status/시각/audit은
  dedupe와 추적을 위해 보존하고, FAILED PII는 redrive/acknowledge 전 보존하는 retention job
- [ ] scrub 시 PII 세 컬럼을 NULL로 만들고 `scrubbedAt`을 기록하며 eventKey와 delivery audit은 삭제하지 않음

### 필수 통합 테스트

- enqueue transaction rollback 시 delivery 없음
- 같은 event_key 동시 enqueue 한 건만 생성
- 두 poller 동시 claim 중복 없음
- stale worker의 markSent가 새 claim owner 상태를 변경하지 못함
- SMTP failure retry/backoff
- max attempt FAILED
- stale PROCESSING 복구
- slow SMTP가 hard deadline에 근접한 상태에서 stale recovery와 경합해도 현재 claim owner만 상태 변경
- SENT PII scrub 후에도 동일 event_key 재enqueue가 중복으로 처리되고 audit 식별자는 보존
- disabled notification에서 enqueue/poller/SMTP 미동작
- SMTP success + mark failure 시 documented retry behavior

### 검증

```bash
bash gradlew :domain:test :infrastructure:common:test :infrastructure:batch:test --offline --no-daemon
bash gradlew :apps:batch:integrationTest --offline --no-daemon
rg -n 'ApplicationEventPublisher|@Async|PremiumUpdatedEvent|NotificationCooldownStore' apps/batch infrastructure
```

결과는 migration/documentation 예시 외 0건이어야 한다.

### Commit / Push

```text
commit: feat: 사용자 알림 전달을 내구성 큐로 전환
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **N-01~N-11** 충족

---

## 14. Phase 8 — 설정·시간·관측성·배포 신뢰성 완성

### 목표

모듈 이동 후 runtime 설정을 정리하고 운영자가 장애를 탐지·진단·복구할 수 있게 한다.

### 설정

- [ ] 모든 custom 설정을 typed `@ConfigurationProperties`로 이동
- [ ] URL, secret, timeout, pool, scheduler, retry 값 validation
- [ ] local/test/prd profile 역할 표 작성
- [ ] prd에서 local fallback/default secret 사용 불가 startup test
- [ ] Hikari max/min/timeout/lifetime 환경변수화
- [ ] `minimumIdle <= maximumPoolSize` 등 교차 검증
- [ ] custom `datasource.mysql-jpa.main`과 `spring.datasource` 이중 설정을 하나의 SSOT로 통합
- [ ] `datasource.redis`와 `spring.data.redis` 중 실제 사용되지 않는 중복 설정 제거
- [ ] SQL show/logging은 local/test만 허용
- [ ] API/Batch management port/network 노출 정책 분리

### 시간

- [ ] 모든 now 호출 최종 scan
- [ ] aggregation cron에 zone 명시
- [ ] UTC 저장/KST bucket 경계 DST 비대상임을 포함한 test
- [ ] cache timestamp parse 실패 시 데이터 폐기 + metric

### 관측성

- [ ] HTTP correlation ID 수신/생성/응답 header/MDC 전파
- [ ] async/bounded executor에 MDC 전파
- [ ] 구조화 로그의 email/token/password/cookie masking 검증
- [ ] 고정 metric 이름과 허용 tag 목록 정의
- [ ] 최소 metric:
  - HTTP request/error/latency
  - cache hit/miss/corrupt
  - job success/failure/skipped/duration/last-success-age
  - lock not-acquired/error
  - WebSocket connected/reconnect/stale/last-message-age
  - premium calculation skipped/invalid
  - notification pending/processing/sent/retry/failed/stale-ownership
- [ ] tag에 email, memberId, token, exception message 등 unbounded 값 금지
- [ ] API readiness: DB + request critical dependency만 포함
- [ ] Batch readiness: DB/Redis + 필수 ingestion 상태 정책 문서화

### Docker/Deploy

- [ ] API/Batch Docker healthcheck 추가
- [ ] API readiness 후 Batch 기동
- [ ] migration failure 시 Batch 미기동
- [ ] image tag를 commit SHA로 고정
- [ ] `git pull && build` 방식 대신 검증된 image 배포
- [ ] deploy 후 smoke/readiness 실패 시 이전 image rollback
- [ ] Grafana 기본 admin/admin을 local 전용으로 제한
- [ ] 운영 secret은 GitHub Environment secret/secret manager에서만 공급

### 검증

```bash
bash gradlew test --offline --no-daemon
docker compose -f docker/infra-compose.yml config
docker compose -f docker/app-compose.yml config
rg -n 'ZoneId\.systemDefault|Instant\.now\(|LocalDateTime\.now\(|ZonedDateTime\.now\(' \
  apps domain infrastructure modules supports --glob '*.kt'
```

### Commit / Push

```text
commit: feat: 운영 설정과 관측성 및 배포 복구 강화
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **O-01~O-12, S-08~S-12** 충족

---

## 15. Phase 9 — Quality Gate, CI, 문서 SSOT

### 목표

아키텍처와 운영 규칙이 이후 변경으로 되돌아가지 않도록 자동 품질 게이트와 문서를 완성한다.

### Compiler/Lint

- [ ] KAPT/QueryDSL 제거 확인
- [ ] JPA Entity data class 제거
- [ ] private constructor copy visibility 경고 제거
- [ ] 테스트 nullable Java mismatch 수정
- [ ] 프로젝트 source warning 0건
- [ ] ktlint 최신 고정 버전과 CI cache 확인; 로컬 artifact 부재 시 CI 결과를 완료 증거로 사용
- [ ] detekt rule/baseline 설정; 신규 issue 허용 안 함, 로컬 artifact 부재 시 CI에서만 실행
- [ ] project source compile에 `allWarningsAsErrors` 활성화

### Test task

- [ ] Unit, architecture, API integration, Batch integration source set/task 분리
- [ ] 각 task timeout과 leaked thread/resource 검출
- [ ] Testcontainers 재사용 여부와 CI isolation 정책 명시
- [ ] `@Disabled` logout test를 실제 Refresh revoke 계약 test로 교체
- [ ] 테스트가 외부 실거래소/SMTP/Slack에 연결하지 않도록 강제
- [ ] JaCoCo aggregate report 구성 및 CI artifact 게시
- [ ] line gate: overall 70%, Domain 85%, Application 80%
- [ ] exclusion: generated, configuration wiring, DTO only; 목록 문서화

### Security/Supply chain

- [ ] OWASP Dependency-Check **standalone CLI**를 CI에서만 고정 버전/checksum으로 내려받아 실행하고,
  일반 Gradle build/plugin resolution에는 연결하지 않음
- [ ] CVSS 7 이상 fail, suppression에는 근거/owner/만료일 필수
- [ ] Web lockfile 전체에 `npm audit --audit-level=high`를 실행하고 suppression에는 근거/owner/만료일 필수
- [ ] dependency locking 생성
- [ ] Gradle dependency verification metadata 생성/review
- [ ] Wrapper distribution checksum 검증
- [ ] GitHub Actions third-party action immutable version/SHA pin

### CI

workflow는 pull request, `refactor/infrastructure-boundary` push, 수동 SHA 지정 `workflow_dispatch`에서 실행한다.
required jobs:

1. compile + architecture
2. unit + coverage
3. API integration
4. Batch integration
5. ktlint + detekt
6. dependency/security scan
7. Docker image build

Deploy job은 위 job 성공, protected main, environment approval 이후에만 실행한다.
GitHub required checks와 environment protection은 `gh api` 출력 또는 repository settings URL/screenshot을
결과 문서에 기록하며, YAML 존재만으로 설정 완료를 주장하지 않는다.

### 문서

- [ ] `.ai/architecture/ARCHITECTURE_DESIGN.md` 최종 구조/데이터 흐름 갱신
- [ ] `.ai/context/project-overview.md` MarketPair와 알림 보장 수준 갱신
- [ ] `.ai/PROJECT_STATUS.md` migration/known issue 상태 갱신
- [ ] `.ai/instructions.md`와 `AGENTS.md` 모듈/명령/경계 갱신
- [ ] `.ai/rules/architecture.md`, `http.md`, `batch.md`, `testing.md`에 새 경계 반영
- [ ] Redis key/TTL/payload version 문서
- [ ] Auth token/cookie/public endpoint 문서
- [ ] Notification delivery/retry/redrive runbook
- [ ] V12 preflight/backfill/cutover runbook
- [ ] migration owner와 deploy/rollback runbook
- [ ] metric/alert dashboard runbook
- [ ] 문서의 오래된 경로/Facade optional/앱 내부 Infrastructure 설명 제거
- [ ] link/path/placeholder 검사

### 검증

```bash
# 로컬: 신규 다운로드 없이 실행 가능한 gate
bash gradlew clean test architectureTest build --offline --no-daemon
bash gradlew :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run build

# CI 격리 runner: ktlint/detekt/coverage/security artifact를 다운로드·실행하고 SHA에 귀속
ci/bootstrap-quality-tools.sh --verify-checksums
java -jar .ci-tools/ktlint.jar '**/src/**/*.kt'
java -jar .ci-tools/detekt-cli.jar --config config/detekt/detekt.yml
bash gradlew jacocoTestReport jacocoTestCoverageVerification --no-daemon
.ci-tools/dependency-check/bin/dependency-check.sh --project premium-spread --scan . --failOnCVSS 7
npm --prefix apps/web audit --audit-level=high
```

`ci/bootstrap-quality-tools.sh`와 checksum manifest는 CI 환경에서만 network를 사용한다. fresh local
`bash gradlew help --offline` 및 공통 local gate가 CI-only 도구 artifact 없이 구성·실행되는 테스트를 추가한다.

### Commit / Push

```text
commit: ci: 아키텍처 품질 게이트와 운영 문서 완성
push:   git push origin refactor/infrastructure-boundary
```

### Phase 완료 조건

- **A-13, Q-01~Q-13, Q-15** 충족

---

## 16. Phase 10 — 최종 End-to-End 검증과 완료 Push

### 목표

모든 Phase 산출물을 clean environment에서 재검증하고 완료 조건의 증거를 남긴 뒤 최종 push한다.

### 16.1 Clean verification

```bash
git status --short
bash gradlew clean compileKotlin test architectureTest build --offline --no-daemon
bash gradlew :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run build

# 후보 SHA를 push한 뒤 격리 CI runner에서 실행
gh workflow run quality-gate.yml --ref refactor/infrastructure-boundary -f sha="$(git rev-parse HEAD)"
gh run watch --exit-status
```

로컬 offline cache에 없는 품질 artifact 때문에 Phase 10을 실패로 오판하지 않는다. 반대로 후보 SHA에
귀속되어 Phase 9의 standalone ktlint/detekt/OWASP, JaCoCo, npm audit를 실행한 CI 결과가 없거나 실패하면
최종 완료로 판정하지 않는다.

### 16.2 Architecture scan

```bash
test ! -d apps/api/src/main/kotlin/io/premiumspread/domain
test ! -d apps/api/src/main/kotlin/io/premiumspread/infrastructure
test ! -d apps/batch/src/main/kotlin/io/premiumspread/cache
test ! -d apps/batch/src/main/kotlin/io/premiumspread/client
test ! -d apps/batch/src/main/kotlin/io/premiumspread/repository

rg -n 'JdbcTemplate|RedisTemplate|StringRedisTemplate|WebClient|MeterRegistry' \
  domain apps/api/src/main/kotlin/io/premiumspread/application \
  apps/batch/src/main/kotlin/io/premiumspread/application

rg -n '^import io\.premiumspread\.(domain|infrastructure)' \
  apps/api/src/main/kotlin/io/premiumspread/interfaces
```

모든 forbidden scan은 0건이어야 한다.

### 16.3 Runtime smoke

1. Docker infra 기동 및 health 확인
2. API 기동, Flyway 성공, readiness UP
3. Batch 기동, Flyway 비활성 확인, readiness UP
4. 회원가입 → 로그인 → 인증 조회 → refresh rotation → old refresh 거부 → logout → refresh 거부
5. Premium/Ticker current/period 조회
6. Position AUTO/MANUAL open, pair 일치, PnL, close
7. WebSocket mock ingestion → Redis latest/time-series → Premium 계산 → 집계 DB 저장
8. Notification 조건 충족 → delivery enqueue → SMTP mock 성공 → SENT
9. SMTP mock 실패 → retry → FAILED/redrive
10. correlation ID, metric, structured masking 확인

실거래소/실SMTP에 의존하는 smoke는 금지하고 MockWebServer/fake SMTP/Testcontainers를 사용한다.

### 16.4 완료 조건 추적표

`docs/superpowers/plans/2026-07-14-infrastructure-boundary-refactoring-result.md`를 생성해
각 `A/D/S/N/O/Q` 항목에 다음을 기록한다.

- 구현 파일
- 검증 테스트/명령
- 결과
- 관련 commit SHA
- 운영 확인이 필요한 항목의 runbook 링크

체크되지 않은 항목이 하나라도 있으면 “완료”로 표시하지 않는다. 결과 문서 commit 자체 SHA는
자기참조하지 않고 push 후 CI/run URL이 해당 SHA를 증명한다.

### 16.5 최종 commit / push

```text
commit: docs: 인프라 경계 리팩터링 완료 결과 기록
push:   git push origin refactor/infrastructure-boundary
```

최종 후보 push 전:

```bash
git status --short
git log --oneline origin/dev..HEAD
git diff --check origin/dev...HEAD
```

- worktree가 clean이어야 한다.
- 각 Phase commit과 push가 원격 branch에 존재해야 한다.
- force push하지 않는다.
- 후보 SHA를 push한 뒤 required CI가 green인지 확인한다.
- 결과 문서 commit을 push한 뒤 docs-only 최종 SHA의 required CI도 green인지 확인하고 완료로 판정한다.
- PR description에 완료 조건 결과 문서를 링크한다.

---

## 17. Phase/완료 조건 추적 매트릭스

| Phase | 주요 완료 조건 |
|---|---|
| 0 | 기준선, V12 상태, timestamp audit, coverage baseline |
| 1 | S-01~03, S-11~12, D-11~13, N-10 |
| 2 | Gradle 물리 경계, Architecture test 기반 |
| 3 | A-01/A-04, D-01~06, D-14 contract ready, O-01~04 |
| 4 | A-12, D-07~14 |
| 5 | A-02/A-05/A-07/A-09, S-01~10 |
| 6 | A-03/A-06/A-08/A-10/A-11, O-06 |
| 7 | N-01~11 |
| 8 | O-01~12, S-08~12 |
| 9 | A-13, Q-01~13/Q-15, 문서 SSOT |
| 10 | 전체 A/D/S/N/O/Q 재검증, Q-14, 최종 push |

---

## 18. 작업 중 중단 조건

다음 조건에서는 추측으로 진행하지 않고 해당 Phase를 중단한다.

1. 운영 DB에서 V12가 pending이고 Position 데이터가 존재함
2. 배포된 운영/스테이징 데이터의 timezone을 판별할 수 없음. `NOT_DEPLOYED`이고 비운영 로컬
   데이터를 UTC 전환 후 재생성하기로 승인한 경우에는 중단하지 않음
3. REST 응답/상태 변경이 Web frontend와 호환되지 않음
4. 한 회원당 Refresh Session 1개 정책이 제품 요구와 충돌함
5. 이메일 중복 가능성을 허용할 수 없어 provider idempotency 또는 다른 전달 채널이 필요함
6. 새 dependency가 보안/라이선스/다운로드 정책을 통과하지 못함
7. 최신 `dev` migration 번호 또는 구조 변경으로 계획의 파일 소유권이 충돌함

중단 시 progress 문서에 증거, 영향, 선택지를 기록하고 사용자 결정을 받은 후 계획을 갱신한다.

---

## 19. 최종 Definition of Done

이 리팩터링은 “파일 이동과 테스트 통과”만으로 완료되지 않는다. 다음 문장이 모두 참이어야 한다.

- API와 Batch는 동일 Domain을 사용하고 서로의 구현을 복제하지 않는다.
- 앱의 Application은 기술 구현을 모르며, Infrastructure는 앱을 모른다.
- 모든 외부 진입점은 하나의 Application use case를 통과한다.
- 운영 설정 오류는 요청 처리 중이 아니라 애플리케이션 시작 시 실패한다.
- 운영 DB는 Batch 또는 Hibernate에 의해 암묵적으로 변경되지 않는다.
- V12 destructive migration은 데이터가 있는 환경에 자동 적용될 수 없다.
- Refresh Token 탈취/재사용 시 회전과 revoke 정책이 동작한다.
- 이메일 발송 대상이 메모리 이벤트 유실로 조용히 사라지지 않는다.
- 시간, soft delete, cache, transaction 정책이 테스트로 고정되어 있다.
- 로그와 메트릭으로 ingestion, 계산, 집계, 알림 장애를 탐지할 수 있다.
- CI가 Architecture, Unit, Integration, Lint, Coverage, Security를 통과해야만 배포를 허용한다.
- 문서와 코드가 동일한 최종 구조를 설명한다.
- 완료 조건 결과 문서와 모든 Phase commit이 원격 branch에 존재한다.
