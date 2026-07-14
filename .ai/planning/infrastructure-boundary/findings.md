# Infrastructure Boundary Refactoring Findings

> 갱신: 2026-07-14 KST
> 최초 스펙 리뷰 판정: BLOCKER 7, MAJOR 8, MINOR 1
> 현재 상태: 권고 보정안과 사용자 결정을 실행 계획에 반영함

## 1. 기준선 known failures

### F-01 운영/스테이징 V12 상태 미확인

저장소에는 예제 env만 있고 운영/스테이징 read-only DB 조회 경로가 없다. 환경별 V12 success/checksum,
position row count, timestamp sample이 없으므로 계획이 요구하는 세 상태 중 하나로 분류할 수 없다.

### F-02 기존 DATETIME 의미 판별 불가

로컬 MySQL은 UTC이지만 코드/설정은 `Asia/Seoul`, system default, JDBC timezone을 혼용했다.
외부 이벤트와 대조할 근거가 없어 기존 row를 UTC/KST 어느 쪽으로 변환할지 결정할 수 없다.

### F-03 Batch integration 실제 회귀

`TickerCacheService.saveToSecondsWithScore()`와 `TimeSeriesCacheSupport`가 직접 현재 시각을 사용해
과거 score fixture를 저장 즉시 retention 삭제한다. Clock/명시적 기준시각으로 재설계하기 전에는
Phase 0 test gate가 green이 아니다.

Docker API를 보정해 전체 64개를 재실행한 결과 25개가 실패했다. 이 중 2개는 위 retention 회귀이고,
나머지 23개는 `batch-schema.sql`에 Repository SQL이 요구하는 ticker `currency`, premium `fx_rate`가
없는 fixture drift다. Repository 16개와 그 하위 aggregation E2E 7개가 같은 원인으로 실패한다.

### F-04 필수 Gradle artifact 부재

오프라인 cache에 JaCoCo ant 0.8.13, ktlint engine 1.0.1, detekt, OWASP Dependency-Check가 없다.
작업 규칙상 신규 Gradle 다운로드는 허용되지 않으므로 외부 cache bootstrap 또는 plan의 local/CI
검증 책임 분리가 필요하다.

## 2. Plan 내부 BLOCKER와 권고 보정

### F-05 V12 preflight 실행 순서

일반 Spring startup bean은 Flyway 이후 실행될 수 있어 V12 `TRUNCATE`를 막지 못한다.
배포 전 외부 preflight를 필수화하고, 수동 기동에는 Flyway callback/별도 migration runner 및
`PENDING_EMPTY` 일회성 승인 플래그가 필요하다. `PENDING_WITH_DATA`는 backup/변환/복원과 V12 이력
처리 절차가 구체화되기 전 실행할 수 없다.

### F-06 interfaces import 규칙 모순

interfaces의 Domain import 0건과 Domain typed exception handler는 동시에 만족할 수 없다.
권고안은 Facade가 Domain exception을 Application error로 변환하고 interfaces는 Application error만
매핑하는 것이다.

### F-07 Refresh rotation 동시성 모순

동시 A refresh에서 첫 요청이 A→B로 성공한 뒤 두 번째 요청이 A reuse로 B까지 revoke하면 성공한
응답의 B가 즉시 무효가 된다. 권고안은 `familyId + generation + previousHash + rotatedAt`을 저장하고,
같은 family의 짧은 동시성 window에서는 loser만 401로 거부하되 현재 B는 유지하며, window 이후
reuse만 family revoke하는 것이다. 다른 login family의 구 token은 현재 family를 revoke하지 않는다.

### F-08 Refresh infrastructure 의존 누락

`infrastructure:api`의 `RedisRefreshSessionStore`가 컴파일되려면 `modules:redis` 직접 implementation
또는 동등한 adapter 이동이 필요하다. 권고안은 `infrastructure:api -> modules:redis` 직접 의존이다.

### F-09 최종 push/CI 순환

push 전 해당 최종 SHA의 CI green을 요구할 수 없다. 후보 SHA push → CI green → 결과 문서 commit/push
→ docs-only 최종 SHA CI green → 완료 판정으로 순서를 바꿔야 한다. GitHub required checks/environment
protection은 저장소 외부 설정 증거로 관리한다.

### F-10 Progress SHA 자기참조

commit 후 같은 commit의 SHA를 문서에 넣는 것은 불가능하다. 다음 Phase의 첫 commit에서 이전 Phase
SHA를 기록하거나 별도 progress commit을 허용해야 한다.

## 3. 추가 MAJOR

- Domain JPA entity 이동 시 `kotlin("plugin.jpa")`와 proxy 정책을 명시해야 한다.
- `supports:logging` runtimeOnly 전환 전에 `WebMvcConfig`의 직접 import를 auto-configuration으로 옮겨야 한다.
- 전 모듈 bytecode를 실제로 읽는 독립 `architecture-tests` 모듈과 0-class guard가 필요하다.
- NotificationSubscription/event key에 canonical MarketPair와 normalized threshold/revision이 필요하다.
- notification stale threshold는 queue/DB margin을 포함한 hard deadline으로 검증해야 한다.
- SENT PII scrub/dedupe 보존 기간과 offline redrive audit 정책이 필요하다.
- JDBC session timezone UTC 강제와 실제 Instant round-trip integration test가 필요하다.
- Kotlin 2.1 호환/coverage 보정 작업과 실행 가능한 toolchain/테스트 추가 Phase가 필요하다.

## 4. 사용자 결정 및 처리

2026-07-14에 다음을 승인받았다.

1. 운영/스테이징은 존재하지 않으므로 `NOT_DEPLOYED`로 분류한다.
2. 의미가 불명확한 로컬 DATETIME은 자동 변환하지 않는다. 기존 volume을 보존하고 UTC 정책 적용 후 새
   non-production volume/fixture로 재생성한다.
3. F-05~F-10과 추가 MAJOR 권고를 계획에 반영한다.
4. 로컬 offline artifact가 없는 quality 검증은 다운로드가 허용된 격리 CI runner의 SHA 귀속 결과로 판정한다.

F-03의 fixture drift는 Phase 2, retention은 Phase 3, Web lint는 Phase 5, quality/security 도구와
npm audit은 Phase 9에서 완료 조건으로 해소한다.

## 5. Phase 3 실행 중 추가 확인사항

### F-11 aggregation cache는 현재 coverage를 증명할 수 없음

기존 Redis ZSET에는 요청 기간 전체가 적재됐음을 나타내는 coverage marker가 없다. parse 가능한 row가 하나라도
있다는 이유로 cache hit를 확정하면 eviction/rebuild 중 DB의 과거 bucket이 누락된다. Phase 3에서는 cache와
DB를 `observedAt`으로 병합하고 동일 bucket은 DB를 정본으로 삼아 정확성을 우선했다. 향후 DB 조회를 생략하는
최적화가 필요하면 versioned payload에 authoritative range/watermark를 추가하고 adapter integration test로
완전성을 증명해야 한다.

### F-12 MySQL Testcontainers TLS 인증서 시각 의존

Docker VM/WSL/JVM 시각 보정 직후 MySQL 컨테이너가 생성한 self-signed certificate의 `notBefore`가 JVM 현재시각보다
앞서 전체 context가 연쇄 실패할 수 있었다. 테스트와 local production URL은 원래 TLS를 사용하지 않는 정책이므로
공통 fixture와 별도 V12 safety container URL에 `sslMode=DISABLED`를 명시했다. 외부 DB TLS 검증은 이 fixture와
분리된 환경별 smoke/CI 책임으로 유지한다.

## 6. Phase 4 실행 중 추가 확인사항

### F-13 Redis 장애와 부분 손상 시 cache hit을 확정하면 안 됨

Redis 연결 오류가 reader 밖으로 전파되면 DB fallback에 도달하지 못하고, ZSet의 손상 row만 제외하면 잘못된
OHLC/평균/count/fxRate가 DB 정본으로 저장될 수 있다. 모든 공통 reader와 Batch 시계열 reader는 Redis 장애를
bounded `ERROR` metric으로 기록하고 fallback 가능한 miss를 반환한다. ZSet은 한 row라도 format/숫자/timestamp가
손상되면 전체 결과를 폐기하며, Premium seconds는 exact 4개 numeric field를 요구한다.

### F-14 Boot auto-configuration 조건은 bean 생성 순서와 독립 소비자를 함께 검증해야 함

`JdbcTemplate`, `EntityManagerFactory`, `StringRedisTemplate` 조건을 같은 구성에 묶으면 조건 평가 순서에 따라
필수 adapter가 누락될 수 있다. common 구성을 JDBC/JPA/cache로 분리하고 `JdbcTemplateAutoConfiguration` 이후에
평가되도록 했다. JPA datasource는 `datasource.mysql-jpa.enabled=true`, Redis business cache는
`redis.enabled=true`에서만 활성화하며, Redis-only/Redis-disabled context test로 불필요한 Hikari/Redisson 생성을
막았다. 양 앱은 third-party Redisson auto-configuration을 제외하고 property-aware custom 구성을 단일 소유자로 쓴다.

### F-15 Legacy cutover TTL과 키 canonicalization

legacy key를 `Symbol.code` 대문자로 조합하면 기존 소문자 Redis key를 놓치고, legacy hit마다 TTL을 다시 설정하면
cutover window가 무기한 연장된다. legacy premium seconds/minutes/hours/days/summary key를 소문자로 canonicalize하고,
현재 TTL이 cutover window보다 길거나 영구 키일 때만 줄인다. 반복 조회가 TTL을 연장하지 않는 계약을 테스트로
고정했다.

## 7. Phase 5 실행 중 추가 확인사항

### F-16 동일 leaf project name의 Gradle component 좌표 충돌

Gradle project path가 달라도 공통 group 아래 `apps/api`와 `infrastructure/api`의 기본 component 좌표는 모두
`io.premiumspread:api`가 된다. API test runtime classpath에서 `:infrastructure:api -> :apps:api` conflict
substitution이 발생해 자동 설정 jar 전체가 빠졌고, 첫 증상은 Domain `PasswordEncoder` bean 누락이었다.
`infrastructure:api`에 고유 group을 부여해 runtime component identity를 분리했으며, dependency report와 실제
116개 통합 테스트로 회귀를 검증했다. 같은 구조의 Batch 두 모듈은 Phase 6 runtimeOnly 전환 시 함께 고유 좌표로
분리하고 Phase 9 전역 dependency gate에서 중복 component 좌표를 차단한다.

## 8. Phase 6 실행 중 추가 확인사항

### F-17 Future timeout과 분산 lock release는 별도 생명주기가 필요함

`Future.cancel(true)`는 interrupt 요청일 뿐 실제 JDBC/Redis I/O 종료를 보장하지 않는다. timeout 직후 lock을
해제하면 취소를 무시한 이전 action과 다음 인스턴스가 동시에 write할 수 있다. Job lock을 owner-token Redis 값으로
원자화하고 Lua owner 비교 renew/release를 적용했으며, action completion latch가 끝날 때까지 lease를 갱신한다.
timeout 실패 기록과 bounded alert는 즉시 남기되 lock release는 실제 종료 뒤에만 수행한다. 잘못된 owner의
renew/release no-op와 PTTL 연장은 실제 Redis 통합 테스트로 검증한다.

### F-18 이동 후 dead technical job이 운영 metric을 고립시킬 수 있음

Scheduler를 Port 기반 Application Job으로 바꾸면서 기존 Infrastructure FlushJob을 함께 등록하면 실제 호출 경로와
`ws.stale`/`ticker.flush` metric 경로가 분리되고 중복 구현이 남는다. flush 운영 신호를 Domain
`TickerFlushObserver` Port로 승격해 Application 경로에서 기록하고, 호출되지 않는 기술 FlushJob은 제거했다.

### F-19 설정 alias fallback은 application.yml 기본값과 함께 검증해야 함

placeholder fallback은 canonical key가 `application.yml`에 존재하면 legacy key를 영원히 평가하지 않는다.
`batch.scheduling.enabled`와 `scheduling.enabled`를 AND로 평가해 어느 키든 false이면 scheduling을 비활성화하고,
canonical default=true와 legacy=false가 동시에 존재하는 실제 구성 형태를 context test로 고정했다.

## 9. Phase 7 실행 중 추가 확인사항

### F-20 interrupt는 SMTP 종료가 아니라 실행 용량 보존과 fencing으로 다뤄야 함

JavaMail 호출은 thread interrupt를 즉시 따르지 않을 수 있고 connect/read/write timeout의 합도 실제 전체 실행시간
상한은 아니다. deadline에는 interrupt를 요청하되 작업이 실제 끝날 때까지 concurrency permit을 반환하지 않고,
가용 permit 수만큼만 DB row를 claim한다. 늦은 SMTP 결과는 claim token fencing으로 현재 owner 상태를 변경하지
못하게 하며, at-least-once 중복 가능성을 runbook에 기록했다.

### F-21 전송 비활성화와 PII retention 생명주기는 분리해야 함

`notification.email.enabled=false`가 기존 SENT row의 개인정보 보존기간 집행까지 멈추면 30일 계약을 지킬 수 없다.
enqueue/poller/SMTP bean은 비활성화하되 retention properties/REQUIRES_NEW transaction/job은 항상 구성하고,
전역 Batch scheduling만 scheduler의 최종 on/off 기준으로 사용한다.

### F-22 DB 식별자 길이와 metric commit 시점도 fencing 계약의 일부임

hostname+UUID worker ID가 `locked_by`보다 길면 strict mode claim 실패 또는 truncate 후 모든 guarded update 실패가
발생한다. worker ID를 100자로 제한하고 경계 테스트를 추가했다. 또한 lifecycle metric은 JDBC update 직후가 아니라
transaction after-commit에만 증가시켜 rollback된 상태를 운영 신호로 남기지 않는다.
