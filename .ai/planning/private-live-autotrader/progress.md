# PRIVATE LIVE Autotrader 진행 기록

> append 중심의 Phase acceptance 증거 문서다. 소스 구조의 정본은 `.ai/architecture/ARCHITECTURE_DESIGN.md`, 프로그램 범위와
> 완료 조건의 정본은 `docs/work/private-live-autotrader/design.md`다.

## 프로그램 상태

`docs/work/private-live-autotrader/design.md` §4의 상태축 현재값은 이 문서가 단독으로 소유한다.

| 상태축 | 현재값 |
|---|---|
| specification | `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL` (Review C 반영본) |
| software | `SOFTWARE_BASELINE` |
| evidence collection | `COLLECTION_NOT_READY` |
| candidate/evidence | `CANDIDATE_NOT_SELECTED` |
| activation | `ACTIVATION_NOT_STARTED` |
| execution latch | `LATCH_CLEAR` |
| program | `PROGRAM_IN_PROGRESS` |

- 현재 Phase: `Master specification 사용자 승인 대기`
- feature branch: `docs/private-live-autotrader-master-spec`
- worktree: `/mnt/c/users/yeop/ideaprojects/premium-spread/.worktrees/docs-private-live-autotrader-master-spec`
- Draft PR: `NOT_CREATED`
- base `origin/dev`: `15cc02f820ed688dae5ef7b38ce50245f2cb1566`
- original master plan commit: `b6e16edb3632978728f62918bddb25f791501467`

## Master specification 재작성 — 2026-07-21

### 이전 Phase 인수

- PR #63은 `dev`에 merge됐고 merge commit은 `15cc02f820ed688dae5ef7b38ce50245f2cb1566`다.
- merged `dev` Quality Gate run `29796418475`의 7개 job은 모두 성공했다.
- 완료된 `feat/private-live-autotrader` local/remote branch와 linked worktree는 정리했다.
- `refactor/infrastructure-boundary` worktree에는 고유 untracked 계획 문서가 있어 강제 삭제하지 않았다.
- 주 작업트리의 사용자 소유 dirty 파일은 수정하거나 새 worktree로 가져오지 않았다.

### scope reset

- project-local `.worktrees/`는 merged `dev`의 `.gitignore`에 포함돼 있다.
- dirty한 주 작업트리에서 최신 `origin/dev`를 직접 기준으로 linked worktree를 만들었다.
- 전체 계획 재감사 결과 기존 master가 roadmap, 상세 설계, 운영 절차와 PR checklist를 중복 소유한다고 판정했다.
- 사용자는 상세 구현 계획이 아닌 상위 specification으로 재작성하고, 각 Phase consumer가 `work`에서 PR 분할을 결정하도록
  승인했다.
- 이에 따라 branch/worktree를 docs-only master specification 책임으로 변경했다.
- 미구현·미커밋 Phase 0A design/DoD는 새 Phase 경계와 충돌하므로 폐기했다.
- 제품 구현은 시작하지 않았으며, Phase -1 이후 product source 변경은 없다.

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, 67 tasks |
| quality/dependency/local-offline/deploy/documentation contracts + `git diff --check` | PASS, exit 0 |
| worktree HEAD와 `origin/dev` 비교 | 둘 다 `15cc02f820ed688dae5ef7b38ce50245f2cb1566` |

### 재작성된 Phase 경계

- Phase 0: Foundation Alignment
- Phase 1: Market & Economics Foundation
- Phase 2: SIMULATION + PAPER Product
- Phase 3: PRIVATE LIVE Capability
- 각 Phase는 capability milestone이며 branch/PR 단위가 아니다.
- Phase consumer가 최신 코드와 첫 consumer를 분석해 `work`에서 PR map과 DoD를 별도로 승인받는다.
- 장기 데이터, 환경, 법률·계정과 activation은 software Phase와 분리한 program gate다.
- master 초안은 Codex 내부 감사 뒤 Claude 2회 독립 리뷰를 거쳐 사용자 최종 승인을 받는다.

### Claude 독립 Review A — 2026-07-22

- 관점: 제품 요구사항 누락, 현재 아키텍처 불변식, 외부 리서치와의 의미 충돌
- 방식: 별도 Claude 세션에서 repository와 리서치 정본을 읽기 전용으로 검토
- 최초 판정: `REVISE`
- 반영한 blocker: SHADOW mode와 Phase 소유권, margin·강제 감축 안전 계약
- 반영한 major: 장기 증거 수집 readiness, bounded LIMITED 종점, evidence 만료, 정본 `MarketPair`, 안정 ID 추적성,
  안전 중요 전이의 owner 알림, 자본 배치·재배치 비용, migration 단일 실행 주체
- 반영한 minor: 기존 migration 세부명 제거, 파생상품 위험 자세의 추상화, mode 간 경제 동등성, 상태명 통일, 논리 책임과
  물리 배치 분리, 외부 리서치의 비구속성, 불리한 비용 조건, self-induced duplicate와 외부 불명 결과의 구분
- 열린 Review A finding: `0` — 반영본은 문서 검증 후 Review B로 전달

### Claude 독립 Review B와 closure — 2026-07-22

- 관점: specification 추상화, requirement traceability, Phase 소비 가능성, 상태·gate adversarial scenario, 보안 적정성
- 방식: Review A의 finding과 결론을 전달하지 않은 별도 Claude 세션에서 읽기 전용 검토
- 최초 판정: `REVISE` — blocker 2, major 5, minor 8
- 반영한 blocker: Phase 2의 strategy decision 소유권, evidence collection과 activation의 진행 상태
- 반영한 major: candidate 비용 gate, Phase 2 `MarketPair`, specification/software/program 상태축, 독립 PR slice 병렬성,
  active candidate reject의 fail-closed 전이
- 반영한 minor: `LIVE-10` 단일 정본화, migration 검증 참조, roadmap 순환 제거, 공통 비회귀, credential 노출 surface,
  논리·물리 저장 비례성, architecture 정본 우선순위, program completion·NO_GO ID
- 동일 Reviewer B closure: `PASS` — 기존 15개 finding 모두 `RESOLVED`, 신규 blocker/major 0
- 최종 reviewer 답변: master specification 사용자 승인 요청 가능 `YES`
- 현재 specification 상태: `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL`

### Claude 독립 Review C와 반영 — 2026-07-25

- 관점: repository 실제 코드·스키마와의 대조, 제품 구조 리스크, 문서 내부 정합성
- 방식: Review A/B 결과를 전달하지 않은 별도 세션에서 읽기 전용 검토 후 사용자 지시로 반영본 작성
- 판정: `REVISE` — blocker 3, major 7, minor 7

반영한 blocker

- 정본 identity: `ARCH-9`가 `MarketPair(symbol, koreaExchange, foreignExchange)`를 정본으로 못박았으나 §1.2의 해외 leg는
  Binance USDT perpetual이고 현재 batch도 이미 `fstream` 선물 stream을 쓴다. instrument class·quote currency를 구분하지
  못하는 identity를 정본으로 고정하지 않도록 `ARCH-9`을 최소 구성 요구로 재작성하고 확장 판정을 `P0-O3`에 귀속했다.
- evidence clock: 현재 `Ticker`는 단일 `price`만 보존하고 Bithumb은 체결가, Binance는 best bid/ask mid이며 호가 수량이
  없다. 기존 이력이 `DATA-3`을 소급 충족할 수 없다는 사실과 그로 인해 장기 증거 clock이 사실상 새로 시작한다는 점을
  `ECG-5`, `DATA-3`, `P1-O1`, §4.3, roadmap에 명시했다.
- 자본 구조: 자동 transfer가 없는 delta-hedged 구조에서 leg 손익이 반대 부호로 실현돼 한쪽 자본이 소진된다는 실행
  가능성 제약을 `ECO-5`로 신설하고 `SAFE-9`, `P2-O10`, `P3-O16`, `ACT-1`에 연결했다.

반영한 major

- Phase 1의 first-consumer 정합성(`P1-O8` vs §6.2), host-local 긴급 중단 대칭성(`SAFE-6`/`LIVE-6`/`P3-O7`),
  수치 스케일·반올림 소유(`ECO-3`), durable intent 선행 순서(`SAFE-1`/`P3-O5`), 상태축 현재값 SSOT와 초기값
  (`SOFTWARE_BASELINE`, §4.2, 이 문서), leg별 provider 검증 단계(`ACT-3`/`P3-O15`/§7.2), 고정 egress와 credential
  rotation(`ACT-2`/§3.5)

반영한 minor

- venue와 provider 소유 구분(§0.3), roadmap의 evidence 분기 시점, 프로그램 재평가 조항(`NOGO-0`), `QUAL-1`의 T2 판정
  근거, `SEM-4`의 leverage 오인 방지, Phase DoD의 ID 역방향 인용 강제(§8), §0.1의 상태 중복 기록 제거

- 열린 Review C finding: `0` — 사용자 승인 대기

### Codex 외부 스펙 리뷰 — 2026-07-27

- 대상: `docs/work/private-live-autotrader/design.md`와 `plan.md`, 커밋 `660f9ca` 기준
- 방식: codex `adversarial-review` 1라운드, Codex session `019fa26a-b22e-7272-913d-ed92259ffa13`
- 판정: `needs-attention` / NO-SHIP — critical 1, high 5, medium 2
- REBUT 0건. 8건 모두 문서 원문과 대조해 실재하는 허점으로 확인하고 ACCEPT했다.

| 이슈 | 지적 | 반영 |
|---|---|---|
| ISSUE-1 (critical) | 중단·강등이 미전송 intent와 거래소 대기 주문을 fence하지 않음 | `SAFE-1` epoch fence, `SAFE-6` halt 완결·recovery-required, `P3-O4`, `P3-O19` |
| ISSUE-2 (high) | `P3-O4`(activation 필요)와 `P3-O14`(만료 후 청산) 권한 모순 | `ACTIVATION_RECOVERY_ONLY` 상태, `LIVE-11`, `LIVE-10`·`P3-O14` 수정 |
| ISSUE-3 (high) | 위험 margin에서 기존 short 보호 수단 부재 | `SAFE-7`에 headroom·stress 기준과 breach 대응, `ACT-1` 승인 항목 |
| ISSUE-4 (high) | Binance account configuration이 activation 대상에 미결합 | `ACT-2` configuration snapshot, `P3-O17` drift 검증 |
| ISSUE-5 (high) | risk budget이 in-flight 제출을 예약하지 않아 동시 초과 가능 | `LIVE-8` 예약 규칙, `P3-O18`, §7.2 negative 검증 |
| ISSUE-6 (high) | plan이 code-ready에서 끝나 activation·종결 실행 단위 없음 | `plan.md` §1에 gate 실행 단위 표(C·A1~A4·Z1·Z2) 추가 |
| ISSUE-7 (medium) | holdout 반복 소비 경로가 열려 있음 | `PROM-4` 신설 |
| ISSUE-8 (medium) | `SAFE-1` 전체를 Phase 2에 배정해 `ARCH-11`과 충돌 | `SAFE-1`을 mode-neutral로 축소하고 외부 제출 책임을 `SAFE-10`으로 분리 |

- Codex 권고에 따라 `dod.md`는 `DRAFT`를 유지하고 `MASTER_SPEC_APPROVED`로 전이하지 않는다.
- 재검토(2라운드)는 미실행이므로 `AC7`은 계속 대기다.

### 리뷰 재발 부류의 구조 보강 — 2026-07-28

- 배경: Claude A·B·C와 Codex 1라운드까지 4회 리뷰에도 지적이 계속 나왔다. 원인을 분석한 결과 codex 지적 8건 중 7건은
  Review C 이전 버전에 이미 존재했고, 문서 크기가 아니라 리뷰 렌즈가 서로 겹치지 않은 것이 원인이었다.
- 특히 `P3-O4` vs `P3-O14` 권한 모순은 Review B가 추가한 항목끼리의 충돌인데 B의 closure 패스에서도 걸리지 않았다.
- 대응: 재발한 두 부류를 산문이 아니라 대조표로 만들어 검사 가능하게 바꿨다.
  - `design.md` §4.3 상태별 허용 행위 — activation 상태 × 제출 권한 단일 대조표
  - `design.md` §8.1 Phase 정직성 검사 — Phase 실행 모드에서 관찰 가능한 계약과 배정 불가 계약
- 두 표가 낡지 않도록 `dod.md`에 기계적 수용기준 `AC9`, `AC10`을 등재했다. 변형본으로 RED를 재현해 검사가 실제로
  결함을 탐지함을 확인했다.
- `AC7`의 종료 조건을 반증 불가능한 "open finding 0"에서 "동일 렌즈 재검토 critical·high 0 + 네 렌즈 각 1회 통과"로
  수렴 기준으로 교체했다.
- §4에 절이 추가되어 collection gate가 §4.3→§4.4, activation gate가 §4.4→§4.5로 이동했고 문서 간 상호 참조 8건을
  함께 갱신했다.

### Codex 외부 스펙 리뷰 2라운드 — 2026-07-28 (미완료)

- job `review-ms4196fk-jpdfh6`, session `019fa68a-0aca-7693-b943-39f1f33b0c44`, 12분 44초 수행 후 실패
- 실패 원인: codex 사용 한도 초과 (`Turn failed`). 한도 해제 예정 2026-08-03
- 실패 전까지 수행한 것: 대상 문서와 `584b6f4` diff 대조, `660f9ca` 이전 버전 비교,
  `bash docs/check-documentation.sh && git diff --check origin/dev...HEAD` (exit 0)
- 실패한 turn의 최종 출력에 `Verdict: approve`와 `No material findings`가 있으나 이는 fallback이며 수렴 근거가 아니다.
  summary가 미래형("재검토하겠습니다")으로 남아 판정이 산출되지 않았음을 드러낸다.
- 판정: `AC7` 미충족 유지. 재검토는 한도 해제 후 동일 프롬프트로 재실행한다.

### Codex 외부 스펙 리뷰 2라운드 재시도와 반영 — 2026-07-28

- 앞선 실패는 사용 한도였고 재시도 전 최소 probe(`PROBE_OK`)로 한도 해제를 확인한 뒤 동일 프롬프트로 재실행했다.
- job `review-ms4f357a-2sbu1x`, session `019fa7ec-8e79-7750-bfae-02424aaa105f`, 판정 `needs-attention`
- 결과: critical 1 · high 1 — 1라운드 8건 대비 2건으로 감소했고 둘 다 1라운드 수정 영역의 2차 공백이었다.

| 이슈 | 지적 | 반영 |
|---|---|---|
| critical | halt 완료가 "전송 후 응답 불명·거래소 ID 미확정" intent를 fence하지 않아 epoch 변경 뒤 주문이 생성될 수 있음 | `SAFE-6`을 미전송 / 거래소 잔존 / 응답 불명 세 집합으로 재작성하고 client order identifier로 terminal 결론을 얻기 전 halt 성공·budget 해제 금지, `P3-O19` 확장 |
| high | §4.3 권한 표에 program 축이 없어 `PROGRAM_TERMINATION_PENDING`에서도 신규 진입 허용 | program 축 2행 추가, 종결 방향이 activation보다 우선하고 pending 진입이 `ACTIVATION_RECOVERY_ONLY` 전이와 원자적임을 명시, §9.4 interlock |

- 이 high 지적은 전날 추가한 §4.3 대조표가 없었으면 드러나지 않았을 누락이다. 구조 보강이 의도대로 작동했다.
- 반영 과정에서 `AC9`가 실제로 RED를 냈고(`undefined_in_axis`에 program 상태 2건), 검사를 축 전체 대조로 확장한 뒤
  GREEN으로 복귀시켰다.
- 3라운드는 미실행이며 `AC7` 수렴 판정은 그 결과로 내린다.

### Codex 외부 스펙 리뷰 3라운드와 반영 — 2026-07-28

- job `review-ms4f9k83-ftwqh0`, session `019fa7f1-1c6d-7e12-bad4-5574ec6056b3`, 1분 22초, 판정 `needs-attention`
- 결과: critical 0 · high 2. 추이는 1R 8건(critical 1) → 2R 2건(critical 1) → 3R 2건(critical 0)이다.

| 이슈 | 지적 | 반영 |
|---|---|---|
| high | `LIVE-11`이 gross·net·residual 전부 단조 감소를 요구해, 한 leg만 체결된 상태의 평탄화 hedge(gross는 늘고 net·residual은 주는 행위)를 금지하는 자기모순 | 위험 벡터별 기준으로 재정의: net·residual 증가 금지, gross 증가는 체결된 leg 평탄화 hedge에만 미헤지 수량 상한·budget 예약·완료 조건부 허용. §4.3 행과 `P3-O14` 정합 |
| high | `SAFE-1`에 "모든 외부 제출은 durable 기록 뒤" 순서 계약이 남아 Phase 2 배정이 §8.1 기준으로 여전히 부정직 | `SAFE-11`을 신설해 외부 제출 순서를 Phase 3 전용으로 분리하고 `SAFE-1`은 의도 식별·중복 방지·epoch 무효화의 mode-neutral 계약으로 축소. `P3-O5` 참조와 §8·§8.1 예시 갱신 |

- 두 지적 모두 1·2라운드 수정이 남긴 2차 결함이다. 특히 `LIVE-11`은 1라운드에서 신설한 문장 자체의 논리 오류였고,
  `SAFE-1` 분리는 1라운드 ISSUE-8을 반쪽만 해소한 상태였다.
- §8.1 정직성 표가 두 번째 지적의 판단 근거로 실제 인용됐다. 구조 보강이 리뷰어의 판정 기준으로 기능하고 있다.
- 반영 후 자동 수용기준 8개(AC1~AC6, AC9, AC10) 전부 PASS, ID 122개, dangling 0건.

### Codex 외부 스펙 리뷰 4라운드와 반영 — 2026-07-28

- job `review-ms4fekup-6zyy5k`, session `019fa7f4-affb-70d3-bf7a-64fe3e9b7133`, 판정 `needs-attention`, critical 0 · high 1
- 지적: 3라운드에서 연 `LIVE-11`의 gross 증가 hedge 예외에 `SAFE-7` 우선순위가 없다. 거래소가 short leg를 강제 감축한
  뒤 복구 로직이 long을 헤지하려고 short를 재진입하면 margin 위기를 악화시키고 `SAFE-7`의 fallback 경로를 우회한다.
- 반영: `SAFE-7` breach, liquidation·ADL·강제 감축, 설명되지 않은 account drift가 활성인 동안 gross 증가 예외를 금지하고
  bounded paired reduction 또는 owner fallback만 허용하도록 `LIVE-11`을 수정했다. §4.3 RECOVERY_ONLY 행, `P3-O6`,
  `P3-O14`를 같은 우선순위로 정렬하고 §7.2 failure matrix에 "강제 감축 후 단일 leg 상태에서 복구 hedge 거부" 경로를 넣었다.
- 추이: 8 → 2 → 2 → 1건, critical은 2라운드 이후 0이다. 2라운드부터의 지적은 모두 직전 수정이 만든 이음새다.

### Codex 외부 스펙 리뷰 5라운드와 구조적 반영 — 2026-07-28

- 판정 `needs-attention`, critical 0 · high 1. 추이는 8 → 2 → 2 → 1 → 1이다.
- 지적: §4.3의 `PROGRAM_TERMINATION_PENDING` 행이 "정리 범위"라는 자유 서술이라 `LIVE-11`의 위험 벡터와 `SAFE-7`
  우선순위를 상속하지 않는다. 종결 중 단일 leg가 남으면 hedge를 거부해 비헤지 노출을 방치하거나, 반대로 차단 조건 없이
  hedge를 허용할 수 있다.
- 3·4·5라운드 지적이 모두 같은 매듭(복구 권한 의미)의 다른 표면이라고 판단해 개별 패치 대신 구조를 바꿨다.
  - `LIVE-11`이 복구 집합 `RECOVERY-A`(cancel·unwind 상시)와 `RECOVERY-B`(체결 leg 평탄화 hedge, 상한·예약·완료 조건,
    `SAFE-7` breach·강제 감축·drift 중 금지)를 **한 번만** 정의한다.
  - §4.3의 모든 상태 행과 `P3-O6`, `P3-O14`, §9.4는 조건을 재서술하지 않고 집합 이름만 참조한다.
- `AC11`을 신설해 복구 열의 자유 서술을 기계적으로 금지했다. 이 검사가 codex가 지적한 1건 외에
  `ACTIVATION_IN_PROGRESS`와 `PRIVATE_LIVE_ACTIVE_COMPLETE` 2건을 추가로 탐지했고, 셋 다 집합 참조로 정리했다.

### Codex 외부 스펙 리뷰 6라운드와 구조적 반영 — 2026-07-28

- 판정 `needs-attention`, critical 0 · high 1. 추이는 8 → 2 → 2 → 1 → 1 → 1이다.
- 지적: `LIVE-10` 강등이 halt·종결 전이와 달리 거래소 대기 exposure-increasing 주문을 fence하지 않는다. 만료나 candidate
  reject 시점에 살아 있던 진입 주문이 복구 구간에 체결되어 노출이 늘 수 있고, 부분 체결·재시작과 겹치면 특히 위험하다.
- 반영: 5라운드와 같은 방식으로 구조를 바꿨다. `SAFE-6`의 세 집합 종결을 `FENCE`(`FENCE-1` 미전송 무효화,
  `FENCE-2` 거래소 잔존 주문 취소·종료 확인, `FENCE-3` 응답 불명 intent terminal reconcile)로 명명해 한 번만 정의하고,
  `LIVE-10`·`P3-O14`·`P3-O19`·§4.3의 모든 차단 전이가 조건을 재서술하지 않고 참조하게 했다.
- `AC12`를 신설해 신규 제출이 차단되는 모든 상태 행이 `FENCE`를 참조하는지 기계적으로 검사한다. 변형본으로 RED를 확인했다.
- 5·6라운드를 거치며 개별 패치 대신 단일 정의 + 기계 검사로 접는 방식이 자리 잡았다. 자동 수용기준은 10개다.

### Codex 외부 스펙 리뷰 7라운드와 반영 — 2026-07-28

- 판정 `needs-attention`, critical 0 · high 1. 추이는 8 → 2 → 2 → 1 → 1 → 1 → 1이다.
- 지적: `SAFE-7`(margin breach)과 `SAFE-9`(자본 부족)는 신규 진입을 막지만 activation 강등이나 halt 전이를 요구하지
  않는다. `FENCE`는 §4.3이 "신규 제출을 차단하는 전이"에만 걸어두었으므로, 이 guard가 발동해도 이미 durable하게 기록된
  미전송 intent나 응답 불명 제출이 남아 뒤늦게 진입으로 확정될 수 있다. `AC12`는 §4.3 표 행만 보므로 이를 탐지하지 못한다.
- 반영: §4.3에 권한 회수 트리거 표를 신설해 중단(`SAFE-6`), 만료·reject(`LIVE-10`), 종결(§9.4), margin·강제 감축(`SAFE-7`),
  자본 부족(`SAFE-9`), 상태 불신(`SAFE-3`)을 한 목록으로 모으고 모두 `FENCE` 동반 전이를 필수로 고정했다. `SAFE-3`·`SAFE-7`·
  `SAFE-9` 본문과 `P3-O6`·`P3-O16`, §7.2 negative 사례도 함께 갱신했다.
- `AC13`을 신설해 트리거 표의 `FENCE` 필수와 표 밖 guard 포함 여부를 기계 검사한다. 자동 수용기준은 11개다.
- 리뷰어가 기계 검사 자체의 사각지대를 지적한 첫 라운드다.

### Codex 외부 스펙 리뷰 8라운드와 반영 — 2026-07-28

- 판정 `needs-attention`, critical 0 · high 1. 추이는 8 → 2 → 2 → 1 → 1 → 1 → 1 → 1이다.
- 지적: 7라운드에 만든 권한 회수 트리거 표가 스스로 "전체 목록"이라 선언했지만 `SAFE-10`(제출 결과 불명·중복 의심)이
  빠져 있었다. `AC13`의 필수 출처에도 없어 기계 검사로도 걸리지 않았다.
- 반영 방식: 한 건씩 채우지 않고 문서 전체에서 "신규 exposure·진입을 차단"하는 계약을 전수 조사했다. 누락은 `SAFE-10`
  하나였고, `LIVE-8`(budget 한도)과 `SAFE-2`(복구 정책 밖 자동 확대 금지)는 권한을 회수하지 않는 범위 제약이라 트리거가
  아님을 확인했다.
- 표에 `SAFE-10`을 `FENCE-3` 포함 필수로 추가하고, **트리거 판별 기준**(제출 권한 자체를 회수하는가)과 비트리거 예시를
  명시해 같은 부류의 재발을 막았다. `SAFE-10` 본문에도 문제된 intent 하나만 막고 다른 intent를 계속 제출하게 두지
  않는다는 조건을 넣었다. `AC13` 필수 출처를 확장했다.

### Codex 외부 스펙 리뷰 9라운드와 반영 — 2026-07-28

- 판정 `needs-attention`, critical 0 · high 1. 추이는 8 → 2 → 2 → 1 → 1 → 1 → 1 → 1 → 1이다.
- 지적: `P3-O17`(승인된 account·symbol configuration snapshot의 drift)이 제출을 차단하고 activation 근거를 무효화하므로
  §4.3 기준상 권한 회수 경로인데 트리거 표와 `AC13`에 없다. drift 시 당면 제출만 거부하고 이미 승인된 intent나 거래소
  대기 주문이 남으면 표가 막으려던 stale-authority race가 재현된다.
- 8라운드 전수 조사가 §2 안전 불변식만 훑고 Phase outcome 구간을 빠뜨린 것이 원인이었다.
- 반영: 트리거 표에 configuration drift 행을 추가하고, 판별 기준에 "Phase outcome과 gate 항목도 이 기준을 만족하면
  트리거"라는 문장을 넣었다. `P3-O17` 본문에 epoch 무효화·`FENCE` 동반 전이·당면 제출만 거부 금지를 명시하고 `AC13`
  필수 출처를 확장했다. 트리거는 8개가 됐다.

### Codex 외부 스펙 리뷰 10라운드와 부류 스윕 — 2026-07-30

- 사용자 지시로 처리 방식을 바꿨다. 지적 인스턴스만 고치지 않고 **부류를 도출해 문서 전체를 스윕한 뒤 기계 검사를 추가**한다.
- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `P3-O17`의 configuration drift 검사가 exposure-increasing 제출 직전에만 수행돼, 점검 이후 설정이 바뀌거나 추가
  제출이 없는 구간의 변경을 탐지하지 못한다. 이미 전송된 대기 주문이 무효한 설정 아래 체결될 수 있다(TOCTOU).
- 부류 도출: "권한 회수 조건을 시점 검사로만 판단하고 지속 감시·재시작 재평가가 없는 계약".
- 스윕 결과: 8개 트리거 중 지속 관찰이 명시된 것은 `SAFE-7` 하나뿐이었다. `LIVE-10`과 `SAFE-9`는 탐지 시점 자체가 없었고
  `SAFE-3`도 지속 감시가 없었으며 재시작 재평가는 `SAFE-6`에만 있었다.
- 반영: 트리거 표에 탐지 경로 열을 신설해 8개 전부 지속·주기 감시와 재시작 재평가를 요구하고, 점검과 전송 사이 변화를
  막기 위해 제출을 검증에 사용한 상태·configuration version에 결속하는 규칙을 넣었다. `LIVE-10`·`SAFE-9`·`SAFE-3`·`P3-O17`
  본문을 함께 고쳤고 §7.2에 TOCTOU negative 사례를 추가했다. `AC14`로 기계 검사한다.
- 지적 1건에 대해 같은 부류 3건을 추가로 수정했다.
- 부작용 처리: 트리거 표가 §4.3에 추가되면서 `AC11` 파서가 두 표를 구분하지 못해 오탐했다. 이것도 부류(표 파싱 검사의
  범위 미지정)로 보고 `AC11`·`AC12`를 상태 표로 명시 한정한 뒤 변형본으로 탐지력을 재확인했다.

### Codex 외부 스펙 리뷰 11라운드와 부류 스윕 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `LIVE-10`은 근거 만료·불일치·candidate reject 때 `ACTIVATION_RECOVERY_ONLY`로 내리지만, 거기서 다시
  `ACTIVATION_IN_PROGRESS`로 돌아가는 전이가 없다. §10의 재승인 요구만으로는 구현이 기존 승인이나 단순 상태 변경으로
  주문을 재개하는 것을 막지 못한다.
- 부류 도출: "상태 전이의 회수 방향만 정의하고 복귀·해제 조건이 비어 있는 계약".
- 스윕 결과: 저하·종결 상태 어느 것도 복귀 조건이 없었다. `CANDIDATE_REJECTED`의 "새 candidate로 재시도 가능"이 유일한
  언급이었고 halt latch 해제 조건, 종결 상태의 되돌림 가능 여부도 정의되지 않았다.
- 반영: `LIVE-12`로 `RESUME`을 단일 정의했다. `FENCE-1`~`FENCE-3` terminal 확인과 전체 reconcile, 활성 트리거 전부 해소와
  원인 재평가, 무효화된 evidence·ACT gate·configuration snapshot 재승인, owner 명시 재개 승인, 새 authorization epoch
  발급을 모두 원자적 선행조건으로 요구하며 재시작이나 동시 재개 요청이 우회할 수 없다. 종결 상태는 `RESUME` 대상이
  아니며 §10의 프로그램 재개 승인을 따르고, `CANDIDATE_REJECTED` 이후 새 candidate는 `ACT-1`부터 다시 통과한다.
- 상태 표에 재개 조건 열을 신설하고 halt latch가 스스로 풀리지 않음을 명시했다. `AC15`로 기계 검사한다.
- 지적 1건에 대해 같은 부류 3건(halt latch 해제, 종결 되돌림, candidate 재시도 경로)을 함께 정의했다.

### Codex 외부 스펙 리뷰 12라운드와 부류 스윕 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `FENCE-2`/`FENCE-3`이 거래소 취소·조회 실패나 timeout으로 장시간 미완료일 수 있는데, 그때의
  `recovery-required`가 §4.2 상태축과 §4.3 권한표에 없다. 허용 제출, `FENCE` 재시도, 재시작 복원, 완료 후 목적 상태,
  `RESUME` 가능 시점이 모두 미정의라 구현이 재제출하거나 빠져나오지 못할 수 있다.
- 부류 도출: "문서가 상태처럼 부르지만 상태축에 등재되지 않은 비정형 상태".
- 스윕 결과: `recovery-required` 외에 `halt latch 활성`도 같은 부류였다.
- 반영: `ACTIVATION_FENCE_PENDING`을 activation 축에 등재하고, latch를 execution latch 축(`LATCH_ENGAGED`/`LATCH_CLEAR`)으로
  정식화했다. §4.3에 세 행을 추가하고 `FENCE` 미완료 구간의 durable 재시작 복원, idempotent 재시도, 새 경제적 제출 금지,
  완료 후 원인별 목적 상태 이동을 명시했다. "상태처럼 부르는 이름은 모두 §4.2에 등재한다"는 규칙도 넣었다.
- `AC16`을 신설해 비정형 상태 이름을 기계 검사한다. 검사가 잔여 사용처 2곳을 추가로 잡아 정리했다. 자동 수용기준 14개.

### Codex 외부 스펙 리뷰 13라운드와 부류 스윕 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 2. 두 지적 모두 12라운드에 추가한 상태가 만든 모순이다.
- 지적 1: 정상·긴급 중단의 전이 대상이 직교 축의 `LATCH_ENGAGED`뿐이라 `FENCE` 완료 후 activation 축을 어디로 둘지
  미정이다. 재시작이나 `RESUME` 때 어떤 권한을 적용할지 구현마다 달라진다.
  - 부류: "전이 대상이 한 축만 지정되고 다른 축이 미정인 계약". 스윕 결과 8개 트리거 전부가 한 축만 적고 있었다.
  - 반영: 전이 대상 열을 "진입 → `FENCE` 완료 후 / latch 축" 형식으로 통일하고, 목적 상태를 `FENCE` context에 durable
    저장해 재시작 후 복원하도록 규칙을 추가했다. `AC17`로 기계 검사한다.
- 지적 2: `ACTIVATION_FENCE_PENDING` 행이 `RECOVERY-A`를 허용하는데 여기에는 외부 제출인 unwind가 포함돼 `SAFE-6`의
  "새 경제적 제출 금지", `SAFE-10`의 `FENCE-3` 선행 요구와 충돌한다.
  - 반영: `LIVE-11`에 `RECOVERY-0`(FENCE 수행에 필요한 취소·조회만, unwind 금지, `FENCE-3` 전 복구 판단 금지)을 신설하고
    해당 행을 `RECOVERY-0` 전용으로 정정했다. `AC11` 정규식도 세 집합으로 확장했다.
- 자동 수용기준 15개, requirement ID 127개, dangling 0건.

### Codex 외부 스펙 리뷰 14라운드와 부류 스윕 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 2.
- 지적 1: 각 트리거가 `FENCE` context에 목적 상태를 저장한다고만 규정해, `ACTIVATION_FENCE_PENDING` 동안 다른 트리거가
  발생하면 먼저 저장된 목적지로 `FENCE`가 완료되어 뒤늦은 중단·종결 요구가 유실될 수 있다.
  - 반영: `FENCE` context를 단일 스냅샷이 아니라 누적되는 전이 요청으로 정의했다. 새 트리거는 목적 상태를 덮어쓰지 않고
    축별로 더 제한적인 값으로 원자 병합하며, latch 축은 `LATCH_ENGAGED`가 program 축은 종결 방향이 항상 우선한다.
    병합 결과는 durable하게 남아 재시작 후 복원되고, 모든 요청이 종결돼야 `FENCE`가 완료된다.
- 지적 2: execution latch 축의 초기값이 §4.2 초기값 선언에 없어 시작·재시작 직후 제출 권한을 안전하게 판정할 수 없다.
  - 부류 도출: "축을 추가하고 초기값 선언을 갱신하지 않는 계약". 스윕 결과 specification과 candidate/evidence 축도
    초기값이 없었다.
  - 반영: 7개 축 전부의 초기값을 명시하고 축 신설 시 초기값 정의를 의무화했다. latch 값이 없거나 손상된 재시작은
    `LATCH_ENGAGED` fail-closed로 처리한다. `progress.md` 현재값 표에도 latch 축을 추가했다. `AC18`로 기계 검사한다.
- 자동 수용기준 16개.

### Codex 외부 스펙 리뷰 15라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 0 · **medium 1**. 1라운드 이후 처음으로 high 지적이 없었다.
- 지적: 14라운드에 채운 candidate/evidence 초기값이 "해당 candidate 없음"이라는 서술형이라 §4.2에 등록된 상태가 아니다.
  첫 candidate를 `EVIDENCE_PENDING`으로 만드는 전이도 없고, `AC18`이 축 이름 존재만 검사해 이 불일치를 통과시킨다.
- 반영: `CANDIDATE_NOT_SELECTED`를 축에 등재하고 초기값을 그것으로 바꿨다. candidate 등록 시 `EVIDENCE_PENDING`,
  `CANDIDATE_REJECTED` 후 폐기 시 `CANDIDATE_NOT_SELECTED`로 돌아가는 전이와 재시작 복원을 명시했다. "초기값은 반드시
  그 축의 등록 상태여야 하며 서술형으로 대체하지 않는다"는 규칙도 추가했다.
- 검사 강화: `AC18`이 초기값의 존재뿐 아니라 그 값이 해당 축의 등록 상태인지까지 검증하도록 명령을 교체했다.
  리뷰어가 기계 검사의 강도를 직접 지적한 두 번째 사례다.
- `progress.md` 현재값 표도 `CANDIDATE_NOT_SELECTED`로 갱신했다.

### Codex 외부 스펙 리뷰 16라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: §4.2는 evidence·activation 축 전이를 사용자 승인으로 선언한다고 규정하는데, §4.3의 `SAFE-3`/`SAFE-7`/`SAFE-9`/
  `SAFE-10`·`LIVE-10` 트리거는 조건 탐지 즉시 원자적 전이를 요구한다. 이 구분이 없으면 구현이 상태 변경을 owner 승인이나
  gate 기록 뒤로 미뤄, 장애 감지와 제출 차단 사이에 신규 주문이 통과할 수 있다.
- 부류 도출: "승인 주체 규칙이 자동 안전 경로와 충돌하는 계약". 스윕 결과 문제는 §4.2의 전이 주체 문장 하나였고,
  `LIVE-12`의 `RESUME` 승인 요구와 §10의 재승인 목록은 모두 위험 증가 방향이라 충돌하지 않음을 확인했다.
- 반영: 전이 선언 주체를 방향으로 나눴다. 위험을 늘리는 방향(activation 상향, gate 통과, `RESUME`, evidence 승격)은
  사용자 승인으로만 선언하고, 위험을 줄이는 방향(권한 회수 트리거에 따른 `ACTIVATION_FENCE_PENDING`·
  `ACTIVATION_RECOVERY_ONLY`·`LATCH_ENGAGED`)은 runtime이 탐지 즉시 durable하게 수행하며 승인·gate 기록·알림을 기다리지
  않는다. owner는 `SAFE-8`의 사후 인지와 `RESUME`에서만 관여한다.
- §7.2에 "탐지와 전이 사이 승인 대기로 신규 제출이 통과하지 않는지" negative 사례를 추가하고 `AC19`로 기계 검사한다.
- 자동 수용기준 17개.

### Codex 외부 스펙 리뷰 17라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1 · medium 1. 두 지적 모두 14·16라운드 수정이 남긴 해석 여지였다.
- 지적 1 (high): `FENCE` context가 "모든 요청이 종결돼야 완료"라고만 정의돼, 종결 기준이 `FENCE-1`~`FENCE-3` terminal
  결과인지 원인 트리거 해소인지 불명확했다. 후자로 구현하면 데이터 불신·margin 침범·drift처럼 노출 정리 전까지 지속되는
  원인에서 `RECOVERY-0`만 허용되는 `ACTIVATION_FENCE_PENDING`에 갇혀 unwind로 노출을 줄일 수 없다.
  - 반영: 완료 기준을 `FENCE-1`~`FENCE-3`의 terminal 결과로만 못박고, 원인이 지속돼도 목적 상태로 전이하도록 했다.
    원인 해소는 `LIVE-12` `RESUME`의 선행조건으로만 남긴다. §7.2에 해당 negative 사례를 추가했다.
- 지적 2 (medium): §4.2가 위험 감소 전이에서 "owner는 사후 인지와 `RESUME`에서만 관여"한다고 했는데, §9.4와 트리거 표,
  plan의 Z2는 owner의 종결 결정을 진입 원인으로 둔다.
  - 반영: owner는 정상 중단·NO_GO 같은 **외부 트리거를 발행**하고, 전이 실행은 이를 수신한 runtime이 즉시 durable하게
    수행한다는 역할 분리를 명시했다. plan의 Z2 행도 "owner의 종결 트리거 발행, 전이 실행은 runtime"으로 맞췄다.
- `AC20`으로 완료 기준과 역할 분리를 기계 검사한다. 자동 수용기준 18개.

### Codex 외부 스펙 리뷰 18라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1. 도메인 관점의 지적이었다.
- 지적: `RECOVERY-A`는 실제 fill마다 gross·net exposure와 residual delta가 모두 증가하지 않을 것을 요구한다. 그러나
  서로 다른 거래소의 spot long과 perpetual short를 청산할 때 원자적 동시 체결은 불가능하므로 어느 leg가 먼저 체결돼도
  residual delta가 일시적으로 증가한다. 따라서 완전히 헤지된 포지션은 `RECOVERY-A`로 첫 청산을 시작할 수 없고,
  `RECOVERY-B`는 이미 한쪽이 평탄화된 뒤의 hedge만 다루며 margin·drift 중에는 금지된다. 17라운드에서 확정한 "FENCE 후
  노출을 줄인다"는 목표와 정면으로 충돌한다.
- 반영: `RECOVERY-C`(bounded paired reduction)를 `LIVE-11`에 신설했다. 헤지된 pair의 순차 청산에서 먼저 체결된 leg로
  인한 일시적 residual·gross 증가를 사전 승인된 worst-case 한도와 budget 예약 안에서 허용하되 완료 시 전체 위험 감소를
  검증하며, 어느 leg가 먼저 체결되거나 부분 체결·재시작이 겹쳐도 종료할 수 있어야 한다. `RECOVERY-A`는 단일 leg의
  비증가 제출로 범위를 좁혔다.
- `SAFE-7`이 breach 대응으로 요구하던 "bounded paired reduction"을 `RECOVERY-C`로 용어 통일했고, §4.3의 모든 복구 열과
  `P3-O6`에 `RECOVERY-C`를 상시 허용으로 반영했다. §7.2에 leg 체결 순서·부분 체결·재시작 조합의 청산 완료 검증을 넣었다.
- `AC11` 정규식을 네 집합으로 확장했다.

### Codex 외부 스펙 리뷰 19라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: 18라운드에 신설한 `RECOVERY-C`가 `P3-O14`와 §9.4에는 전파되지 않아, 완전히 헤지된 pair가 남은 채 만료·종결되면
  outcome 문구를 따르면 청산을 시작할 수 없고 상태 표를 따르면 가능해지는 모순이 생겼다. `AC11`은 상태 표만 검사하므로
  이 회귀를 잡지 못한다.
- 부류 도출: "단일 정의 집합을 여러 곳에서 열거하다 새 집합 전파를 빠뜨리는 계약".
- 스윕 결과: 지적된 두 곳 외에 `LIVE-11`의 `RECOVERY-B` 차단 조건 문장도 `RECOVERY-C`를 빠뜨려, `SAFE-7` breach 대응이
  곧 `RECOVERY-C`라는 18라운드 정의와 모순이었다.
- 반영: 세 곳을 모두 고치되 열거를 반복하지 않도록 §4.3과 `LIVE-11` 단일 정의를 참조하는 형태로 바꿨다. `AC21`을 신설해
  `RECOVERY-A`·`RECOVERY-B`만 열거하고 `RECOVERY-C`를 빠뜨린 줄을 기계적으로 탐지한다.
- 자동 수용기준 19개.

### Codex 외부 스펙 리뷰 20라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `RECOVERY-B`는 headroom·stress 침범 시 차단되는데 `RECOVERY-C`는 상시 허용이었다. `RECOVERY-C`는 비원자적 청산
  때문에 gross·residual의 일시 증가를 허용하고 사전 승인된 정적 한도와 budget 예약만 요구하므로, 이미 breach한 시점에는
  현재 margin이 그 worst-case 체결 경로를 견디지 못해 강제 청산을 촉발할 수 있다. breach 대응 경로가 breach를 악화시킨다.
- 부류 도출: "정적 사전 승인 한도만 검사하고 현재 상태 기준 실현 가능성을 검사하지 않는 계약".
- 반영: `RECOVERY-C`를 제출 직전 현재 account·margin 상태 기준의 leg별 worst-case 실현 가능성 검사에 결속했다. 현재
  headroom과 risk budget을 지키지 못하면 `RECOVERY-C`도 금지하고 노출 비증가 `RECOVERY-A` 또는 owner fallback만 허용한다.
  §4.3의 다섯 행, `P3-O6`, `LIVE-11` 차단 조건을 같은 표현으로 정렬하고 §7.2에 거부·분기 시나리오를 추가했다.
- `AC22`로 동적 검사 결속과 fallback 분기를 기계 검사한다. 자동 수용기준 20개.

### Codex 외부 스펙 리뷰 21라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `SAFE-3`은 데이터·account·order·position 상태 불신을 fail-closed 트리거로 두지만, 그 결과 상태인
  `ACTIVATION_RECOVERY_ONLY`의 복구 열은 여전히 현재 headroom 검사만으로 `RECOVERY-C`를 허용한다. 검사의 입력이 바로 그
  신뢰할 수 없는 상태이므로, stale한 margin·노출 관점으로 순차 청산의 한 leg를 제출해 잔여 노출을 오히려 키울 수 있다.
- 부류 도출: "검사를 요구하면서 그 검사 입력의 신뢰를 전제하지 않는 계약". 스윕 결과 `RECOVERY-B`의 미헤지 수량 상한과
  `RECOVERY-A`의 unwind 판단도 같은 입력에 의존함을 확인했다.
- 반영: `LIVE-11`에 입력 신뢰 전제를 도입했다. 안전 판단이 시장·account·order·position 상태에 의존하는 집합
  (`RECOVERY-A`의 unwind, `RECOVERY-B`, `RECOVERY-C`)은 입력이 신뢰 가능하고 최신일 때만 허용하며, `SAFE-3`이 활성이거나
  필요한 reconcile이 끝나지 않았으면 취소(`RECOVERY-0`과 `RECOVERY-A`의 cancel)와 owner fallback만 남는다. §4.3에도
  "표 값과 무관하게 입력 신뢰 전제가 우선한다"를 명시하고 §7.2에 stale 상태의 청산 거부 사례를 추가했다.
- `AC23`으로 기계 검사한다. 자동 수용기준 21개.

### Codex 외부 스펙 리뷰 22라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 2.
- 지적 1: `FENCE-2`는 exposure-increasing 주문만 취소하고 `FENCE-3`은 거래소 주문 ID가 확정되지 않은 제출만 다룬다.
  따라서 이미 ID가 확정된 unwind·hedge working 주문은 `FENCE` 완료 전에 취소도 terminal 확인도 요구되지 않아,
  `ACTIVATION_RECOVERY_ONLY` 전이 후 새 복구 결정과 경합해 leg를 과다 청산하거나 의도치 않은 residual을 만들 수 있다.
  - 반영: `FENCE-2` 범위를 복구 노출에 영향을 줄 수 있는 모든 working 주문으로 넓히고, 취소 대신 유지하려면 하나의
    durable 복구 작업으로 예약·직렬화해 경합하지 않음을 보장하도록 했다.
- 지적 2: `owner fallback`이 문서에서 아홉 번 참조되는데 정의된 적이 없다. 상태 불신 구간에서 허용되는 유일한 대안인데도
  권한 범위, 거래소 직접 확인, 미해결 주문과의 상호작용, 자동 복구 재개 전 reconcile 장벽이 정의되지 않았다.
  - 부류 도출: "여러 번 참조되지만 정의되지 않은 절차·용어". 스윕에서 `DONE-5`의 `operator fallback`과 `SAFE-6`의
    "수동 거래소 fallback"이 같은 대상을 다른 이름으로 부르고 있었음을 함께 확인했다.
  - 반영: `LIVE-13`으로 owner fallback을 fenced 비상 절차로 정의했다. 노출 감소·취소로 범위를 한정하고, 시작 전 알려진
    outstanding 주문의 취소·terminal 확인과 자동 제출 경로 잠금, 수동 체결의 durable 기록과 거래소 직접 대조, 그 reconcile과
    새 authorization epoch 전 자동 복구·`RESUME` 금지, 뒤늦은 체결의 중복·상충 탐지를 요구한다. 세 표현을 한 용어로 통일했다.
- `AC24`로 `FENCE-2` 범위와 fallback 정의·용어를 기계 검사한다. 자동 수용기준 22개, requirement ID 128개.

### Codex 외부 스펙 리뷰 23라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `LIVE-13`은 수동 체결·취소의 durable 기록과 거래소 대조를 요구하지만, 각 조치와 그 결과를 fallback 이전의 특정
  전략 노출에 연결하거나 외부 항목으로 분류하는 계약이 없다. `SAFE-5`는 동시에 수동 거래의 자동 흡수를 금지하므로,
  구현은 수동 hedge를 전략 ledger에 흡수해 `SAFE-5`를 어기거나, 기존 전략 노출을 미설명 drift로 남겨 reconcile·`RESUME`을
  모호하게 만들 수밖에 없다. fallback이 `SAFE-3` 활성 중 유일한 경로라 영향이 크다.
- 부류 도출: "외부·수동 기원 변화의 귀속 경계가 없는 계약". 스윕 결과 거래소 강제 감축·liquidation·ADL(`SAFE-7`)도
  "조용히 흡수하지 않는다"고만 하고 귀속 절차가 없었다.
- 반영: `SAFE-5`를 귀속 절차의 단일 정의로 확장했다. 외부·수동 기원 변화는 owner 확인 매핑이 있을 때만 특정 전략 노출에
  귀속하고 매핑은 durable하게 기록하며, 매핑되지 않은 것은 `unmanaged`로 분류해 전략 ledger 밖에 둔다. reconcile은 매핑된
  노출만 종결시키고, `unmanaged`가 남아 있으면 별도 해소나 새 승인 baseline 전까지 `RESUME`을 차단한다.
- `LIVE-13`과 `SAFE-7`이 이 절차를 참조하고 `LIVE-12`의 `RESUME` 선행조건에 `unmanaged` 부재를 추가했다. `AC25`로 기계 검사.

### Codex 외부 스펙 리뷰 24라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `SAFE-5`의 `unmanaged`는 `RESUME`만 차단하고, `SAFE-4`의 statement·ledger 차이는 탐지만 요구하며, §4.3의 폐쇄된
  트리거 목록에도 둘 다 없었다. 따라서 활성 중 수동 거래나 자금 이동으로 미귀속 항목이 생겨도 자동 제출 권한이 유지되고,
  `DONE-3`의 단순 대조와 `DONE-4`의 residual 부재만으로 mismatch·unmanaged를 남긴 채 완료를 선언할 수 있었다.
  `SAFE-8`이 reconcile mismatch를 안전 중요 전이로 다루는 것과도 충돌한다.
- 부류 도출: "탐지만 요구하고 fail-closed 전이나 완료 조건을 요구하지 않는 계약".
- 반영: `SAFE-4`를 지속·주기 탐지 + 권한 회수 트리거로 승격하고, 미귀속(`unmanaged`) 발견과 reconcile mismatch를 §4.3
  트리거 표의 9번째 행으로 추가했다. `DONE-3`에 mismatch 잔존 금지, `DONE-4`에 `unmanaged` 해소 또는 승인 baseline 종결,
  `NOGO-2`에 같은 조건을 넣었다. §7.2에 활성 중 미귀속 발생 시 자동 제출 즉시 차단 사례를 추가했다.
- `AC13` 필수 출처를 9개로 확장하고 `AC26`을 신설했다. 자동 수용기준 24개.

### Codex 외부 스펙 리뷰 25라운드와 반영 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `LIVE-13`은 fallback 시작 전 자동 제출 경로를 잠근다고만 하고 그것을 `FENCE`·epoch 무효화·
  `ACTIVATION_FENCE_PENDING`으로 수행한다고 정의하지 않았다. 반면 §4.3은 권한 회수 트리거가 모두 표에 있고 `FENCE`를
  동반한다고 선언하는데 9개 행에 `LIVE-13`이 없었다. 정상 activation 중 fallback을 시작하면 미전송·응답 불명 intent가
  종결되지 않은 채 수동 취소·청산과 자동 실행이 경합할 수 있다.
- 부류 점검: 최근 신설한 계약 중 권한에 영향을 주는 것을 전수 확인했다. `LIVE-11`·`LIVE-12`는 집합·재개 정의라 트리거가
  아니고 `SAFE-11`은 제출 순서 계약이라 회수가 아니다. 누락은 `LIVE-13` 하나였다.
- 반영: fallback 개시를 10번째 트리거로 등재했다. 전이 대상은 `ACTIVATION_FENCE_PENDING` → `ACTIVATION_RECOVERY_ONLY` /
  `LATCH_ENGAGED`이고 탐지 경로는 owner 트리거 수신 즉시와 재시작 시 진행 상태 복원이다. `LIVE-13` 본문에 epoch 무효화와
  `FENCE-1`~`FENCE-3` 완료를 수동 조치 시작의 선행조건으로 명시했다.
- `AC13` 필수 출처를 10개로 확장했다. 자동 수용기준 24개 유지.

### Codex 외부 스펙 리뷰 26라운드와 부류 스윕 — 2026-07-30

- 판정 `needs-attention`, critical 0 · high 1.
- 지적: `LIVE-13`은 fallback 시작 전 `FENCE` 완료와 수동 결과 기록·reconcile을 요구하지만, fallback이 언제 완료돼
  §4.3의 활성 트리거에서 해제되는지가 없다. `RESUME`은 모든 활성 트리거 해소를 요구하므로 구현은 영구 halt에 빠지거나
  `FENCE` 완료만으로 해제해 귀속·전체 reconcile 전에 재개할 수 있다.
- 부류 도출: "트리거 진입 조건만 정의하고 해제 조건이 없는 계약". 스윕 결과 트리거 표 10개 행 **전부** 해제 조건이 없었다.
- 반영: 트리거 표에 해제 조건 열을 신설하고 10개 행을 모두 채웠다. 중단은 owner 재개 트리거와 `RESUME` 완료, 만료·reject는
  새 evidence·candidate 승인과 `RESUME`, 종결은 해제하지 않음(§10 대상), margin은 headroom 회복과 reconcile, 자본 부족은
  가용 자본 확인, 상태 불신은 freshness 회복과 reconcile, 응답 불명은 `FENCE-3` terminal과 중복 해소, drift는 snapshot
  재승인 또는 원복, 미귀속·mismatch는 `SAFE-5` 매핑 완료 또는 baseline, fallback은 lifecycle 종료다.
- fallback은 `FALLBACK_STARTED` → `FALLBACK_ACTING` → `FALLBACK_RECONCILING` → `FALLBACK_CLOSED`의 durable lifecycle로
  정의하고 각 단계를 재시작 후 복원하며 종료 단계에서만 트리거를 해제한다. `AC27`로 기계 검사한다. 자동 수용기준 25개.

## Phase -1 기준선 — 2026-07-20

### 격리

주 작업트리에는 아래 사용자 소유 변경이 있었으며 feature worktree로 가져오거나 수정하지 않았다.

```text
 M .gitignore
 M deploy/nginx.ssl.conf
 M deploy/setup-server.sh
?? .ai/planning/private-live-autotrader/
?? .claude/worktrees/
?? .oci-launch.env
```

project-local `.worktrees/`는 ignore 대상이 아니고 `.gitignore`가 사용자 변경 중이므로 외부 linked worktree를 사용했다.

### 변경 전 검증

| 명령 | 결과 | 구분 |
|---|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | exit 0, `BUILD SUCCESSFUL in 5m 15s`, 67 tasks | 기준선 GREEN |
| `bash ci/quality-gate-contract-test.sh && bash ci/dependency-bootstrap-contract-test.sh && bash docker/deploy-contract-test.sh && bash docs/check-documentation.sh` | exit 0 | 기존 계약 GREEN. Phase -1과 반대인 legacy 계약을 검증하므로 새 contract RED/GREEN과 구분 |
| `npm --prefix apps/web ci --include=optional` | exit 0, 706 packages, audit moderate 2건 | install 기준선 GREEN; Phase -1 dependency 변경 없음 |
| `npm --prefix apps/web run lint` | exit 0 | 기준선 GREEN |
| `npm --prefix apps/web run build` | exit 1: Node 18.20.8, Next.js 16은 Node 20.9+ 요구 | 환경 BLOCKED; 소스 compile 이전 실패 |

Node 20을 사용하는 Draft PR의 기존 Quality Gate가 Phase acceptance의 Web/Docker build 증거를 제공한다. 로컬 Node runtime을
이 Phase에서 다운로드하거나 repository scope로 추가하지 않는다.

### CI·운영 책임 경계

- GitHub Actions에는 exchange credential을 제공하지 않는다.
- 기존 GitHub-hosted `Quality Gate`만 유지한다.
- 장기 soak는 production credential이 없는 validation host에서 operator가 실행한다.
- migration, 배포, canary와 activation은 PRIVATE_LIVE host에서 operator가 host-local 명령으로 실행한다.
- runtime service account는 operator-owned 명령 파일과 배포 bundle을 수정할 수 없어야 한다.
- Phase -1은 runner, validation host, PRIVATE_LIVE host 또는 실제 secret을 구성하지 않는다.

## Phase acceptance 기록

Phase -1 구현·review·검증 결과와 acceptance commit/push는 이 아래에 append한다.

### Task 1~3 direct/review 결과 — 2026-07-20

- Task 1 direct verification: PASS — `bash ci/dependency-bootstrap-contract-test.sh`, exit 0,
  `dependency bootstrap behavioral contracts verified`
- Task 1 Spec Review: PASS — open finding 0
- Task 1 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 2 direct verification: PASS — `bash docker/deploy-contract-test.sh`, exit 0, `deploy contracts verified`
- Task 2 Spec Review: PASS — open finding 0
- Task 2 Code Review: PASS — HIGH/MEDIUM 0, LOW test gap 3건은 비차단
- Task 3 direct verification: PASS — `bash ci/quality-gate-contract-test.sh`, exit 0,
  `quality gate and supply-chain contracts verified`
- Task 3 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 3 Code Review: PASS — HIGH/MEDIUM/LOW 0, review mutation 11개를 모두 fail-closed로 탐지

### 실제 운영 상태 — 2026-07-20

- 실제 host/activation: NOT_RUN
- Phase -1 범위에서는 실제 host, secret manager, runner 또는 activation을 구성하지 않는다.

### Task 4 direct/review 결과 — 2026-07-20

- Task 4 direct verification: PASS — documentation check, 확장 AC4 semantic contract와 `git diff --check`, 모두 exit 0
- Task 4 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 4 Code Review: PASS — 초기 MEDIUM 3건과 후속 migration 순서 MEDIUM 1건을 수정한 뒤 HIGH/MEDIUM/LOW 0
- 실제 host/activation: NOT_RUN

### Phase -1 로컬 acceptance — 2026-07-20

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 1m 13s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; audit moderate 2건은 기존 기준선과 동일 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| Quality/dependency/deploy/documentation contracts + shell syntax + `git diff --check` | PASS, exit 0 |

Web build는 green으로 기록하지 않는다. Phase acceptance commit을 push한 뒤 Node 20을 사용하는 Draft PR `#63`의 7개
Quality Gate job 결과로 판정한다.

### 동결 변경 재승인 — 2026-07-21

- Holistic Spec Review: 동결 AC4의 5→8문서 범위 확장에 대한 사용자 명시적 재승인 전까지 FAIL
- Holistic Code Review: PR artifact 오승격 가능성 MEDIUM 1건을 merged `dev`의 `event=push`, `branch=dev` 경계로 수정
- Holistic Code Re-review: PASS — HIGH/MEDIUM 0
- 사용자 재승인: `승인`

### 재승인 후 acceptance 재검증 — 2026-07-21

| 명령 | 결과 |
|---|---|
| `./gradlew compileKotlin test architectureTest --offline --no-daemon` | PASS, exit 0, `BUILD SUCCESSFUL in 24s`, 67 tasks |
| `npm --prefix apps/web ci --include=optional` | PASS, exit 0, 706 packages; 현재 advisory snapshot 1 low·2 moderate, lockfile 변경 없음 |
| `npm --prefix apps/web run lint` | PASS, exit 0 |
| `npm --prefix apps/web run build` | 환경 BLOCKED, exit 1; Node 18.20.8은 Next.js 요구 `>=20.9.0` 미충족 |
| AC1~AC4 contract, 전체 shell syntax와 `git diff --check` | PASS, exit 0 |

- Final Spec/DoD Re-review: PASS — HIGH/MEDIUM/LOW 0
- Final Code Re-review: PASS — HIGH/MEDIUM 0, 기존 README의 개발용 `--build` 안내 LOW는 Phase 0 후속
- PR #63 Node 20 Quality Gate: acceptance commit push 전이므로 PENDING

### 원격 acceptance RED와 Task 5 remediation — 2026-07-21

- acceptance commit: `c6fec014da4afeb5e356da9d89e6607e0d20db0c`, push 완료
- PR run: `29789180354`, Compile + architecture job `88507065048`
- 원격 결과: FAIL — repository contract step에서 `rg: command not found`, 이후 6개 job은 dependency 때문에 SKIPPED
- root cause: GitHub-hosted runner가 제공하지 않는 ripgrep을 contract script 두 곳이 암묵적으로 요구
- 수정: runner package/Action을 추가하지 않고 `ci/quality-gate-contract-test.sh`, `docs/check-documentation.sh`를 기본
  `grep`/`find`로 의미 보존 이식
- Task 5 direct verification: PASS — shadow `rg=127`과 일반 환경에서 quality/docs/dependency/deploy contract, syntax,
  `git diff --check` 모두 exit 0
- Task 5 Spec Review: PASS — HIGH/MEDIUM/LOW 0
- Task 5 Code Review: PASS — HIGH/MEDIUM/LOW 0
- 새 PR Quality Gate: remediation commit push 전이므로 PENDING

### Phase -1 원격 acceptance GREEN — 2026-07-21

- remediation commit: `f40d4b2ba66d64904158eaabbfa99450e3052339`
- PR run: `29790214157`
- 1. Compile + architecture: SUCCESS
- 2. Unit + coverage: SUCCESS
- 3. API integration: SUCCESS
- 4. Batch integration: SUCCESS
- 5. ktlint + detekt: SUCCESS
- 6. Dependency + security scan: SUCCESS
- 7. Docker image build: SUCCESS
- 결과: `7/7 SUCCESS`, Phase -1 DoD `VERIFIED`
- 실제 host/activation: NOT_RUN
