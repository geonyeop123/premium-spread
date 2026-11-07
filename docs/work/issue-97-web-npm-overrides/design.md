# apps/web npm overrides 재검토 — 설계

- 이슈: #97 `apps/web npm overrides 재검토`
- 브랜치: `chore/issue-97-web-npm-overrides` (base `dev`)
- 선행: #82(PR #95) — `npm audit fix` 실패로 전이 의존성 취약점을 `overrides`로 해소. #98 — Dependabot 주기 갱신 도입

## 1. 문제

`apps/web/package.json`의 `overrides`에 14개 항목이 있다. override는 소비자가 선언한 범위와 상관없이 버전을 강제한다.
upstream이 고친 뒤에도 남고, major 상향을 막는다. 예를 들어 `vite ^7.3.7`은 vitest·plugin-react가 vite 8을 허용해도 vite 8을 막는다.
Dependabot(#98)은 `overrides`를 갱신하지 않는다. 그래서 항목이 왜 필요한지 판단하는 사람이 없으면 계속 쌓인다.

## 2. 실측 (2026-11-07, node v20.20.0 / npm 10.8.2)

방법: 현재 `package.json`·`package-lock.json` 사본에서 override를 하나씩(14케이스), 그리고 전부(1케이스) 뺐다.
각 사본에서 `npm install --package-lock-only --ignore-scripts --no-audit --no-fund`로 다시 해결한 뒤 `npm audit --json`을 실행했다.
worktree는 건드리지 않았다. 원자료는 오케스트레이터 작업 디렉터리의 `exp97-data/results.json`이다. 판정에 쓴 수치는 아래 표에 옮겼다.

| 케이스 | high | critical | moderate | low | lock 변경 |
|---|---|---|---|---|---|
| baseline (현 상태) | 0 | 0 | 12~13 | 2 | 0 |
| 단독 제거 14케이스 | 전부 0 | 전부 0 | 11~13 | 2 | 0~6 |
| 전부 제거 (`rm-all`) | 0 | 0 | 11 | 2 | 15 |

- 남은 advisory는 두 개뿐이다: `minimatch` GHSA-g3hv-75wg-m249(moderate), `deepmerge` GHSA-gcqx-w48m-w2h4(low).
  나머지 moderate 표시는 이 둘을 경유한 패키지가 함께 집계된 것이다.
- moderate가 12와 13으로 갈린 것은 `eslint` 경유 표시가 회차마다 들어가거나 빠졌기 때문이다(병렬 실행 회차 차이).
  게이트 판정(high 이상)에는 영향이 없다. 최종 수치는 Task 2에서 다시 잰 값을 증거로 쓴다.

## 3. 판정표 (이슈 AC1)

판정 기준은 "override를 빼고 해결했을 때 audit high 이상이 생기는가"다. 14개 모두 high 0이라 **전부 제거**한다(③ 합의 1).
GHSA ID는 도입·갱신 커밋 본문에 적힌 것만 옮겼다(294a2d2·957ce04·b756d39). 커밋과 #82 문서(PR #95 본문 포함)에 ID가 없는 항목은 도입 커밋과 당시 기록으로 적는다. ID를 추정해서 적지 않았다.

| # | override | 도입 (커밋 / 날짜) | 도입 근거 (기록) | 현 해결 버전 | 제거 시 해결 버전 | 제거 시 high | 판정 |
|---|---|---|---|---|---|---|---|
| 1 | `sharp ^0.35.5` | fe133b2 (2026-07-31), #82에서 하한 상향 | next 경유 sharp advisory 해소 (#82 design §1: critical/high) | 0.35.5 | 0.35.5 | 0 | 제거 — lock 무변경 |
| 2 | `postcss ^8.5.18` | fe133b2 (2026-07-31) | 당시 audit 해소 (커밋 기록) | 8.5.25 | 8.5.25 + next 하위 8.5.23 사본 | 0 | 제거 — next가 선언한 범위로 8.5.23 사본이 생기지만 advisory가 없다 |
| 3 | `fast-uri ^3.1.6` | fe133b2 (2026-07-31), 294a2d2·b756d39 갱신 | GHSA-7p8r-x3mc-p8w7(294a2d2), GHSA-5jgf-p345-68v8·GHSA-f65p-4m7j-42xc 외 1건(b756d39) | 3.1.9 | 3.1.9 | 0 | 제거 — lock 무변경 |
| 4 | `brace-expansion ^5.0.12` | fe133b2 (2026-07-31), #82에서 하한 상향 | 당시 audit 해소. 커밋 본문: "brace-expansion 5.x가 minimatch 3.x와 비호환이라 @eslint/config-array를 0.23.5로 상향해 minimatch 10 경로로 통일" (#5와 짝) | 5.0.12 | 5.0.12 + minimatch 3.1.5 하위 1.1.21 | 0 | 제거 — minimatch 3이 자기 범위(1.x)의 brace-expansion을 다시 받는다. 위 커밋 본문이 말한 비호환 상태가 풀린다 |
| 5 | `@eslint/config-array ^0.23.5` | fe133b2 (2026-07-31) | #4를 위한 짝 override (위 커밋 본문) | 0.23.5 | 0.21.2 (eslint 9 하위) | 0 | 제거 — eslint 9.39.3이 선언한 범위로 돌아간다. 이 경로의 minimatch 10.2.6 사본이 없어져 moderate 1건이 줄어든다 |
| 6 | `ip-address ^10.4.0` | 294a2d2 (2026-08-04) | GHSA-mwp4-54f8-5fhr 외 2건 (294a2d2, SSRF·trust-boundary bypass) | 10.7.5 | 10.7.5 | 0 | 제거 — lock 무변경 |
| 7 | `js-yaml ^4.3.2` | 957ce04 (2026-08-26), #82에서 하한 상향 | GHSA-5p4m-2wfm-xmqj (957ce04). #82 하한 상향은 D4-결과 | 4.3.2 | 4.3.2 | 0 | 제거 — lock 무변경 |
| 8 | `nanoid ^3.3.18` | 957ce04 (2026-08-26) | GHSA-2v37-7h3g-55p8 (957ce04) | 3.3.18 | 3.3.18 | 0 | 제거 — lock 무변경 |
| 9 | `browserslist ^4.28.7` | b756d39 (2026-09-07) | GHSA-c83g-rgw3-j3cx·GHSA-73wf-gq98-2v4g (b756d39) | 4.28.9 | 4.29.5 | 0 | 제거 — 소비자 범위 안에서 최신으로 다시 해결된다(연쇄 5건, §4) |
| 10 | `follow-redirects ^1.16.2` | #82 PR #95 (2026-10-26) | 당시 audit high/moderate 해소 (#82 D4-결과) | 1.16.2 | 1.16.2 | 0 | 제거 — lock 무변경 |
| 11 | `vite ^7.3.7` | #82 PR #95 | 당시 audit 해소 (#82 D4-결과) | 7.3.7 | 7.3.7 | 0 | 제거 — lock 무변경. vite 8 상향 차단도 풀린다 |
| 12 | `source-map-js ^1.2.2` | #82 PR #95 | 당시 audit 해소 (#82 D4-결과) | 1.2.2 | 1.2.2 | 0 | 제거 — lock 무변경 |
| 13 | `zod ^4.6.7` | #82 PR #95 | shadcn 경유 zod high 해소 (#82 design §1) | 4.6.7 | 4.6.7 | 0 | 제거 — lock 무변경 |
| 14 | `@humanfs/node ^0.16.8` | #82 PR #95 | 당시 audit 해소 (#82 D4-결과) | 0.16.8 | 0.16.8 | 0 | 제거 — lock 무변경 |

"lock 무변경"은 override 없이도 lockfile에 이미 고정된 버전이 소비자 범위를 만족한다는 뜻이다. 그래서 override는 아무것도 강제하지 않고 있었다.
수정판이 lockfile에 남아 있으므로 override를 빼도 해당 advisory는 다시 생기지 않는다. 위 audit 결과(high 0)가 그 확인이다.

### 3.1 열린 advisory — 상위 갱신 대기 (③ 합의 2)

| advisory | 등급 | 취약 범위 / 수정판 | 설치된 사본과 소비자 | 경로 | 판정 |
|---|---|---|---|---|---|
| `minimatch` GHSA-g3hv-75wg-m249 | moderate | `>=7.0.0 <11.0.1` / 11.0.1 | 10.2.4~10.2.6. 소비자: `@ts-morph/common`(`^10.0.1`), `@typescript-eslint/typescript-estree`(`^10.2.2`). 제거 전에는 `@eslint/config-array`(`^10.2.4`)도 소비자였다 | devDependency (`eslint-config-next` → `typescript-eslint`, `shadcn` → `ts-morph`) | 열어 둔다. 수정판 11.0.1은 모든 소비자의 선언 범위(`^10`) 밖이라 override로만 강제할 수 있다. 상위가 minimatch 11을 허용하는 버전을 낼 때까지 기다린다 |
| `deepmerge` GHSA-gcqx-w48m-w2h4 | low | 4.3.1 / 수정판 없음 | 4.3.1. 소비자: `shadcn`, `@shadcn/registry` | devDependency (`shadcn` CLI) | 열어 둔다. 강제할 수정판이 없다 |

- 두 건 모두 Quality Gate 6번 잡(`--audit-level=high`)을 막지 않는다.
- Dependabot alert는 dismiss하지 않는다. 상위가 갱신되면 Dependabot이 PR을 열고, 머지되면 alert가 자동으로 닫힌다.

## 4. lockfile 계약 — 바뀌는 15건 (③ 합의 1)

`overrides` 블록만 지우고 `npm --prefix apps/web install --package-lock-only --ignore-scripts --no-audit --no-fund`로 다시 해결한 결과다.
lockfile 전체 재생성과 `npm audit fix` 결과는 적용하지 않는다.

| # | lock 키 (`packages`) | 변경 전 | 변경 후 | 원인 override |
|---|---|---|---|---|
| 1 | `node_modules/@eslint/config-array` | 0.23.5 | (삭제) | `@eslint/config-array` |
| 2 | `node_modules/@eslint/config-array/node_modules/minimatch` | 10.2.6 | (삭제) | `@eslint/config-array` |
| 3 | `node_modules/@eslint/object-schema` | 3.0.5 | (삭제) | `@eslint/config-array` |
| 4 | `node_modules/eslint/node_modules/@eslint/config-array` | (없음) | 0.21.2 | `@eslint/config-array` |
| 5 | `node_modules/eslint/node_modules/@eslint/object-schema` | (없음) | 2.1.7 | `@eslint/config-array` |
| 6 | `node_modules/minimatch/node_modules/brace-expansion` | (없음) | 1.1.21 | `brace-expansion` |
| 7 | `node_modules/minimatch/node_modules/balanced-match` | (없음) | 1.0.2 | `brace-expansion` |
| 8 | `node_modules/concat-map` | (없음) | 0.0.1 | `brace-expansion` |
| 9 | `node_modules/next/node_modules/postcss` | (없음) | 8.5.23 | `postcss` |
| 10 | `node_modules/browserslist` | 4.28.9 | 4.29.5 | `browserslist` |
| 11 | `node_modules/baseline-browser-mapping` | 2.11.21 | 2.11.35 | `browserslist` |
| 12 | `node_modules/caniuse-lite` | 1.0.30001810 | 1.0.30001823 | `browserslist` |
| 13 | `node_modules/electron-to-chromium` | 1.5.422 | 1.5.478 | `browserslist` |
| 14 | `node_modules/node-releases` | 2.0.54 | 2.0.63 | `browserslist` |
| 15 | `node_modules/update-browserslist-db` | 1.3.2 | 1.3.4 | `browserslist` |

- 실험 스크립트는 변경 목록을 12건까지만 출력한다. 그래서 1~3행은 단독 제거 케이스 `rm-eslint_config-array`의 삭제 3건으로 채웠다(그 케이스의 변경 5건 = 1~5행). 합계 15건은 `rm-all`의 `lock_changes: 15`와 맞는다. 2026-11-07 오케스트레이터가 `rm-all` lockfile을 현 lockfile과 전수 비교해 15건이 이 표와 같음을 확인했다. Task 1의 diff 명령으로 다시 대조한다.
- 10~15행은 registry의 최신 버전으로 다시 해결된다. 실행하는 날에 새 버전이 나와 있으면 값이 달라질 수 있다. 달라지면 구현을 멈추고 dod `## 변경 요청`에 적는다.
- `typescript-eslint` 8.56.1, `shadcn` 4.22.1 같은 직접·전이 의존성은 그대로다. 바뀌면 범위 내 갱신이 섞인 것이다.
- lockfile은 `overrides`를 기록하지 않는다. 그래서 root 항목(`packages[""]`)은 바뀌지 않는다.

## 5. `npm audit fix` 재확인 (이슈 AC4)

- 2026-11-07, npm 10.8.2에서 사본에 `npm audit fix --package-lock-only`를 실행했다. #82에서 본 arborist 크래시(`Cannot read properties of null (reading 'edgesOut')`)는 재현되지 않았다.
- 명령은 끝까지 실행됐고 lockfile을 약 150건 범위 내 갱신했다(typescript-eslint 8.56→8.72, shadcn 4.22→4.23, `@modelcontextprotocol/sdk` 제거 등).
  force 없이는 고칠 수 없는 moderate·low가 남아 exit 1로 끝났다.
- 판정: **동작한다.** 다음 취약점 대응에서는 override보다 이 명령을 먼저 쓴다.
- 이번 PR에는 적용하지 않는다. 열린 Dependabot PR #113(`web-minor-patch`)과 범위가 겹치고, 이슈 범위(override 재검토)를 넘는다.
- 최종 브랜치 상태에서 Task 3으로 다시 실행해 증거로 남긴다.

## 6. 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | `overrides` 블록을 통째로 지운다 | §3. 14개 모두 제거해도 high 0이다. 빈 블록(`"overrides": {}`)도 남기지 않는다 |
| D2 | lockfile은 `--package-lock-only` 재해결 결과(§4의 15건)만 쓴다 | ③ 합의 1. 전체 재생성이나 `npm audit fix`를 섞으면 diff를 종이에서 리뷰할 수 없고, Dependabot PR #112~#116과 범위가 겹친다 |
| D3 | 남은 moderate·low 2건은 override로 강제하지 않고 열어 둔다 | ③ 합의 2. minimatch 11은 소비자 범위 밖 major라 override로 강제하면 이번에 없앤 문제가 다시 생긴다. deepmerge는 수정판이 없다 |
| D4 | override 정책의 정본은 `docs/runbooks/deployment.md` "apps/web 의존성 갱신 (Dependabot)" 절에 둔다. `apps/web/README.md`에는 그 절을 가리키는 짧은 절만 둔다 | 오케스트레이터 제안은 README에 정책과 표를 함께 두는 것이었다. 이 배치로 바꾼 근거는 셋이다. ① Dependabot·audit 대응 절차가 이미 그 runbook 절에 있다. override 재도입은 그 절차의 한 분기라 정책이 두 곳으로 나뉘면 안 된다. ② runbook은 `docs/check-documentation.sh`의 정본 목록에 있어 링크·placeholder 검사를 받는다. README는 검사를 받지 않는다. ③ `apps/web/README.md`는 create-next-app 기본 문서라 운영 규칙의 정본으로 쓰기 어렵다. 이 배치도 이슈의 "README 또는 docs"를 만족한다. 항목별 상세 판정표는 이 문서 §3에 둔다 |
| D5 | runbook의 "`overrides`는 Dependabot이 갱신하지 않는다(재검토는 #97)" 문장은 D4의 정책 항목으로 바꾼다 | 재검토가 끝났다. Dependabot이 override를 갱신하지 않는다는 사실은 정책 항목 안에서 재도입 시 주의점으로 남긴다 |
| D6 | Docker 이미지 로컬 기동 스모크는 DoD에 넣지 않는다 | 바뀌는 15건 중 14건은 devDependency 도구 경로다(eslint 9, minimatch 3, browserslist 연쇄). 나머지 1건인 `next` 하위 postcss 8.5.23 사본은 `next`가 선언한 범위의 버전이고, next build의 CSS 처리에 쓰인다. 그래서 `npm run build`(R4)로 확인하고, 이미지 빌드는 CI `7a. Docker image (web)` 잡(AC6)이 확인한다. 이미지 안의 `npm ci --include=optional`도 같은 lockfile을 쓴다. `sharp` 등 네이티브 optional 패키지의 해결 버전은 바뀌지 않는다 |

## 7. 제외

- 직접 의존성 major 상향 (vite 8, minimatch 11을 위한 상위 교체 등)
- CI 게이트 변경 (`ci/run-npm-audit.sh` 임계값, `ci/npm-audit-suppressions.json`)
- `npm audit fix` 결과 적용, Dependabot PR #112~#116 머지
- Dependabot alert dismiss
- `docs/work/issue-82-web-npm-audit/` 수정. 완료된 작업 기록이라 그대로 둔다. understanding.md §7의 "`npm audit fix` 버그" 문단은 당시 사실이다. 현재 상태의 정본은 runbook이다

## 8. 이 작업에 해당하지 않는 설계 항목

애플리케이션 코드, Facade, Domain port, MarketPair, 시간 계약, DB·캐시, Flyway, 배치, 알림, 공개 endpoint는 모두 해당 없다.
변경 파일은 `apps/web/package.json`, `apps/web/package-lock.json`, 문서뿐이다. Gradle 빌드에 영향이 없다.

## 9. 위험과 테스트 전략

| 위험 | 대응 |
|---|---|
| 재해결 날짜에 따라 §4의 10~15행 버전이 달라진다 | Task 1의 diff로 대조한다. 다르면 멈추고 `## 변경 요청`에 적는다 |
| `@eslint/config-array` 0.21.2 복귀로 eslint 설정(`eslint.config.mjs`의 `[[]id[]]` glob 등) 해석이 바뀐다 | R2(`npm run lint` exit 0)로 확인한다. 0.21.2는 eslint 9.39.3이 선언한 범위라 override 이전의 정상 조합이다 |
| Dependabot PR이 먼저 머지되어 lockfile이 충돌한다 | 새 base에서 Task 1을 다시 실행한다. 15건 목록이 달라지면 `## 변경 요청`에 적는다 |
| 머지 전에 advisory가 바뀐다 | 최종 SHA에서 R1을 다시 실행하고 `audit --json` 수치를 증거로 남긴다. high가 생기면 `## 변경 요청`으로 재승인을 받는다. override 재도입은 D4 정책 절차를 따른다 |

테스트 전략: 코드 변경이 없으므로 새 테스트는 없다. 검증은 기존 gate가 맡는다.
설치(`npm ci`), audit(R1), lint(R2), vitest(R3), next build(R4), 계약 테스트(R5), 문서 검사(R6), CI Quality Gate 전 check(AC6)이다.
