# PRIVATE LIVE Autotrader 진행 기록

> append 중심의 Phase acceptance 증거 문서다. 소스 구조의 정본은 `.ai/architecture/ARCHITECTURE_DESIGN.md`, 프로그램 범위와
> 완료 조건의 정본은 `docs/work/private-live-autotrader/design.md`다.

## 프로그램 상태

`docs/work/private-live-autotrader/design.md` §4의 상태축 현재값은 이 문서가 단독으로 소유한다.

| 상태축 | 현재값 |
|---|---|
| specification | `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL` (Review C 반영본) |
| software | `SOFTWARE_BASELINE` |
| evidence collection | `COLLECTION_NOT_READY` |
| candidate/evidence | 해당 candidate 없음 |
| activation | `ACTIVATION_NOT_STARTED` |
| program | `PROGRAM_IN_PROGRESS` |

- 현재 Phase: `Master specification 사용자 승인 대기`
- feature branch: `docs/private-live-autotrader-master-spec`
- worktree: `/mnt/c/users/yeop/ideaprojects/premium-spread/.worktrees/docs-private-live-autotrader-master-spec`
- Draft PR: `NOT_CREATED`
- base `origin/dev`: `15cc02f820ed688dae5ef7b38ce50245f2cb1566`
- original master plan commit: `b6e16edb3632978728f62918bddb25f791501467`

## Master specification 재작성 — 2026-07-21

### 이전 Phase 인수

- PR #63은 `dev`에 merge됐고 merge commit은 `15cc02f820ed688dae5ef7b38ce50245f2cb1566`다.
- merged `dev` Quality Gate run `29796418475`의 7개 job은 모두 성공했다.
- 완료된 `feat/private-live-autotrader` local/remote branch와 linked worktree는 정리했다.
- `refactor/infrastructure-boundary` worktree에는 고유 untracked 계획 문서가 있어 강제 삭제하지 않았다.
- 주 작업트리의 사용자 소유 dirty 파일은 수정하거나 새 worktree로 가져오지 않았다.

### scope reset

- project-local `.worktrees/`는 merged `dev`의 `.gitignore`에 포함돼 있다.
- dirty한 주 작업트리에서 최신 `origin/dev`를 직접 기준으로 linked worktree를 만들었다.
- 전체 계획 재감사 결과 기존 master가 roadmap, 상세 설계, 운영 절차와 PR checklist를 중복 소유한다고 판정했다.
- 사용자는 상세 구현 계획이 아닌 상위 specification으로 재작성하고, 각 Phase consumer가 `work`에서 PR 분할을 결정하도록
  승인했다.
- 이에 따라 branch/worktree를 docs-only master specification 책임으로 변경했다.
- 미구현·미커밋 Phase 0A design/DoD는 새 Phase 경계와 충돌하므로 폐기했다.
- 제품 구현은 시작하지 않았으며, Phase -1 이후 product source 변경은 없다.

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, 67 tasks |
| quality/dependency/local-offline/deploy/documentation contracts + `git diff --check` | PASS, exit 0 |
| worktree HEAD와 `origin/dev` 비교 | 둘 다 `15cc02f820ed688dae5ef7b38ce50245f2cb1566` |

### 재작성된 Phase 경계

- Phase 0: Foundation Alignment
- Phase 1: Market & Economics Foundation
- Phase 2: SIMULATION + PAPER Product
- Phase 3: PRIVATE LIVE Capability
- 각 Phase는 capability milestone이며 branch/PR 단위가 아니다.
- Phase consumer가 최신 코드와 첫 consumer를 분석해 `work`에서 PR map과 DoD를 별도로 승인받는다.
- 장기 데이터, 환경, 법률·계정과 activation은 software Phase와 분리한 program gate다.
- master 초안은 Codex 내부 감사 뒤 Claude 2회 독립 리뷰를 거쳐 사용자 최종 승인을 받는다.

### Claude 독립 Review A — 2026-07-22

- 관점: 제품 요구사항 누락, 현재 아키텍처 불변식, 외부 리서치와의 의미 충돌
- 방식: 별도 Claude 세션에서 repository와 리서치 정본을 읽기 전용으로 검토
- 최초 판정: `REVISE`
- 반영한 blocker: SHADOW mode와 Phase 소유권, margin·강제 감축 안전 계약
- 반영한 major: 장기 증거 수집 readiness, bounded LIMITED 종점, evidence 만료, 정본 `MarketPair`, 안정 ID 추적성,
  안전 중요 전이의 owner 알림, 자본 배치·재배치 비용, migration 단일 실행 주체
- 반영한 minor: 기존 migration 세부명 제거, 파생상품 위험 자세의 추상화, mode 간 경제 동등성, 상태명 통일, 논리 책임과
  물리 배치 분리, 외부 리서치의 비구속성, 불리한 비용 조건, self-induced duplicate와 외부 불명 결과의 구분
- 열린 Review A finding: `0` — 반영본은 문서 검증 후 Review B로 전달

### Claude 독립 Review B와 closure — 2026-07-22

- 관점: specification 추상화, requirement traceability, Phase 소비 가능성, 상태·gate adversarial scenario, 보안 적정성
- 방식: Review A의 finding과 결론을 전달하지 않은 별도 Claude 세션에서 읽기 전용 검토
- 최초 판정: `REVISE` — blocker 2, major 5, minor 8
- 반영한 blocker: Phase 2의 strategy decision 소유권, evidence collection과 activation의 진행 상태
- 반영한 major: candidate 비용 gate, Phase 2 `MarketPair`, specification/software/program 상태축, 독립 PR slice 병렬성,
  active candidate reject의 fail-closed 전이
- 반영한 minor: `LIVE-10` 단일 정본화, migration 검증 참조, roadmap 순환 제거, 공통 비회귀, credential 노출 surface,
  논리·물리 저장 비례성, architecture 정본 우선순위, program completion·NO_GO ID
- 동일 Reviewer B closure: `PASS` — 기존 15개 finding 모두 `RESOLVED`, 신규 blocker/major 0
- 최종 reviewer 답변: master specification 사용자 승인 요청 가능 `YES`
- 현재 specification 상태: `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL`

### Claude 독립 Review C와 반영 — 2026-07-25

- 관점: repository 실제 코드·스키마와의 대조, 제품 구조 리스크, 문서 내부 정합성
- 방식: Review A/B 결과를 전달하지 않은 별도 세션에서 읽기 전용 검토 후 사용자 지시로 반영본 작성
- 판정: `REVISE` — blocker 3, major 7, minor 7

반영한 blocker

- 정본 identity: `ARCH-9`가 `MarketPair(symbol, koreaExchange, foreignExchange)`를 정본으로 못박았으나 §1.2의 해외 leg는
  Binance USDT perpetual이고 현재 batch도 이미 `fstream` 선물 stream을 쓴다. instrument class·quote currency를 구분하지
  못하는 identity를 정본으로 고정하지 않도록 `ARCH-9`을 최소 구성 요구로 재작성하고 확장 판정을 `P0-O3`에 귀속했다.
- evidence clock: 현재 `Ticker`는 단일 `price`만 보존하고 Bithumb은 체결가, Binance는 best bid/ask mid이며 호가 수량이
  없다. 기존 이력이 `DATA-3`을 소급 충족할 수 없다는 사실과 그로 인해 장기 증거 clock이 사실상 새로 시작한다는 점을
  `ECG-5`, `DATA-3`, `P1-O1`, §4.3, roadmap에 명시했다.
- 자본 구조: 자동 transfer가 없는 delta-hedged 구조에서 leg 손익이 반대 부호로 실현돼 한쪽 자본이 소진된다는 실행
  가능성 제약을 `ECO-5`로 신설하고 `SAFE-9`, `P2-O10`, `P3-O16`, `ACT-1`에 연결했다.

반영한 major

- Phase 1의 first-consumer 정합성(`P1-O8` vs §6.2), host-local 긴급 중단 대칭성(`SAFE-6`/`LIVE-6`/`P3-O7`),
  수치 스케일·반올림 소유(`ECO-3`), durable intent 선행 순서(`SAFE-1`/`P3-O5`), 상태축 현재값 SSOT와 초기값
  (`SOFTWARE_BASELINE`, §4.2, 이 문서), leg별 provider 검증 단계(`ACT-3`/`P3-O15`/§7.2), 고정 egress와 credential
  rotation(`ACT-2`/§3.5)

반영한 minor

- venue와 provider 소유 구분(§0.3), roadmap의 evidence 분기 시점, 프로그램 재평가 조항(`NOGO-0`), `QUAL-1`의 T2 판정
  근거, `SEM-4`의 leverage 오인 방지, Phase DoD의 ID 역방향 인용 강제(§8), §0.1의 상태 중복 기록 제거

- 열린 Review C finding: `0` — 사용자 승인 대기

## Phase -1 기준선 — 2026-07-20

### 격리

주 작업트리에는 아래 사용자 소유 변경이 있었으며 feature worktree로 가져오거나 수정하지 않았다.

```text
 M .gitignore
 M deploy/nginx.ssl.conf
 M deploy/setup-server.sh
?? .ai/planning/private-live-autotrader/
?? .claude/worktrees/
?? .oci-launch.env
```

project-local `.worktrees/`는 ignore 대상이 아니고 `.gitignore`가 사용자 변경 중이므로 외부 linked worktree를 사용했다.

### 변경 전 검증

| 명령 | 결과 | 구분 |
|---|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | exit 0, `BUILD SUCCESSFUL in 5m 15s`, 67 tasks | 기준선 GREEN |
| `bash ci/quality-gate-contract-test.sh && bash ci/dependency-bootstrap-contract-test.sh && bash docker/deploy-contract-test.sh && bash docs/check-documentation.sh` | exit 0 | 기존 계약 GREEN. Phase -1과 반대인 legacy 계약을 검증하므로 새 contract RED/GREEN과 구분 |
| `npm --prefix apps/web ci --include=optional` | exit 0, 706 packages, audit moderate 2건 | install 기준선 GREEN; Phase -1 dependency 변경 없음 |
| `npm --prefix apps/web run lint` | exit 0 | 기준선 GREEN |
| `npm --prefix apps/web run build` | exit 1: Node 18.20.8, Next.js 16은 Node 20.9+ 요구 | 환경 BLOCKED; 소스 compile 이전 실패 |

Node 20을 사용하는 Draft PR의 기존 Quality Gate가 Phase acceptance의 Web/Docker build 증거를 제공한다. 로컬 Node runtime을
이 Phase에서 다운로드하거나 repository scope로 추가하지 않는다.

### CI·운영 책임 경계

- GitHub Actions에는 exchange credential을 제공하지 않는다.
- 기존 GitHub-hosted `Quality Gate`만 유지한다.
- 장기 soak는 production credential이 없는 validation host에서 operator가 실행한다.
- migration, 배포, canary와 activation은 PRIVATE_LIVE host에서 operator가 host-local 명령으로 실행한다.
- runtime service account는 operator-owned 명령 파일과 배포 bundle을 수정할 수 없어야 한다.
- Phase -1은 runner, validation host, PRIVATE_LIVE host 또는 실제 secret을 구성하지 않는다.

## Phase acceptance 기록

Phase -1 구현·review·검증 결과와 acceptance commit/push는 이 아래에 append한다.

### Task 1~3 direct/review 결과 — 2026-07-20

- Task 1 direct verification: PASS — `bash ci/dependency-bootstrap-contract-test.sh`, exit 0,
  `dependency bootstrap behavioral contracts verified`
- Task 1 Spec Review: PASS — open finding 0
- Task 1 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 2 direct verification: PASS — `bash docker/deploy-contract-test.sh`, exit 0, `deploy contracts verified`
- Task 2 Spec Review: PASS — open finding 0
- Task 2 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 3 direct verification: PASS — `bash ci/quality-gate-contract-test.sh`, exit 0,
  `quality gate and supply-chain contracts verified`
- Task 3 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 3 Code Review: PASS — HIGH/MEDIUM/LOW 0, review mutation 11개를 모두 fail-closed로 탐지

### 실제 운영 상태 — 2026-07-20

- 실제 host/activation: NOT_RUN
- Phase -1 범위에서는 실제 host, secret manager, runner 또는 activation을 구성하지 않는다.

### Task 4 direct/review 결과 — 2026-07-20

- Task 4 direct verification: PASS — documentation check, 확장 AC4 semantic contract와 `git diff --check`, 모두 exit 0
- Task 4 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 4 Code Review: PASS — 초기 MEDIUM 3건과 후속 migration 순서 MEDIUM 1건을 수정한 뒤 HIGH/MEDIUM/LOW 0
- 실제 host/activation: NOT_RUN

### Phase -1 로컬 acceptance — 2026-07-20

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 1m 13s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; audit moderate 2건은 기존 기준선과 동일 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| Quality/dependency/deploy/documentation contracts + shell syntax + `git diff --check` | PASS, exit 0 |

Web build는 green으로 기록하지 않는다. Phase acceptance commit을 push한 뒤 Node 20을 사용하는 Draft PR `#63`의 7개
Quality Gate job 결과로 판정한다.

### 동결 변경 재승인 — 2026-07-21

- Holistic Spec Review: 동결 AC4의 5→8문서 범위 확장에 대한 사용자 명시적 재승인 전까지 FAIL
- Holistic Code Review: PR artifact 오승격 가능성 MEDIUM 1건을 merged `dev`의 `event=push`, `branch=dev` 경계로 수정
- Holistic Code Re-review: PASS — HIGH/MEDIUM 0
- 사용자 재승인: `승인`

### 재승인 후 acceptance 재검증 — 2026-07-21

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 24s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; 현재 advisory snapshot 1 low·2 moderate, lockfile 변경 없음 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| AC1~AC4 contract, 전체 shell syntax와 `git diff --check` | PASS, exit 0 |

- Final Spec/DoD Re-review: PASS — HIGH/MEDIUM/LOW 0
- Final Code Re-review: PASS — HIGH/MEDIUM 0, 기존 README의 개발용 `--build` 안내 LOW는 Phase 0 후속
- PR #63 Node 20 Quality Gate: acceptance commit push 전이므로 PENDING

### 원격 acceptance RED와 Task 5 remediation — 2026-07-21

- acceptance commit: `c6fec014da4afeb5e356da9d89e6607e0d20db0c`, push 완료
- PR run: `29789180354`, Compile + architecture job `88507065048`
- 원격 결과: FAIL — repository contract step에서 `rg: command not found`, 이후 6개 job은 dependency 때문에 SKIPPED
- root cause: GitHub-hosted runner가 제공하지 않는 ripgrep을 contract script 두 곳이 암묵적으로 요구
- 수정: runner package/Action을 추가하지 않고 `ci/quality-gate-contract-test.sh`, `docs/check-documentation.sh`를 기본
  `grep`/`find`로 의미 보존 이식
- Task 5 direct verification: PASS — shadow `rg=127`과 일반 환경에서 quality/docs/dependency/deploy contract, syntax,
  `git diff --check` 모두 exit 0
- Task 5 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 5 Code Review: PASS — HIGH/MEDIUM/LOW 0
- 새 PR Quality Gate: remediation commit push 전이므로 PENDING

### Phase -1 원격 acceptance GREEN — 2026-07-21

- remediation commit: `f40d4b2ba66d64904158eaabbfa99450e3052339`
- PR run: `29790214157`
- 1. Compile + architecture: SUCCESS
- 2. Unit + coverage: SUCCESS
- 3. API integration: SUCCESS
- 4. Batch integration: SUCCESS
- 5. ktlint + detekt: SUCCESS
- 6. Dependency + security scan: SUCCESS
- 7. Docker image build: SUCCESS
- 결과: `7/7 SUCCESS`, Phase -1 DoD `VERIFIED`
- 실제 host/activation: NOT_RUN
