# Project Status

> Last updated: 2026-07-27, PRIVATE LIVE master specification 재작성 후 사용자 승인 대기

## Current State

| Module | 상태 | 현재 책임 |
|---|---|---|
| `apps:api` | Active | REST interfaces, Application Facade, API 조립 루트(8080/management 9080) |
| `apps:batch` | Active | thin scheduler, Application Job, Batch 조립 루트(8081/management 9081) |
| `apps:web` | Active | Next.js 대시보드, 인증, position UI |
| `domain` | Stable | Entity/Value/Policy/Service/Port, MarketPair와 계산 정본 |
| `infrastructure:common` | Active | JPA/JDBC, Redis business cache, Flyway V1~V14 |
| `infrastructure:api` | Active | JWT/cookie/refresh-session Security, cache warmup |
| `infrastructure:batch` | Active | 거래소/FX/WebSocket, cache/lock/metric/email adapter |
| `modules:jpa` | Stable | 표준 DataSource/JPA/auditing foundation |
| `modules:redis` | Stable | 표준 Redis/Redisson, key/TTL/time-series foundation |
| `supports:*` | Active | logging, monitoring, email auto-configuration |
| `architecture-tests` | Active | 모듈/계층/의존 그래프 회귀 방지 |

앱 내부 기술 adapter는 제거됐다. API Controller는 Application Facade 하나를, Batch Scheduler는
Application Job 하나를 호출한다. Application은 Domain service/port만 의존하고, infrastructure 모듈은
앱에 역의존하지 않는다.

## Infrastructure Boundary 진행 상태

- Phase 0~9 구현은 각각 commit/push와 독립 spec/code review를 완료했다.
- Phase 9 코드 후보 `ed1855b`의 GitHub Quality Gate run `29395537342`에서 compile/architecture,
  unit/coverage, API/Batch integration, ktlint/detekt, dependency/security, Docker build 7개 job이 모두 성공했다.
- Phase 10은 완료 결과 문서와 상태 문서를 마감한 뒤, 이 문서를 포함하는 closing commit 자체의 동일 7-job CI로
  최종 판정한다.
- Q-11은 저장소 기본 branch가 `dev`이고 `main`, branch protection, `production` Environment가 없어
  `NOT_CONFIGURED`다. 운영/스테이징 배포도 `NOT_DEPLOYED`이며 코드/CI 완료와 구분한다.
- 전체 상세와 Phase별 증거는 [진행 문서](planning/infrastructure-boundary/progress.md), 완료 조건별 판정은
  [결과 문서](../docs/superpowers/plans/2026-07-14-infrastructure-boundary-refactoring-result.md)에 기록한다.

## PRIVATE LIVE 프로그램 상태

- master specification을 상세 구현 계획이 아닌 상위 spec으로 재작성했고 Phase 경계를 Phase 0 Foundation Alignment,
  Phase 1 Market & Economics, Phase 2 SIMULATION + PAPER, Phase 3 PRIVATE LIVE Capability로 정리했다.
- 프로그램 문서는 `feature-workflow` 산출물 계약에 맞춰 [`docs/work/private-live-autotrader/`](../docs/work/private-live-autotrader/README.md)의
  `design.md`(스펙), `plan.md`(상위 plan), `dod.md`(완료 기준 계약서)로 정렬했다. 재작성 전 경로인
  `.ai/planning/private-live-autotrader/task_plan.md`는 더 이상 사용하지 않는다.
- 완료된 Phase -1 산출물과 acceptance 증거는 동결 대상이므로 `.ai/planning/private-live-autotrader/`와
  `docs/dod/`에 그대로 둔다. 상태축 현재값은
  [`progress.md`](planning/private-live-autotrader/progress.md)가 단독으로 소유한다.
- 현재 판정은 specification `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL`, software `SOFTWARE_BASELINE`,
  evidence collection `COLLECTION_NOT_READY`, activation `ACTIVATION_NOT_STARTED`다. 사용자 승인 전에는 Phase 0을
  시작하지 않는다.
- Claude 독립 리뷰 3회(A·B·C)를 반영했고 외부 관점 `codex-spec-review`는 아직 수행하지 않았다. 제품 소스 변경은
  Phase -1 이후 없다.

## PRIVATE LIVE Phase -1 상태

- secret-bearing deploy workflow는 제거했고 기존 GitHub-hosted Quality Gate만 CI 경계로 유지한다.
- Quality Gate는 `github.sha`로 묶인 image archive와 provenance evidence까지만 만들며 production/exchange credential을
  받거나 host 배포를 수행하지 않는다.
- 배포 owner는 validation/PRIVATE_LIVE host의 operator다. 검증 artifact, host registry credential, host secret source와
  `docker/deploy.sh` 실행을 host-local 경계에서 관리한다.
- Phase -1은 실제 host, secret 또는 activation을 구성하지 않았다. 운영/스테이징 배포 상태는 `NOT_DEPLOYED`다.

## 기능 상태

- WebSocket Binance/Bithumb 시세 수집과 1초 down-sample
- USD/KRW 30분 수집, Premium 1초 계산, minute/hour/day 집계
- canonical `MarketPair` 기반 premium/position/notification 저장·조회
- Position AUTO/MANUAL 오픈과 KRW 손익 계산
- JWT Access/rotating Refresh 인증, Redis family/session fencing
- MySQL durable notification queue, retry/stale recovery/FAILED redrive/PII scrub
- Redis pair-aware v2 key와 default-pair legacy read cutover
- DB/Redis/ingestion readiness, Prometheus, correlation ID/masking
- commit SHA image 배포와 API migration/readiness 선행, 이전 SHA rollback

## Migration 상태와 소유권

| Migration | 내용 | 상태/주의 |
|---|---|---|
| V1~V11 | 초기 ticker/premium/position/member/aggregation/notification subscription | immutable 적용 이력 |
| V12 | `position`을 한국 Long + 해외 Short pair로 재구성 | `TRUNCATE` 포함 immutable 예외; preflight/승인 없이 실행 금지 |
| V13 | premium snapshot/집계에 MarketPair 컬럼/index/unique 추가 | 기본 BITHUMB/BINANCE backfill, pair-aware 저장 정본 |
| V14 | 구독 pair/revision/optimistic lock + `notification_delivery` | durable delivery queue 정본 |

- migration 파일과 Flyway 실행 owner는 `infrastructure:common`, runtime 실행 owner는 API 하나뿐이다.
- Batch Flyway는 항상 비활성이다.
- V12 로컬 상태는 `APPLIED`, 운영/스테이징은 존재하지 않아 `NOT_DEPLOYED`다.
- 향후 새 환경은 `docker/preflight-v12.sh`와
  [`docs/runbooks/v12-migration.md`](../docs/runbooks/v12-migration.md)를 먼저 수행한다.
- rollback은 application image만 이전 SHA로 되돌리며 DB down migration을 수행하지 않는다.

## Known Issues / Explicit Limits

1. 현재 운영/스테이징 환경은 없다. `prd` profile과 host-local runbook은 향후 환경의 계약이며 실제 host 배포와
   activation은 별도 operator 실행 증거가 있어야 완료로 판정한다.
2. Batch runtime은 `batch.market`의 한 MarketPair만 수집한다. DB/Redis/API identity는 다중 pair를 구분하지만
   여러 pair 동시 ingestion은 별도 확장 작업이다.
3. 이메일 전달은 at-least-once다. SMTP 수락 후 DB mark 실패 시 중복 메일이 가능하다.
4. logout은 Refresh Token/cookie를 폐기한다. 이미 발급된 Access Token은 TTL까지 유효하다.
5. V12는 destructive migration이다. 기존 row가 있는 환경은 backup, 업무 mapping 승인, backfill 검증과
   cutover 없이 자동 실행할 수 없다.
6. Premium symbol-only legacy Redis key는 기본 pair에서만 최대 5초 read window로 호환한다. writer는 v2 key만
   쓰므로 legacy key에 의존하는 외부 consumer가 있다면 cutover 전에 제거해야 한다.
7. Docker app과 local `bootRun`을 동시에 실행하면 8080/8081 port가 충돌한다.

## 운영 정본

- 시스템 구조: [`.ai/architecture/ARCHITECTURE_DESIGN.md`](architecture/ARCHITECTURE_DESIGN.md)
- 설정/profile: [`docs/runbooks/configuration-profiles.md`](../docs/runbooks/configuration-profiles.md)
- Redis: [`docs/runbooks/redis-contract.md`](../docs/runbooks/redis-contract.md)
- Auth: [`docs/runbooks/auth-security.md`](../docs/runbooks/auth-security.md)
- Notification: [`docs/runbooks/durable-notification-delivery.md`](../docs/runbooks/durable-notification-delivery.md)
- Migration: [`docs/runbooks/v12-migration.md`](../docs/runbooks/v12-migration.md)
- Deploy/Rollback: [`docs/runbooks/deployment.md`](../docs/runbooks/deployment.md)
- Metrics/Alert: [`docs/runbooks/metrics-alerting.md`](../docs/runbooks/metrics-alerting.md)
