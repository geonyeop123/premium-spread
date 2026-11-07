---
feature: apps/web npm overrides 재검토
slug: issue-97-web-npm-overrides
status: FROZEN
frozen_at: 2026-11-07
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/97
---

## 범위

**포함**

- `apps/web/package.json`의 `overrides` 14개 전부 제거. 블록 자체를 지운다 (③ 합의 1)
- `apps/web/package-lock.json`을 `npm install --package-lock-only` 재해결 결과(design §4의 15건)로 갱신
- override별 유지·제거 판정표와 근거 (design §3), 열린 advisory 2건(minimatch GHSA-g3hv-75wg-m249, deepmerge GHSA-gcqx-w48m-w2h4)의 "상위 갱신 대기" 기록 (design §3.1)
- override 정책(재도입 조건·기록 표)을 `docs/runbooks/deployment.md`에 두고, `apps/web/README.md`에 그 절을 가리키는 절 추가 (design D4·D5)
- 현재 npm(10.8.2)에서 `npm audit fix`가 동작하는지 재확인하고 기록. 결과는 적용하지 않는다 (design §5)

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 직접 의존성 major 상향
- CI 게이트 변경 (`ci/run-npm-audit.sh` 임계값, `ci/npm-audit-suppressions.json`)
- lockfile 전체 재생성, `npm audit fix` 결과 적용, Dependabot PR #112~#116 머지
- Dependabot alert dismiss
- `docs/work/issue-82-web-npm-audit/` 수정

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` | 아니오. JVM 모듈 대상이라 `apps/web`을 보지 않는다 |
| lint 규칙 | 있음 — `npm --prefix apps/web run lint` (eslint 9 + eslint-config-next) | 예. R2. `@eslint/config-array` 복귀 영향을 잡는다 |
| custom gradle/npm task | 있음 — `ci/run-npm-audit.sh`(suppression 0건 강제 + `npm audit --audit-level=high`), `npm run test`(vitest), `npm run build`(next build), `npm ls`, `npm pkg get` | 예. R1·R3·R4, AC1·AC2 |
| 계약·문서 검사 | 있음 — `ci/quality-gate-contract-test.sh`(`next`==`eslint-config-next` 버전, web install 플래그 등), `docs/check-documentation.sh`(정본 문서의 링크·placeholder) | 예. R5·R6 |
| CI job | 있음 — `.github/workflows/quality-gate.yml` 6번 잡(`npm ci --include=optional` → lint → test → audit), 7a·7번 잡(web Docker image matrix build + 조립) | 예. AC6 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `apps/web/package.json`에 `overrides` 키가 없을 때 `npm pkg get overrides`가 빈 객체를 출력한다 | design D1 | `요구` (③ 합의 1) | T2 | `기존` `npm pkg get` | `npm --prefix apps/web pkg get overrides` | 출력이 `{}`이고 design §3의 패키지 이름이 하나도 없다. RED: 14개 키를 가진 객체 |
| AC2 | `npm ci --include=optional`로 설치한 트리에서 `npm ls`가 design §4의 재해결 결과를 보여 주고, 범위 내 갱신은 섞이지 않는다 | design §4, D2 | `요구` (③ 합의 1) | T2 | `기존` `npm ls` | `npm --prefix apps/web ls browserslist @eslint/config-array brace-expansion postcss typescript-eslint shadcn --all` | exit 0. 출력에 `overridden`·`invalid`가 없다. 버전 집합이 정확히 `browserslist` {4.29.5}, `@eslint/config-array` {0.21.2}, `brace-expansion` {1.1.21, 5.0.12}, `postcss` {8.5.23, 8.5.25}, `typescript-eslint` {8.56.1}, `shadcn` {4.22.1}이다. RED: `browserslist@4.28.9`·`@eslint/config-array@0.23.5`에 `overridden` 표시 |
| AC3 | design §3 판정표에 14개 override가 한 행씩 있고, 각 행에 도입 커밋·도입 근거·제거 시 high 건수·판정이 있다. §3.1에 minimatch·deepmerge advisory가 GHSA ID·소비자 범위·"열어 둔다" 판정과 함께 있다 | 이슈 AC1, ③ 합의 2 | `요구` | T3 | `관찰` `docs/work/issue-97-web-npm-overrides/design.md` | — | 리뷰어가 14행 + 2행을 확인하고, Task 2의 `audit --json` 결과(남은 advisory가 이 두 개뿐)와 대조한다 |
| AC4 | 최종 브랜치의 `package.json`·lockfile 사본에서 `npm audit fix --package-lock-only`가 `edgesOut` 크래시 없이 끝까지 실행된다. 그 결과(exit code·변경 건수)가 증거 로그에 남고, 브랜치에는 적용되지 않는다 | 이슈 AC4, design §5 | `요구` | T2 | `기존` `npm audit fix` | `npm --prefix <scratch>/issue97-auditfix audit fix --package-lock-only --ignore-scripts --no-fund` (`<scratch>`는 Task 3의 임시 디렉터리) | stderr에 `edgesOut`이 없다. 사본 lockfile이 바뀐다(변경 1건 이상). exit code를 그대로 기록한다(실측 예상값 1: force 없이 고칠 수 없는 moderate·low가 남음). worktree의 `apps/web/package-lock.json`은 Task 1 커밋과 같다 |
| AC5 | `docs/runbooks/deployment.md` "apps/web 의존성 갱신 (Dependabot)" 절에 overrides 정책 항목(재도입 조건, 기록 표 헤더, 현재 등록 0건)이 있고 "(재검토는 #97)" 문장이 없다. `apps/web/README.md`에 그 절을 가리키는 "의존성 overrides 정책" 절이 있다 | 이슈 "근거를 README 또는 docs에 표로 기록", design D4·D5 | `리뷰` | T3 | `관찰` 두 문서 diff | — | plan Task 4의 전문과 diff가 일치한다. R6 통과 |
| AC6 | PR head 커밋에서 `quality-gate.yml`의 check 11개(1~6, 7a×3, 7 조립, 8)가 전부 success다 | 이슈 AC2·AC3의 CI 대응, design D6 | `리뷰` | T3 | `기존` `.github/workflows/quality-gate.yml` | — | `gh pr checks` 기록에 11/11 success |

**기준 개수**: 6개

**분할하지 않는 이유**: (8개 이하라 해당 없음)

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC1 → T2: `npm pkg get`은 키가 없어도 exit 0이다. 판정은 출력 비교로만 할 수 있다. 일회성 확인을 위해 검사 스크립트를 새로 만드는 것은 과하다
- AC2 → T2: `npm ls`의 exit code는 트리 정합성만 판정한다. 기대 버전과 같은지는 출력을 비교해야 한다. 이유는 AC1과 같다
- AC3 → T3: 판정표는 사람이 읽는 산출물이다
- AC4 → T2: 임시 사본을 만드는 준비 단계가 필요하다. 기대 결과가 exit 0이 아니라 "크래시 없이 실행됨"이라 exit code만으로 판정할 수 없다
- AC5 → T3: 문서 내용은 사람이 읽는 산출물이다. 링크·placeholder 같은 기계 검사는 R6가 맡는다
- AC6 → T3: CI 결과는 GitHub에서만 관찰할 수 있다

**보존형 이슈 AC의 위치** *(Gate 1 확인 요청)*

- 이슈 AC2(`npm ci` + `run-npm-audit.sh` exit 0)와 AC3(lint·test·build)은 현재 상태에서도 통과한다. 그래서 RED를 만들 수 없다.
  수용기준이 아니라 회귀 방어선 R1~R4로 둔다. 이슈 요구에서 나온 것은 그대로다

## 검사 산출물

해당 없음 (`신규스크립트` 미사용).

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 깨끗한 `npm --prefix apps/web ci --include=optional` 뒤 audit 게이트가 high·critical 0건으로 exit 0 (이슈 AC2) | `bash ci/run-npm-audit.sh` |
| R2 | eslint가 오류 0건으로 종료한다 (이슈 AC3) | `npm --prefix apps/web run lint` |
| R3 | vitest 기존 테스트가 통과한다 (이슈 AC3) | `npm --prefix apps/web run test` |
| R4 | `next build`가 6개 라우트(`/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]`)를 빌드한다 (이슈 AC3) | `npm --prefix apps/web run build` |
| R5 | Quality Gate 계약 테스트가 통과한다 (`next`==`eslint-config-next` 등) | `bash ci/quality-gate-contract-test.sh` |
| R6 | 정본 문서의 링크·placeholder 검사가 통과한다 | `bash docs/check-documentation.sh` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC1

- [RED] 2026-11-07 4c7cbfc — `npm --prefix apps/web pkg get overrides` exit 0. `sharp`부터 `@humanfs/node`까지 14개 키 객체 출력
- [GREEN] 2026-11-07 360e2af — 같은 명령(깨끗한 `npm ci --include=optional`, exit 0 뒤) 출력 `{}`

### AC2

- [RED] 2026-11-07 4c7cbfc — `npm --prefix apps/web ls browserslist @eslint/config-array brace-expansion postcss typescript-eslint shadcn --all` exit 0.
  `browserslist@4.28.9 overridden`, `@eslint/config-array@0.23.5 overridden`, `brace-expansion@5.0.12 overridden`, `postcss@8.5.25` 단일
- [GREEN] 2026-11-07 360e2af — 같은 명령 exit 0, `overridden`·`invalid` 없음. 버전 집합:
  `browserslist@4.29.5`, `@eslint/config-array@0.21.2`(eslint 하위), `brace-expansion@5.0.12`(typescript-eslint·ts-morph 경로)와
  `1.1.21`(minimatch@3.1.5 경로), `postcss@8.5.25`(tailwind·vite·shadcn)와 `8.5.23`(next 하위), `typescript-eslint@8.56.1`, `shadcn@4.22.1`

### R 기준선

- [RED] 2026-11-07 4c7cbfc — `npm ci --include=optional` exit 0. `bash ci/run-npm-audit.sh` exit 0, `15 vulnerabilities (2 low, 13 moderate)`, high 0

### Task 1 lockfile 변경

- [GREEN] 2026-11-07 360e2af — `npm --prefix apps/web install --package-lock-only --ignore-scripts --no-audit --no-fund` exit 0.
  LOCKDIFF `changed=15`, plan 계약 2와 줄 단위 일치(browserslist 4.28.9→4.29.5, minimatch/config-array 제거, concat-map·brace-expansion@1.1.21 추가 등).
  `git diff --stat -- apps/web`: package-lock.json, package.json 두 파일

### AC3 / R1~R5 (Task 2)

- [GREEN] 2026-11-07T05:33Z 360e2af — 깨끗한 `npm ci --include=optional` exit 0 이후
  - R1 `bash ci/run-npm-audit.sh` exit 0
  - `npm audit --json` exit 1, ran_at=2026-11-07T05:33:11Z, `{"info":0,"low":2,"moderate":11,"high":0,"critical":0,"total":13}`
  - audit advisory: `deepmerge low GHSA-gcqx-w48m-w2h4`, `minimatch moderate GHSA-g3hv-75wg-m249` (이 둘뿐)
  - Dependabot open alert(npm): `#2 GHSA-g3hv-75wg-m249 minimatch medium`, `#1 GHSA-gcqx-w48m-w2h4 deepmerge low` (audit와 일치)
  - R2 lint exit 0 · R3 test exit 0 (`Tests 10 passed (10)`) · R4 build exit 0 (`/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]` 6개) · R5 `ci/quality-gate-contract-test.sh` exit 0 (`quality gate and supply-chain contracts verified`)

### AC4

- [GREEN] 2026-11-07 360e2af — npm 10.8.2. 사본(SCRATCH/issue97-auditfix)에서 `npm audit fix --package-lock-only --ignore-scripts --no-fund`
  exit 1(force 없이 못 고치는 moderate 11·low 2 잔존), stderr `edgesOut` 0건. 사본 LOCKDIFF `changed=158`
  (대표: `@babel/generator 7.29.7→7.29.13`, `@shadcn/registry 0.2.0→0.2.1`, `@typescript-eslint/parser 8.56.1→8.72.1`).
  `git status --porcelain apps/web` 빈 출력(브랜치에 미적용)

### R6

- [GREEN] 2026-11-07 360e2af(+문서 변경) — `bash docs/check-documentation.sh` exit 0, `documentation check passed (20 files, 15 required paths)`.
  `grep -n '재검토는 #97' docs/runbooks/deployment.md` 출력 없음(exit 1)

### ⑨ 리뷰 기록 (AC3·AC5 T3 제출)

- [REVIEW] 2026-11-07 ff95bd4 — spec-reviewer(계약): BLOCKER·MAJOR 0, MINOR 2(이 절 위 AC1·AC2 GREEN 줄에 ISO 시각 없음, R6 앵커가 미커밋 상태)
  → 아래 ⑩ 최종 검증 줄로 해소. package.json = plan 계약 1, runbook·README·PROJECT_STATUS = Task 4 계약 1~3 전문 일치, design §3 14행 + §3.1 2행 확인(AC3·AC5)
- [REVIEW] 2026-11-07 ff95bd4 — code-reviewer(버그·회귀): 지적 0. lockfile `dev` 플래그·선언 범위·integrity 정합, 제거한 override의 원 advisory 재개방 경로 없음,
  origin/dev 대비 audit moderate 13→11
- [CHECK] 2026-11-07 ff95bd4 — 오케스트레이터: `git show --stat 4c7cbfc` = dod.md 1파일 2줄(동결 단독). `git diff 4c7cbfc HEAD -- dod.md` 삭제 줄 0(수용기준 불변).
  origin/dev lockfile 대비 version 변경 15건, 같은 버전 필드 변경은 `node_modules/postcss` `dev: true` 1건(next가 하위 8.5.23 사본을 쓰게 된 결과)

### ⑩ 최종 검증 (ff95bd4)

- [GREEN] 2026-11-07T05:36:44Z ff95bd4 — `./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon`
  exit 0, `BUILD SUCCESSFUL in 3m 57s`, 실패·오류·skip 0 (unit 합계 1,416 · architectureTest 35 · api integration 174 · batch integration 117 · common integration 96). `harnessCheck` exit 0
- [GREEN] 2026-11-07T05:36:56Z ff95bd4 — 깨끗한 `npm --prefix apps/web ci --include=optional` exit 0
- [GREEN] 2026-11-07T05:37:20Z ff95bd4 — AC1 `pkg get overrides` → `{}`. AC2 `npm ls ... --all` exit 0, overridden·invalid 없음, 버전 집합 위 AC2 GREEN과 동일
- [GREEN] 2026-11-07T05:37:30Z ff95bd4 — R1 `bash ci/run-npm-audit.sh` exit 0. `npm audit --json` `{"info":0,"low":2,"moderate":11,"high":0,"critical":0,"total":13}`, advisory는 minimatch GHSA-g3hv-75wg-m249 · deepmerge GHSA-gcqx-w48m-w2h4 둘뿐
- [GREEN] 2026-11-07T05:37:32Z~05:37:54Z ff95bd4 — R2 lint exit 0 · R3 test `10 passed` · R4 build 6개 라우트 · R5 contract exit 0 · R6 `documentation check passed (20 files, 15 required paths)`
- [CHECK] 2026-11-07 ff95bd4 — 경계면: package.json ↔ lockfile `packages[""]` 선언 일치(overrides 양쪽 없음), Dockerfile `npm ci --include=optional` ↔ CI 6번 잡 동일, 변경 문서 상대 링크 실재

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.
> 앵커는 이 파일 밖에 있어야 한다 — PR 코멘트 URL, 커밋 SHA, 이슈 링크.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|

(T4 기준 없음)

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

> 아래 수는 전부 위 수용기준 표에서 센 값이다. 표와 어긋나면 판정을 낼 수 없다.
> 판정이 가리키는 SHA와 브랜치 최종 SHA가 다르면 그 판정은 만료다.

```
DoD VERDICT: issue-97-web-npm-overrides @ <판정 시 기입>
  수용기준 표:     6개  (T1 0 · T2 3 · T3 3 · T4 0)
  T1/T2 자동:      3개 중 <p>개 PASS
  T3 기록 제출:    3개 중 <q>건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       <k>건
  =>
```

**사람 확인이 필요한 항목**

- 없음 (T4 기준 없음). Gate 1에서 티어 강등 사유 6건과 "보존형 이슈 AC의 위치"를 승인받는다
