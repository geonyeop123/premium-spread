# Infrastructure Boundary Refactoring 결과

> 기준일: 2026-07-15 KST
> 실행 브랜치: `refactor/infrastructure-boundary`
> 코드 후보 SHA: `ed1855b91dd4a228bb2d96d6ee0f11c2ca98b580`

## 1. 최종 판정

애플리케이션 아키텍처 변경과 저장소 내부 품질 게이트는 완료됐다. 코드 후보 SHA의
[Quality Gate run 29395537342](https://github.com/geonyeop123/premium-spread/actions/runs/29395537342)은
필수 7개 job을 모두 통과했다.

다만 전체 계획을 무조건적인 `COMPLETE`로 표시하지 않는다. 저장소 기본 branch는 `dev`이고 `main` branch가
없으며 GitHub Environment도 0개다. 따라서 **Q-11 외부 repository governance는 `NOT_CONFIGURED`**다.
운영/스테이징도 존재하지 않아 실제 배포는 `NOT_DEPLOYED`다. 구현/CI 완료와 외부 설정 미완료를 분리해 기록한다.

| 범위 | 판정 | 증거/제한 |
|---|---|---|
| 아키텍처·도메인·보안·알림·운영 코드 | COMPLETE | A/D/S/N/O 항목 자동 테스트 및 Architecture gate 통과 |
| 저장소 내부 Quality Gate | COMPLETE | `ed1855b`, run `29395537342`, 7/7 success |
| 운영/스테이징 배포 | NOT_DEPLOYED | 사용자가 환경 부재를 확인함 |
| `main`/branch protection/`production` approval | NOT_CONFIGURED | default=`dev`, `main` 404, environments=`[]` |
| 전체 계획의 무조건적 완료 선언 | 보류 | Q-11 외부 설정 전에는 완료로 과장하지 않음 |

## 2. Phase/commit 증거

| Phase | 주요 commit | 결과 |
|---|---|---|
| 0. 기준선 | `cc7030e` | 기준선, V12 상태, known failure owner 확정 |
| 1. 운영 안전장치 | `e1f1487` | 인증/secret/schema/management 안전장치 |
| 2. 모듈 경계 | `8268148` | Domain/Infrastructure/Architecture 모듈 생성 |
| 3. Domain/시간 | `937e092` | 공통 계산·MarketPair·UTC 정책 |
| 4. 공통 Infrastructure | `ccd5952` | JPA/Redis/Flyway/transaction 경계 |
| 5. API/Auth | `78e9239` | Facade, JWT, refresh-session fencing |
| 6. Batch Adapter | `193cf35` | Port/Job/외부 adapter 분리 |
| 7. Durable delivery | `6d46477` | DB queue, claim fencing, retry/redrive |
| 8. 운영/관측/배포 | `efff300` | typed config, readiness, metric, rollback 계약 |
| 9. Quality Gate/문서 | `fb202a6` ~ `ed1855b` | strict CI, coverage, lock/verification, CVSS gate, Docker artifact |
| 10. 결과 기록 | 이 문서를 포함하는 closing commit | push 후 docs-only 최종 CI로 검증 |

Phase 9 의존성 bootstrap은 marker-only commit과 parent SHA/fingerprint/만료 검증으로 격리했다. 생성 bundle은
16개 allowlist 파일 및 `SHA256SUMS`를 검토한 뒤에만 반영했고, 최종 root metadata는 967 artifacts,
build-logic metadata는 143 artifacts가 각각 artifact별 SHA-256 하나만 가진다.

## 3. 권위 있는 검증

| Job | 결과 |
|---|---|
| 1. Compile + architecture | SUCCESS |
| 2. Unit + coverage | SUCCESS |
| 3. API integration | SUCCESS |
| 4. Batch integration | SUCCESS |
| 5. ktlint + detekt | SUCCESS |
| 6. Dependency + security scan | SUCCESS |
| 7. Docker image build | SUCCESS |

- Unit 476, Architecture 25, Common Integration 14, API Integration 120, Batch Integration 69가
  failure/error/skip 0으로 검증됐다.
- JaCoCo line coverage는 overall 72.82%, Domain 93.63%, Application 85.80%로 70%/85%/80% 기준을 넘는다.
- OWASP는 API/Batch production runtime만 검사하고 CVSS 7 이상에서 실패한다. 현재 미해결 CVSS 7 이상은 0건이다.
- false-positive suppression 8개는 exact 좌표/버전과 CVE 또는 실제 제품 오인 CPE로 제한되며
  `reason`, `owner`, `expires`, `until`을 가진다. Tomcat은 확인된 19개 CVE만 열거해 신규 CVE를 숨기지 않는다.
- NVD API key 없이 NIST CVE 2.0 static datafeed로 격리 DB를 구성하며 update/scan 실패는 fail-closed다.

## 4. 완료 조건 추적

`COMPLETE`는 코드와 저장소 내부 자동 검증으로 증명된 항목이다. `NOT_DEPLOYED`와 `NOT_CONFIGURED`는
기능 실패가 아니라 현재 존재하지 않는 외부 환경/저장소 설정을 내부 증거로 대체하지 않은 판정이다.

### 4.1 Architecture (A-01~A-13)

| ID | 판정 | 대표 구현 | 검증/결과 | Commit |
|---|---|---|---|---|
| A-01 | COMPLETE | `domain/build.gradle.kts`, API/Batch build | dependency graph/Architecture: 공통 Domain 모듈 참조 | `8268148`→`937e092` |
| A-02 | COMPLETE | `apps/api` interfaces/application | 앱 Domain·Infrastructure package 0, Architecture green | `78e9239` |
| A-03 | COMPLETE | `apps/batch` interfaces/application | 앱 기술 adapter package 0, Architecture green | `193cf35` |
| A-04 | COMPLETE | Domain dependency policy | 허용 JPA/context/tx/data 외 기술 의존 0 | `8268148`→`937e092` |
| A-05 | COMPLETE | API Application Facade | application 기술 import 0 | `78e9239` |
| A-06 | COMPLETE | Batch Application Job | application 기술 구현 참조 0 | `193cf35` |
| A-07 | COMPLETE | API Controller 6개 | 각 Controller가 Facade 하나만 호출 | `78e9239` |
| A-08 | COMPLETE | Batch Scheduler | `ThinSchedulerTest`: 각 Scheduler가 Job 하나만 호출 | `193cf35` |
| A-09 | COMPLETE | Facade Criteria/Result | Entity 노출 0, controller/application tests green | `78e9239` |
| A-10 | COMPLETE | `infrastructure:*` build | infrastructure→apps compile edge 0 | `8268148` |
| A-11 | COMPLETE | API/Batch runtime wiring | app adapter `runtimeOnly`, context tests green | `8268148`→`193cf35` |
| A-12 | COMPLETE | common cache policy | 공통 cache adapter와 장애/손상/legacy tests green | `ccd5952` |
| A-13 | COMPLETE | `architecture-tests`, CI | Architecture 25 + compile/architecture job success | `8268148`→`ed1855b` |

### 4.2 Domain/Data (D-01~D-14)

| ID | 판정 | 대표 구현 | 검증/결과 | Commit |
|---|---|---|---|---|
| D-01 | COMPLETE | `PremiumPolicy` | API/Batch 공통 계산 직접 검증 | `937e092` |
| D-02 | COMPLETE | Domain policy/value tests | 복제 계산 제거, 순수 정책 tests green | `937e092` |
| D-03 | COMPLETE | `MarketPair` | symbol+한국/해외 거래소 canonical identity green | `937e092` |
| D-04 | COMPLETE | Position pair snapshot | open snapshot pair 일치 tests green | `937e092` |
| D-05 | COMPLETE | `BaseEntity` | proxy-safe equality/delete/auditing tests green | `937e092` |
| D-06 | COMPLETE | Kotlin/Java compiler policy | warnings-as-errors, compile 0 warning failure | `937e092`→`fb202a6` |
| D-07 | COMPLETE | Domain repository adapters | active-only ID/list/exists tests green | `ccd5952` |
| D-08 | COMPLETE | Position summary query | DB count 정본, global cache 제거 | `ccd5952` |
| D-09 | COMPLETE | `AfterCommitCacheExecutor` | rollback 시 DB/Redis 미변경, commit 후 기록 | `ccd5952` |
| D-10 | COMPLETE | common Flyway migrations | migration owner common, runtime owner API 하나 | `ccd5952` |
| D-11 | COMPLETE | Batch JPA/Flyway config | schema auto/Flyway 항상 비활성 | `e1f1487`→`ccd5952` |
| D-12 | COMPLETE | V12 preflight/callback | data 존재 시 실행 전 차단·보존 | `e1f1487`→`ccd5952` |
| D-13 | COMPLETE | migration checksum gate | 적용 이력 V1~V13 checksum 고정, V12 destructive 예외 고정 | `e1f1487`→`ccd5952` |
| D-14 | COMPLETE | pair-aware DB/Redis | V13 backfill/unique, v2 key와 default-only legacy read | `937e092`→`ccd5952` |

### 4.3 Security/Auth (S-01~S-12)

| ID | 판정 | 대표 구현 | 검증/결과 | Commit |
|---|---|---|---|---|
| S-01 | COMPLETE | refresh cookie endpoint | 로그인 cookie만으로 refresh E2E green | `e1f1487`→`78e9239` |
| S-02 | COMPLETE | JWT configuration | local/test 외 default secret 불가, startup fail-fast | `e1f1487`→`78e9239` |
| S-03 | COMPLETE | JWT claims policy | issuer/audience/type/TTL/skew tests green | `e1f1487`→`78e9239` |
| S-04 | COMPLETE | Redis refresh session | 원문 대신 HMAC hash+jti 저장 | `78e9239` |
| S-05 | COMPLETE | Lua refresh rotation | CAS winner/loser/reuse fencing integration green | `78e9239` |
| S-06 | COMPLETE | member session family | 재로그인 교체와 family 격리 green | `78e9239` |
| S-07 | COMPLETE | logout policy | refresh 폐기, access는 TTL까지 유효 E2E | `78e9239` |
| S-08 | COMPLETE | refresh cookie policy | local secure=false/prd secure=true startup tests | `78e9239` |
| S-09 | COMPLETE | public endpoint SSOT | method+path exact matcher tests green | `78e9239` |
| S-10 | COMPLETE | CORS/origin policy | Origin/Sec-Fetch-Site 검증 tests green | `78e9239` |
| S-11 | COMPLETE | prd OpenAPI config | prd Swagger/OpenAPI 비활성 | `e1f1487` |
| S-12 | COMPLETE | Actuator exposure | liveness/readiness만 공개, 9080/9081 HTTP green | `e1f1487`→`efff300` |

### 4.4 Durable Notification (N-01~N-11)

| ID | 판정 | 대표 구현 | 검증/결과 | Commit |
|---|---|---|---|---|
| N-01 | COMPLETE | `NotificationDelivery`, V14, JDBC repository | threshold→durable row→SMTP E2E green | `6d46477` |
| N-02 | COMPLETE | unique `event_key` | 동일/동시 event dedupe, 1건만 저장 | `6d46477` |
| N-03 | COMPLETE | `FOR UPDATE SKIP LOCKED` | 두 poller 중복 claim 0 | `6d46477` |
| N-04 | COMPLETE | lockedBy+claimToken fencing | stale recovery 뒤 old owner update 차단 | `6d46477` |
| N-05 | COMPLETE | stale PROCESSING recovery | 경합/slow SMTP E2E green | `6d46477` |
| N-06 | COMPLETE | delivery retry properties/job | backoff/max-attempt/FAILED tests green | `6d46477` |
| N-07 | COMPLETE | durable email sender | deliveryId 기반 stable Message-ID green | `6d46477` |
| N-08 | COMPLETE | redrive audit + runbook | actor/reason/new claim token 검증 | `6d46477` |
| N-09 | COMPLETE | durable DB queue | legacy event/`@Async`/cooldown production scan 0 | `6d46477` |
| N-10 | COMPLETE | email conditional config | disabled 시 query/insert/SMTP 모두 미동작 | `e1f1487`→`6d46477` |
| N-11 | COMPLETE | delivery runbook/guarded mark test | at-least-once와 중복 가능성 명시 | `6d46477` |

### 4.5 Operations (O-01~O-12)

| ID | 판정 | 대표 구현 | 검증/결과 | Commit |
|---|---|---|---|---|
| O-01 | COMPLETE | injected `Clock` | production direct-now/system-default scan 0 | `937e092`→`efff300` |
| O-02 | COMPLETE | Instant audit/UTC datasource | JVM zone 독립 DATETIME(6) round-trip green | `937e092` |
| O-03 | COMPLETE | aggregation zone properties/metric | zone fail-fast, KST/UTC boundary tests green | `937e092`→`efff300` |
| O-04 | NOT_DEPLOYED | V12 audit/preflight/runbook | local APPLIED; 운영/스테이징 부재 | `cc7030e`→`e1f1487` |
| O-05 | COMPLETE | dependency/ingestion readiness | 별도 management port readiness 200/UP | `efff300` |
| O-06 | COMPLETE | bounded operator alert | hang/timeout에도 scheduler lock 장기 점유 방지 | `193cf35`→`efff300` |
| O-07 | COMPLETE | correlation filter/MDC | header·MDC·async 전파/정리 green | `efff300` |
| O-08 | COMPLETE | operational metric policy | 고정 이름/bounded allowlist tag, PII 거부 | `efff300` |
| O-09 | COMPLETE | typed configuration properties | validation/fail-fast, production `@Value` 0 | `efff300` |
| O-10 | COMPLETE | Hikari properties | env 제어/교차검증/prd fallback 거부 | `efff300` |
| O-11 | COMPLETE | app compose/deploy script | DB→API migration/readiness→Batch 계약 green | `efff300` |
| O-12 | COMPLETE | deploy workflow/runbook | exact SHA/smoke/previous-SHA rollback 계약 green | `efff300` |

O-04의 코드와 절차는 완료됐지만 실제 운영 데이터 검증은 환경이 없으므로 실행하지 않았다. O-12도 rollback
로직과 계약 검증의 완료를 뜻하며 실제 운영 배포/rollback 수행을 뜻하지 않는다.

### 4.6 Quality/Delivery (Q-01~Q-15)

| ID | 판정 | 대표 구현/설정 | 검증/결과 | Commit |
|---|---|---|---|---|
| Q-01 | COMPLETE | Gradle dependencies | active QueryDSL/KAPT 0 | `8268148` |
| Q-02 | COMPLETE | compiler convention | warnings-as-errors, compile job success | `fb202a6`→`ed1855b` |
| Q-03 | COMPLETE | test isolation gate | 승인 없는 `@Disabled` 0 | `fb202a6`→`ed1855b` |
| Q-04 | COMPLETE | test source sets/timeouts | Unit/Architecture/Integration 독립 실행 | `fb202a6`→`ed1855b` |
| Q-05 | COMPLETE | Quality Gate workflow | Unit 476, Arch 25, Common 14, API 120, Batch 69 | `ed1855b` |
| Q-06 | COMPLETE | aggregate JaCoCo | overall 72.82%, Domain 93.63%, App 85.80% | `ed1855b` |
| Q-07 | COMPLETE | coverage exclusion contract | exact allowlist와 변경 gate green | `fb202a6`→`ed1855b` |
| Q-08 | COMPLETE | ktlint/detekt | 410 Kotlin files, detekt smell 0 | `ed1855b` |
| Q-09 | COMPLETE | OWASP/npm security | production runtime CVSS≥7 0, npm high/critical 0 | `ed1855b` |
| Q-10 | COMPLETE | locks/verification/checksums | lock 14개, metadata 967/143, SHA-256 strict | `ed1855b` |
| Q-11 | NOT_CONFIGURED | repository governance | default=`dev`, `main` 404, environments 0 | 외부 설정 필요 |
| Q-12 | COMPLETE | deploy workflow/contract | Quality Gate success+`main` push 조건, exact artifact/SHA; approval은 Q-11 미구성 | `efff300`→`ed1855b` |
| Q-13 | CLOSING | `.ai` 구조/상태, AGENTS, 운영 runbook | `docs/check-documentation.sh` green; closing SHA의 7/7 CI로 외부 확정 | closing commit |
| Q-14 | CLOSING | result/status/progress docs | closing commit push 후 clean SHA와 7/7 CI로 외부 확정 | closing commit |
| Q-15 | COMPLETE | `apps/web` | Node 20 npm ci/lint/production build green | `ed1855b` |

## 5. 외부 제한과 잔여 위험

1. Q-11은 GitHub 저장소 관리자 권한으로 `main`, required checks/branch protection,
   `production` Environment approval을 구성하기 전까지 `NOT_CONFIGURED`다. 현재 workflow 계약만 존재한다.
2. 운영/스테이징이 없으므로 V12 데이터 preflight, 실제 image 배포, smoke, rollback은 `NOT_DEPLOYED`다.
3. Batch는 한 번에 설정된 MarketPair 하나만 수집한다. 저장/조회 identity는 다중 pair를 지원하지만 동시 ingestion은
   별도 확장 범위다.
4. 알림 전달은 at-least-once다. SMTP 수락 후 SENT mark 실패 시 stable Message-ID가 있어도 중복 메일 가능성이 있다.
5. logout 후 이미 발급된 access token은 만료 시점까지 유효하다.

## 6. Closing commit 판정 규칙

이 결과 문서와 상태 문서를 포함하는 closing commit을 원격 branch에 push하고, 그 exact SHA의 Quality Gate
7개 job이 모두 성공하며 로컬 HEAD와 원격 branch가 일치하고 worktree가 clean일 때 Q-14의 저장소 내부 조건을
완료로 확정한다. CI run URL/SHA는 이 문서에 다시 쓰지 않고 최종 인수인계에 기록해 문서 갱신→새 SHA→새 CI의
재귀를 피한다. Q-11과 실제 운영/스테이징 배포 판정은 이 closing CI 성공으로 바뀌지 않는다.
