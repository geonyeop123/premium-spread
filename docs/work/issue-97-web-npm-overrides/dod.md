---
feature: apps/web npm overrides 재검토
slug: issue-97-web-npm-overrides
status: DRAFT
frozen_at:
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
