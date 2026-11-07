# apps/web npm overrides 재검토 — 구현 계획

> design: `docs/work/issue-97-web-npm-overrides/design.md` · dod: `docs/work/issue-97-web-npm-overrides/dod.md`
> 모든 명령은 worktree 루트(`.worktrees/chore-issue-97-web-npm-overrides`)에서 실행한다.
> 변경 파일: `apps/web/package.json`, `apps/web/package-lock.json`, `docs/runbooks/deployment.md`, `apps/web/README.md`,
> `.ai/PROJECT_STATUS.md`, 이 디렉터리의 dod 증거 로그. 이 밖의 파일이 diff에 나오면 멈춘다.
> 코드 변경이 없어 `test-strategy`의 Domain→Controller 순서는 해당 없다. 순서는 RED 기록 → 변경 → gate 재실행 → 문서다.

## 공통 — 임시 디렉터리와 lock 비교 스크립트

임시 파일은 worktree 밖에 둔다. 각 Task를 시작할 때 셸마다 한 번 정의한다.

```bash
SCRATCH="${SCRATCH:-$(mktemp -d)}"; echo "SCRATCH=$SCRATCH"
LOCKDIFF='
const fs = require("fs");
const [before, after] = process.argv.slice(1).map((f) => JSON.parse(fs.readFileSync(f, "utf8")).packages);
const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort();
let n = 0;
for (const k of keys) {
  const b = before[k]?.version ?? null, a = after[k]?.version ?? null;
  if (b !== a) { n++; console.log(`${k}\t${b}\t${a}`); }
}
console.log(`changed=${n}`);
'
# 사용: node -e "$LOCKDIFF" <before-lock.json> <after-lock.json>
```

`LOCKDIFF`는 `packages` 항목의 `version`만 비교한다. 실험 스크립트(design §2)와 같은 기준이다.

---

## Task 0 — RED·기준선 기록 (커밋 없음)

**파일**: `docs/work/issue-97-web-npm-overrides/dod.md` 증거 로그만

**실행**
```bash
rm -rf apps/web/node_modules apps/web/.next
npm --prefix apps/web ci --include=optional
npm --prefix apps/web pkg get overrides                                                                     # AC1 RED
npm --prefix apps/web ls browserslist @eslint/config-array brace-expansion postcss typescript-eslint shadcn --all   # AC2 RED
bash ci/run-npm-audit.sh                                                                                    # R1 기준선
```

**예상 결과**
- AC1 RED: `sharp`부터 `@humanfs/node`까지 14개 키를 가진 객체가 출력된다
- AC2 RED: `browserslist@4.28.9`와 `@eslint/config-array@0.23.5` 줄에 `overridden`이 붙는다. `brace-expansion`은 5.0.12 하나, `postcss`는 8.5.25 하나뿐이다
- R1 기준선: exit 0 (high 0)

dod 증거 로그에 `### AC1`, `### AC2`, `### R 기준선` 절을 만들고 `[RED] <날짜> <HEAD sha>` 형식으로 붙인다.

---

## Task 1 — overrides 제거와 lockfile 재해결 (D1·D2)

**파일**: `apps/web/package.json`, `apps/web/package-lock.json`

**계약 1 — `apps/web/package.json` 최종 형태 (전문)**. `overrides` 키와 `devDependencies` 닫는 괄호 뒤의 쉼표가 사라진다. 그 외 바이트는 그대로다.

```json
{
  "name": "web",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "eslint",
    "test": "vitest run"
  },
  "dependencies": {
    "axios": "^1.20.0",
    "class-variance-authority": "^0.7.1",
    "clsx": "^2.1.1",
    "lightweight-charts": "^5.1.0",
    "lucide-react": "^0.575.0",
    "next": "16.4.3",
    "radix-ui": "^1.4.3",
    "react": "19.2.3",
    "react-dom": "19.2.3",
    "tailwind-merge": "^3.5.0"
  },
  "devDependencies": {
    "@tailwindcss/postcss": "^4",
    "@testing-library/jest-dom": "^6.9.1",
    "@testing-library/react": "^16.3.0",
    "@types/node": "^20",
    "@types/react": "^19",
    "@types/react-dom": "^19",
    "@vitejs/plugin-react": "^5.0.4",
    "eslint": "^9",
    "eslint-config-next": "16.4.3",
    "jsdom": "^27.0.0",
    "shadcn": "^4.22.1",
    "tailwindcss": "^4",
    "tw-animate-css": "^1.4.0",
    "typescript": "^5",
    "vitest": "^4.1.12"
  }
}
```

`"eslint-config-next": "16.4.3",` 줄은 그대로 둔다. `ci/quality-gate-contract-test.sh`의 sed(`^    "eslint-config-next": "([^"]+)",?$`)는 4칸 들여쓰기의 한 줄 `"키": "값"` 형태를 읽는다(끝 쉼표는 선택).

**계약 2 — lockfile 변경 목록 (전문)**. design §4 표의 15건과 같아야 한다. `LOCKDIFF` 출력 형식(키 정렬, 탭 구분)으로 쓰면 아래와 같다.

```
node_modules/@eslint/config-array	0.23.5	null
node_modules/@eslint/config-array/node_modules/minimatch	10.2.6	null
node_modules/@eslint/object-schema	3.0.5	null
node_modules/baseline-browser-mapping	2.11.21	2.11.35
node_modules/browserslist	4.28.9	4.29.5
node_modules/caniuse-lite	1.0.30001810	1.0.30001823
node_modules/concat-map	null	0.0.1
node_modules/electron-to-chromium	1.5.422	1.5.478
node_modules/eslint/node_modules/@eslint/config-array	null	0.21.2
node_modules/eslint/node_modules/@eslint/object-schema	null	2.1.7
node_modules/minimatch/node_modules/balanced-match	null	1.0.2
node_modules/minimatch/node_modules/brace-expansion	null	1.1.21
node_modules/next/node_modules/postcss	null	8.5.23
node_modules/node-releases	2.0.54	2.0.63
node_modules/update-browserslist-db	1.3.2	1.3.4
changed=15
```

**실행**
```bash
# 1) package.json을 계약 1의 전문으로 바꾼다.
# 2) lockfile만 다시 해결한다. node_modules 설치·lockfile 전체 재생성·audit fix 금지.
npm --prefix apps/web install --package-lock-only --ignore-scripts --no-audit --no-fund
# 3) 계약 2와 대조한다.
git show HEAD:apps/web/package-lock.json > "$SCRATCH/lock-before.json"
node -e "$LOCKDIFF" "$SCRATCH/lock-before.json" apps/web/package-lock.json
git diff --stat -- apps/web
# 4) 두 파일만 커밋한다 (Task 0의 dod 증거 로그는 Task 4 커밋에 들어간다).
git add apps/web/package.json apps/web/package-lock.json
git commit
```

**예상 결과**
- `npm install` exit 0
- `LOCKDIFF` 출력이 계약 2와 줄 단위로 같다 (`changed=15`)
- `git diff --stat -- apps/web`에는 `apps/web/package.json`, `apps/web/package-lock.json` 두 파일만 있다

**다르면**: 커밋하지 말고 멈춘다. `browserslist` 연쇄(6줄) 버전만 더 높게 나왔다면 그새 registry에 새 버전이 나온 것이다. 그 경우에도 dod `## 변경 요청`에 실제 출력을 적고 승인을 받는다. 다른 패키지가 섞였다면 명령 플래그를 다시 확인한다.

**커밋 메시지**: `chore: apps/web overrides 14개 제거 (#97)`. 본문에 "lockfile은 --package-lock-only 재해결 결과 15건만 반영, audit fix 미적용"을 적는다.

---

## Task 2 — 깨끗한 설치에서 gate 재실행 (AC1·AC2·AC3 입력, R1~R5)

**파일**: dod 증거 로그만 (커밋은 Task 4와 함께)

**실행**
```bash
rm -rf apps/web/node_modules apps/web/.next
npm --prefix apps/web ci --include=optional
npm --prefix apps/web pkg get overrides                                                                     # AC1
npm --prefix apps/web ls browserslist @eslint/config-array brace-expansion postcss typescript-eslint shadcn --all   # AC2
bash ci/run-npm-audit.sh                                                                                    # R1
npm --prefix apps/web audit --json > "$SCRATCH/audit.json"; echo "audit-json exit=$?"
node -e '
const a = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
console.log("ran_at=" + new Date().toISOString(), JSON.stringify(a.metadata.vulnerabilities));
const adv = new Set();
for (const v of Object.values(a.vulnerabilities)) for (const s of v.via) if (typeof s === "object") adv.add(`${s.name}\t${s.severity}\t${s.url}`);
console.log([...adv].sort().join("\n"));
' "$SCRATCH/audit.json"                                                                                     # AC3 대조 입력
gh api 'repos/geonyeop123/premium-spread/dependabot/alerts?state=open&ecosystem=npm&per_page=100' \
  --jq '.[] | [.number, .security_advisory.ghsa_id, .dependency.package.name, .security_advisory.severity] | @tsv'   # AC3 대조 입력
npm --prefix apps/web run lint                                                                              # R2
npm --prefix apps/web run test                                                                              # R3
npm --prefix apps/web run build                                                                             # R4
bash ci/quality-gate-contract-test.sh                                                                       # R5
```

**예상 결과**
- `npm ci` exit 0. lockfile과 package.json이 맞지 않으면 여기서 실패한다
- AC1: `{}`
- AC2: exit 0. `overridden`·`invalid` 없음. 버전 집합이 dod AC2 통과 조건과 같다
- R1: exit 0 (high 0, critical 0)
- `audit-json exit=1`(남은 moderate·low 때문). 집계는 high 0 · critical 0 · moderate 11 근처 · low 2. advisory 목록은 `minimatch`(GHSA-g3hv-75wg-m249)와 `deepmerge`(GHSA-gcqx-w48m-w2h4) 두 개뿐이다
- Dependabot alert 목록에 위 두 GHSA가 열린 상태로 있다. 다른 GHSA가 있으면 design §3.1에 행을 추가하지 말고 오케스트레이터에 보고한다
- R2·R3·R4·R5 exit 0. R3은 `Tests 10 passed`(#82 기준), R4는 6개 라우트 출력

**실패하면**: R1에 high가 나오면 advisory가 바뀐 것이다. 멈추고 dod `## 변경 요청`에 적는다. override를 다시 넣지 않는다. R2가 실패하면 `@eslint/config-array` 0.21.2 복귀 영향이다(design §9). 멈추고 보고한다.

dod 증거 로그에 `[GREEN] <ISO 시각> <Task 1 커밋 sha>`로 붙인다.

---

## Task 3 — `npm audit fix` 재확인, 적용하지 않음 (AC4)

**파일**: dod 증거 로그만. worktree의 `apps/web/` 파일은 바뀌면 안 된다

**실행**
```bash
npm --version                                                    # 10.8.2 예상. 다르면 그대로 기록
mkdir -p "$SCRATCH/issue97-auditfix"
cp apps/web/package.json apps/web/package-lock.json "$SCRATCH/issue97-auditfix/"
cp "$SCRATCH/issue97-auditfix/package-lock.json" "$SCRATCH/lock-before-fix.json"
npm --prefix "$SCRATCH/issue97-auditfix" audit fix --package-lock-only --ignore-scripts --no-fund 2> "$SCRATCH/auditfix.err"; echo "audit-fix exit=$?"
grep -c edgesOut "$SCRATCH/auditfix.err"                         # 0 예상
node -e "$LOCKDIFF" "$SCRATCH/lock-before-fix.json" "$SCRATCH/issue97-auditfix/package-lock.json"
git status --porcelain apps/web                                  # 빈 출력 예상
```

**예상 결과**
- `edgesOut` 0건. 명령이 끝까지 실행된다
- `audit-fix exit=1` (force 없이 고칠 수 없는 moderate·low가 남음). 0이 나와도 실패가 아니다. 그대로 기록한다
- `LOCKDIFF`의 `changed=`가 1 이상이다. design §5 실측(약 150건, typescript-eslint 8.56→8.72, shadcn 4.22→4.23)과 같은 방향의 범위 내 갱신이다. 대표 줄 3개와 `changed=` 값을 기록한다
- `git status --porcelain apps/web` 빈 출력. 사본만 바뀌었다

dod 증거 로그 `### AC4`에 npm 버전, exit code, `edgesOut` 건수, `changed=` 값을 붙인다.

---

## Task 4 — 문서 (AC3·AC5, R6)

**파일**: `docs/runbooks/deployment.md`, `apps/web/README.md`, `.ai/PROJECT_STATUS.md`

**계약 1 — `docs/runbooks/deployment.md`**. "apps/web 의존성 갱신 (Dependabot)" 절의 마지막 항목을 바꾼다.

교체 전 (현재 85~86행 전문):
```markdown
- **범위 밖.** `overrides`는 Dependabot이 갱신하지 않는다(재검토는 #97). Gradle 의존성은 dependency verification 계약
  때문에 자동 갱신하지 않는다. 갱신 PR이 남긴 Actions cache는 PR이 닫힐 때 `Actions cache PR cleanup`이 지운다.
```

교체 후 (전문):
```markdown
- **overrides 정책.** `apps/web/package.json`에는 `overrides`를 두지 않는다. #97에서 14개를 모두 제거했고, 항목별 판정은
  [#97 설계](../work/issue-97-web-npm-overrides/design.md) §3에 있다. override는 소비자가 선언한 범위와 상관없이 버전을
  강제하고 Dependabot이 갱신하지 않는다. 그래서 upstream이 고친 뒤에도 남고 major 상향을 막는다.
  - audit high 이상이 생기면 보안 PR이나 `npm --prefix apps/web audit fix`(force 없이)를 먼저 쓴다. 2026-11-07 lockfile
    기준 npm 10.8.2에서 크래시 없이 동작했다(#97). #82에서는 같은 버전으로 `edgesOut` 크래시가 났으므로, 크래시가 다시
    나면 아래 override 분기로 간다.
  - 둘 다로 해소되지 않을 때만 override를 넣는다. override 범위는 그 패키지를 쓰는 모든 소비자의 선언 범위 안이어야
    한다. 범위 밖이면 소비자 패키지 상향을 먼저 검토한다.
  - override를 넣는 PR은 아래 표에 행을 추가하고, 빼는 PR은 행을 지운다. 표가 비어 있으면 `overrides`도 없어야 한다.
  - high 미만 advisory는 override로 강제하지 않고 상위 갱신을 기다린다. Dependabot alert는 dismiss하지 않는다.

  | 패키지 | override 범위 | 근거 advisory (GHSA) | 소비자와 선언 범위 | 제거 조건 | 도입 PR |
  |---|---|---|---|---|---|

  현재 등록된 override는 없다.
- **범위 밖.** Gradle 의존성은 dependency verification 계약 때문에 자동 갱신하지 않는다. 갱신 PR이 남긴 Actions cache는
  PR이 닫힐 때 `Actions cache PR cleanup`이 지운다.
```

**계약 2 — `apps/web/README.md`**. 파일 끝(`## Deploy on Vercel` 절 뒤)에 빈 줄 하나를 두고 추가한다. 기존 내용은 바꾸지 않는다.

```markdown
## 의존성 overrides 정책

`package.json`에는 주석을 쓸 수 없어 `overrides` 정책은 저장소 runbook에 둔다.

- 정본: [`docs/runbooks/deployment.md`](../../docs/runbooks/deployment.md)의 "apps/web 의존성 갱신 (Dependabot)" 절
- 현재 `overrides`는 없다. #97에서 14개를 모두 제거했고, 항목별 판정은 [`docs/work/issue-97-web-npm-overrides/design.md`](../../docs/work/issue-97-web-npm-overrides/design.md) §3에 있다
- `overrides`를 다시 넣을 때는 같은 PR에서 runbook의 overrides 표에 행을 추가한다
```

**계약 3 — `.ai/PROJECT_STATUS.md`** (⑪ tech-docs가 상태 문구를 다듬을 수 있다. 사실 내용은 바꾸지 않는다)

- 진행 현황: `- apps/web 의존성 주기 갱신(#98, ...)` 항목 바로 뒤에 추가한다.
```markdown
- apps/web overrides 재검토(#97, 구현·로컬 검증 완료, PR CI 대기): `apps/web/package.json` 의 `overrides` 14개를 모두
  제거했다. 제거해도 audit high 이상이 생기지 않았다. 재도입 조건은 [runbook](../docs/runbooks/deployment.md)을, 항목별 판정은
  [설계](../docs/work/issue-97-web-npm-overrides/design.md)를 따른다.
```
- Known Issues 8: 마지막 문장 "운영은 [`deployment.md`](../docs/runbooks/deployment.md)를 따른다."가 있는 행(현재 169행) 다음에 3칸 들여쓰기로 새 줄을 추가한다(같은 항목). 169행 자체는 바꾸지 않는다.
```markdown
   #97에서 `overrides`를 모두 제거했다. 2026-11-07 기준 moderate `minimatch`(GHSA-g3hv-75wg-m249)와 low
   `deepmerge`(GHSA-gcqx-w48m-w2h4)가 devDependency 경로(eslint·typescript-eslint·shadcn CLI)에 남아 있다. 게이트(high 이상)를
   막지 않으며 상위 갱신을 기다린다.
```

**실행**
```bash
bash docs/check-documentation.sh                                 # R6
git diff -- docs/runbooks/deployment.md apps/web/README.md .ai/PROJECT_STATUS.md
grep -n '재검토는 #97' docs/runbooks/deployment.md               # 출력 없음 예상 (exit 1)
```

**예상 결과**
- R6 exit 0 (`documentation check passed ...`). runbook과 PROJECT_STATUS에 새로 넣은 상대 링크가 모두 해석된다
- diff가 계약 1~3의 전문과 같다
- `재검토는 #97` 문장이 없다

**커밋**: `docs: apps/web overrides 정책 기록 (#97)`. Task 0·2·3의 dod 증거 로그도 이 커밋에 포함한다.

---

## Task 5 — PR과 CI (AC6) — 오케스트레이터 단계

- 최종 커밋 SHA에서 Task 2의 R1과 `audit --json` 요약을 다시 실행하고, 실행 시각과 함께 dod 증거 로그에 남긴다
- PR 본문에 design §3 판정표 요약(14개 제거, 근거 링크), 열린 advisory 2건과 대기 사유, AC4 결과를 적는다. `Closes #97`
- `gh pr checks` 11/11 success를 dod `### AC6`에 기록한다
- Dependabot PR #112~#116은 머지하지 않는다. #97 머지 뒤 Dependabot이 rebase한다
