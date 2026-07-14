# Infrastructure Boundary Refactoring Progress

> 갱신: 2026-07-14 KST

| Phase | 상태 | Commit | Push | 검증 |
|---|---|---|---|---|
| 0. 기준선/운영 데이터 점검 | COMPLETE | `cc7030e` | `origin/refactor/infrastructure-boundary` | unit 443 green, Batch integration 64개 중 25개 known failures 승인 |
| 1. 긴급 운영 안전장치 | COMPLETE | 이번 Phase commit | 예정 | unit 473 green, API integration 91개 중 90 green/1 approved disabled |
| 2~10 | NOT_STARTED | 없음 | 없음 | Phase 1 commit/push 대기 |

## Phase 0 실행 기록

- `origin/dev` `a0a59ee`에서 전용 worktree와 `refactor/infrastructure-boundary` 브랜치를 생성했다.
- 원본 workspace의 사용자 변경 `.ai/instructions.md`, `.claude/worktrees/`는 수정하지 않았다.
- 최초 계획 문서를 동일 SHA-256으로 worktree에 복제한 뒤, 스펙 리뷰와 사용자 승인 내용을 실행본에 반영했다.
- default test 443개는 성공했다.
- API integration은 Docker API 1.44 보정 후 성공했다.
- Batch integration 전체 64개를 Docker API 보정 후 재실행해 25개 실패를 확인했다: retention 2건은
  Phase 3, test fixture schema drift 23건은 Phase 2 owner가 해결한다.
- Web production build는 성공했지만 lint 1건은 실패했다.
- 로컬 V12는 APPLIED/position 0건이다.
- 운영/스테이징 환경은 없으며 `NOT_DEPLOYED`로 분류했다.
- 의미가 불명확한 로컬 timestamp는 변환하지 않고 volume을 보존한 뒤 UTC 기반 새 volume/fixture를 사용한다.
- offline cache 부재로 coverage/ktlint/detekt/OWASP 결과를 만들 수 없다.
- 사용자가 Batch retention 2건(Phase 3), schema fixture drift 23건(Phase 2), Web lint 1건(Phase 5), quality tool 부재(Phase 9 CI)를
  `baseline-known-failures`로 승인했다.

## Phase 1 실행 기록

- refresh 공개 matcher를 exact POST path로 제한하고 실제 로그인 refresh cookie만으로 재발급되는 E2E를 추가했다.
- JWT secret/issuer/audience/access TTL/refresh TTL/clock skew는 local/test 외 환경에서 필수이며, 값과 서명 claim을 검증한다.
- API 공통 JPA는 `validate`, local/test만 `create-drop`이고 Batch Flyway는 비활성화했다.
- V12 외부 preflight와 API Flyway callback이 APPLIED/PENDING_EMPTY/PENDING_WITH_DATA를 같은 정책으로 차단한다.
- V11 MySQL에 position 데이터가 있는 통합 테스트에서 V12 실행 전 차단, 기존 row 보존, V12 이력 미생성을 검증했다.
- 이메일 비활성 시 SMTP/EmailSender/listener/service bean이 없고, 활성화 시 발신자와 SMTP 설정을 fail-fast 검증한다.
- 공개 Actuator는 liveness/readiness GET으로 제한하고 Batch management port는 내부 9081로 분리했다.
- Phase 1 전체 단위 테스트는 API 290 + Batch 176 + Email 7 = 473개가 모두 성공했다.
- API integration은 91개 중 90개 성공, 기존 JWT blacklist 미구현 테스트 1개는 계획된 Phase 5 항목으로 disabled 상태다.
- shell syntax, V12 분류 script, `git diff --check`가 성공했다.
- 스펙 리뷰와 코드 리뷰는 BLOCKER/MAJOR 없이 PASS했다. 리뷰 중 발견한 workflow fail-stop, 안전하지 않은
  JWT template placeholder, Boot 기본 SMTP auto-configuration 우회 경로를 수정하고 재검증했다.

## 승인 및 재개 상태

2026-07-14 사용자 결정: 운영/스테이징 없음, 불명확한 로컬 timestamp는 변환하지 않는 추천안 채택,
스펙 리뷰 보정안 전체 승인. Phase 0 commit/push를 완료했고 Phase 1 review gate를 수행한다.
