# apps/web npm audit 취약점 해소 — 구현 계획

> design: `docs/work/issue-82-web-npm-audit/design.md` · dod: `docs/work/issue-82-web-npm-audit/dod.md`
> 모든 명령은 worktree 루트(`.worktrees/fix-issue-82-web-npm-audit`)에서 실행한다.
> 변경 파일은 `apps/web/package.json`, `apps/web/package-lock.json`, 필요할 때만 `apps/web/vitest.config.ts`·테스트 파일.

## Task 1 — next / eslint-config-next 16.4.3 (D1)

- [x] `npm --prefix apps/web install --save-exact next@16.4.3 eslint-config-next@16.4.3`
- [x] `npm --prefix apps/web ls next eslint-config-next --depth=0` → 둘 다 `16.4.3` (AC2 GREEN)
- [x] `npm --prefix apps/web run lint` exit 0, `npm --prefix apps/web run build` exit 0 (R1·R3)
- [x] 커밋: `fix: apps/web next·eslint-config-next 16.4.3 상향`

## Task 2 — vitest 4 (D2)

- [x] `npm --prefix apps/web install --save-dev vitest@^4.1.12`
- [x] `npm --prefix apps/web ls vite @vitejs/plugin-react --depth=1`로 peer 경고 확인. `@vitejs/plugin-react`가 설치된 vite 버전을 peer로 받지 않으면 `npm --prefix apps/web install --save-dev @vitejs/plugin-react@latest`
- [x] `npm --prefix apps/web run test` → Test Files 1 passed · Tests 10 passed (R2). 실패하면 vitest 4 마이그레이션 가이드 기준으로 `vitest.config.ts`/테스트만 고친다
- [x] 커밋: `fix: apps/web vitest 4 상향`

## Task 3 — shadcn 4.22.1 (D3)

- [x] 상향 전 `cp apps/web/node_modules/shadcn/dist/tailwind.css <scratchpad>/shadcn-tailwind-3.css`
- [x] `npm --prefix apps/web install --save-dev shadcn@^4.22.1`
- [x] `diff <scratchpad>/shadcn-tailwind-3.css apps/web/node_modules/shadcn/dist/tailwind.css` 결과를 dod 증거 로그(R3 주석)에 기록
- [x] `npm --prefix apps/web run build` exit 0 (R3)
- [x] 커밋: `fix: apps/web shadcn 4.22.1 상향`

## Task 4 — 전이 의존성 lockfile 갱신 (D4)

- [x] `npm --prefix apps/web audit fix` (`--force` 금지)
- [x] `npm --prefix apps/web audit --audit-level=high` 잔여 확인. high 이상이 남으면 `package.json` `overrides`의 해당 항목 하한을 audit이 제안한 수정판으로 올리거나 항목을 추가하고 `npm --prefix apps/web install`
- [x] 깨끗한 설치로 재검증: `rm -rf apps/web/node_modules` 후 `npm --prefix apps/web ci --include=optional`
- [x] `bash ci/run-npm-audit.sh` exit 0 (AC1 GREEN), 잔여 low/moderate 목록을 `npm --prefix apps/web audit --json`으로 저장 (AC4 입력)
- [x] R1·R2·R3 재실행
- [x] 커밋: `fix: apps/web 전이 의존성 취약점 해소`

## Task 5 — next dev 스모크 (AC5)

- [x] `npm --prefix apps/web run dev`를 백그라운드로 기동
- [x] `/`, `/login`, `/register`, `/trackings`, `/trackings/1` 각각 `curl -s -o /dev/null -w '%{http_code}' http://localhost:3000<path>` → 전부 `200`
- [x] dev 서버 종료, 결과를 dod 증거 로그에 기록

## Task 6 — production Docker 이미지 스모크 (AC7)

- [x] `docker build -f apps/web/Dockerfile -t premium-web:issue-82 .` exit 0
- [x] `docker run -d --name pw82 -p 3082:3000 premium-web:issue-82`, 기동 대기 후 `/`·`/login`에 `curl -s -o /dev/null -w '%{http_code}' http://localhost:3082<path>` → `200`
- [x] `docker rm -f pw82`, 결과를 dod 증거 로그에 기록

## Task 7 — 검증·마무리

- [ ] 최종 커밋 SHA에서 `npm --prefix apps/web ci --include=optional` 후 `bash ci/run-npm-audit.sh` 재실행, `npm --prefix apps/web audit --json`(실행 시각 포함)을 증거로 남김 (AC1)
- [ ] dod 증거 로그에 GREEN 기록 (AC1·AC2·AC5·AC7·R1~R3)
- [ ] PR을 **Draft**로 생성. 본문에 잔여 low/moderate 목록과 사유(AC4). `Closes #82`는 아직 넣지 않는다
- [ ] PR checks 7/7 success 확인 후 기록 (AC3)
- [ ] 사용자 육안 확인(AC6)을 PR 코멘트로 받은 뒤 `Closes #82`를 본문 최상단에 넣고 Ready for review로 전환
