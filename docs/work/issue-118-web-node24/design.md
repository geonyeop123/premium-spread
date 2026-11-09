# apps/web Node 런타임 20→24 전환과 jsdom 30 적용 — 설계

- 이슈: #118 `apps/web Node 런타임 20→24 전환 및 jsdom 30 적용`
- 브랜치: `chore/issue-118-web-node24` (base `dev`)
- 계기: Dependabot PR #114(jsdom 27.4.0→30.1.3)가 Quality Gate 6번 잡에서 실패해 닫았다. 선행: #98(Dependabot 주기 갱신), #97(overrides 제거)
- 변경 이력: ⑥ 스펙 리뷰(ACCEPT-with-fixes) 9건 반영 — D3의 참조 번호(D5→D6), D8의 setup-node 해석 문구, §7 변형 개수(11→13, C2·C3 포함)를 고쳤다
- 변경 이력: ⑧ D5 개정 — lockfile 재해결을 node:24-alpine npm 11.19.1로(5641e22), 동결 기준 불변

## 1. 문제

PR #114는 Node 20의 CI 6번 잡(`npm --prefix apps/web run test`)에서 `undici/lib/interceptor/decompress.js`의 TypeError로 실패했다.
jsdom 30의 `engines.node`는 `^22.22.2 || ^24.15.0 || >=26`이다. 그래서 Node 20에서는 지원되지 않는 조합이다.
Node 20은 2026-04에 EOL이 지났다(오늘 2026-11-07). jsdom 30을 받으려면 런타임을 먼저 올려야 한다.

현재 Node major는 네 곳에 흩어져 있고 서로 검사하지 않는다.

| 위치 | 현재 값 |
|---|---|
| `.github/workflows/quality-gate.yml` 189행 (6번 잡 `actions/setup-node`) | `node-version: '20'` |
| `apps/web/Dockerfile` 1행(builder)·8행(runtime) | `node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293` |
| `apps/web/package.json` `devDependencies` | `"@types/node": "^20"` |
| `apps/web/package.json` `engines` | 없음 |

`ci/quality-gate-contract-test.sh`는 이 값들을 보지 않는다. 한 곳만 바뀌면 #114처럼 CI에서만 깨진다.

## 2. 실측 (2026-11-07, 메인 세션 측정)

**이미지**

| 태그 | digest | node / npm |
|---|---|---|
| `node:24-alpine` (채택) | `sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8` | v24.22.2 / 11.19.1 |
| `node:22-alpine` (참고) | `sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202` | v22.23.4 |
| `node:26-alpine` (참고) | — | v26.13.0 |

**lockfile 재해결** — 로컬 node v20.20.0 / npm 10.8.2에서
`npm install --package-lock-only --ignore-scripts jsdom@^30.1.3 @types/node@^24`를 실행했다.
`package.json`은 `"@types/node": "^24.19.2"`, `"jsdom": "^30.1.3"`이 됐고 lockfile이 다시 해결됐다. Node 20이라 `EBADENGINE` 경고만 나왔다.
이 사본은 D5 개정으로 채택하지 않았다. 채택 lock은 npm 11.19.1 재해결(dod AC4, changed=37 nonDev=[])이다.

**node:24-alpine 컨테이너 검증** — 위 사본으로

| 항목 | 결과 |
|---|---|
| `npm ci` | exit 0 |
| `npm run lint` | exit 0 |
| `npm run test` | 1 file, `10 passed` |
| `npm run build` | exit 0. 라우트 6개 (`/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]`) |
| `npm ls --all` | `invalid` 0 |
| `undici` | jsdom@30.1.3은 하위에 전용 `undici@8.12.4`(engines >=22.19.0)를 둔다(lock `node_modules/jsdom/node_modules/undici`). 최상위 `undici@7.30.1`은 shadcn·@dotenvx 등이 쓴다 |
| `npm audit` | high 0 · critical 0 · moderate 11 · low 2 (dev와 같다) |

## 3. 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | Node 24로 올린다 | ③ 합의 1. Node 22는 2027-04 EOL로 5개월 남았다. Node 26은 LTS가 막 시작됐다. Node 24는 2028-04 EOL이다 |
| D2 | jsdom 30 적용을 같은 PR에 넣는다 | ③ 합의 2. 런타임 전환의 계기가 jsdom 30이다. 나눠서 올리면 Dependabot이 jsdom 30 PR을 다시 열 때 같은 확인을 한 번 더 해야 한다 |
| D3 | `engines`를 `{"node": "^24.15.0"}`로 추가한다 | ③ 합의 3. 하한 24.15.0은 jsdom 30 `engines`의 24 계열 하한과 같다. `^`는 major를 24로 묶는다. `>=24.15.0`이면 25·26도 허용돼 D6의 major 일치 검사가 의미를 잃는다 |
| D3-a | `engine-strict`는 켜지 않는다 | `.npmrc`가 없어 npm 기본값 `engine-strict=false`가 적용된다. 그래서 `engines`는 맞지 않는 Node에서 **`EBADENGINE` 경고만 내고 설치를 막지 않는다**. 로컬 Node 20 사용자도 `npm install`은 된다(테스트는 jsdom 30 때문에 실패할 수 있다). 설치 차단은 ③ 합의 범위 밖이다. 실제로 Node 24를 강제하는 곳은 CI 6번 잡과 Docker 이미지다 |
| D4 | `@types/node`는 `^24`로 올린다. `.github/dependabot.yml`의 `@types/node` major ignore는 유지하고 주석의 근거 문구만 24로 고친다 | ③ 합의 3. 타입 정의 major는 런타임 major와 같이 움직여야 한다. Dependabot이 `@types/node` 25를 따로 올리면 런타임과 타입이 다시 갈린다 |
| D5 | lockfile은 node:24-alpine 컨테이너의 npm 11.19.1에서 `--package-lock-only` 재해결 결과만 쓴다 (⑧ 개정 2026-11-09) | 처음에는 로컬 npm 10.8.2로 정했으나 ⑧ Task 1에서 npm 10.8.2가 lockfile의 `"libc": ["glibc"/"musl"]` 18곳을 지웠다. 그중 `@next/swc-linux-*` 4건은 production 트리라 AC4·R4(`nonDev=[]`)를 어긴다. libc가 없으면 alpine(musl)에서 glibc 바이너리가 설치 후보가 된다. npm 11.19.1은 CI·Docker가 실제로 쓰는 npm이고, 같은 입력에서 libc 18곳을 보존하며 `changed=37 nonDev=[]`(npm 10은 55)였다. 동결 기준은 바꾸지 않는다 |
| D6 | `ci/quality-gate-contract-test.sh`에 **Node major 일치 계약**을 추가한다. 대상은 CI `node-version`, Dockerfile 두 stage, `engines.node`, `@types/node` 네 곳이다 | 네 곳이 갈리면 #114가 다시 생긴다. 기존 #98 블록(`next`==`eslint-config-next`)과 같은 grep/sed 방식으로 쓰고, 최종 `echo` 직전에 둔다. 1번 잡이 이 스크립트를 실행한다(`Verify repository quality and documentation contracts` step). 검사 규칙 C1~C7 전문은 plan Task 3에 있다 |
| D6-a | 루트 `README.md`는 계약 검사에 넣지 않는다 | 산문이라 형식이 고정돼 있지 않다. runbook의 갱신 절차(D7)에 항목으로만 둔다 |
| D6-b | 변형(mutation) 검증은 구현 시 한 번 실행해 dod 증거로 남긴다. 스크립트 안에 자가검증을 넣지 않는다 | 기존 #98 블록에도 자가검증이 없다. 스크립트 내 mutation 자가검증은 cleanup workflow 계약(#107)처럼 별도 테스트 스크립트를 둔 경우에만 쓴다. 이번 규칙은 네 값의 문자열 비교라 1회 변형 검증(M0~M12, 규칙 C1~C7 전부)으로 무엇을 잡는지 증명할 수 있다 |
| D7 | `docs/runbooks/deployment.md` "apps/web 의존성 갱신 (Dependabot)" 절에 **"Node 런타임 major 갱신"** 항목을 추가한다 | 다음 major 갱신(Node 26) 때 바꿀 곳과 순서를 한 곳에서 찾게 한다. D6 검사가 실패했을 때 무엇을 함께 바꿔야 하는지도 이 항목이 알려 준다 |
| D8 | CI `node-version`은 major 별칭 `'24'`로 둔다. 정확한 patch로 고정하지 않는다 | 기존 `'20'`과 같은 방식이다. `actions/setup-node`는 러너 toolcache의 24.x를 쓰고, 없으면 최신 24.x를 받는다. Docker는 digest로 24.22.2에 고정된다. 둘 다 `engines`(`^24.15.0`) 안이고 jsdom 30 하한을 넘는다 |
| D9 | Docker 이미지 로컬 기동 스모크를 DoD에 넣는다 | #97 D6과 달리 이번에는 runtime stage 베이스 이미지가 바뀐다. CI 7a 잡은 이미지를 빌드만 하고 기동하지 않는다. `node server.js`가 Node 24에서 뜨는지와 `addgroup`·`adduser`가 새 alpine에서 동작하는지는 기동해야 알 수 있다 |
| D10 | Gradle 회귀는 로컬에서 돌리지 않고 PR CI check 11개 통과로 갈음한다 | 바뀌는 파일 중 Gradle 입력은 없다. Gradle은 `apps/web`·`.github/dependabot.yml`·`README.md`·runbook을 읽지 않는다. `ci/quality-gate-contract-test.sh`는 Gradle 태스크가 아니라 1번 잡의 별도 step이고, 로컬 R2로 직접 실행한다. `apps/api`·`apps/batch` Dockerfile도 그대로다. CI 1~5·8번 잡이 Gradle 전체를 다시 돌린다(AC9) |

## 4. 바꾸는 값 (요약 — 전문은 plan)

| 파일 | 변경 전 | 변경 후 | 태스크 |
|---|---|---|---|
| `apps/web/package.json` | `engines` 없음 | `"engines": {"node": "^24.15.0"}` (`"private"` 다음) | 1 |
| `apps/web/package.json` | `"@types/node": "^20"` | `"@types/node": "^24.19.2"` | 1 |
| `apps/web/package.json` | `"jsdom": "^27.0.0"` | `"jsdom": "^30.1.3"` | 1 |
| `apps/web/package-lock.json` | `@types/node` 20.19.43, jsdom 27.x | `@types/node` 24.19.2, jsdom 30.1.3 및 하위. `packages[""]`에 `engines` | 1 |
| `.github/workflows/quality-gate.yml` 189행 | `node-version: '20'` | `node-version: '24'` | 2 |
| `apps/web/Dockerfile` 1행·8행 | `node:20-alpine@sha256:fb4cd…2293` | `node:24-alpine@sha256:a34ab6d7…67c8` | 2 |
| `ci/quality-gate-contract-test.sh` | Node 검사 없음 | Node major 일치 블록 | 3 |
| `.github/dependabot.yml` 18행 | "…이 20이므로…" | "…이 24이므로…" | 5 |
| `docs/runbooks/deployment.md` 77행 + 새 항목 | "Node 런타임(20)" | "Node 런타임(24)" + "Node 런타임 major 갱신" 항목 | 5 |
| `README.md` 101행 | `- Node.js 20+` | `- Node.js 24 (apps/web, ^24.15.0)` | 5 |

package.json의 jsdom 현재 선언은 `^27.0.0`이다(lockfile 해결 버전은 27.4.0). 이슈 본문의 "27.4.0"은 해결 버전을 가리킨다.

## 5. 범위

**포함** — §4의 파일, `.ai/PROJECT_STATUS.md` 진행 현황 항목, `docs/work/README.md` 인덱스 행과 이 디렉터리의 README(⑪).

**제외**

- 과거 기록 문서의 "Node 20" 언급: `docs/plans/2026-02-26-*`, `.ai/planning/**`, `docs/dod/private-live-*`, `docs/superpowers/**`,
  `docs/work/private-live-autotrader*/`, `docs/work/issue-82-web-npm-audit/`, `docs/work/issue-97-web-npm-overrides/`. 당시 사실을 적은 이력이라 바꾸지 않는다
- `engine-strict` 설정, `.nvmrc`·`.node-version` 추가 (D3-a, ③ 합의 밖)
- `actions/setup-node` 액션 버전 변경, 다른 의존성 갱신, `npm audit fix`
- `apps/api`·`apps/batch` Dockerfile, Gradle 빌드
- `.ai/PROJECT_STATUS.md` Known Issue 8 문구 — audit 수치(high 0 · moderate 11 · low 2)와 열린 advisory 2건이 dev와 같아(§2) 바뀌는 사실이 없다

## 6. 이 작업에 해당하지 않는 설계 항목

모두 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경).

- Facade·Controller 주입: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- Domain port와 구현 모듈: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- MarketPair identity 보존 경로: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- 시간·범위 계약(`Instant`, `[from, to)`): 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- DB·캐시 순서: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- Flyway: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- 배치 `JobExecutor` lock·lease·timeout·`JobResult`: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- 알림 event identity·claim/fencing: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)
- 공개 endpoint·`PublicEndpointPolicy`: 해당 없음(이유: apps/web 빌드·런타임 도구 체인만 변경)

## 7. 위험

| 위험 | 대응 |
|---|---|
| 실행하는 날 registry에 `@types/node` 24.x·jsdom 30.x 새 patch가 나와 §2와 다른 버전으로 해결된다 | Task 1에서 해결 버전을 대조한다. 다르면 커밋하지 않고 dod `## 변경 요청`에 실제 값을 적는다 |
| jsdom 30 하위 트리가 production 의존성(next 등)과 공유하는 패키지를 바꾼다 | Task 1의 lock 변경 검사가 "바뀐 항목은 모두 `dev: true`"를 확인한다. 아니면 멈추고 보고한다 |
| Node 24에서 `next build`·standalone `server.js`가 다르게 동작한다 | §2 실측 build exit 0. Task 4에서 커밋된 HEAD로 다시 확인하고 이미지를 기동한다(AC5·AC6) |
| alpine 베이스가 바뀌어 runtime stage의 `addgroup`·`adduser`가 실패한다 | Task 4의 `docker build`가 잡는다 |
| 로컬 개발자가 Node 20으로 `npm install`하면 경고만 보고 넘어간 뒤 테스트가 실패한다 | README 사전 요구사항을 Node 24로 고친다. 실패 원인은 runbook "Node 런타임 major 갱신" 항목에서 찾을 수 있다 |
| 계약 검사 정규식이 정상 값을 놓치거나 위반을 놓친다 | Task 3의 변형 13종(M0~M12)으로 규칙 C1~C7이 각각 기대 메시지로 실패하는지 확인한다(AC2) |

## 8. 테스트 전략

애플리케이션 코드가 바뀌지 않아 새 단위·통합 테스트는 없다. 검증 계층은 다음과 같다.

| 계층 | 무엇을 | 수단 |
|---|---|---|
| 정적 계약 | 네 곳의 Node major 일치, digest 고정 | `bash ci/quality-gate-contract-test.sh` (AC1) + 변형 검증 13종 (AC2) |
| 선언 값 | CI·Dockerfile·package.json·lockfile의 정확한 값 | `grep`, `npm pkg get`, `node -e` (AC3·AC4) |
| Node 24 설치·도구 | `npm ci`·lint·vitest(jsdom 30)·next build·audit | `git archive HEAD` 사본을 node:24-alpine 컨테이너에서 실행 (AC5, R1) |
| 런타임 이미지 | `node -v`, 3000 포트 응답 | `docker build` + `docker run` (AC6) |
| 문서 | 문구 교체와 링크·placeholder | `grep`, `bash docs/check-documentation.sh` (AC7, R3) |
| 전체 회귀 | Gradle 전체·Docker 3종·web 잡 | PR CI check 11개 (AC9, D10) |
