---
feature: apps/web Node 런타임 20→24 전환과 jsdom 30 적용
slug: issue-118-web-node24
status: DRAFT
frozen_at:
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/118
---

> 변경 이력(DRAFT): ⑥ 스펙 리뷰 반영 — AC2를 변형 13종·C1~C7로 넓힘, AC4 기준 lock을 `BASE`로 고정하고 `changed>=1` 추가,
> R1에 audit 패키지 이름 집합·advisory 비교 추가, 증거 로그에 `### R2·R3·R4` 칸 추가

## 범위

**포함**

- CI 6번 잡 `node-version` `'20'`→`'24'`, `apps/web/Dockerfile` builder·runtime 두 stage를 `node:24-alpine@sha256:a34ab6d7…67c8`로 (③ 합의 1, design D1·D8)
- `apps/web/package.json`의 jsdom `^30.1.3`, `@types/node` `^24.19.2`, `engines` `{"node": "^24.15.0"}` 추가와 lockfile 재해결 (③ 합의 2·3, design D2~D5)
- `ci/quality-gate-contract-test.sh`에 Node major 일치 계약(C1~C7) 추가와 변형 검증 (design D6)
- `.github/dependabot.yml` 주석 근거 문구, `docs/runbooks/deployment.md`(77행 + "Node 런타임 major 갱신" 항목), `README.md` 사전 요구사항 (③ 합의 3, design D7)
- `.ai/PROJECT_STATUS.md` 진행 현황, `docs/work/README.md` 인덱스, 이 디렉터리 README (⑪)

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 과거 기록 문서의 "Node 20" 언급 수정 (`docs/plans/2026-02-26-*`, `.ai/planning/**`, `docs/dod/private-live-*`, `docs/superpowers/**`, 완료된 `docs/work/*`)
- `engine-strict` 설정, `.nvmrc`·`.node-version` 추가
- `.github/dependabot.yml`의 `@types/node` major ignore 해제
- 다른 의존성 갱신, `npm audit fix`, `actions/setup-node` 액션 버전 변경
- `apps/api`·`apps/batch` Dockerfile, Gradle 빌드, `.ai/PROJECT_STATUS.md` Known Issue 8

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` | 아니오. JVM 모듈 대상이라 `apps/web`·CI 설정을 보지 않는다 |
| lint 규칙 | 있음 — `npm --prefix apps/web run lint` | 예. AC5 (Node 24에서 실행) |
| custom gradle/npm task | 있음 — `npm run test`(vitest + jsdom), `npm run build`, `npm ls`, `npm pkg get`, `ci/run-npm-audit.sh` | 예. AC3·AC4·AC5, R1 |
| 계약·문서 검사 | 있음 — `ci/quality-gate-contract-test.sh`(`next`==`eslint-config-next`, web install 플래그 등. Node 버전은 보지 않음), `docs/check-documentation.sh` | 예. 계약 테스트에 Node 블록을 더해 AC1·AC2로 쓴다. R2·R3 |
| CI job | 있음 — `.github/workflows/quality-gate.yml` 1번 잡(계약 테스트), 6번 잡(Node `npm ci` → lint → test → audit), 7a·7번 잡(web 이미지 빌드·조립) | 예. AC9 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | CI `node-version`, Dockerfile 두 stage, `engines.node`, `@types/node`의 Node major가 일치하고 Dockerfile 이미지가 digest로 고정돼 있을 때만 계약 테스트가 통과한다 | design D6, plan Task 3 계약 1 | `리뷰` (#114 재발 방지) | T1 | `기존` `ci/quality-gate-contract-test.sh` (Node 블록 추가) | `bash ci/quality-gate-contract-test.sh` | exit 0, `quality gate and supply-chain contracts verified`. RED: dev 상태(M0)에서 exit 1, `engines must be exactly one` |
| AC2 | 계약 검사 C1~C7이 각각의 위반 변형을 기대 메시지로 거부한다 | design D6-b, plan Task 3 계약 2 | `리뷰` | T2 | `기존` 계약 테스트 + 변형 13종 | plan Task 3 실행 2) (`mutate M0` ~ `mutate M12`) | `M0 RED ok` ~ `M12 RED ok` 13줄, `NOT-APPLIED`·`FAIL` 0줄, `targets restored`, 복원 뒤 계약 테스트 exit 0. C1~C7 각각을 잡는 변형이 하나 이상 있다(plan Task 3 계약 2 "잡는 규칙" 열) |
| AC3 | CI·Dockerfile·package.json의 선언 값이 plan Task 1·2 계약과 같다 | ③ 합의 1·2·3 | `요구` | T2 | `기존` `grep`, `npm pkg get` | `grep -n "node-version" .github/workflows/quality-gate.yml && grep -n '^FROM' apps/web/Dockerfile && npm --prefix apps/web pkg get engines 'devDependencies.@types/node' devDependencies.jsdom` | `189:          node-version: '24'` 한 줄. `FROM` 두 줄이 plan Task 2 계약 2 전문과 같다. `engines` = `{"node":"^24.15.0"}`, `@types/node` = `"^24.19.2"`, `jsdom` = `"^30.1.3"`. RED: `'20'`, `node:20-alpine@sha256:fb4c…2293`, `engines` 없음, `"^20"`, `"^27.0.0"` |
| AC4 | lockfile이 jsdom 30.1.3·`@types/node` 24.19.2로 재해결되고 루트에 `engines`가 있으며, 바뀐 항목이 전부 dev 트리다 | design D5, plan Task 1 계약 2 | `요구` (③ 합의 2·3) | T2 | `기존` `node -e`(lockfile JSON), `npm ls` | `git show "$BASE:apps/web/package-lock.json" > "$SCRATCH/lock-before.json" && node -e "$LOCKCHECK" "$SCRATCH/lock-before.json" apps/web/package-lock.json` (plan Task 1) + Node 24 컨테이너의 `npm --prefix apps/web ls jsdom @types/node undici --all` (plan Task 4) | `LOCKCHECK` 첫 줄이 plan Task 1 계약 2와 같고, 둘째 줄이 `changed>=1`·`nonDev=[]`. `npm ls`에 `jsdom@30.1.3`·`@types/node@24.19.2`·`undici@7.30.1`, `ls-targets nonzero` 줄 없음, `invalid=0`. RED: `{"engines":null,"types":"20.19.43","jsdom":"27.4.0"}` |
| AC5 | node:24-alpine(v24.22.2) 컨테이너에서 커밋된 HEAD 사본으로 `npm ci --include=optional`·lint·test·build가 통과한다. test는 jsdom 30에서 돈다 | 이슈 #118 (PR #114 실패 해소), design §2 | `요구` | T2 | `기존` npm scripts, `docker run` | plan Task 4 실행 1) | `node -v` = `v24.22.2`. 스크립트가 build 뒤까지 진행한다(네 단계 exit 0). test `10 passed (10)`. build 라우트 6개(`/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]`). RED: dev에서 jsdom이 27.4.0(AC4 RED). Node 20 + jsdom 30 실패는 PR #114 6번 잡 기록이다 |
| AC6 | `apps/web/Dockerfile`로 빌드한 이미지가 Node 24로 기동해 3000 포트에서 응답한다 | design D9 | `리뷰` | T2 | `기존` `docker build`·`docker run`·`curl` | plan Task 4 실행 2) | `docker build` exit 0. `node -v` = `v24.22.2`. `/ 200`, `/login 200`. 로그에 `Ready`. RED: 현 runtime 베이스 `docker run --rm node:20-alpine@sha256:fb4c…2293 node -v` → `v20.` |
| AC7 | 옛 Node 20 문구가 README·runbook·dependabot 주석에서 사라지고 새 문구가 있다 | ③ 합의 3 | `요구` | T2 | `기존` `grep` | plan Task 5 실행의 grep 7개 | 옛 문구 3개(`Node.js 20+`, `Node 런타임(20)`, `이 20이므로`) 출력 없음. 새 문구 4개(`Node.js 24 (apps/web, ^24.15.0)`, `Node 런타임(24)`, `**Node 런타임 major 갱신.**`, `engines)이 24이므로`) 각 1건. RED: 옛 문구 3개가 각 1줄 |
| AC8 | runbook "Node 런타임 major 갱신" 항목이 바꿀 다섯 곳·계약 검사·digest 확인 방법·`engine-strict` 꺼짐을 설명하고, design §5의 제외 문서는 바뀌지 않는다 | design D7, §5 | `리뷰` | T3 | `관찰` 문서 diff | `git diff "$BASE" -- docs/runbooks/deployment.md .github/dependabot.yml README.md` 와 `git diff --stat "$BASE"` | 리뷰어가 diff를 plan Task 5 계약 1~3 전문과 대조한다. `--stat`에 plan 머리말의 변경 파일 밖 경로가 없다 |
| AC9 | PR head 커밋에서 `quality-gate.yml`의 check 11개(1~6, 7a×3, 7 조립, 8)가 전부 success다 | design D10 | `리뷰` | T3 | `기존` `.github/workflows/quality-gate.yml` | — | `gh pr checks` 기록에 11/11 success. 6번 잡은 Node 24에서 실행된다 |

`BASE`·`SCRATCH`·`LOCKCHECK`·`AUDITSUM`은 plan "공통" 절과 Task 1에 정의돼 있다.

**기준 개수**: 9개

**분할하지 않는 이유**: 기준이 8개를 넘지만 ③ 합의 2가 "Node 24 전환과 jsdom 30을 한 PR"로 정했다. 런타임 선언(AC3·AC6)·의존성(AC4·AC5)·계약 검사(AC1·AC2)는 서로 없이는 통과할 수 없다. jsdom 30은 Node 24 없이 test가 실패하고, 계약 검사는 네 값이 함께 바뀌어야 통과한다. 쪼개면 중간 상태 PR이 CI를 통과하지 못한다.

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC2 → T2: 변형은 작업 트리 파일을 바꿨다가 되돌리는 일회성 절차다. 스크립트 안 자가검증으로 넣지 않는 이유는 design D6-b
- AC3 → T2: `grep`·`npm pkg get`은 값이 달라도 exit 0이다. 판정은 출력 비교로만 할 수 있다. 같은 일치 규칙은 AC1(T1)이 상시 검사한다
- AC4 → T2: `changed`·`nonDev`와 해결 버전은 출력 비교로 판정한다. `BASE` lockfile 사본이 필요하다
- AC5 → T2: 컨테이너 실행과 `git archive` 준비가 필요하다. CI 6번 잡이 같은 단계를 Node 24로 다시 돌린다(AC9)
- AC6 → T2: 이미지 빌드·기동·포트 응답은 로컬 docker에서만 관찰한다. CI 7a 잡은 빌드만 하고 기동하지 않는다
- AC7 → T2: 문구 존재는 grep 출력으로 판정한다. 문서 검사 스크립트(R3)는 문구 내용을 보지 않는다
- AC8 → T3: 절차 설명은 사람이 읽는 산출물이다
- AC9 → T3: CI 결과는 GitHub에서만 관찰할 수 있다

## 검사 산출물

`신규스크립트`는 쓰지 않는다. 기존 `ci/quality-gate-contract-test.sh`에 블록을 더한다.

| 산출물 | 위치 | 규칙 | 실행 위치 |
|---|---|---|---|
| Node major 일치 블록 | `ci/quality-gate-contract-test.sh`, #98 블록 뒤·최종 `echo` 앞 | plan Task 3 C1~C7 | 로컬, CI 1번 잡 `Verify repository quality and documentation contracts` |

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | Node 24 컨테이너의 깨끗한 `npm ci --include=optional` 뒤 audit 게이트가 high·critical 0건으로 통과한다(`audit-gate exit=0` 줄). 집계가 dev와 같다(high 0 · critical 0 · moderate 11 · low 2). `packages=`(취약 패키지 이름 집합)가 Task 0의 dev 기준선과 같다. 남은 advisory는 minimatch GHSA-g3hv-75wg-m249·deepmerge GHSA-gcqx-w48m-w2h4 두 개뿐이다 | plan Task 4 실행 1)의 `bash ci/run-npm-audit.sh`·`node -e "$AUDITSUM"` 출력과 `diff <(grep '^packages=' …baseline) <(grep '^packages=' …node24-verify.log)` |
| R2 | 기존 계약(`next`==`eslint-config-next`, dependabot 설정 줄, web install 플래그 등)이 그대로 통과한다 | `bash ci/quality-gate-contract-test.sh` |
| R3 | 정본 문서의 링크·placeholder 검사가 통과한다 | `bash docs/check-documentation.sh` |
| R4 | production 의존성 트리가 바뀌지 않는다(`packages[""].dependencies` 동일, 바뀐 lock 항목 전부 dev) | plan Task 1 `node -e "$LOCKCHECK" ...`의 `prodDepsSame:true`, `nonDev=[]` |

Gradle 전체 회귀는 로컬에서 돌리지 않고 AC9(CI 1~5·8번 잡)로 갈음한다. 근거는 design D10.

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC1

### AC2

### AC3

### AC4

### AC5

### AC6

### AC7

### R 기준선

### R1

### R2·R3·R4

### ⑨ 리뷰 기록 (AC8 T3 제출)

### ⑩ 최종 검증

### AC9

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
DoD VERDICT: issue-118-web-node24 @ (미판정 — DRAFT)
  수용기준 표:     9개  (T1 1 · T2 6 · T3 2 · T4 0)
  T1/T2 자동:      7개 중 0개 PASS (AC1 · AC2 · AC3 · AC4 · AC5 · AC6 · AC7)
  T3 기록 제출:    2개 중 0건 (AC8 ⑨ 리뷰 기록, AC9 PR CI 11/11)
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       0건
  => 미판정
```

**사람 확인이 필요한 항목**

- 없음 (T4 기준 없음). Gate 1에서 티어 강등 사유 8건과 "분할하지 않는 이유"를 승인받는다
