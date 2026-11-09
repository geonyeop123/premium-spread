# Project Status

> Last updated: 2026-10-29, PRIVATE LIVE master specification 재작성 후 사용자 승인 대기

## Current State

| Module | 상태 | 현재 책임 |
|---|---|---|
| `apps:api` | Active | REST interfaces, Application Facade, API 조립 루트(8080/management 9080) |
| `apps:batch` | Active | thin scheduler, Application Job, Batch 조립 루트(8081/management 9081) |
| `apps:web` | Active | Next.js 대시보드, 인증, position UI |
| `domain` | Stable | Entity/Value/Policy/Service/Port, MarketPair와 계산 정본 |
| `infrastructure:common` | Active | JPA/JDBC, Redis business cache, Flyway V1~V23 |
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
- USD/KRW 5분 수집(Twelve Data), Premium 1초 계산, minute/hour/day 집계
- canonical `MarketPair` 기반 premium/position/notification 저장·조회
- Tracking 기록(현재 시세/입력값)과 KRW gross 손익 계산, 종료 시점 스냅샷 확정
- JWT Access/rotating Refresh 인증, Redis family/session fencing
- MySQL durable notification queue, retry/stale recovery/FAILED redrive/PII scrub
- Redis pair-aware v2 key와 default-pair legacy read cutover
- DB/Redis/ingestion readiness, Prometheus, correlation ID/masking
- commit SHA image 배포와 API migration/readiness 선행, 이전 SHA rollback
- 실계정 순손익 알림(구현 완료, 기본 꺼짐, owner 로컬 실행 전용): 빗썸/바이낸스 read-only REST 조회, 순수익률 알림과
  모니터링 중단·복구 메일, 전용 outbox(`account_pnl_mail`, V18). 바이낸스 leg 는 `BINANCE_MODE=api|manual`
  로 선택(manual = env 입력 포지션 + 공개 호가, 메일에 출처 표기). owner 실 key 검산(AC13)과 manual 모드
  로컬 실행 검산(AC9)은 미완료이며
  [runbook](../docs/runbooks/account-pnl-alert.md)을 따른다
- owner 알림 채널(구현 완료, `OWNER_ALERT_*` env): 수익·정시 보고·중단·복구 알림을 종류별로 메일 및/또는 Discord webhook 으로
  라우팅(Domain `OwnerAlertKind`), `OwnerAlertDispatcher`(apps:batch)·`DiscordWebhookSenderAdapter`(infrastructure:batch),
  outbox `account_pnl_mail` 에 `channel`·Discord 컬럼과 `UNIQUE(event_key, channel)`(V19). owner Discord 수신 확인(AC13)은
  미완료이며 후속은 이슈 #89~#91이다
- owner 계정 알림 3종(#89, 구현·검증 완료, PR #94 merged `728e3f1`, AC14 (c)(d)(e) owner 확인 대기): 실계정 포지션 변경,
  순수익률 급변(정시 기준선), 미반영 항목 하루 1회 리마인더. 상태는 `account_pnl_monitor_state` 12열(V20)에
  저장하고 [runbook](../docs/runbooks/account-pnl-alert.md)에 체크리스트가 있다. 후속 후보:
  - 계정 알림이 켜진 상태의 Job save 예외·interrupt 분기 테스트
  - 평단 1원 미만 변경 시 알림 본문에서 before와 after가 같게 보이는 표시 개선
- owner 운영 알림(#91, 구현·검증 완료, PR 예정, owner 확인 대기: AC13 runbook 검토·AC14 Discord 수신): 환율 수집 지연·시세 수집
  끊김·Job 연속 실패를 장애 시작 1회, 6시간 재알림, 복구 1회로 메일 및/또는 Discord 알림. `OwnerOpsSignalJob`(30초)이 상태
  `owner_alert_signal_state`(V21)와 outbox 를 함께 저장하고 독립 `OwnerAlertDeliveryJob`(60초)이 전달한다. PnL 모니터와
  독립이다. [runbook](../docs/runbooks/owner-ops-alert.md)에 Discord 수신 체크리스트가 있다. 상위 #87 은 #88·#89·#91 완료,
  #90 구현·검증 완료, owner 확인(AC17) 대기. 후속 후보:
  - E2E 에서 전달 Job 과 PnL 회차 디스패처의 동시 전달 경합을 강제로 만드는 테스트
  - `isCancellation()` 공용화(`AccountPnlMonitorJob` 동결 해제 후)
  - MarketPair guard 의 exchange 불일치 테스트
  - infrastructure:api `RedisRefreshSessionStoreIntegrationTest` initializationError 간헐 실패 조사
  - 재시도 백오프(시도 간 최소 간격) 검토
- owner 시장 알림(#90, 구현·검증 완료, PR 예정, owner 확인 대기: AC17 Discord 수신): 김프 급변·기준선 돌파·신고가/신저가·일일
  요약을 메일 및/또는 Discord(market 대상)로 알림. `OwnerMarketSignalJob`(30초)이 상태 `owner_market_alert_state`(V22)와
  outbox 를 함께 저장하고 `OwnerAlertDeliveryJob`이 전달한다. 요약은 PnL 모니터가 남기는 `account_pnl_latest`(V22)를 읽는다.
  [runbook](../docs/runbooks/owner-market-alert.md)에 체크리스트가 있다. 상위 #87 은 #88~#91 구현·검증 완료, #90 owner 확인(AC17) 대기.
  후속 후보:
  - `AccountPnlMonitorWiringTest.kt:387` 타임스탬프 부분 일치로 인한 기존 flaky
  - `WebSocketConnectionManagerRegressionTest` stop/reconnect 기존 flaky
  - 기준선 돌파 쿨다운 또는 히스테리시스 하한(히스테리시스 0/작은 값에서 flapping 가능)
  - PnL 최신값 기록 중 취소 시 `Skipped` 처리(현재는 기록을 건너뛰고 deliver 진행)
  - api integrationTest Tracking 계열 간헐 실패(`TrackingControllerE2ETest:90` 409, `TrackingArchiveIntegrationTest:130` — 단독 재실행 통과)
  - 태스크 리뷰에서 미룬 테스트 보강 묶음(창 경계 정각·KST 자정, 대칭 경로, 동시성 중간 예외 등)
- 실계정 알림 자산 칸(#105, 구현·검증 완료, PR 예정, 사람 확인 AC8·AC9 머지 후 배포 환경에서 대기): 수익·정시 알림에
  메일 `■ 자산`, Discord 2칸+2칸(총 자산·바이낸스 증거금+손익 / 잔여 금액·USDT 원화가)을 추가했다. 빗썸 `/v1/accounts`
  KRW 잔고와 `/v1/ticker`(KRW-BTC,KRW-USDT), 바이낸스 `/fapi/v3/account` 1회(전용 timeout 1.5초, `-1021` 미재시도)를
  쓰며 실패는 adapter 안에서 null + WARN 으로 흡수해 순손익·중단 판정에 영향이 없다. 공통 Discord 렌더러의 2칸 행 뒤
  채움 칸을 non-inline 행 끊기 칸(U+200B)으로 바꿔 시장·운영 알림에도 적용된다. Flyway/Redis/API 무변경.
  [설계](../docs/work/account-balance-alert/design.md)와 [runbook](../docs/runbooks/account-pnl-alert.md)을 따른다.
- 환율 제공자 Twelve Data 교체(#93, 구현·검증 완료, PR #99, AC8 로컬 운영 실기동 관찰 완료 — 주말 동작 미관찰):
  수집 5분·허용 공백 30분(`FxIngestionFreshness`)·캐시 TTL 40분을 테스트와 기동 검증으로 묶었다.
  [설계](../docs/work/fx-provider-twelve-data/design.md)와 [runbook](../docs/runbooks/deployment.md)을 따른다. 후속 후보:
  - api `TrackingFacade.FX_MAX_AGE` 35분이 제공자 timestamp 기준이라 주말(forex 휴장, timestamp 금요일 마감 고정)에 추적 확정 실패(F1)
  - 환율 클라이언트 `overallDeadline` 공식이 시도당 connect+responseTimeout+body timeout(8s)이 아니라 5s로 계산되어, max-retries override 시 block deadline이 먼저 끊을 수 있음
  - Twelve Data가 한도 초과를 HTTP 200 + code 429로 줄 때 metric이 contract로 집계됨(rate_limited 분리 후보)
  - `exchange_rate.rate DECIMAL(10,4)` 대 캐시 `toPlainString` 전체 정밀도 차이
- Quality Gate Docker build 시간 단축(#107, 구현·로컬 검증 완료, PR CI 실측·머지 뒤 관찰 대기): 이미지 3개를
  `7a. Docker image (<component>)` matrix 로 병렬 build 하고 `7. Docker image build` 가 만료되지 않은 part 3개를 확인한 뒤
  기존 `docker-images-<sha>` artifact 로 조립한다. buildkit `cache-to` 는 dev push 에서만 쓰고 PR 은 dev cache 를 읽기만 한다.
  `Actions cache PR cleanup`(PR 종료 시 그 merge ref cache 전부 삭제)과 `Actions cache stale cleanup`(매일, dev 의 `gradle-*`
  cache 중 dev 활동 기준 3일 미접근 삭제, 수동 실행은 기본 dry-run)을 추가했다. buildkit cache 는 blob 이 없으면 build 가
  실패하므로 일정 삭제하지 않는다. 종료된 PR #106/#108/#109 cache 4.6GB 를 수동 삭제해 저장소 cache 사용량이 약 11.0GB 에서
  6.43GB 가 됐다. [설계](../docs/work/docker-build-time/design.md)와 [runbook](../docs/runbooks/deployment.md)을 따른다.
- apps/web 의존성 주기 갱신(#98, 설정 추가, 첫 갱신 PR 은 머지 뒤 관찰): `.github/dependabot.yml` 이 `apps/web` npm 갱신
  PR 을 매주 `dev` 대상으로 만든다. `next`·`eslint-config-next` 는 한 그룹으로 함께 올라가며, 두 버전이 다르면
  `ci/quality-gate-contract-test.sh` 가 1번 잡을 실패시킨다. 운영(누가·언제 머지)은 [runbook](../docs/runbooks/deployment.md)을 따른다.
- apps/web overrides 재검토(#97, 머지 완료(PR #117)): `apps/web/package.json` 의 `overrides` 14개를 모두
  제거했다. 제거해도 audit high 이상이 생기지 않았다. 재도입 조건은 [runbook](../docs/runbooks/deployment.md)을, 항목별 판정은
  [설계](../docs/work/issue-97-web-npm-overrides/design.md)를 따른다.
- apps/web Node 24 전환(#118, 구현·로컬 검증 완료(⑩ T1/T2 7/7 PASS @ e3a1a97), PR CI 대기): CI 6번 잡과 `apps/web/Dockerfile` 두 stage를 Node 24
  (`node:24-alpine`, digest 고정)로 올리고 `engines.node` `^24.15.0`·`@types/node` `^24`·jsdom 30을 적용했다. 네 곳의 Node
  major가 갈리면 `ci/quality-gate-contract-test.sh`가 1번 잡을 실패시킨다. 갱신 절차는 [runbook](../docs/runbooks/deployment.md)을 따른다.
- apps/web 트래킹 조회 훅 분리(#96, 구현 완료, PR 대기): 트래킹 목록·상세 페이지의 조회를
  `apps/web/src/hooks/useTrackings.ts`(`useTrackingList`·`useTracking`·`useGrossPnl`)로 옮기고, #82 D7의
  `react-hooks/set-state-in-effect` override를 `apps/web/eslint.config.mjs`에서 제거했다(#82의 후속 해소). 백엔드·API 계약 변경은 없다.
  [설계](../docs/work/issue-96-trackings-fetch/design.md)를 따른다.

## Migration 상태와 소유권

| Migration | 내용 | 상태/주의 |
|---|---|---|
| V1~V11 | 초기 ticker/premium/position/member/aggregation/notification subscription | immutable 적용 이력 |
| V12 | `position`을 한국 Long + 해외 Short pair로 재구성 | `TRUNCATE` 포함 immutable 예외; preflight/승인 없이 실행 금지 |
| V13 | premium snapshot/집계에 MarketPair 컬럼/index/unique 추가 | 기본 BITHUMB/BINANCE backfill, pair-aware 저장 정본 |
| V14 | 구독 pair/revision/optimistic lock + `notification_delivery` | durable delivery queue 정본 |
| V18 | `account_pnl_monitor_state`, `account_pnl_mail` | 실계정 손익 알림 전용 상태·outbox |
| V19 | `account_pnl_mail` 에 `channel`·Discord 컬럼, `UNIQUE(event_key, channel)` | owner 알림 채널(메일·Discord) 확장 |
| V20 | account_pnl_monitor_state 에 owner 계정 알림 상태 12열 | 포지션 변경·순수익률 급변·미반영 리마인더 상태 |
| V21 | `owner_alert_signal_state` 신규 | owner 운영 알림 신호별 장애 상태(additive, 롤백 SQL 없음) |
| V22 | `owner_market_alert_state`·`account_pnl_latest` 신규 | owner 시장 알림 신호별 상태, 실계정 최신 순수익률(additive, 롤백 SQL 없음) |
| V23 | `account_pnl_mail.discord_layout` 추가 | owner 알림 Discord embed 칸 배치 보존(additive nullable, 롤백 SQL 없음). API(Flyway) 먼저 배포 |

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
   local 프로필도 Hibernate `validate`이므로 batch는 api의 Flyway migration이 끝난 뒤(readiness UP) 기동한다.
8. Quality Gate 6번 잡의 `ci/run-npm-audit.sh`는 실시간 advisory DB를 조회한다. `apps/web` lockfile이 그대로여도
   새 advisory가 공개되면 다시 실패한다(#82에서 23건 해소). #98에서 Dependabot 주기 갱신(주 1회, `dev` 대상)을 도입해
   실패 기간을 줄이지만 advisory 공개와 갱신 PR 머지 사이의 실패는 남는다. 운영은 [`deployment.md`](../docs/runbooks/deployment.md)를 따른다.
   #97에서 `overrides`를 모두 제거했다. 2026-11-07 기준 moderate `minimatch`(GHSA-g3hv-75wg-m249)와 low
   `deepmerge`(GHSA-gcqx-w48m-w2h4)가 devDependency 경로(eslint·typescript-eslint·shadcn CLI)에 남아 있다. 게이트(high 이상)를
   막지 않으며 상위 갱신을 기다린다.
9. #105 자산 알림의 사람 확인 AC8(Discord 배치·세로 여백 육안)·AC9(실 key 값 검산)은 머지 후 배포 환경에서 수행한다(대기).
   체크리스트는 [`account-pnl-alert.md`](../docs/runbooks/account-pnl-alert.md)에 있다.
10. #107 AC7·AC8(cleanup workflow 실제 삭제·일정 실행)은 머지 뒤 관찰 대기다. AC7 표본은 cache 가 남은 PR 이 닫힐 때만 생긴다.

## 운영 정본

- 시스템 구조: [`.ai/architecture/ARCHITECTURE_DESIGN.md`](architecture/ARCHITECTURE_DESIGN.md)
- 설정/profile: [`docs/runbooks/configuration-profiles.md`](../docs/runbooks/configuration-profiles.md)
- Redis: [`docs/runbooks/redis-contract.md`](../docs/runbooks/redis-contract.md)
- Auth: [`docs/runbooks/auth-security.md`](../docs/runbooks/auth-security.md)
- Notification: [`docs/runbooks/durable-notification-delivery.md`](../docs/runbooks/durable-notification-delivery.md)
- Migration: [`docs/runbooks/v12-migration.md`](../docs/runbooks/v12-migration.md)
- Deploy/Rollback: [`docs/runbooks/deployment.md`](../docs/runbooks/deployment.md)
- Metrics/Alert: [`docs/runbooks/metrics-alerting.md`](../docs/runbooks/metrics-alerting.md)
