# Premium Spread PRIVATE LIVE Program Specification

> 이 문서는 상세 구현 계획이 아니라 제품·아키텍처·전달 단계의 상위 specification이다.
> 각 Phase의 구현 방식, 파일, API, migration, 테스트 명령과 PR 분할은 해당 Phase를 시작할 때 `work`로 결정한다.

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ④ 스펙 문서 (program altitude) |
| slug | `private-live-autotrader` |
| 상위 plan | [`plan.md`](plan.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| 진행·acceptance 증거 | [`.ai/planning/private-live-autotrader/progress.md`](../../../.ai/planning/private-live-autotrader/progress.md) |

이 문서는 프로그램 전체의 spec이며 하나의 MR 단위가 아니다. 각 Phase는 별도의 `feature-workflow` 실행 단위로 소비되고
자신의 `docs/work/{phase-slug}/design.md`, `plan.md`, `dod.md`를 갖는다. 그 매핑은 [`plan.md`](plan.md)가 소유한다.

## 0. 문서 계약

### 0.1 상태

- 문서 상태: `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL`
- 반영 회차: Review C 반영본 (blocker 3, major 7, minor 7) + Codex 외부 리뷰 1~4라운드 반영 (8 → 2 → 2 → 1건)
- 기준 branch: `dev`
- 완료 기준선: PR #63 merge commit `15cc02f820ed688dae5ef7b38ce50245f2cb1566`
- 다음 specification 상태: `MASTER_SPEC_APPROVED`

PR #63에서 수행한 Phase -1은 완료된 역사다. 이 문서의 남은 작업과 Phase에는 포함하지 않는다.

§4의 상태축(specification, software, evidence collection/candidate, activation, program) **현재값은
`progress.md`가 단독으로 소유한다.** 이 문서는 상태의 정의와 전이 조건만 고정하고 현재값을 중복 기록하지 않는다.

### 0.2 이 문서가 소유하는 것

이 문서는 다음만 고정한다.

- 제품 목표와 비범위
- 사용자에게 제공할 보장과 안전 불변식
- 시스템의 책임 경계와 데이터 흐름
- Phase별 목표, 의존성, 진입·종료 조건
- software completion과 실제 activation의 구분
- 프로그램 최종 완료 및 안전한 중단 조건

### 0.3 이 문서가 소유하지 않는 것

다음은 이 문서에서 미리 결정하지 않는다.

- 클래스, 필드, package, endpoint와 table 이름
- migration 번호와 저장 payload 형식
- queue, pool, timeout, TTL과 성능 수치
- 데이터 provider, library와 저장 제품의 선택
- 운영 script와 명령 형식
- 하나의 Phase를 구성할 PR 개수와 순서
- 테스트 함수와 acceptance 명령

거래 venue와 instrument는 위 항목에 포함되지 않는다. §1.2의 거래소·상품 조합은 제품 범위이며 §10의 재승인 대상이다.
같은 venue를 관측하는 데이터 provider, 접속 방식과 library 선택만 Phase가 소유한다.

위 항목은 첫 실제 consumer가 생기는 Phase의 `work` 설계와 동결된 DoD가 소유한다. 구현 세부가 바뀌었다는 이유만으로
master specification을 변경하지 않는다.

### 0.4 정본과 입력의 우선순위

1. 사용자 승인과 이 specification의 제품·안전 불변식
2. `.ai/context/project-overview.md`와 `.ai/architecture/ARCHITECTURE_DESIGN.md`
3. `.ai/rules/architecture.md`, `.ai/rules/batch.md`, `.ai/rules/testing.md`
4. Phase별 승인된 design, DoD, implementation plan
5. 외부 리서치와 PoC

외부 리서치는 문제와 선택지를 발견하기 위한 입력이다. 리서치가 제안한 Phase 순서, 공수, workflow와 범위는 이
specification을 자동으로 구속하지 않는다. 합성 데이터, 근사 계산, 미컴파일 stub과 과거 API·법률 조사는 수익성 또는
LIVE 활성화의 증거가 아니다.

이 문서의 `ARCH-*`와 `STORE-*`는 repository 아키텍처 규칙을 구체화하지만 조용히 우선하거나 대체하지 않는다. 둘이
충돌하면 해당 Phase 구현 전에 충돌을 해소하고 필요한 정본 변경을 함께 승인받는다.

## 1. 제품 Specification

### 1.1 제품 목표

Premium Spread는 한 명의 소유자가 자기 자금과 자기 거래소 계정으로 운영하는 PRIVATE LIVE 김치 프리미엄
자동매매 도구를 목표로 한다.

이 프로그램에서 mode는 다음 의미로 사용한다.

- `SIMULATION`: versioned historical/replay input으로 전략과 경제 결과를 재현하며 실시간 계정이나 주문을 사용하지 않는다.
- `PAPER`: 실시간 시장 입력에 모의 체결 정책을 적용하며 실제 계정이나 주문을 사용하지 않는다.
- `SHADOW`: 실시간 시장과 read-only 계정 상태로 decision과 intent를 만들지만 주문 제출은 구조적으로 비활성화한다.
- `PRIVATE LIVE`: 승인된 범위 안에서 실제 private 계정과 주문을 사용하는 실행이다.

SIMULATION, PAPER와 SHADOW는 별도 최종 제품이 아니라 다음을 증명하기 위한 필수 승격 단계다.

- 전략의 경제 계산이 총손익이 아닌 비용 반영 순손익을 설명한다.
- 같은 전략과 경제 규칙이 과거 replay와 실시간 모의 실행에서 일관되게 동작한다.
- 부분 체결, 실패, 재시작과 데이터 이상이 노출 확대나 중복 실행으로 이어지지 않는다.
- 실제 주문 기능이 활성화되기 전에 전략·데이터·계정·운영 위험을 관찰할 수 있다.

### 1.2 V1 사용자와 거래 범위

- 사용자: 사전에 결합된 한 명의 private owner
- 계정: owner가 직접 소유한 한국 거래소 계정과 해외 거래소 계정 각 하나
- 시장: BTC 단일 market pair
- 한국 leg: Bithumb KRW spot
- 해외 leg: Binance USDT 선형 perpetual
- 방향: 낮은 executable premium에서 후보 진입, 높은 executable premium에서 후보 청산
- 포지션 의도: 한국 spot long과 해외 perpetual short의 delta-hedged pair
- 파생상품 위험 자세: 포지션별 margin 위험을 격리하고 단일 방향 position 귀속을 모호하지 않게 유지
- 자금 운용: 거래소 간 자동 입출금·송금·wallet transfer 없음
- 자본 전제: 양 거래소에 사전 배치된 자본으로만 실행하며 leg 간 자본 이동은 owner의 수동 절차다

두 leg의 손익은 서로 다른 통화와 계정에서 반대 부호로 실현되므로 cycle을 반복하면 한쪽 자본이 소진된다. 이 구조적
제약은 단순 비용 항목이 아니라 실행 가능성의 전제이며 `ECO-5`가 소유한다.

위 범위를 바꾸는 것은 구현 선택이 아니라 제품 범위 변경이므로 사용자 재승인을 요구한다.

### 1.3 비범위

V1은 다음을 제공하지 않는다.

- SaaS, 타인 자금, 다중 tenant, 자동매매 권한의 공개 가입
- 수익률 보장 또는 자동 승격되는 수익성 판정
- BTC 이외 종목과 추가 거래소
- 자동 입출금·송금·리밸런싱
- 다중 operator 승인, 조직형 RBAC, 고객별 credential
- 고가용성 cluster, multi-region, 무중단 거래 보장
- HSM/KMS, WORM, 별도 감사 서명과 custom hash chain
- 자체 CI runner와 credential을 가진 배포 workflow

SaaS 전환은 이 프로그램의 후속 Phase가 아니라 별도의 제품·규제·보안 프로그램으로 다시 설계한다.

## 2. 사용자에게 제공할 보장

### 2.1 의미와 표현

- `SEM-1` 기존 Position 기능은 실제 주문을 생성하지 않는 수동 추적 기능으로 일관되게 표현한다.
- `SEM-2` 추적 record와 자동매매 execution record를 같은 개념으로 재사용하지 않는다.
- `SEM-3` 화면과 문서는 진입·청산 방향을 서로 반대로 설명하지 않는다.
- `SEM-4` 기존 Position 손익은 현재 시점의 gross snapshot임을 드러내며 계정 ROI나 실제 체결 손익으로 표시하지 않는다.
  leverage와 notional 표기도 투입 자본 대비 수익률로 오인되지 않게 한다.
- `SEM-5` PAPER와 SHADOW는 실제 주문을 만들지 않는다는 사실을 사용자가 오인할 수 없게 한다.

### 2.2 경제 계산

- `ECO-1` 공통 경제 엔진은 두 leg의 가격·수량, 양 거래소 수수료, spread·slippage, funding settlement, FX·USDT
  basis, 투입 자본·margin, 잔여 delta·hedge 오차를 분리해 설명한다. 최초 자본 배치와 수동 재배치에 수반되는 환전·이체·
  유휴 자본 영향도 자동 transfer가 없다는 이유로 비용 0으로 간주하지 않는다.
- `ECO-2` 계산은 mode마다 별도 구현하지 않는다. 동일한 경제 input은 SIMULATION과 PAPER에서 동일한 경제 output을 만들며,
  차이는 선언된 execution adapter 정책으로만 설명한다. 실제 거래소 제출·응답·reconcile 상태는 경제 계산과 분리한다.
- `ECO-3` 표시되는 모든 지표는 단위, 부호, 평가 시점, 분모와 비용 포함 범위를 추적할 수 있어야 한다. 가격·수량·수수료·
  손익의 단위, 스케일과 반올림 규칙은 공통 경제 엔진이 단일 정의하며 저장·전송·표시 계층이 이를 임의로 축소하거나
  다시 정의하지 않는다.
- `ECO-4` candidate 평가는 현재 적용 가능한 venue fee와 자본 조건을 입력으로 삼고, 불리한 fee·funding·slippage·basis
  조건에서도 결과를 검토한다. 필요한 비용 입력이 불명확하면 viability를 승인하지 않는다.
- `ECO-5` 자동 transfer가 없는 자본 구조를 실행 가능성 입력으로 다룬다. 최소한 재배치 없이 수행 가능한 연속 cycle 수,
  자본 소진까지의 경로, 수동 재배치의 lead time과 그동안의 실행 중단, 재배치 실패 시 거동을 산출하며 이 값들은
  `ACT-1` viability 판단의 필수 입력이다.

### 2.3 시장 데이터와 재현성

- `DATA-1` 전략 판단과 검증에 사용되는 데이터는 provider와 instrument provenance를 보존한다.
- `DATA-2` 시장 관측 시점과 시스템 수신 시점을 구분한다.
- `DATA-3` 당시 실제 거래 가능한 가격·수량을 표현한 수준과 한계를 드러낸다. 각 leg의 가격 의미(체결가, 호가, 중간값)와
  수량 관측 수준을 명시하며 서로 다른 의미의 값을 동일한 executable 가격으로 취급하지 않는다.
- `DATA-4` 누락, 지연, 역순, 중복과 clock 이상을 품질 상태로 드러낸다.
- `DATA-5` normalized dataset이 어떤 원본과 가정으로 만들어졌는지 추적할 수 있다.
- `DATA-6` 동일한 versioned input과 정책은 동일한 경제 결과를 재현한다. 합성·대체·backfill 데이터는 실제 수집 데이터와
  구분되며, 엔진 검증에는 사용할 수 있지만 전략 viability를 자동으로 증명하지 않는다.

### 2.4 전략 승격

- `PROM-1` 전략 candidate는 전략 규칙·parameter, 경제·비용·risk 정책, dataset·데이터 품질, engine·실행 의미와 검토
  결과를 하나의 승인 대상으로 결합한다.
- `PROM-2` Backtest 결과만으로 PAPER, SHADOW 또는 PRIVATE LIVE에 자동 승격하지 않는다.
- `PROM-3` holdout 결과를 본 뒤 기준을 유리하게 바꾸는 것을 방지하기 위해 viability policy를 평가 전에 승인한다.
- `PROM-4` holdout을 반복 소비해 사실상 학습 데이터로 만드는 것을 금지한다. candidate family와 parameter 범위는 holdout
  공개 전에 동결하며, 결과를 본 뒤의 수정본은 새 untouched 또는 forward 증거를 요구하거나 사전 승인된 multiple-testing
  정책 아래에서만 평가한다.

### 2.5 실행 안전

- `SAFE-1` 각 경제적 실행 의도는 안정적으로 식별하며 시스템 스스로 유발하는 중복 제출을 방지한다. 실행 권한은 그
  시점의 authorization epoch에 결합하고, epoch이 바뀌거나 중단이 선언되면 아직 전송되지 않은 intent는 무효가 되어
  뒤늦게 실행될 수 없다. 이 계약은 실행 mode와 무관하며 모의 체결에서도 관찰된다.
- `SAFE-2` 양 leg의 부분 체결과 실패는 잔여 exposure로 관찰되고 승인된 복구 정책 밖에서 자동 확대되지 않는다.
- `SAFE-3` 데이터, account, order 또는 position 상태를 신뢰할 수 없으면 신규 exposure를 fail-closed한다.
- `SAFE-4` 실제 fee와 funding을 포함한 거래소 statement와 내부 ledger의 차이를 탐지한다.
- `SAFE-5` 수동 거래, 자금 변화와 전략 외 position을 전략 소유분으로 자동 흡수하지 않는다.
- `SAFE-6` 정상 중단, 긴급 중단과 수동 거래소 fallback을 구분한다. 긴급 중단은 Web, API, 알림과 분석 저장소의 가용성과
  무관하게 실행 runtime의 host-local 경로만으로 성립해야 하며, 중단 자체가 실패하면 그 사실을 fail-closed로 드러낸다.
  중단 latch는 재시작을 견딘다. 중단이 다뤄야 하는 집합은 세 가지다. 아직 전송하지 않은 intent는 무효화하고, 거래소에
  남아 있는 exposure-increasing 주문은 취소·종료 확인하며, **전송을 시작했으나 응답이 불명이고 거래소 주문 식별자가
  확정되지 않은 intent**는 자체 client order identifier로 조회해 terminal 결론을 얻는다. 세 집합이 모두 종결되기 전에는
  중단을 성공으로 간주하지 않고 예약된 budget도 해제하지 않으며, 확인 전 상태는 halt 완료가 아니라 recovery-required로
  유지한다.
- `SAFE-7` margin 적정성을 지속적으로 관찰하고 위험 상태에서는 신규 exposure를 차단한다. 거래소가 수행한 liquidation,
  ADL 또는 강제 감축은 설명되지 않은 account drift로 탐지하며 내부 전략 결과로 조용히 흡수하지 않는다. 신규 차단만으로는
  이미 열린 헤지 leg를 보호하지 못하므로, 사전 승인된 liquidation headroom과 stress 기준을 두고 그 기준을 침범하면
  exposure-increasing 주문 취소와 승인된 범위의 bounded paired reduction 또는 즉시 owner fallback을 수행한다. 자동 이체가
  없으므로 collateral 보충은 owner의 수동 절차이며 그 lead time을 headroom 기준에 반영한다.
- `SAFE-8` fail-closed, unresolved order, 설명되지 않은 residual exposure, reconcile mismatch와 halt 같은 안전 중요 전이는
  owner가 능동적으로 인지할 수 있어야 하며 조회 화면에만 의존하지 않는다.
- `SAFE-9` 양 leg의 가용 자본과 margin이 의도한 헤지를 성립시키지 못하면 신규 진입을 fail-closed한다. 자본 부족을 이유로
  한쪽 leg만 실행해 비헤지 노출을 만들지 않는다.
- `SAFE-11` 실제 거래소 제출은 해당 intent의 durable 기록이 커밋된 뒤에만 수행한다. 이 순서 계약은 외부 전송 상태를
  갖는 실행에만 적용한다.
- `SAFE-10` 실제 거래소 제출의 결과가 불명확하거나 중복 효과가 의심되면 성공으로 단정하지 않고 이를 탐지·노출하며,
  reconcile과 복구 전에는 신규 exposure를 허용하지 않는다. 이 요구는 외부 전송 상태를 갖는 실행에만 적용하며 `ARCH-11`에
  따라 공통 경제 엔진의 필수 상태로 끌어올리지 않는다.

### 2.6 PRIVATE LIVE 안전 경계

- `LIVE-1` private credential은 private execution과 reconciliation을 담당하는 경계만 읽을 수 있다.
- `LIVE-2` credential은 source, 일반 application 설정, DB, Web, CI, 로그, metric과 알림 payload에 저장하거나 전달하지
  않는다.
- `LIVE-3` 제품은 withdrawal과 transfer operation을 호출·노출하지 않으며 credential도 해당 권한을 갖지 않는다.
- `LIVE-4` 기본 build, test, profile과 배포 상태에서는 실제 주문이 불가능하다.
- `LIVE-5` LIVE 활성화는 single owner의 명시적 승인과 host-local 절차를 요구한다.
- `LIVE-6` Web/API는 상태 확인과 중단 요청을 제공할 수 있지만 credential 입력이나 LIVE 활성화를 제공하지 않는다. Web/API의
  중단 요청은 편의 경로이며 `SAFE-6`의 host-local 중단을 대체하지 않는다.
- `LIVE-7` 기존 회원 가입과 인증 기능은 유지할 수 있지만 일반 회원은 자동매매 surface와 owner binding에 접근할 수 없다.
- `LIVE-8` risk budget과 활성화 조건은 실행 중 임의로 완화할 수 없다. 승인 범위는 최소한 자본·notional·leverage,
  신규·누적 exposure, residual delta, 손실과 market/account freshness를 다룬다. budget 사용량은 체결된 결과만이 아니라
  working, unresolved, partial과 아직 전송되지 않은 durable intent의 최악 전량 체결을 예약해 계산한다. risk 검증, 예약과
  intent 생성은 어떤 동시 실행 순서에서도 승인 한도를 넘을 수 없다.
- `LIVE-9` 실제 계정과 주문을 사용하지 않는 fake·recorded 검증이 real protocol보다 먼저 통과해야 한다.
- `LIVE-10` activation authorization이나 그 근거 evidence가 만료·불일치하거나 해당 candidate가 `CANDIDATE_REJECTED`가
  되면 신규 exposure를 차단하고 activation을 `ACTIVATION_RECOVERY_ONLY`로 되돌린다. 기존 exposure의 안전한 청산과
  reconcile은 계속할 수 있어야 한다.
- `LIVE-11` 신규 진입 권한과 복구 권한을 분리하고, 복구 권한은 여기서 한 번만 정의한다. 어떤 상태에서 복구가 허용되는지는
  §4.3이 정하고, **무엇이 복구인지는 이 항목이 정한다.** 다른 항목과 표는 아래 집합 이름을 참조하며 조건을 다시 서술하지
  않는다.
  - `RECOVERY-A` (상시 허용): cancel과 unwind. 실제 fill 기준으로 gross·net exposure와 residual delta 중 어느 것도
    증가시키지 않는다.
  - `RECOVERY-B` (조건부 허용): 이미 체결된 한쪽 leg를 평탄화하는 hedge. net exposure와 residual delta를 줄이는 대신
    gross exposure 증가를 허용하되, 상한은 그 leg의 미헤지 수량이며 budget 예약과 완료 조건을 갖춘다.
  - `RECOVERY-B` 차단 조건: `SAFE-7`의 headroom·stress 기준 침범, 거래소의 liquidation·ADL·강제 감축, 설명되지 않은
    account drift 중 하나라도 활성이면 `RECOVERY-B`를 금지하고 `RECOVERY-A`와 owner fallback만 허용한다. reconcile과
    headroom 회복이 확인되면 다시 열린다.
  - 어떤 상태에서도 새 경제적 기회를 취하는 제출은 복구가 아니다. 복구 권한은 노출이 정리되고 reconcile이 끝나면 종료한다.

## 3. 목표 아키텍처

### 3.1 논리 흐름

```text
Public market providers
        │
        ▼
Market intake ───────► durable market evidence
        │                       │
        ▼                       ▼
realtime observations      reproducible datasets
        │                       │
        └──────────┬────────────┘
                   ▼
          strategy + economic engine
             │                │
 SIMULATION/PAPER/SHADOW   LIVE intent
             │                │
             ▼                ▼
       reports/evidence   private executor ──► exchange private APIs
                              │
                              ▼
                      reconcile/audit/control
```

### 3.2 책임 경계

- `ARCH-1` Market intake는 공개 데이터 수집과 전송 품질을 소유하며 private credential을 갖지 않는다.
- `ARCH-2` Dataset 책임은 원본 lineage와 replay 가능한 입력을 제공하되 전략 판단을 소유하지 않는다.
- `ARCH-3` 공통 Domain은 경제 계산, 전략 규칙, fill·ledger 의미와 mode-neutral risk 불변식을 소유한다.
- `ARCH-4` Strategy application은 입력을 소비해 decision과 intent를 만들지만 실제 거래소 credential을 소유하지 않는다.
- `ARCH-5` PAPER adapter는 실제 거래소와 통신하지 않고 결정론적 체결·실패 모델을 제공한다.
- `ARCH-6` Private executor는 LIVE 전송 상태, account reconcile과 exchange private protocol을 소유한다.
- `ARCH-7` API/Web은 owner에게 관찰·제어 surface를 제공하되 전략 실행과 private adapter를 직접 구현하지 않는다.
- `ARCH-8` `apps:* → domain port ← infrastructure:*` 경계를 유지한다. 기술 listener나 scheduler가 application use case를
  우회해 Domain 또는 다른 adapter를 직접 조합하지 않는다.
- `ARCH-9` 신규 evidence, decision, execution과 reconcile record는 정본 market identity를 보존한다. 정본 identity는 최소한
  symbol과 각 leg의 venue, instrument class, quote currency를 구분해야 하며 symbol-only identity나 leg의 상품 종류를
  구분하지 못하는 identity를 허용하지 않는다. 현재 `MarketPair(symbol, koreaExchange, foreignExchange)`는 이 계약의
  부분집합이므로 §1.2의 spot/perpetual 조합을 어떻게 표현할지와 확장을 어느 Phase가 수행할지는 Phase 0가 판정하고,
  이후 신규 record는 확장된 identity를 사용한다. legacy symbol-only 호환 경로는 자동매매 판단의 입력이 될 수 없다.
- `ARCH-10` 위 책임 구분과 §3.4 저장 책임은 논리 경계다. 실제 consumer와 독립적인 실패·배포 이유가 입증되기 전에는
  별도 module, runtime 또는 저장 제품을 의무화하지 않는다.

### 3.3 공통 경제 상태와 LIVE 전송 상태

`ARCH-11` Backtest, PAPER와 LIVE가 공유하는 것은 경제적 decision, trade cycle, fill, ledger, PnL과 risk 의미다. 실제
주문 제출, 응답 불명, private event, 외부 order mapping과 reconcile은 LIVE 전용 책임이다. 이를 공통 엔진의 필수 상태로
끌어올려 SIMULATION과 PAPER가 네트워크 장애 모델에 종속되게 하지 않는다.

### 3.4 저장 책임

저장 기술의 구체적 제품과 schema는 Phase 설계에서 결정하되 다음 논리 책임은 분리한다.

- `STORE-1` 대량 원본과 normalized event evidence는 장기 보존과 streaming replay에 적합한 책임에 둔다.
- `STORE-2` application metadata는 dataset catalog, 실행 요청, report와 사용자 설정을 소유한다.
- `STORE-3` LIVE execution state는 application 분석 데이터와 분리된 접근 경계를 갖는다.
- `STORE-4` realtime transport/cache는 유실 가능성을 전제로 하며 장기 정본으로 사용하지 않는다.
- `STORE-5` 적용된 migration history와 기존 immutable migration 예외는 append-only로 보존하고 다시 쓰거나 숨기지 않는다.
- `STORE-6` 각 schema 경계의 migration 실행 주체는 정확히 하나여야 한다. 새 runtime이나 저장 경계가 생기면 첫 persistence
  consumer의 Phase 설계가 소유자를 정하며, batch runtime이 동시 migration 주체가 되는 것은 허용하지 않는다.

### 3.5 적정화된 V1 운영 구조

V1은 한 명의 owner가 운영할 수 있는 최소 구조를 우선한다. 다음은 필요가 입증되기 전에는 프로그램 선행조건이 아니다.

- 서비스별 Redis ACL 전면 재구축
- 다중 host 고가용성
- 다중 승인자와 조직형 권한 모델
- 별도 cryptographic evidence 체계
- 상시 staging과 자체 CI runner

운영환경, object storage, registry, alert transport와 backup 방식은 비용·복구 가능성·현재 인프라를 함께 검토한 후 선택한다.
환경 선택은 §4.5가 요구하는 고정 egress 경계와 `SAFE-6`의 host-local 중단 가능성을 제약 조건으로 포함한다. 이를 만족하지
못하는 환경은 activation 직전이 아니라 선택 시점에 배제한다. 선택되지 않은 외부 환경은 code Phase의 실패가 아니라
activation gate의 `PENDING`이다.

## 4. Program 상태와 Gate

### 4.1 Software 상태

| 상태 | 의미 |
|---|---|
| `SOFTWARE_BASELINE` | 기존 repository gate 기준선만 확보됐고 이 프로그램의 software Phase는 시작하지 않음 |
| `FOUNDATION_ALIGNED` | 기존 제품 의미와 이후 개발의 기준이 일치 |
| `MARKET_ECONOMICS_READY` | 검증 가능한 시장 입력과 공통 경제 엔진이 준비됨 |
| `STAGE_A_SOFTWARE_COMPLETE` | SIMULATION/PAPER 제품 기능이 repository 기준으로 완료됨 |
| `PRIVATE_LIVE_CODE_READY` | 실제 credential 없이 PRIVATE LIVE 코드와 안전 경계가 완료됨 |

### 4.2 독립 상태축

Software milestone과 다음 상태축은 서로 독립적으로 기록한다.

| 상태축 | 상태 | 의미 |
|---|---|---|
| specification | `MASTER_SPEC_DRAFT_FOR_DUAL_REVIEW` | master specification을 독립 검토 중 |
| specification | `MASTER_SPEC_REVIEWED_AWAITING_USER_APPROVAL` | 검토 반영을 마치고 사용자 승인을 기다림 |
| specification | `MASTER_SPEC_APPROVED` | 검토 반영본을 사용자가 승인 |
| evidence collection | `COLLECTION_NOT_READY` | 장기 증거 clock을 시작할 최소 환경이나 승인이 준비되지 않음 |
| evidence collection | `COLLECTION_READY` | 장기 증거 수집의 환경·보존·관찰·비용 전제가 승인됐지만 clock은 시작하지 않음 |
| evidence collection | `COLLECTION_IN_PROGRESS` | 기록된 시작 시점부터 장기 증거를 수집 중 |
| candidate/evidence | `EVIDENCE_PENDING` | 특정 candidate의 데이터·전략·운영 증거를 수집 중 |
| candidate/evidence | `CANDIDATE_APPROVED` | 승인된 정책에 따라 candidate가 다음 gate의 입력이 됨 |
| candidate/evidence | `CANDIDATE_REJECTED` | 특정 candidate가 실패했으며 새 candidate로 재시도 가능 |
| activation | `ACTIVATION_NOT_STARTED` | 실제 계정과 주문을 사용하는 전이를 시작하지 않음 |
| activation | `ACTIVATION_PENDING` | 순차 gate·사용자 승인·유효한 evidence를 기다리며 신규 exposure가 비활성화됨 |
| activation | `ACTIVATION_IN_PROGRESS` | 승인된 canary 또는 bounded LIMITED를 수행 중이며 현재 risk 경계가 적용됨 |
| activation | `ACTIVATION_RECOVERY_ONLY` | 신규 진입은 차단되고 `LIVE-11`의 노출 감소 제출만 허용되는 복구 구간 |
| activation | `PRIVATE_LIVE_ACTIVE_COMPLETE` | 승인된 candidate가 제한된 실제 실행과 대조 증거를 통과 |
| program | `PROGRAM_IN_PROGRESS` | 프로그램이 개시됐고 아직 완료·종결되지 않음. 특정 Phase나 gate의 진행 가능 여부는 각 진입 조건이 결정한다 |
| program | `PROGRAM_COMPLETED` | bounded LIMITED라는 V1 제품 종점과 정본 동기화를 완료 |
| program | `PROGRAM_TERMINATION_PENDING` | 종결을 결정했지만 열린 위험 또는 정리 작업이 남음 |
| program | `PROGRAM_TERMINATED_NO_GO` | 위험과 권한을 닫고 프로그램을 안전하게 종결 |

프로그램 개시 시점의 초기값은 software `SOFTWARE_BASELINE`, evidence collection `COLLECTION_NOT_READY`, activation
`ACTIVATION_NOT_STARTED`, program `PROGRAM_IN_PROGRESS`다. 각 축의 현재값과 전이 근거는 `progress.md`에만 기록하며,
software 축 전이는 해당 Phase DoD의 merged 검증 결과로, evidence와 activation 축 전이는 사용자 승인으로 선언한다.
기록과 근거가 어긋나면 근거를 정본으로 삼아 `progress.md`를 다시 기록한다.

예를 들어 software는 `PRIVATE_LIVE_CODE_READY`이면서 activation은 `ACTIVATION_PENDING`일 수 있다. 한 candidate의
`CANDIDATE_REJECTED`는 프로그램 종결을 뜻하지 않는다. `PRIVATE_LIVE_CODE_READY`를 실제 LIVE 완료로 표현하지 않는다.
`PROGRAM_COMPLETED`는 승인된 bounded LIMITED까지를 V1 성공으로 정의하며, 그보다 큰 위험 범위는 이 프로그램의 숨은 다음
단계가 아니다. 활성 activation의 근거 candidate가 reject되면 `LIVE-10`을 따른다.

### 4.3 상태별 허용 행위

상태 정의가 늘어날수록 개별 계약 문장끼리 권한이 어긋나기 쉽다. 제출 권한은 아래 표를 단일 대조표로 삼는다.

| 상태 | 신규 exposure 제출 | 노출 감소 제출 (cancel·hedge·unwind) | 미전송 intent | reconcile·관찰 |
|---|---|---|---|---|
| `ACTIVATION_NOT_STARTED` | 불가 | 해당 없음 | 생성하지 않음 | 가능 |
| `ACTIVATION_PENDING` | 불가 | 해당 없음 (열린 exposure가 있으면 `ACTIVATION_RECOVERY_ONLY`가 옳은 상태) | 무효 | 가능 |
| `ACTIVATION_IN_PROGRESS` | 승인된 risk budget 안에서 가능 | `RECOVERY-A` 상시, `RECOVERY-B`는 차단 조건이 없을 때만 | 현재 epoch에서만 유효 | 가능 |
| `ACTIVATION_RECOVERY_ONLY` | 불가 | `RECOVERY-A` 상시, `RECOVERY-B`는 차단 조건이 없을 때만 | 무효 | 가능 |
| `PRIVATE_LIVE_ACTIVE_COMPLETE` | 불가 (새 승인 필요) | `RECOVERY-A` 상시, `RECOVERY-B`는 차단 조건이 없을 때만 | 무효 | 가능 |
| `PROGRAM_TERMINATION_PENDING` (program 축) | 불가 | `RECOVERY-A` 상시, `RECOVERY-B`는 차단 조건이 없을 때만 | 무효 | 가능 |
| `PROGRAM_TERMINATED_NO_GO` (program 축) | 불가 | 해당 없음 (열린 노출 없음이 전제) | 없음 | 가능 |
| halt latch 활성 (상태축과 직교) | 불가 | `RECOVERY-A` 상시, `RECOVERY-B`는 차단 조건이 없을 때만 | 무효 | 가능 |

- 제출 권한을 부여하거나 제한하는 계약을 새로 만들면 이 표에 반영한다. 표에 나타나지 않는 권한은 존재하지 않는 것으로 본다.
- 개별 ID 문장과 이 표가 어긋나면 그 자체가 결함이며 승인 전에 해소한다. 구현이 둘 중 하나를 임의로 선택하지 않는다.
- halt latch는 activation 상태와 직교한다. 두 조건이 동시에 적용되면 더 제한적인 쪽을 따른다.
- 복구 열의 모든 값은 `LIVE-11`이 정의한 집합 이름을 참조한다. 상태 행마다 허용 조건을 다시 서술하지 않으며,
  집합 정의가 바뀌면 모든 상태에 동시에 적용된다. 종결 상태도 이 정의를 상속하며 더 넓은 "정리 범위"를 갖지 않는다.
- program 축이 종결 방향이면 activation 축보다 우선한다. `PROGRAM_TERMINATION_PENDING` 진입은 activation을
  `ACTIVATION_RECOVERY_ONLY`로 전이시키는 것과 하나의 원자적 전이이며, epoch 무효화·미전송 intent 폐기·
  exposure-increasing 주문 취소를 진입 조건으로 포함한다. 종결 선언과 제출 차단 사이에 신규 진입이 생길 수 있는
  구간을 두지 않는다.

### 4.4 Evidence Collection Readiness Gate

장기 data/evidence 수집은 다음 조건을 충족해 `COLLECTION_READY`가 된 뒤에만 시작할 수 있다.

- `ECG-1` 선택한 최소 실행 환경에서 수집 결과가 runtime 수명과 분리되어 지속 보존된다.
- `ECG-2` provenance, 시장·수신 clock과 gap을 관찰할 수 있다.
- `ECG-3` 환경과 보존 비용 및 책임 owner가 승인됐다.
- `ECG-4` 준비 이후 실제 수집 시작 시점과 적용할 사전 승인 품질 정책을 기록하면 `COLLECTION_IN_PROGRESS`로 전이하고
  그때부터 장기 evidence clock을 계산한다.
- `ECG-5` 이 프로그램 이전에 수집된 기존 데이터의 evidence 적격성을 Phase 1 exit 이전에 명시적으로 판정해 기록한다.
  기존 데이터가 `DATA-1`~`DATA-3`을 소급 충족하지 못하면 엔진 검증과 정성적 참고로만 사용하고 장기 증거 기간에
  산입하지 않는다. 이 판정은 후속 gate에서 유리하게 재해석하지 않는다.

장기 evidence clock은 이 프로그램에서 가장 긴 lead time 항목이다. 따라서 collection readiness는 Phase 1 전체 완료를
기다리지 않고 최소 수집 계약(`P1-O1`)이 확정되어 배포 가능해지는 즉시 평가하고 시작한다.

이 gate가 `COLLECTION_NOT_READY` 또는 `COLLECTION_READY`여도 software Phase는 통제된 fixture와 명시적으로 구분된 입력으로
진행할 수 있다. 단, `COLLECTION_IN_PROGRESS` 전 데이터는 장기 실제 증거 기간에 산입하지 않는다. 수집 시작 후의 gap과
보존 실패는 미리 승인한 정책에 따라 해당 candidate evidence의 `PENDING` 또는 `FAIL`로 평가한다.

### 4.5 Activation Gate

외부 gate는 한 번에 평가하지 않고 권한과 위험이 증가하는 전이마다 순서대로 평가한다.

1. `ACT-1` Candidate readiness — production credential 전에 평가
   - 승인된 viability policy와 전략·데이터 증거
   - `ECO-5`의 자본 소진·재배치 산출과 그에 따른 운영 가능 cycle 범위
   - `SAFE-7`의 liquidation headroom·stress 기준과 breach 시 대응 정책
   - 선택된 runtime, storage, network, alert와 복구 방식
   - 운영비, 거래 자본과 허용 손실에 대한 owner 승인
   - 현재 법률·세무·거래소 자격과 필요한 전문가 확인
2. `ACT-2` Account readiness — read-only 확인 뒤 주문 권한 전에 평가
   - single owner/account binding
   - credential의 최소 권한과 withdrawal/transfer 불가
   - 거래소 API 키에 적용할 고정 egress 경계와 접근 제한
   - credential rotation과 즉시 폐기 절차, owner가 단독으로 수행 가능한지 확인
   - 주문 의미와 margin 귀속을 바꾸는 account·symbol configuration의 승인된 snapshot. 최소한 position mode, margin type,
     multi-assets 여부, leverage와 auto-add-margin 성격의 설정을 포함하며, 승인 범위와 다르면 activation을 통과시키지 않는다
   - 기존 order, position, balance와 전략 기준선 reconcile
3. `ACT-3` Canary readiness — 첫 실제 주문 전에 평가
   - 승인된 candidate와 실제 read-only account 상태로 수행한 SHADOW
   - leg별 provider 검증 단계 확인. fake·recorded 검증은 두 leg 모두 필수이며, 실자금 없이 real protocol을 시험할 수
     있는 leg는 그 단계를 통과하고, 그런 수단이 없는 leg는 어떤 대체 증거로 갈음할지 승인받는다
   - 현재 candidate·account·risk budget에 대한 별도 사용자 승인
   - 주문 기능이 잠긴 상태에서의 최종 preflight
4. `ACT-4` LIMITED readiness — canary 이후 위험 범위를 확대하기 전에 평가
   - canary의 외부 statement와 내부 ledger 대조
   - unresolved order와 설명되지 않은 residual exposure 부재
   - bounded LIMITED 운영에 대한 별도 사용자 승인

`ACT-3` 승인 뒤 canary가 시작되면 activation은 `ACTIVATION_IN_PROGRESS`로 전이하고, `ACT-4`를 통과한 bounded LIMITED가
§9.3을 충족할 때까지 그 상태를 유지한다.

기간, 표본 수, coverage와 허용 손실 같은 정책값은 측정 결과를 보기 전에 사용자에게 승인받는다. 이 specification에는
근거 없는 기본 숫자를 고정하지 않는다. 각 전이의 필수 항목 중 하나라도 `UNKNOWN`, 만료 또는 불일치이면 그 전이와
이후 전이를 차단한다. activation 근거의 만료·불일치·candidate reject 처리는 `LIVE-10`을 따른다.

## 5. Phase Roadmap

Phase는 사용자 가치와 아키텍처 능력의 성숙 단위다. Phase 자체는 branch나 PR이 아니다. 각 Phase를 consume하는 agent가
최신 `dev`와 실제 diff 범위를 분석해 하나 이상의 독립 PR로 분해한다.

```text
Software:  Phase 0 → Phase 1 ─────────────→ Phase 2 → Phase 3 → PRIVATE_LIVE_CODE_READY
                        │
                        └ 최소 수집 계약(P1-O1) 확정 시점
                              │
Evidence:                     └→ Collection readiness → COLLECTION_IN_PROGRESS
                                 → candidate evidence ────────────────────────┐
                                 (Phase 1 종료를 기다리지 않고 병렬 진행)      │
                                                                              │
Activation:  PRIVATE_LIVE_CODE_READY ─────────────────────────────────────────┘
                 → Candidate(ACT-1) → Account → Canary → LIMITED
                 → PRIVATE_LIVE_ACTIVE_COMPLETE → PROGRAM_COMPLETED

Termination:  owner NO_GO → PROGRAM_TERMINATION_PENDING
                            → PROGRAM_TERMINATED_NO_GO
```

### Phase 0 — Foundation Alignment

**목표**

현재 제품의 의미와 이후 자동매매 개발이 전제할 용어·방향·관찰 가능성을 일치시킨다.

**진입 조건**

`MASTER_SPEC_APPROVED`이며 최신 `dev` 기준선이 기존 repository gate를 통과한다.

**필수 outcome**

- `P0-O1` 기존 Position이 비주문 추적 record라는 사실이 API, Web과 문서에서 일치한다.
- `P0-O2` premium 방향과 기존 PnL의 보장 범위가 모순 없이 설명된다.
- `P0-O3` `ARCH-9`의 정본 identity 요구와 현재 identity의 차이를 판정한다. 최소한 두 leg의 instrument class와 quote
  currency가 어디에 기록되는지, 현재 수집·저장 자산이 이후 기능의 입력으로 쓰일 수 있는 범위와 한계가 무엇인지,
  identity 확장을 어느 Phase가 수행할지를 결정해 기록한다.
- `P0-O4` dead 또는 미연결된 기존 계약은 실제 사용 근거에 따라 유지·수정·제거가 결정된다.
- `P0-O5` As-Is 아키텍처와 Planned capability가 문서에서 구분된다.

**종료 조건**

기존 기능이 자동 주문이나 net/account PnL을 제공하는 것처럼 오해되지 않으며, Phase 1이 사용할 baseline과 미해결 결정이
명시돼 있어 `FOUNDATION_ALIGNED`를 선언할 수 있다.

**이 Phase가 선결정하지 않는 것**

새 시장 observation type, archive provider, 전략 엔진과 LIVE 계약.

### Phase 1 — Market & Economics Foundation

**목표**

전략이 재현 가능한 시장 근거 위에서 비용 반영 경제 결과를 계산할 수 있게 한다.

**진입 조건**

`FOUNDATION_ALIGNED`이며 Phase 0에서 발견된 미해결 사항이 Phase 1의 설계를 차단하지 않는다.

**필수 outcome**

- `P1-O1` 실행 가능성을 평가할 수 있는 market observation과 provenance가 정의된다. 이 정의는 장기 수집을 시작할 수 있는
  최소 수집 계약을 포함한다. 최소 수집 계약은 두 leg의 가격 의미를 서로 비교 가능하게 맞추고, 호가와 수량을 어느
  수준까지 관측·보존할지와 그 수준이 이후 체결 모의와 slippage 주장을 어디까지 지지하는지를 명시한다. 이 계약은 Phase 1의
  첫 산출물로 다루며 확정 즉시 §4.4의 collection readiness 평가 대상이 된다.
- `P1-O2` 원본의 지속 보존, normalized dataset과 realtime 입력의 책임이 구분된다.
- `P1-O3` 누락·지연·clock·backfill과 합성 데이터가 품질 상태로 드러난다.
- `P1-O4` replay input이 bounded하게 소비되고 동일 입력의 경제 결과가 재현된다.
- `P1-O5` 공통 경제 엔진이 fee, slippage, funding, FX, capital 배치·재배치, margin과 residual risk를 분리하며 불리한
  비용 조건도 평가할 수 있다.
- `P1-O6` 외부 주문 전송 상태를 도입하지 않고 경제·risk 불변식을 검증한다.
- `P1-O7` 시장·경제 evidence 전 구간에서 `ARCH-9`의 정본 identity가 보존된다.
- `P1-O8` Phase 1이 고정하는 dataset·engine 계약은 최소 하나의 vertical proof consumer로 관통 검증된다. 그 consumer가
  요구하지 않는 계약은 이 Phase에서 확정하지 않고 Phase 2의 첫 consumer에게 넘긴다.

시장 데이터 lane과 경제 엔진 lane은 인터페이스 기대가 합의된 뒤 병렬로 수행할 수 있다. 실제 장기 수집과 strategy
viability 증거는 이 Phase의 code 완료를 막지 않고 별도 gate로 계속 진행한다.

**종료 조건**

실행 가능한 가격이 아닌 입력이나 출처가 불명확한 데이터로 전략 성과를 확정할 수 없으며, 통제된 입력과 기록된 실제
입력으로 경제 엔진의 부호·비용·분모·재현성을 proof consumer 경로에서 검증해 `MARKET_ECONOMICS_READY`를 선언할 수 있다.

**이 Phase가 선결정하지 않는 것**

전략의 수익성, LIVE leg 순서, 데이터 보존 provider와 운영 성능 수치.

### Phase 2 — SIMULATION + PAPER Product

**목표**

동일한 전략과 경제 엔진을 과거 replay와 실시간 모의 실행에 적용하고 owner가 결과와 위험을 검토할 수 있게 한다.

**진입 조건**

`MARKET_ECONOMICS_READY`다. 장기 실제 데이터가 아직 충분하지 않아도 engine 검증용 입력이 명확히 구분돼 있으면 software
개발을 진행할 수 있다.

**필수 outcome**

- `P2-O1` Backtest는 미래 데이터를 사용하지 않고 dataset·정책·engine lineage를 보존한다.
- `P2-O2` PAPER는 실제 주문 없이 두 leg의 체결, 부분 체결, 실패와 복구를 모의한다. 체결과 slippage 모델은 수집된 관측
  수준이 지지하는 범위에서만 주장하며 관측되지 않은 호가 깊이를 가정으로 대체하지 않는다.
- `P2-O3` Backtest와 PAPER가 공통 경제 의미를 공유하며 차이가 선언된 adapter 정책으로 설명된다.
- `P2-O4` 중복·재시작·gap·중단 상황에서 결과와 잔여 exposure가 추적된다.
- `P2-O5` 전략 candidate의 입력, 결과, 승인과 승격 이력이 구분된다.
- `P2-O6` API/Web은 gross/net, 비용, funding, 데이터 freshness와 PAPER 비주문 상태를 명확히 보여준다.
- `P2-O7` `QUAL-1`에 따라 기존 auth, premium, position과 notification 기능의 회귀를 만들지 않는다.
- `P2-O8` 전략 decision 규칙과 parameter가 명시적 정책으로 표현되고 Backtest와 PAPER가 같은 규칙을 소비한다.
- `P2-O9` SIMULATION/PAPER decision, execution과 candidate record가 `ARCH-9`의 정본 identity를 보존한다.
- `P2-O10` `ECO-5`의 자본 소진·재배치 제약이 전략 결과에 반영되고, 자본이 헤지를 지지하지 못하는 구간이 결과에서
  드러난다.

**종료 조건**

repository와 fake environment에서 SIMULATION/PAPER 사용자 흐름과 failure matrix가 검증되고
`STAGE_A_SOFTWARE_COMPLETE`를 선언할 수 있다. 실제 데이터 기간이나 수익성 정책이 아직 충족되지 않았다면 software는
완료하되 evidence 상태는 `EVIDENCE_PENDING`으로 남는다.

**이 Phase가 선결정하지 않는 것**

실제 전략 GO, production credential, 실제 주문과 activation.

### Phase 3 — PRIVATE LIVE Capability

**목표**

single owner의 제한된 실제 실행을 기본 비활성 상태에서 안전하게 준비한다.

**진입 조건**

`STAGE_A_SOFTWARE_COMPLETE`다. 장기 strategy evidence는 `EVIDENCE_PENDING`일 수 있지만 실제 activation은 통과한
evidence 없이는 진행하지 않는다.

**필수 outcome**

- `P3-O1` strategy decision과 private execution/reconciliation이 credential 경계로 분리된다.
- `P3-O2` account, balance, fee, funding, order와 position의 read-only reconcile이 가능하다.
- `P3-O3` SHADOW가 read-only account 상태로 decision과 intent를 생성하되 주문 제출은 구조적으로 비활성화한다. code-ready
  판정은 fake·recorded account로 검증하고 실제 계정 SHADOW는 `ACT-3`에서 수행한다.
- `P3-O4` 실제 제출은 명시적 activation, 현재 risk budget과 신뢰 가능한 market/account 상태를 요구하며 제출 권한은
  `SAFE-1`의 authorization epoch에 결합된다.
- `P3-O5` self-induced duplicate, 응답 불명, private event gap, partial fill과 restart를 탐지하고 복구할 수 있다. 모든
  제출은 `SAFE-11`에 따라 durable intent 기록 이후에만 수행하며 재시작이 미해결 제출을 지운 것처럼 보이게 하지 않는다.
- `P3-O6` margin 위험과 거래소 강제 감축을 관찰하고 설명되지 않은 변화를 fail-closed한다. 이 상태가 활성인 동안에는
  `RECOVERY-B`가 거부되고 `RECOVERY-A` 또는 owner fallback만 진행된다.
- `P3-O7` 정상 중단과 긴급 중단이 신규 exposure를 차단하고 기존 exposure의 상태를 드러낸다. 긴급 중단은 `SAFE-6`에 따라
  Web/API/알림/분석 저장소가 불가용해도 host-local로 성립하며 중단 실패 자체가 관찰된다.
- `P3-O8` 실제 provider protocol은 recorded/fake 검증으로 회귀 가능하며 test와 CI는 실제 거래소에 연결하지 않는다.
- `P3-O9` owner는 status·중단 결과와 안전 중요 전이를 능동적으로 인지할 수 있다.
- `P3-O10` Web/API로 secret을 입력하거나 LIVE를 활성화할 수 없다.
- `P3-O11` default build/config/deploy는 실제 주문을 만들지 않는다.
- `P3-O12` V1 single-owner와 single-account-pair 범위가 강제되며 다른 회원이나 account가 자동매매 권한을 얻지 않는다.
- `P3-O13` LIVE execution과 reconcile record가 `ARCH-9`의 정본 identity를 보존한다.
- `P3-O14` activation authorization·evidence 만료 또는 candidate reject 시 신규 exposure를 차단하고
  `ACTIVATION_RECOVERY_ONLY`로 전이하되, `RECOVERY-A`와 조건을 만족하는 `RECOVERY-B`로 기존 exposure의 청산과 reconcile을
  계속할 수 있다. 한 leg만 체결된 상태에서 `RECOVERY-B`가 조건 없이 차단되어 비헤지 노출이 방치되지 않아야 하며, 차단
  조건이 활성일 때는 `RECOVERY-B`가 거부되고 `P3-O6`의 경로만 열린다.
- `P3-O15` leg별 provider 검증 단계가 구분된다. fake·recorded harness는 두 leg 모두 갖추고, 실자금 없이 real protocol을
  시험할 수 있는 leg는 그 경로를 준비하되 기본 build/CI 경로에는 포함하지 않는다.
- `P3-O16` 자본 부족으로 헤지가 성립하지 않는 상태에서 `SAFE-9`에 따라 신규 진입이 차단된다.
- `P3-O17` 승인된 account·symbol configuration snapshot을 보관하고 exposure-increasing 제출 직전에 drift를 검증한다.
  drift가 있으면 제출을 차단하고 activation 근거를 무효로 처리한다.
- `P3-O18` risk budget이 in-flight 제출의 최악 전량 체결을 예약하며, 동시 실행 worker가 경쟁해도 승인 한도를 넘는
  제출이 만들어지지 않는다.
- `P3-O19` 중단과 activation 강등이 `SAFE-6`의 세 집합(미전송, 거래소 잔존 주문, 전송 후 응답 불명)을 모두 종결시키며,
  확인 전에는 recovery-required 상태를 유지한다. `PROGRAM_TERMINATION_PENDING` 진입도 같은 경로를 원자적으로 수행한다.

**종료 조건**

실제 credential 없이 전체 LIVE 경계와 failure/reconcile 계약을 검증해 `PRIVATE_LIVE_CODE_READY`를 선언할 수 있다.
실제 주문과 위험 범위 확대는 각각 해당되는 §4.5의 선행 gate와 별도 사용자 승인을 통과하기 전까지 불가능해야 한다.

**이 Phase가 선결정하지 않는 것**

실제 자본 규모, canary/limited 기간, 운영 host/provider와 활성화 승인.

## 6. Phase Consumption Contract

### 6.1 `work` 진입

각 Phase를 consume하는 agent는 구현 전에 다음을 수행한다.

1. 최신 `dev`, 이 specification, 현재 아키텍처와 `progress.md`를 읽는다.
2. 해당 Phase에 필요한 리서치만 다시 검증하고 현재 코드의 gap을 제시한다.
3. 최소 두 가지 설계 또는 delivery 선택지와 trade-off를 제시한다.
4. 첫 consumer 원칙으로 지금 필요한 계약과 뒤로 미룰 계약을 분리한다.
5. Phase outcome을 독립적으로 green을 유지할 수 있는 PR 후보로 분해한다.
6. 설계, PR map과 Phase DoD를 사용자에게 승인받은 뒤 구현한다.

Phase를 consume했다는 사실만으로 구현 세부와 PR 수가 승인된 것은 아니다.

### 6.2 PR 분할 원칙

PR 개수는 master에서 고정하지 않는다. Phase agent는 다음 기준으로 분할안을 제안한다.

- 각 PR이 하나의 설명 가능한 outcome을 가지는가
- merge된 중간 상태가 compile·test·architecture gate를 통과하는가
- 아직 consumer가 없는 추상화를 만들지 않는가
- migration, 외부 protocol, 새 runtime과 보안 경계를 별도로 검토할 필요가 있는가
- API와 Web 계약을 함께 바꿔야 하는지 독립 배포 가능한지
- rollback 또는 후속 수정이 다른 책임을 불필요하게 되돌리지 않는가
- diff와 failure surface가 한 리뷰에서 이해 가능한가

단순히 파일 수나 모듈 수를 맞추기 위해 PR을 쪼개지 않는다. 반대로 서로 다른 완료 이유와 위험을 한 PR에 묶지 않는다.

### 6.3 PR lifecycle

승인된 각 PR slice는 `work`, repository의 `AGENTS.md`와 개발 규칙이 정의한 격리·설계·DoD·검증·리뷰·merge lifecycle을
따른다. master는 그 명령이나 도구 구성을 복제하지 않는다.

각 slice는 최신 `dev`를 기준으로 독립 검증 가능해야 한다. 의존 관계가 있는 slice는 선행 slice의 merged 결과와 원격 gate
증거가 확인된 뒤 진행하며, 쓰기 범위와 계약이 독립적인 slice는 각각 최신 `dev`에서 검증할 수 있을 때 병렬 수행할 수
있다. 아직 존재하지 않는 원격 run을 같은 acceptance commit 안에 기록하도록 요구하지 않는다.

### 6.4 Phase 완료 판정

한 Phase에 여러 PR이 필요하면 마지막 PR만으로 Phase를 판단하지 않는다. 승인된 Phase DoD가 모든 slice의 merged 결과에서
충족되고, 남은 scope가 다음 Phase 또는 명시적 deferred decision으로 귀속됐을 때만 Phase 상태를 전이한다.

## 7. 검증과 Evidence 원칙

### 7.1 Evidence tier

- `QUAL-1` 모든 software Phase는 그 Phase가 명시적으로 변경하도록 승인받은 계약을 제외한 기존 사용자 기능과 아키텍처
  경계의 회귀를 만들지 않는다. 회귀 여부의 판정 근거는 T2의 merged Quality Gate와 기존 계약 검증이며 Phase DoD가 이를
  더 약한 기준으로 대체하지 않는다.
- T1: local deterministic unit, integration, architecture, migration, Web와 documentation 검증
- T2: PR 및 merged `dev` Quality Gate
- T3: 선택된 validation/operation 환경에서의 replay, 성능, 복구와 장기 데이터 증거
- T4: 사용자 승인, 비용, 법률·세무, 거래소 자격, 실제 account/credential와 activation 증거

Phase-local software DoD는 T1과 T2를 소유한다. T3/T4가 필요한 항목은 code completion을 거짓 실패로 만들지 않고 별도
program gate의 `PENDING | PASS | FAIL`로 기록한다.

### 7.2 테스트 경계

- 테스트는 실제 거래소 주문, 실제 SMTP, 실제 Slack과 실제 credential을 사용하지 않는다.
- 외부 protocol은 fake, recorded fixture와 contract test로 재현한다.
- 실자금과 실계정을 쓰지 않는 provider testnet은 fake·recorded 다음, 실계정 이전의 별도 단계로 취급한다. 기본 build와
  CI 경로에는 포함하지 않으며 leg별 적용 여부는 `ACT-3`에서 판정한다.
- 시간은 주입된 Clock 또는 명시적 Instant를 사용한다.
- no-lookahead, duplicate, partial, restart, stale/gap과 reconcile mismatch에 대한 negative 검증을 포함한다.
- 동시 intent가 승인된 risk budget을 초과하지 않는지, 중단·강등 이후 미전송 intent와 거래소 대기 주문이 실행되지
  않는지, 전송 후 응답 불명 intent가 terminal 결론 전에 halt 성공으로 처리되지 않는지, 종결 선언 직후 신규 진입이
  생기지 않는지, 복구 구간의 제출이 허용된 위험 벡터 기준을 벗어나지 않는지를 negative 검증으로 포함한다. 거래소가 한
  leg를 강제 감축해 단일 leg만 남은 상태에서 복구 hedge가 거부되고 paired reduction 또는 owner fallback으로만 진행되는
  경로를 failure matrix에 포함한다. 종결 전이와 단일 leg 잔존, 강제 감축, 응답 불명이 겹치는 조합도 같은 matrix에서
  판정한다.
- 성능 수치는 host와 workload가 정의된 T3에서만 판정한다.
- migration 검증은 `STORE-5`와 `STORE-6`을 acceptance 대상으로 포함한다.

### 7.3 CI 적정화

repository gate는 새 capability의 source, architecture와 배포 가능한 산출물을 실제로 검증해야 한다. 정확한 task, job,
artifact와 도구 구성은 Phase 설계가 현재 CI를 기준으로 결정한다.

자체 runner, secret-bearing 배포 workflow 또는 별도 integrity 체계를 도입하는 것은 일반 구현 단계로 간주하지 않으며
사용자 재승인을 요구한다.

## 8. 요구사항 Traceability

아래 표는 안정적인 requirement ID의 소유권만 고정한다. Phase별 DoD는 ID를 더 구체적인 acceptance로 확장할 수 있지만 의미를
축소하거나 다른 Phase에 암묵적으로 넘길 수 없다. 각 Phase DoD는 자신에게 할당된 모든 ID를 acceptance 항목으로 인용하며,
인용되지 않은 ID가 남아 있으면 그 Phase를 완료로 판정하지 않는다.

| Requirement ID | 책임 | 최종 확인 지점 |
|---|---|---|
| `SEM-1`~`SEM-4` | Phase 0 | `FOUNDATION_ALIGNED` |
| `SEM-5` | Phase 2, Phase 3 | `STAGE_A_SOFTWARE_COMPLETE`, `PRIVATE_LIVE_CODE_READY` |
| `ECO-1`~`ECO-3` | Phase 1 | `MARKET_ECONOMICS_READY` |
| `ECO-4` | Phase 1, Candidate Gate | Phase 1 exit와 `ACT-1` |
| `ECO-5` | Phase 1, Phase 2, Candidate Gate | Phase 1 exit, Stage A exit와 `ACT-1` |
| `DATA-1`~`DATA-5` | Phase 1, Evidence Collection Gate | Phase 1 exit와 `COLLECTION_READY` |
| `DATA-6` | Phase 1, Candidate Gate | Phase 1 exit와 `ACT-1` |
| `PROM-1`~`PROM-4` | Phase 2, Candidate Gate | Stage A exit와 `ACT-1` |
| `SAFE-1`~`SAFE-3` | Phase 2, Phase 3 | Stage A exit와 LIVE code-ready |
| `SAFE-10`~`SAFE-11` | Phase 3 | `PRIVATE_LIVE_CODE_READY` |
| `SAFE-4`~`SAFE-8` | Phase 3, Activation Gate | LIVE code-ready와 해당 activation evidence |
| `SAFE-9` | Phase 2, Phase 3 | Stage A exit와 LIVE code-ready |
| `LIVE-1`~`LIVE-9` | Phase 3 | `PRIVATE_LIVE_CODE_READY` |
| `LIVE-10` | Phase 3, Activation Gate | LIVE code-ready와 activation 갱신·만료 판정 |
| `LIVE-11` | Phase 3, Activation Gate | LIVE code-ready와 복구 구간 권한 판정 |
| `ARCH-1`~`ARCH-2` | Phase 1 | `MARKET_ECONOMICS_READY` |
| `ARCH-3` | Phase 1, Phase 2 | Market & Economics와 Stage A exit |
| `ARCH-4` | Phase 2 | `STAGE_A_SOFTWARE_COMPLETE` |
| `ARCH-5` | Phase 2 | `STAGE_A_SOFTWARE_COMPLETE` |
| `ARCH-6` | Phase 3 | `PRIVATE_LIVE_CODE_READY` |
| `ARCH-7` | Phase 0, Phase 2, Phase 3 | 각 사용자 surface가 생기는 Phase exit |
| `ARCH-8` | 모든 software Phase | 각 merged Phase의 architecture gate |
| `ARCH-9` | Phase 0, Phase 1, Phase 2, Phase 3 | Phase 0의 identity 판정과 각 identity consumer의 Phase exit |
| `ARCH-10` | 모든 software Phase | 각 Phase 설계 승인 |
| `ARCH-11` | Phase 1, Phase 2, Phase 3 | Stage A와 LIVE code-ready |
| `STORE-1`~`STORE-2` | Phase 1 | `MARKET_ECONOMICS_READY` |
| `STORE-3` | Phase 3 | `PRIVATE_LIVE_CODE_READY` |
| `STORE-4` | Phase 1, Phase 2 | Stage A exit |
| `STORE-5`~`STORE-6` | persistence를 변경하는 Phase | 해당 Phase migration gate |
| `ECG-1`~`ECG-3` | Evidence Collection Gate | `COLLECTION_READY` |
| `ECG-4` | Evidence Collection Gate | `COLLECTION_IN_PROGRESS`와 기록된 시작 시점 |
| `ECG-5` | Phase 1, Evidence Collection Gate | Phase 1 exit 이전의 기록된 적격성 판정 |
| `ACT-1` | Candidate Gate | `CANDIDATE_APPROVED` |
| `ACT-2` | Account Gate | read-only account readiness 승인 |
| `ACT-3` | Canary Gate | 첫 실제 주문 전 별도 승인 |
| `ACT-4` | LIMITED Gate | bounded LIMITED 전 별도 승인 |
| `P0-O1`~`P0-O5` | Phase 0 | `FOUNDATION_ALIGNED` |
| `P1-O1`~`P1-O8` | Phase 1 | `MARKET_ECONOMICS_READY` |
| `P2-O1`~`P2-O10` | Phase 2 | `STAGE_A_SOFTWARE_COMPLETE` |
| `P3-O1`~`P3-O19` | Phase 3 | `PRIVATE_LIVE_CODE_READY` |
| `QUAL-1` | 모든 software Phase | 각 Phase의 merged verification |
| `NOGO-0` | Program 재평가 | 승인된 재평가 시점의 계속·축소·종결 결정 |
| `DONE-1`~`DONE-6` | Activation과 program completion | `PROGRAM_COMPLETED` |
| `NOGO-1`~`NOGO-4` | Program termination | `PROGRAM_TERMINATED_NO_GO` |

### 8.1 Phase 정직성 검사

ID를 어느 Phase에 배정하기 전에 그 Phase의 실행 모드에서 해당 계약을 **관찰할 수 있는지** 확인한다. 관찰할 수 없는
요소가 섞여 있으면 ID를 배정하지 않고 분리한다. 관찰 불가한 계약을 배정하면 그 Phase는 공허하게 PASS하거나
`ARCH-11`을 어기고 LIVE 전송 상태를 조기에 도입하게 되며, 두 경우 모두 Phase DoD와 gate 판정이 거짓이 된다.

| Phase | 실행 모드 | 관찰 가능한 계약 | 배정할 수 없는 계약 |
|---|---|---|---|
| Phase 0 | 실행 없음, 문서·표현·identity 정렬 | 의미·표현 일관성, identity 판정, 기존 자산의 한계 | 체결, 외부 제출, 계정 상태 |
| Phase 1 | 데이터·경제 계산, 주문 없음 | 관측·provenance·품질·재현성, 경제 계산과 비용 분해 | 체결 결과, 주문 전송, 계정 reconcile |
| Phase 2 | SIMULATION/PAPER, 모의 체결 | mode-neutral 실행 의미(의도 식별, 중복 방지, 잔여 노출, 자본 제약) | 실제 외부 제출 결과, 응답 불명 reconcile, 계정 drift |
| Phase 3 | SHADOW와 기본 비활성 LIVE 코드 | 외부 protocol·계정·전송 상태를 fake·recorded로 재현한 계약 | 실제 activation 판정, 실자금·실계정 증거 |
| Gate (`ECG`·`ACT`·`DONE`·`NOGO`) | 실제 환경·계정·자금과 사용자 승인 | T3·T4 증거와 승인 판정 | software DoD의 T1·T2로 대체 |

`SAFE-1`, `SAFE-11`과 `SAFE-10`의 분리가 이 검사의 적용 예다. 의도 식별·중복 방지·epoch 무효화는 모의 체결에서도
관찰되므로 Phase 2에 배정하지만, 외부 제출 전 durable 기록 순서(`SAFE-11`)와 제출 결과의 불명확성(`SAFE-10`)은 실제
전송 상태가 있어야 관찰되므로 Phase 3에만 배정한다.

SaaS와 다중 tenant 요구는 이 표의 미할당 항목이 아니라 명시적 비범위이며 별도 프로그램 승인 없이는 추가하지 않는다.

## 9. 전체 완료 조건

### 9.1 Stage A software completion

Phase 2의 필수 outcome과 종료 조건이 merged 결과에서 충족되면 `STAGE_A_SOFTWARE_COMPLETE`다. 이 상태는 software
milestone이며 candidate/evidence 상태를 자동으로 승인하지 않는다. Phase 2가 이 milestone의 단일 상세 정본이다.

### 9.2 PRIVATE LIVE code completion

Phase 3의 필수 outcome과 종료 조건이 merged 결과에서 충족되면 `PRIVATE_LIVE_CODE_READY`다. 이 상태는 activation
승인이나 실제 계정 검증을 의미하지 않는다. Phase 3이 이 milestone의 단일 상세 정본이다.

### 9.3 PRIVATE LIVE active completion

`PRIVATE_LIVE_ACTIVE_COMPLETE`는 software commit만으로 선언할 수 없다. 동일한 승인 candidate에 대해 다음이 확인돼야 한다.

- `DONE-1` §4.5에서 LIMITED 완료까지 적용되는 모든 gate가 PASS이고 UNKNOWN 또는 만료가 없다.
- `DONE-2` 제한된 실제 실행이 승인된 risk budget 안에서 수행됐다.
- `DONE-3` 주문, fill, fee, funding, balance와 position의 내부·외부 기록이 대조됐다.
- `DONE-4` unresolved order와 설명되지 않은 residual exposure가 없다.
- `DONE-5` 중단과 operator fallback이 준비돼 있다.
- `DONE-6` redacted evidence와 프로젝트 상태가 정본 문서에 동기화됐다.

위 조건을 충족하면 activation을 `PRIVATE_LIVE_ACTIVE_COMPLETE`, program을 `PROGRAM_COMPLETED`로 함께 기록한다. bounded
LIMITED가 이 프로그램의 V1 성공 종점이다. `PROGRAM_COMPLETED`는 영구 주문 권한을 뜻하지 않으며 이후 activation 근거의
유효성 변화는 `LIVE-10`을 따른다.

### 9.4 안전한 NO_GO 종결

- `NOGO-0` 프로그램 개시 시 승인한 비용·기간 상한이나 evidence 수집 기간의 재평가 시점에 도달하면 계속·축소·종결을
  명시적으로 재평가한다. 상한값과 재평가 시점은 결과를 보기 전에 승인하며, 재평가를 수행하지 않은 채 프로그램을
  계속하지 않는다.

전략 경제성, 데이터 품질, 법률·거래소 자격, 환경 비용 또는 실제 검증이 허용 범위를 충족하지 못하면 사용자는 종결을
결정할 수 있다. 열린 위험이나 정리 작업이 남아 있으면 먼저 `PROGRAM_TERMINATION_PENDING`으로 전이한다. 이 전이는
§4.3에 따라 activation을 `ACTIVATION_RECOVERY_ONLY`로 함께 내리며, 종결 중 허용되는 제출도 `LIVE-11`의 `RECOVERY-A`와
`RECOVERY-B` 정의를 그대로 상속한다. `NOGO-1`의 credential·activation 폐기는 종결 시점의 최종 조건이지 진입 시점의
유일한 차단 수단이 아니다.

이 상태는 LIVE 제품 성공이 아니지만 다음을 만족하는 안전한 프로그램 종료다.

- `NOGO-1` credential과 activation이 폐기 또는 비활성화됨
- `NOGO-2` 열린 order와 전략 귀속 exposure가 없음
- `NOGO-3` 실패 근거와 재개 시 필요한 재승인 범위가 기록됨
- `NOGO-4` SIMULATION/PAPER 산출물의 보존·폐기 결정이 기록됨

잔여 exposure를 owner가 수동으로 인수했더라도 신규 자동 실행을 차단하고 정리가 확인되기 전에는
`PROGRAM_TERMINATED_NO_GO`를 선언하지 않는다.

## 10. 재승인 조건

다음 변경은 해당 Phase의 구현 판단만으로 진행하지 않는다.

- single owner, BTC 또는 Bithumb/Binance 범위 변경
- 한국 spot과 해외 선형 perpetual이라는 leg 상품 구성 변경
- 자동 입출금·송금·wallet transfer 추가
- SaaS, 다중 account 또는 자동매매 권한의 공개 제공
- credential을 private execution/reconciliation 경계 밖에서 사용
- SIMULATION/PAPER/SHADOW 검증 없이 PRIVATE LIVE로 승격
- default-disabled 또는 host-local activation 원칙 완화
- host-local 긴급 중단 보장의 완화
- 평가 결과를 본 뒤 viability policy 변경
- 정본 identity 범위, 최소 수집 계약 또는 evidence 산입 규칙의 축소
- actual credential이나 exchange order를 CI/test에서 사용
- 자체 runner, 배포 workflow, 별도 cryptographic integrity 체계 도입
- 실제 canary 또는 LIMITED activation 시작·재개
- 승인된 bounded LIMITED의 자본·notional·leverage·exposure·손실 범위 확대

## 11. Master specification 변경 조건

이 문서는 다음이 바뀔 때만 수정한다.

- 제품 목표·비범위
- 사용자 보장 또는 안전 불변식
- 시스템 간 책임 경계
- Phase의 outcome·의존성·프로그램 완료 상태

구현 파일, 내부 타입, 테스트 도구, migration 세부와 PR 분할 변경은 Phase 문서와 DoD에서만 처리한다. 이 경계를 지키는
것이 동일 계약을 master와 Phase가 중복 소유해 반복 변경되는 문제를 방지한다.
