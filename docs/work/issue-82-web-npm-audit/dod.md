---
feature: apps/web npm audit 취약점 해소
slug: issue-82-web-npm-audit
status: FROZEN
frozen_at: 2026-10-26
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/82
---

## 범위

**포함**

- `npm audit fix`로 해소되는 전이 의존성의 lockfile 갱신 (필요하면 `overrides` 하한 상향)
- `next` 16.2.12 → 16.4.3, `eslint-config-next` 동일 버전으로 맞춤
- `vitest` 3 → 4.1.12 (major), 깨진 설정·테스트 수정
- `shadcn` 3 → 4.22.1 (major, ③ 합의: 제거 대신 업그레이드)
- 남는 low/moderate 건을 PR 본문에 기록

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- `ci/run-npm-audit.sh` 임계값 완화, `ci/npm-audit-suppressions.json` suppression 도입
- 웹 기능 변경, Node 20 → 24 전환, Dockerfile 변경
- Dependabot/Renovate 도입

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` | 아니오. JVM 모듈 대상이라 `apps/web`을 보지 않는다 |
| lint 규칙 | 있음 — `npm --prefix apps/web run lint` (eslint + eslint-config-next) | 예. 회귀 방어선 R1 |
| custom gradle/npm task | 있음 — `ci/run-npm-audit.sh`(suppression 0건 강제 + `npm audit --audit-level=high`), `npm run test`(vitest), `npm run build`(next build) | 예. AC1·R2·R3 |
| CI job | 있음 — `.github/workflows/quality-gate.yml` 6번 잡(ci → lint → test → audit), 7번 잡(Web Docker image build 포함) | 예. AC3 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `verdict_commit` SHA에서 lockfile로 `npm ci`한 상태로 `ci/run-npm-audit.sh`가 high·critical 0건으로 종료한다. 같은 시점의 `npm audit --json` 출력(실행 시각 포함)을 증거 로그에 남긴다 | design §1, codex 리뷰 ISSUE-1 | `요구` | T1 | `기존` `ci/run-npm-audit.sh` | `bash ci/run-npm-audit.sh` | exit 0. advisory 갱신으로 재실행이 실패하면 `## 변경 요청`에 적고 재승인받는다 |
| AC2 | 설치된 `next`와 `eslint-config-next`가 둘 다 `16.4.3`이다 | design D1 | `요구` | T2 | `기존` `npm ls` | `npm --prefix apps/web ls next eslint-config-next --depth=0` | 출력의 두 패키지 버전이 모두 `16.4.3` |
| AC3 | PR head 커밋에서 `quality-gate.yml`의 7개 잡이 전부 success다 | 이슈 AC | `요구` | T3 | `기존` `.github/workflows/quality-gate.yml` | — | PR checks 화면/`gh pr checks` 기록에 7/7 success |
| AC4 | high 미만 잔여 advisory가 있으면 PR 본문에 패키지·advisory ID·미해결 사유가 적혀 있다 (없으면 "잔여 0건"이 적혀 있다) | design D6 | `요구` | T3 | `관찰` PR 본문 | — | 본문 목록이 `npm audit` 최종 출력과 일치 |
| AC5 | `next dev` 실행 중 `/`, `/login`, `/register`, `/trackings`, `/trackings/1`이 HTTP 200을 반환한다 | design §4 next 위험 | `리뷰` | T2 | `기존` `next dev` + `curl` | `curl -s -o /dev/null -w '%{http_code}' http://localhost:3000/<path>` (경로별 실행) | 5개 경로 모두 `200` |
| AC6 | 프리미엄 차트·포지션(트래킹) 화면이 업그레이드 전과 같은 모습으로 보인다 | 이슈 테스트 전략 | `요구` | T4 | 사람 육안 | — | 사용자가 PR 코멘트로 확인 |
| AC7 | `apps/web/Dockerfile`로 빌드한 production 이미지(`node server.js`)를 기동하면 `/`와 `/login`이 HTTP 200을 반환한다 | design §4 Node·standalone 위험, codex 리뷰 ISSUE-2 | `리뷰` | T2 | `기존` `apps/web/Dockerfile` | ① `docker build -f apps/web/Dockerfile -t premium-web:issue-82 .` ② `docker run -d --name pw82 -p 3082:3000 premium-web:issue-82` ③ `curl -s -o /dev/null -w '%{http_code}' http://localhost:3082/` (`/login`도 동일) ④ `docker rm -f pw82` | ① exit 0, ③ 두 경로 모두 `200` |

**기준 개수**: 7개

**판정·종료 규칙** *(codex 리뷰 ISSUE-3)*

- T4(AC6)가 대기 중이면 판정은 `AWAITING_HUMAN`이다. 이 상태에서는 "완료"라고 쓰지 않고, PR은 Draft로 두며 `Closes #82`를 본문에 넣지 않는다
- AC6 확인 앵커(PR 코멘트 URL)가 생긴 뒤에만 `Closes #82`를 넣고 Ready for review로 전환한다

**분할하지 않는 이유**: (8개 이하라 해당 없음)

**티어 강등 사유** *(T1이 아닌 항목만. Gate 1에서 사람이 함께 승인한다)*

- AC2 → T2: 두 버전이 같은지 비교하는 단일 산출물이 없다. 이를 위해 스크립트를 새로 만드는 것은 일회성 확인 대비 과하다
- AC3 → T3: CI 결과는 GitHub에서만 관찰 가능하다. 로컬 단일 명령으로 재현할 수 없다
- AC4 → T3: PR 본문은 사람이 읽는 산출물이다
- AC5 → T2: dev 서버 기동이 필요해 단일 명령으로 묶을 수 없다
- AC6 → T4: 화면 동일성은 사람만 판정한다 (③ 합의)
- AC7 → T2: 이미지 빌드·컨테이너 기동·요청·정리를 하나의 이름 붙은 산출물로 묶은 것이 없다. CI에 런타임 잡을 추가하는 것은 "변경은 package.json·lockfile에 한정"이라는 이슈 요구를 벗어난다

## 검사 산출물

해당 없음 (`신규스크립트` 미사용).

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | eslint가 오류 0건으로 종료한다 | `npm --prefix apps/web run lint` |
| R2 | 기존 vitest 테스트 10개가 vitest 4에서 통과한다 | `npm --prefix apps/web run test` |
| R3 | `next build`가 6개 라우트를 빌드한다 (shadcn `tailwind.css` import 포함) | `npm --prefix apps/web run build` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### AC1

```
[RED] 2026-10-26 728e3f1
$ bash ci/run-npm-audit.sh
23 vulnerabilities (8 moderate, 11 high, 4 critical)
exit 1
```

### AC2

```
[RED] 2026-10-26 728e3f1
$ npm --prefix apps/web ls next eslint-config-next --depth=0
├── eslint-config-next@16.2.12
└── next@16.2.12
(16.4.3 아님)
```

### AC7 기준선

```
[BASELINE] 2026-10-26 728e3f1 (업그레이드 전에도 통과해야 하는 회귀 성격이라 RED 아님)
$ docker build -f apps/web/Dockerfile -t premium-web:issue-82 .   → exit 0
$ docker run -d --name pw82 -p 3082:3000 premium-web:issue-82
$ curl ... http://localhost:3082/       → 200
$ curl ... http://localhost:3082/login  → 200
$ docker rm -f pw82
```

### R1·R2·R3 기준선

```
[BASELINE] 2026-10-26 728e3f1
$ npm --prefix apps/web run lint    → exit 0
$ npm --prefix apps/web run test    → Test Files 1 passed (1) · Tests 10 passed (10), exit 0
$ npm --prefix apps/web run build   → exit 0, Route: / · /_not-found · /login · /register · /trackings · /trackings/[id]
```

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC6 | 프리미엄 차트·트래킹 화면 육안 확인 | | | |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: issue-82-web-npm-audit @ <commit SHA>
  수용기준 표:     7개  (T1 1 · T2 3 · T3 2 · T4 1)
  T1/T2 자동:      4개 중 <p>개 PASS
  T3 기록 제출:    2개 중 <q>건
  T4 사람 확인:    1개 중 <r>건 완료, <1-r>건 대기
  변경 요청:       <k>건
  =>
```
