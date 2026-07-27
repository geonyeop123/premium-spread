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
- [ ] **T7. 외부 스펙 리뷰 (`feature-workflow` ⑥)** — `codex-spec-review`로 `design.md` + `plan.md`를 리뷰하고
      ACCEPT/REBUT 루프를 닫는다. 현재까지의 리뷰는 모두 Claude 세션이므로 외부 관점이 비어 있다.
      → 예상: open finding 0 또는 반영 후 재검토
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
- 장기 증거 수집의 환경·비용 선택 — `design.md` §4.3의 gate가 소유한다
- activation 정책값(기간, 표본 수, 허용 손실) — `design.md` §4.4가 사용자 승인으로 고정한다
