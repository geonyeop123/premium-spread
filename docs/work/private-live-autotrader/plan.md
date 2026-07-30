# PRIVATE LIVE Autotrader — Program Plan

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ⑤ plan 문서 (program altitude) |
| slug | `private-live-autotrader` |
| spec | [`design.md`](design.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| base branch | `dev` |

> 이 plan은 **프로그램을 workflow 실행 단위로 분해**하는 상위 plan이다. `feature-workflow` ⑤가 요구하는
> "파일 경로 / 정확한 코드 / 명령 / 예상 결과, placeholder 금지"는 **Phase plan**의 계약이며, 이 문서는 그 Phase plan을
> 언제 누가 어떤 진입 조건에서 만드는지를 고정한다. 상세를 여기에 미리 적으면 `design.md` §0.3과 §11이 금지하는
> 중복 소유가 발생한다.

## 1. 실행 단위 분해

각 Phase는 하나 이상의 `feature-workflow` 실행 단위다. Phase agent가 최신 `dev`를 분석해 PR을 분해하므로 아래 slug는
Phase 진입 시 확정되는 후보이며, 한 Phase가 복수 slug로 나뉠 수 있다.

| # | 실행 단위 | slug 후보 | 진입 조건 | 산출 문서 | 종료 판정 |
|---|---|---|---|---|---|
| 0 | Master specification (현재) | `private-live-autotrader-master-spec` | 없음 (프로그램 개시) | 이 디렉터리의 `design.md`·`plan.md`·`dod.md` | 사용자 승인 → `MASTER_SPEC_APPROVED` |
| 1 | Phase 0 Foundation Alignment | `private-live-autotrader-phase-0` | `MASTER_SPEC_APPROVED` | `docs/work/{slug}/design.md`·`plan.md`·`dod.md` | `FOUNDATION_ALIGNED` |
| 2 | Phase 1 Market & Economics | `private-live-autotrader-phase-1` | `FOUNDATION_ALIGNED` | 〃 | `MARKET_ECONOMICS_READY` |
| 3 | Phase 2 SIMULATION + PAPER | `private-live-autotrader-phase-2` | `MARKET_ECONOMICS_READY` | 〃 | `STAGE_A_SOFTWARE_COMPLETE` |
| 4 | Phase 3 PRIVATE LIVE Capability | `private-live-autotrader-phase-3` | `STAGE_A_SOFTWARE_COMPLETE` | 〃 | `PRIVATE_LIVE_CODE_READY` |

software Phase만으로는 프로그램이 정의된 최종 상태에 도달하지 못한다. `design.md` §4.4·§4.5·§9가 요구하는 gate도
각각 실행 단위를 갖는다. 이들은 코드 산출물이 아니라 gate 판정과 evidence를 산출하며, 전이 선언 권한은 사용자에게 있다.

| # | 실행 단위 | 진입 상태 | owner | 산출물 | 종료 판정 |
|---|---|---|---|---|---|
| C | Collection 시작 | `P1-O1` 최소 수집 계약 확정 | owner + Phase 1 agent | `ECG-1`~`ECG-3` 충족 근거, `ECG-5` 적격성 판정, `ECG-4` 시작 시점 기록 | `COLLECTION_IN_PROGRESS` |
| A1 | Candidate gate | `EVIDENCE_PENDING` + 승인된 viability policy | owner | `ACT-1` 항목별 판정, `ECO-5`·`SAFE-7` 정책 승인 기록 | `CANDIDATE_APPROVED` 또는 `CANDIDATE_REJECTED` |
| A2 | Account gate | `CANDIDATE_APPROVED` + `PRIVATE_LIVE_CODE_READY` | owner | read-only reconcile 결과, credential·egress·configuration snapshot 승인 | account readiness 승인 |
| A3 | Canary gate | A2 통과 | owner | 실계정 SHADOW 결과, leg별 provider 검증 단계 판정, preflight 기록 | `ACTIVATION_IN_PROGRESS` |
| A4 | LIMITED gate | canary 완료 | owner | 외부 statement 대조, unresolved order·residual exposure 부재 확인 | bounded LIMITED 승인 |
| Z1 | Active closure | bounded LIMITED 수행 완료 | owner | `DONE-1`~`DONE-6` 판정과 redacted evidence | `PRIVATE_LIVE_ACTIVE_COMPLETE` + `PROGRAM_COMPLETED` |
| Z2 | NO_GO closure | owner의 종결 트리거 발행 또는 `NOGO-0` 재평가 결과 (전이 실행은 runtime) | owner | `NOGO-1`~`NOGO-4` 확인 기록 | `PROGRAM_TERMINATED_NO_GO` |

각 gate 실행 단위는 진입 전에 자신의 판정 기준을 `docs/work/{gate-slug}/dod.md`로 동결하고, 판정 결과와 evidence는
`progress.md`에 append한다. gate는 T3·T4 증거를 다루므로 software DoD의 T1·T2와 섞지 않는다. 어느 항목이라도
`UNKNOWN`·만료·불일치이면 그 전이와 이후 전이를 차단한다.

완료된 실행 단위는 다음과 같다. 산출물은 동결 증거이므로 경로를 옮기지 않는다.

| 실행 단위 | slug | 산출 문서 | 상태 |
|---|---|---|---|
| Phase -1 CI 실행 계약 단순화 | `private-live-autotrader-phase-minus-1` | [design](../../../.ai/planning/private-live-autotrader/phase-minus-1-design.md) · [plan](../../../.ai/planning/private-live-autotrader/phase-minus-1-plan.md) · [dod](../../dod/private-live-autotrader-phase-minus-1.dod.md) · [understanding](understanding.md) | PR #63 merged, DoD `VERIFIED` |

Phase 진입 시 `feature-workflow` ①부터 다시 시작한다. 즉 base 동기화 → worktree/branch 생성 → 브레인스토밍 →
사용자 합의 → 그 Phase의 `design.md`/`dod.md` → `plan.md` → 스펙 리뷰 → 사용자 승인(DoD `FROZEN`) → 구현 순서를
Phase마다 반복하며, 이 문서는 그 반복의 진입 조건만 고정한다.

## 2. 현재 실행 단위 태스크

현재 실행 단위는 `#0 Master specification`이다. 산출물은 코드가 아니라 문서이므로 `feature-workflow` ⑧ 구현은
문서 작성으로 대체되고 ⑨ 코드 리뷰는 문서 리뷰로 갈음한다.

- [x] **T1. 프로그램 재범위화** — 기존 master가 roadmap·상세 설계·운영 절차·PR checklist를 중복 소유하는 문제를 확인하고
      상위 specification으로 재작성 범위를 사용자와 합의한다.
      → 증거: `progress.md` "scope reset" 절
- [x] **T2. Specification 초안 작성** — 제품 목표/비범위, 사용자 보장, 목표 아키텍처, 상태·gate, Phase roadmap,
      consumption contract, traceability, 완료·종결 조건을 작성한다.
      → 산출: `docs/work/private-live-autotrader/design.md`
- [x] **T3. 독립 리뷰 A 반영** — SHADOW mode·Phase 소유권, margin/강제 감축 안전 계약 등 blocker·major·minor 반영.
      → 증거: `progress.md` "Claude 독립 Review A"
- [x] **T4. 독립 리뷰 B 반영과 closure** — Phase 2 strategy decision 소유권, evidence/activation 진행 상태 등 반영 후
      동일 reviewer closure `PASS`.
      → 증거: `progress.md` "Claude 독립 Review B와 closure"
- [x] **T5. 독립 리뷰 C 반영** — 저장소 실제 코드·스키마 대조에서 나온 blocker 3(정본 identity, evidence clock,
      자본 소진), major 7, minor 7을 반영한다.
      → 산출: `ECO-5`, `SAFE-9`, `ECG-5`, `P1-O8`, `P2-O10`, `P3-O15`, `P3-O16`, `NOGO-0` 신설
      → 증거: `progress.md` "Claude 독립 Review C와 반영"
- [x] **T6. workflow 산출물 형식 정렬** — 프로그램 문서를 `docs/work/{slug}/` 계약으로 재구성하고 `plan.md`·`dod.md`를
      생성한다. 동결된 Phase -1 산출물과 그 검증 명령이 참조하는 경로는 이동하지 않는다.
      → 명령: `bash docs/check-documentation.sh`
      → 예상: `exit 0`, `documentation check passed`
- [x] **T7. 외부 스펙 리뷰 (`feature-workflow` ⑥)** — `codex-spec-review` 1라운드 완료. critical 1·high 5·medium 2를
      받아 REBUT 없이 전부 ACCEPT하고 반영했다.
      → 산출: `SAFE-10`, `LIVE-11`, `PROM-4`, `P3-O17`~`P3-O19`, `ACTIVATION_RECOVERY_ONLY`, gate 실행 단위 표
      → 증거: `progress.md` "Codex 외부 스펙 리뷰"
      → 구조 보강: 재발 부류를 §4.3 권한 표와 §8.1 정직성 표로 전환하고 `AC9`·`AC10`으로 기계 검사 등재
      → 라운드별 지적: 1R 8건 → 2R 2건 → 3R 2건 → 4R 1건 → 5R 1건, critical은 2R 이후 0. 전부 REBUT 없이 반영
      → 5R에서 복구 권한을 `LIVE-11` 단일 정의로, 6R에서 전이 fence를 `SAFE-6`의 `FENCE`로 접고 `AC11`·`AC12`로 기계 차단
      → 7R에서 권한 회수 트리거를 한 표로 모으고 `AC13`으로 표 밖 guard까지 기계 검사
      → 8R에서 차단 계약을 전수 조사해 트리거 목록을 닫고 판별 기준을 명시
      → 9R에서 Phase outcome 경로(`P3-O17`)까지 트리거로 등재해 목록을 8개로 확장
      → 라운드별 지적: 8 → 2 → 2 → 1 → 1 → 1 → 1 → 1 → 1건, critical은 2R 이후 0
      → 10R부터 사용자 지시로 지적마다 부류를 도출해 문서 전체를 스윕하고 기계 검사를 추가하는 방식으로 전환
      → 10R TOCTOU 부류(+3건 동시 수정), 11R 재개 전이 부류(+3건 동시 정의). 자동 수용기준 13개
      → **완료: 33·34라운드 연속 지적 0건으로 수렴, `AC7` 충족**
- [ ] **T8. 사용자 리뷰와 DoD 동결 (`feature-workflow` ⑦)** — 문서 3종을 공유해 승인받고 `dod.md`를
      `status: FROZEN` + `frozen_at`으로 전이한다. 동결 전에는 Phase 0으로 진행하지 않는다.
- [x] **T9. 프로젝트 문서 최신화 (`feature-workflow` ⑪-a)** — `.ai/PROJECT_STATUS.md`에 프로그램 상태와 문서 경로
      변경을 반영한다. 코드 변경이 없으므로 ERD·상태 다이어그램·progress 카운트는 대상이 아니다.
      → 산출: `.ai/PROJECT_STATUS.md` "PRIVATE LIVE 프로그램 상태" 절, `CLAUDE.md` 문서 경로 표
      → 이 저장소에는 `docs/tech-debt.md`·`docs/progress/`·`.ai/diagrams/`가 없어 해당 범위는 대상 없음
- [x] **T10. 이해문서 (`feature-workflow` ⑪-b)** — 이번 실행 단위의 `explain-pr` 산출물을 작성한다. 기존
      `understanding.md`는 Phase -1(PR #63) 대상이므로 파일명 폴백 규칙에 따라 별도 파일로 만든다.
      → 산출: `docs/work/private-live-autotrader/understanding-master-spec.md`
- [ ] **T11. finalize (`feature-workflow` ⑪-c)** — 커밋 완료, PR 생성은 미수행. 이슈 번호 컨텍스트가 없으므로 `Closes #N` 규칙은
      해당하지 않는다.

## 3. 이 plan이 결정하지 않는 것

- Phase별 클래스·필드·endpoint·table 이름과 migration 번호
- Phase별 PR 개수와 순서 — `design.md` §6.2가 Phase agent에 위임한다
- 장기 증거 수집의 환경·비용 선택 — `design.md` §4.4의 gate가 소유한다
- activation 정책값(기간, 표본 수, 허용 손실) — `design.md` §4.5가 사용자 승인으로 고정한다
