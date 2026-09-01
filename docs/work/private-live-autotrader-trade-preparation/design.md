# 거래 준비 (Trade Preparation) — 스펙

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ④ 스펙 문서 |
| slug | `private-live-autotrader-trade-preparation` |
| base branch | `dev` (`67bc948`) |
| branch | `feat/trade-preparation` |
| 상위 스펙 | [`../private-live-autotrader/design.md`](../private-live-autotrader/design.md) |
| 근거 산출 | [`../private-live-autotrader/eco-5-capital-cycle.md`](../private-live-autotrader/eco-5-capital-cycle.md) |
| 상태 | `DRAFT` — ⑥ 스펙 리뷰 · ⑦ 사용자 승인 전 |

## 0. 이 단위의 성격

상위 spec §5 roadmap 상 **Phase 3 outcome을 앞당긴 단위**다. 재순서화 근거는
`.ai/planning/private-live-autotrader/progress.md` 2026-08-26 절이 소유한다.

당긴 이유는 계획 검토(`#68`)가 지적한 **Phase 3 집중**이다. 목표 능력(실제 주문)을 만드는 단위가
Phase 3 하나뿐이고 전체 outcome의 45%를 차지하며 가장 마지막에 있어서, 그 전에 중단하면 목표 대비
회수가 0이다. 상태축에 중간 성취가 나타나지 않는 것도 같은 원인이다.

이 단위는 **주문을 제출하지 않는다.** 실행 능력의 얇은 수직 경로를 만들어 중간 성취를 만든다.

## 1. 목표와 범위

### 1.1 목표

owner가 자기 실계정 잔고로 **지금 얼마나 잡을 수 있는지**를 알고, **희망 프리미엄을 걸어두면
그 조건이 충족될 때 실행 가능 상태로 전이**되는 것까지를 제공한다.

단, `ARMED` 전이는 verified 잔고 결속을 요구하므로(D9·D19) verified 원천이 없는 production에서는
`WATCHING` + 조건 충족 관측까지가 도달 상태다. `ARMED`를 포함한 전 사슬의 code-ready는
recorded fixture로 검증하며(`P3-O3`), production의 `ARMED`는 `ACT-2` 이후 같은 코드로 열린다.

### 1.2 포함

| | 근거 |
|---|---|
| 실잔고 조회 계약 (Domain port, 표시용/판정용 2단) | `P3-O2` read-only reconcile |
| 잔고 스냅샷 — 조회 시각·출처·`balanceBasis` 보존 | `SAFE-3` freshness 지속 감시 |
| 사이징 산출 — `R` → `L` → `Q` | `ECO-5` 관계식 |
| 캡 판정 — 레버 7배 · 효율 60% · 강제청산 거리 | `ECO-5` · `SAFE-7` |
| 직전 포지션의 진입 프리미엄과 현재 gap 표시 | 재진입 참조값 |
| owner 희망 프리미엄을 받는 조건부 계획 (durable) | `SAFE-11` durable 기록 |
| 프리미엄 조건 평가와 무장 상태 전이 | `P3-O3` decision·intent 생성 |
| 보유 `ACTIVE` tracking 존재 시 거절 | D13 · `SAFE-9` |
| 무효화 producer — 체결(동일 트랜잭션)·주기 reconcile Job | D17 · `P3-O17` |

### 1.3 제외 *(scope creep 차단선)*

| 제외 | 어디로 |
|---|---|
| **주문 제출과 durable intent 전송** | `SAFE-11` · `P3-O5` |
| **진입 판정 규칙** (프리미엄이 좋은지 판단) | Phase 1 `P1-O1` 이후. owner가 숫자를 준다 |
| **보유 중 감시와 청산 방어** | `SAFE-7` · `P3-O16`. 별개 실행 경로 |
| **실거래소 어댑터 구현** | `ACT-2` 이후. port만 정의 |
| **credential 관리·egress 경계** | `P3-O1` · `ACT-2` |
| **경제 엔진** (순손익·비용 모델) | Phase 1 `ECO-1` |
| **전략 판정 자동화** | Phase 3 본체 |

## 2. 결정

각 결정은 버린 대안과 그 이유를 함께 기록한다.

### D1. 잔고 조회를 Domain port로 분리하고 어댑터를 둘 둔다

`BalanceReadPort` 하나를 Domain이 소유하고 구현을 둘 둔다.

- `DeclaredBalanceAdapter` — 요청에 담긴 owner 신고값. 지금 사용
- `ExchangeBalanceAdapter` — 거래소 private API read-only. Phase 3 / `ACT-2` 이후

**버린 대안 ①: owner 신고값만 받는다.** "정말 계정에 있는지 확인"이라는 이 기능의 존재 이유를
포기한다. 나중에 실조회를 넣을 때 도메인을 고쳐야 한다.

**버린 대안 ②: 실계정을 바로 읽는다.** `P3-O1`·`P3-O2`와 `ACT-2` credential 승인이 선행돼야 하고,
저장소에 거래소 private API 어댑터가 아예 없다. Phase 3 전체를 당기는 셈이다.

**채택 근거.** `P3-O3`이 "code-ready 판정은 fake·recorded account로 검증하고 실제 계정 SHADOW는
`ACT-3`에서 수행한다"고 이미 이 경로를 규정한다. `.ai/rules/architecture.md`의 "비즈니스가 필요로
하는 capability를 Domain port로 정의한다"와도 일치하며 기존 `TrackingRepository`·
`LatestMarketTickReadPort`가 같은 형태다.

### D2. 잔고 읽기를 표시용과 판정용 2단 계약으로 나눈다

| 계약 | 캐시 | 용도 |
|---|---|---|
| 표시용 | 허용 | 화면·계획 표시. `observedAt`과 `balanceBasis`를 응답에 실는다 |
| 판정용 | **불가** | exposure를 늘리는 결정 직전에만. 그 스냅샷 id에 계획을 결속 |

같은 port의 두 메서드가 아니라 **타입이 다른 두 계약**이다. 판정용을 캐시에서 읽을 수 없게 막는다.

**버린 대안: 단일 계약 + 짧은 TTL.** TTL 값을 정할 근거가 없고, 낡은 잔고가 exposure를 승인할
경로가 열린다. 잔고가 낡아 주문이 거절되면 한쪽 leg만 체결돼 비헤지 노출이 된다 —
`SAFE-9`가 금지하는 상태다.

**채택 근거.** `P3-O17`이 "exposure-increasing 제출 **직전에** drift를 검증한다… 제출은 검증에
사용한 configuration version에 **결속**하며 그 version이 바뀌면 전송 전 제출도 무효다"라고 규정한다.
**계획이 권위가 아니라 제출 직전 검사가 권위**이므로 계획 단계의 캐시는 안전하다.

호출량은 제약이 아니다. Binance USDT-M `REQUEST_WEIGHT` 한도가 분당 2,400이고 잔고 조회 weight가
5이므로 분당 480회가 가능하다. §1.2가 단일 owner·단일 계정쌍으로 범위를 못박으므로 다중 사용자
직관을 적용하면 과하다. (빗썸 한도는 미확인 — ⑥ 이전에 확인한다.)

### D3. `STALE` 잔고는 조회에서 라벨링하고 exposure 증가에서 fail-closed한다

- 조회 요청 — 계획을 반환하되 `balanceBasis`를 실어 낡음을 드러낸다
- exposure를 늘리는 요청 — 거절한다

**버린 대안 ①: 항상 거절.** 마지막 관측값이 추적 도구에서 유용한데 그걸 버린다.
**버린 대안 ②: 낡으면 그 요청에서 실조회로 승격.** 지연이 들쭉날쭉해지고 외부 장애가 곧 기능 장애가 된다.

**채택 근거.** Phase 0이 같은 판단을 이미 했다 — `priceBasis`에 `STALE_MARKET`을 추가하며
"값을 감추지 않는 이유는 마지막 관측값이 추적 도구에서 여전히 유용하기 때문이고, 라벨을 붙이는
이유는 그것을 현재 시세라고 부르면 거짓이기 때문"이라고 적었다. exposure 증가 쪽 fail-closed는
`SAFE-3`·`SAFE-9`가 요구한다.

### D4. 계획 무효화는 사건 기반이다

시간 만료가 아니라 아래 사건에서 무효화한다.

- 우리 체결
- owner의 명시 refresh
- 주기 reconcile이 불일치를 발견

시계 만료는 없다. 초안에 있던 백스톱 문장은 AC6(시간 경과만으로는 무효화하지 않는다)과 모순해 D15에서 삭제했다.

**버린 대안: TTL 만료.** flat 상태에서 잔고를 바꾸는 것은 owner의 수동 이동뿐이고, 그것은
`ECO-5`가 산출한 재배치 주기(중앙 7~9 사이클)에 한 번 일어난다. 그 사이 시계로 만료시킬 근거가
없다. §4.5가 "근거 없는 기본 숫자를 고정하지 않는다"고 못박는다.

**관찰 근거.** 거래 준비는 **포지션이 없을 때** 호출된다. 그 상태에서 바이낸스 가용 증거금은
미실현손익이 없어 안정적이고, 빗썸 원화도 현물이라 고정이다. 잔고가 시계로 낡는 것이 아니라
사건으로만 변한다. 가격에 따라 계속 변하는 것은 **보유 중**이며 그것은 §1.3이 제외한 별개 경로다.

### D5. 계획은 durable하며 잔고 스냅샷에 결속된다

거래 준비의 산출물은 식별자를 갖는 durable 기록이다. 잔고 스냅샷 id에 결속되며 스냅샷이
무효화되면 계획도 무효다.

**버린 대안: stateless 계산 후 반환.** owner가 승인한 계획과 실행되는 계획이 달라질 수 있다.
잔고가 그 사이 변하면 재계산 결과가 다르다.

**채택 근거.** `P3-O4`가 "실제 제출은 **명시적 activation**"을, `ACT-3`가 "현재 candidate·account·
risk budget에 대한 **별도 사용자 승인**"을 요구한다. 승인 대상이 특정돼야 한다.
단 이것은 `SAFE-11`이 말하는 **durable intent가 아니다** — intent는 제출 직전 단계이고 이 설계는
그 앞에서 멈춘다.

### D6. 진입 판정 규칙을 제외하고 owner의 희망 프리미엄을 입력으로 받는다

거래 준비는 "지금 들어가야 하나"를 판단하지 않는다. owner가 희망 프리미엄 값을 준다.
직전 포지션이 있으면 그 진입 프리미엄과 현재 gap을 함께 제시해 owner의 판단을 돕는다.

**목표 프리미엄은 둘이다** (owner 확인 2026-08-30). 초안은 이를 구분하지 않아 T2 구현에서 부등호
방향이 뒤집혔다 — 5라운드 스펙 리뷰가 잡지 못한 공백이라 여기 명시한다.

| 값 | 예 | 뜻 | 소유 |
|---|---|---|---|
| **진입 목표 프리미엄** | 1.5% | 이 값 **이하**로 내려오면 진입 조건 충족 | **이 단위** (`desiredEntryPremiumRate`) |
| **종료 목표 프리미엄** | +1.0%p | 진입가 대비 상대값. 예의 경우 2.5%에서 종료 | 이 단위 밖 — 열린 포지션(`Tracking`)의 속성 |

**조건 판정은 `currentPremiumRate <= desiredEntryPremiumRate` 다.** 방향의 근거는 상위 spec §1.2
("낮은 executable premium에서 후보 진입, 높은 executable premium에서 후보 종료")다. 종료 직후
프리미엄은 진입가+1.0%p로 **높으므로**, 재진입하려면 진입 수준으로 **내려와야** 한다.

**등록 시점에 이미 목표 이하이면 즉시 충족이다.** 종료 목표가 진입가 대비 상대값이므로 0.8% 진입 →
1.8% 종료로 사이클이 성립한다. 목표보다 유리한 값을 거부할 이유가 없고, 교차(crossing)를 요구하면
상태만 늘고 얻는 게 없다.

**버린 대안: 시스템이 임계값을 정한다.** 임계값을 유도할 데이터가 없다. `ECO-5` 산출에서 확인된
대로 1%p 갭이 열리는 빈도와 사이클의 달력 주기가 미상이며, 그것은 `P1-O1` 최소 수집 계약이
채운다. 근거 없는 숫자를 박는 것은 §4.5 위반이다.

**부수 효과.** 정책(owner의 숫자)과 기제(조건 감시)가 분리되므로 수집을 기다리지 않고
이 단위를 완결할 수 있다. 나중에 그 자리에 전략 엔진을 끼우면 `P3-O3` SHADOW가 된다.

### D7. 조건 충족 시 무장 상태로 전이하고 owner 확인을 기다린다

프리미엄 조건이 충족되면 계획을 **무장(실행 가능)** 상태로 전이하고 멈춘다. 주문을 제출하지 않는다.

**버린 대안 ①: 알림만.** intent 생성과 무장 상태를 만들지 않으므로 Phase 3이 재사용할 몫이 적다.

**버린 대안 ②: 자동 체결.** `SAFE-11`(durable intent 이후 제출)·`P3-O5`(중복·응답 불명·event gap·
부분 체결·재시작 복구)·`P3-O4`(authorization epoch 결합)가 통째로 들어온다. 그것은 Phase 3 본체이며
이 단위를 "얇은 수직 경로"가 아니게 만든다. 또한 `P3-O4`의 명시적 activation과 `ACT-3` 승인 없이는
어차피 켤 수 없다.

**채택 근거.** `P3-O3`이 "SHADOW가 read-only account 상태로 **decision과 intent를 생성하되 주문
제출은 구조적으로 비활성화**한다"고 규정한다. D7이 그 골격과 같은 모양이며, 차이는 전략 판정
자리에 owner의 숫자가 있다는 점뿐이다. 버려지는 디딤돌이 아니라 Phase 3이 필요한 것을 미리 만든다.

```
이 단위    owner 희망 프리미엄 → 조건 평가 → 계획 무장 → owner 확인
Phase 3    전략 판정          → 조건 평가 → intent   → epoch 자율 제출
                ↑ 여기만 교체          ↑ 재사용        ↑ ACT-3 이후
```

### D8. 참조하는 직전 포지션은 최근 **종료된** 것이다

거래 준비가 제시하는 "직전 포지션의 진입 프리미엄과 현재 gap" 은 **가장 최근에 종료된** 추적을
가리킨다. 보유 중인 포지션이 아니다.

**버린 대안 ①: 보유 중 포지션을 참조한다.** 종료 판단용 gap 을 보여주는 용도가 되는데, 그것은
§1.3 이 제외한 "보유 중 감시" 경로다.

**버린 대안 ②: 둘 다 지원한다.** 두 상태에서 같은 필드가 다른 의미를 갖게 되어 응답 계약이
모호해진다. Phase 0 이 `priceBasis` 로 확립한 "필드가 자기 근거를 말한다" 원칙에 반한다.

**채택 근거.** 거래 준비는 **현재 준비금**(구매 가능 원화·USDT)을 기반으로 구성된다.
준비금이 온전히 가용하다는 것은 포지션이 없다는 뜻이므로, 이 요청의 정의상 보유 중 상태가 아니다.
D4 가 "flat 상태에서 잔고는 사건으로만 변한다" 를 무효화 근거로 삼은 것과 같은 전제다.

따라서 직전 포지션은 **재진입 참조값**으로만 쓰인다 — owner 의 원래 설명대로
"목표 진입 프리미엄(거의 대부분 직전 진입 프리미엄)" 을 정하는 근거다.
보유 중인 포지션이 있으면 거래 준비 자체가 성립하지 않으며, 그 경우의 거동은 `TP-OPEN-6` 이다.

### D9. 신고 잔고는 `VerifiedBalance`를 만들 수 없다

**1R ISSUE-1 반영.** D1·D2 초안은 `DeclaredBalanceAdapter`가 판정용 잔고까지 공급할 수 있게 두어,
owner가 임의·낡은 숫자를 넣어도 `ARMED`에 도달하는 경로를 남겼다. 실제 자본이 없는 계획이 durable하게
남고 이후 실행 단계가 그것을 재사용한다.

어댑터별로 만들 수 있는 타입을 고정한다.

| 어댑터 | `BalanceSnapshot` (표시용) | `VerifiedBalance` (판정용) | 언제 |
|---|---|---|---|
| `DeclaredBalanceAdapter` | `UNVERIFIED` | **만들 수 없다** | 지금 |
| `RecordedBalanceAdapter` | `FRESH`/`STALE` | 가능 | 지금 — 테스트 fixture |
| `ExchangeBalanceAdapter` | `FRESH`/`STALE` | 가능 | `ACT-2` 이후 |

`BalanceBasis`에 `UNVERIFIED`를 추가한다. `VerifiedBalance` 생성자는 Domain 내부에 감추고
`UNVERIFIED` 스냅샷을 입력으로 받으면 만들지 않는다. 이 경계를 자동 테스트로 고정한다.

**결과.** declared 입력만으로는 `WATCHING`까지만 갈 수 있고 `ARMED`는 불가능하다.
`P3-O3`이 "code-ready 판정은 **fake·recorded account로 검증**"이라고 규정하므로, `ARMED` 경로의
code-ready 판정은 `RecordedBalanceAdapter`로 수행한다. 실계정 `ARMED`는 `ACT-3`에서다.

**버린 대안: 이 단위의 목표를 신고값 기반 계산으로 축소한다.** §1.1의 "실계정 잔고로" 라는 목표를
포기하게 되고, 얇은 수직 경로가 만들려던 중간 성취가 사라진다.

### D10. owner는 인증 principal에서 도출하고 모든 조회를 owner-scoped로 한다

**1R ISSUE-2 반영.** 초안은 인증만 요구하고 **객체 단위 인가**를 정의하지 않았다. durable 레코드에
owner가 있고 ID로 조회하는데, 일반 로그인 회원이 남의 계획을 조회·무장시킬 경로가 열려 있었다.
상위 `P3-O12`(다른 회원이나 account가 자동매매 권한을 얻지 않는다)를 충족하지 못한다.

- owner는 **요청 값이 아니라 인증 principal**에서 도출한다. 요청 body의 owner 필드는 받지 않는다
- 모든 ID 조회·변경은 owner-scoped repository query로 한다. Phase 0의
  `findOwnedByIdForUpdate(id, memberId)` 패턴을 그대로 쓴다
- 남의 계획에 대한 조회·변경은 **존재를 노출하지 않는 404**다. 403은 존재를 알려준다
- V1은 단일 owner이므로(§1.2) 허가된 owner가 아닌 회원의 생성 요청도 거절한다

**버린 대안: 403 반환.** 계획 ID의 존재 여부를 노출한다. Phase 0이 `TRACKING_NOT_FOUND`로
소유·존재·삭제를 구분하지 않기로 한 것과 같은 판단이다.

### D11. 상태 전이를 version으로 선형화하고 `INVALIDATED`를 종점으로 둔다

**1R ISSUE-3 반영.** 초안은 무효화 endpoint가 REST 표에 없었고, evaluator·refresh·reconcile 3자가
같은 계획을 동시에 갱신할 때의 규칙이 없었다. evaluator가 `WATCHING`을 읽은 뒤 refresh가 무효화해도
evaluator가 뒤늦게 `ARMED`를 쓰는 lost-update 경로가 가능했다.

- 계획에 `version`을 두고 모든 상태 전이를 **조건부 update**로 한다.
  `WHERE id = ? AND version = ? AND status = ?` 형태이며 영향 행 0이면 재시도하거나 포기한다
- **`INVALIDATED`는 종점이다.** 어떤 경로로도 `ARMED`로 되돌아가지 않는다
- 무효화 endpoint를 신설한다 — owner refresh와 명시 무효화
- 기존 `WATCHING` 계획의 식별 규칙을 정한다. owner당 `WATCHING`은 최대 하나이며 새 계획이
  `WATCHING`이 되면 이전 것은 무효화된다

**버린 대안: 비관적 행 잠금.** evaluator가 주기적으로 도는 경로라 잠금 보유 구간이 길어진다.
Phase 0이 archive에서 비관적 잠금을 쓴 것은 단발 요청이라 다르다.

### D12. durable 계획은 재현에 필요한 provenance와 반올림 후 재판정을 보존한다

**1R ISSUE-4 반영.** `ECO-5` 식은 `F`·`X`·`P`·`K`를 전제하는데 초안의 계획 레코드는 두 잔고와
산출값만 담아 나중에 재현할 수 없었다. 거래소 lot/step-size 반올림도 없어 반올림 후 양 leg 수량이
달라지면 헤지가 깨진다.

계획이 보존하는 것.

- `MarketPair` (`.ai/rules/architecture.md` identity 보존)
- 해외가·FX·프리미엄의 snapshot id·관측 시각·출처
- Decimal scale — 공통 경제 엔진의 단위·스케일 규칙을 따른다 (`ECO-3`)

반올림 규칙.

- 각 거래소의 lot size·step size·최소 주문 수량을 적용한다
- **보수적 방향으로 반올림한다** — 물량을 늘리지 않는다
- **반올림 뒤 `Q`·`L`·캡을 다시 판정한다.** 반올림이 캡을 넘기면 계획을 만들지 않는다
- 양 leg 수량이 반올림 후에도 같은지 확인한다. 다르면 작은 쪽에 맞춘다

거래소별 lot/tick 값은 설정으로 받으며 이 문서가 숫자를 정하지 않는다.

### D13. 보유 `ACTIVE` tracking이 있으면 거래 준비를 거절한다

**2R ISSUE-2 반영 · `TP-OPEN-6` 해소 (owner 승인 2026-08-28).**

owner의 `ACTIVE` tracking이 존재하면 `prepare`와 `registerTarget` 둘 다 명시 오류로 거절한다
(fail-closed). 검사는 `prepare` 한 번으로 끝나지 않고 **`registerTarget`(무장 경로 진입)에서 다시**
수행한다 — `prepare` 통과 직후 tracking이 생기는 교차가 가능하기 때문이다.

**버린 대안 ①: 정보만 반환.** "조회는 되는데 저장은 안 되는" 상태 분기가 API에 하나 더 생긴다.
보유 중 미리보기가 필요해지면 read-only endpoint로 나중에 추가할 수 있다.
**버린 대안 ②: 허용.** 보유 중 잔고 상당분이 기존 포지션의 증거금인데 신고값 기반이라 전액 가용으로
계산한다. 그 계획이 `ARMED`까지 가면 이중 포지션 — 자본이 헤지를 지지하지 못하는, `SAFE-9`가
금지하는 상태다.

**채택 근거.** owner의 운영 모델이 순차 사이클이고(진입 → 수익실현 → 재진입), `ECO-5` 산출도
owner당 포지션 하나를 전제로 세웠다. D8("준비금이 온전히 가용")과도 일관된다.

### D14. premium 신선도 — 양방향 유계·pair 일치·fail-closed

**2R ISSUE-4 반영 · `TP-OPEN-4` 해소 (owner 승인 2026-08-28).**

`WATCHING` → `ARMED` 전이는 조건값 도달만으로 성립하지 않는다.

```
armable = inBounds(premium.observedAt, now, MAX_AGE)   // 0 ≤ now − observedAt ≤ MAX_AGE
        && premium.marketPair == plan.marketPair
```

- **양방향 유계** — Phase 0의 `inBounds` 패턴 재사용. 과거로 낡은 값뿐 아니라 생산자 clock skew의
  **미래 시각**도 거른다 (Phase 0 15R에서 미래 시각이 "신선"으로 통과하던 결함의 재발 방지)
- **`MAX_AGE`는 수집 계약에서 유도** — 배치의 "관측값이 10초보다 오래되면 seconds 기록 중단" 계약에서
  유도한 값을 설정으로 받는다. 근거 없는 숫자를 이 문서가 정하지 않는다 (§4.5)
- **stream unavailable이면 평가를 멈춘다.** `ARMED` 불가. 계획은 무효화되지 않고 `WATCHING`으로
  남았다가 stream 회복 시 재개된다
- **`MarketPair` 불일치는 miss다.** 다른 pair의 프리미엄으로 보정하지 않는다 (`.ai/rules/architecture.md`)

**버린 대안: 조건값 도달만 검사.** 멈춘 stream의 마지막 값으로 `ARMED`가 된다. owner가 확인을
누르는 순간이 곧 잘못된 진입이고 `SAFE-3` 위반이다.

### D15. `ARMED`는 무기한이며 시계가 없다

**2R ISSUE-3 일부 반영 · `TP-OPEN-3` 해소 (owner 승인 2026-08-28).**

`ARMED` 계획은 owner 확인이 올 때까지 유지된다. 시계 만료가 없고 무효화 사건(D4)에만 종속된다.

**근거: `ARMED`는 실행 권한이 아니다.** 권위는 항상 제출 직전 검사에 있다 (`P3-O17`). owner가
확인을 눌러 실제 실행으로 가는 단계(이번 범위 밖)에서 잔고·신선도·캡을 다시 검증하므로, 낡은
`ARMED`는 그 검사에서 떨어질 뿐 위험을 만들지 않는다.

**버린 대안: N분 만료.** N의 근거가 없고(§4.5), D4의 사건 기반 원칙과 충돌한다. 이 결정으로 D4의
"시계 백스톱" 문장을 삭제해 AC6과 문서를 한쪽으로 통일했다 — **시계는 어디에도 없다.**

### D16. owner당 `WATCHING` 유일성은 DB unique index가 강제한다

**2R ISSUE-1 반영 (owner 승인 2026-08-28).**

D11의 version 조건부 update는 한 행의 lost update만 막는다. 유일성은 두 행에 걸친 불변식이라,
서로 다른 `DRAFT` 두 개가 동시에 target 등록하면 둘 다 "기존 `WATCHING` 없음"을 관찰하고 각자
성공하는 **phantom 경쟁**이 남는다. 애플리케이션 코드로는 직렬화 격리 없이 막을 수 없다.

```sql
active_key BIGINT AS (CASE WHEN status IN ('WATCHING','ARMED') THEN owner_id END) STORED,
UNIQUE KEY uk_trade_preparation_owner_active (active_key)
```

*(초안은 `WATCHING`만 묶었으나 D23이 활성 범위로 확장했다.)*

MySQL은 partial index가 없어 generated column을 쓴다. `WATCHING`이 아니면 `NULL`이고 `NULL`은
unique 검사에서 제외된다.

- 정상 경로: 한 트랜잭션에서 기존 `WATCHING`을 무효화한 뒤 새 계획을 승격한다 (D11)
- 경합 경로: 진 쪽이 constraint violation을 받고, 애플리케이션이 "이미 감시 중인 계획이 있다"
  오류로 변환한다. Phase 0의 동시 archive 계약(정확히 1건 성공)과 같은 모양이다

**버린 대안: owner 단위 잠금 프로토콜.** 코드가 틀리면 불변식이 깨진다. 스키마 강제는 코드와
무관하게 성립하고 검증도 단순하다.

### D17. 무효화 producer를 이번 범위에 넣는다

**2R ISSUE-3 반영 (owner 승인 2026-08-28 — 대안 A 선택).**

D4의 trigger 셋 중 둘은 실행 주체가 없었다. 함수가 존재한다는 것과 불린다는 것은 다른 주장이다.
producer를 둘 명시한다.

| trigger | producer | 방식 |
|---|---|---|
| 체결 | tracking 생성·archive 경로 (`TrackingFacade`) | **같은 DB 트랜잭션**에서 이 owner의 활성 계획을 무효화. 기존 "활성 구독 조회와 enqueue는 같은 transaction" 선례를 따른다 |
| 주기 reconcile | **T8 배치 Job 신설** | `WATCHING`·`ARMED` 계획의 결속 스냅샷 vs 현재 판정용 잔고를 대조, 불일치면 무효화. 기존 `JobExecutor`·typed `JobConfig`·Redis lock 계약을 따른다 |
| owner refresh | REST `refresh` endpoint (D11) | 기존 |

**declared 단계의 한계를 명시한다.** 지금 판정용 잔고의 출처가 recorded/declared 수준이므로
reconcile이 대조하는 데이터도 그 수준이다. **기제는 진짜지만 데이터는 아직 fake다.** 실데이터
대조는 `ExchangeBalanceAdapter`(`ACT-2` 이후)가 끼워지는 순간 같은 기제로 성립한다.

**버린 대안 B: 범위에서 빼고 `ARMED` 도달을 불가로 명시.** 보장 못 할 것을 약속하지 않는 정직한
축소지만, D7이 죽고 이 단위의 존재 이유(얇은 수직 경로의 중간 성취)가 반토막 난다. 또한 reconcile
Job은 Phase 3 `P3-O17`("주기 reconcile과 재시작 시 재평가")이 어차피 요구하므로 지금 만들어도
버려지지 않는다. 단 5라운드 상한 도달 시의 범위 축소 검토에서 B가 후퇴선이다.

### D18. owner 단위 직렬화 — 두 경로가 같은 잠금을 잡는다

**3R ISSUE-1 반영.** D13의 `ACTIVE` 재검사와 D17의 체결 무효화는 서로 다른 테이블을 읽고 자기
테이블만 쓴다. 등록 트랜잭션은 tracking에서 `ACTIVE` 없음을 관찰하고, 동시의 tracking 생성은
커밋 전의 `WATCHING`을 관찰하지 못한다 — 둘 다 커밋되면 공존한다 (write-skew). version 술어는
한 행을, unique index는 `WATCHING` 행끼리를 보호할 뿐 **교차 테이블 불변식은 어느 쪽도 못 지킨다.**

`registerTarget`과 tracking 생성·archive(체결 producer 경로)는 **트랜잭션 시작점에서 같은 owner의
member 행을 `SELECT … FOR UPDATE`로 잠근다.** 두 경로가 직렬화되어 나중 트랜잭션이 앞의 커밋된
상태를 반드시 본다.

- 잠금 순서는 항상 **member → tracking/plan** 이다. archive가 이미 잡는 tracking 행 잠금
  (`findOwnedByIdForUpdate`)보다 member를 먼저 잡아 교착을 막는다
- V1이 단일 owner라 이 잠금의 경합 비용은 무시할 수 있다 (§1.2)
- AC16 테스트는 유리한 순서가 아니라 **교차 순서를 강제한다** — 두 트랜잭션이 서로의 미커밋
  상태를 보지 못하는 시점을 고정한 뒤 둘 다 커밋을 시도하게 한다

**버린 대안 ①: SERIALIZABLE 격리.** MySQL gap lock 부작용이 무관한 경로까지 미치고 전역 비용이다.
**버린 대안 ②: 교차 테이블 제약.** DB 제약으로 표현할 수 없다.

### D19. production 도달 상태는 `WATCHING`까지다 — `ARMED`는 verified 결속을 요구한다

**3R ISSUE-2 반영.** D9의 귀결을 이 단위의 산출물 정의에 명시한다. declared만 존재하는
production에서 `ARMED` 도달 경로가 없다는 지적은 맞다 — 그리고 **그것이 올바른 상태다.**
그 경로를 지금 만들 방법은 이름만 바꾼 신고값뿐이고, 그것은 D9가 닫은 구멍을 다시 연다.

- **`ARMED` 전이는 `VerifiedBalance` 결속을 요구한다.** `UNVERIFIED` 결속 계획은 조건이
  충족돼도 상태가 바뀌지 않는다. 대신 관측 필드 `conditionFirstMetAt`(최초 충족 시각)와
  당시 프리미엄이 기록되어 owner가 조회할 수 있다 — **권한 없는 관측**이다
- **`registerTarget`의 잔고 규칙을 명확화한다.** verified 원천이 있으면 판정용 fresh 읽기이며
  `STALE`은 거절한다 (D3). 원천이 declared뿐이면 `UNVERIFIED` 결속을 허용한다 — watching은
  exposure를 만들지 않기 때문이다. **fail-closed 경계는 `ARMED`에 있다**
- 따라서 이 단위가 production에 제공하는 것은 *준비 계산 → `WATCHING` → 조건 충족 관측*까지이고,
  `ARMED`는 verified 원천(`ExchangeBalanceAdapter`)이 생기는 `ACT-2` 이후 같은 코드로 열린다
- **code-ready는 전 사슬(→`ARMED`)을 `RecordedBalanceAdapter`로 검증한다.** `P3-O3`이 규정한
  "code-ready 판정은 fake·recorded account로 검증" 그대로다. fixture가 계약을 증명하고
  production 원천은 gate가 연다

**버린 대안: production용 recorded balance 입력 경로 신설.** 인증된 owner가 값을 넣는 어떤
형태든 결국 신고값이다. codex도 같은 경고를 했다 ("declared data를 받으면 D9가 닫은 결함이
다시 열린다").

### D20. `registerTarget`은 판정용 호출이 아니다 — 검증 수준은 원천이 정한다

**4R ISSUE-1 반영.** D19를 design에만 넣고 plan T5의 "판정용 잔고 필요" 문장을 남겨 두 문서가
모순했다. 계약을 한쪽으로 고정한다.

`registerTarget`은 exposure를 만들지 않으므로 판정용(exposure-increasing) 호출이 아니다.
결속되는 잔고의 검증 수준은 **가용한 원천이 정한다.**

| 원천 | 동작 |
|---|---|
| verified 가능 (recorded·exchange) | 판정용 fresh 읽기. `STALE`이면 거절 (D3) |
| declared뿐 | `UNVERIFIED` 결속으로 등록 — 표시·관측 계약 |

이 단위 안에서 판정용 계약(`VerifiedBalance`)의 소비자는 **`ARMED` 전이 하나**다 (D19).
타입 경계는 그대로다 — declared에서 `VerifiedBalance`가 생기는 경로는 없다 (D9).

### D21. 조건 평가는 Domain capability + batch Application Job으로 나눈다

**4R ISSUE-2 반영.** T5는 상태 전이 유스케이스를 `apps:api` Facade에 두고 T7은 평가를
`apps:batch` Job으로 요구했는데, **앱 모듈은 서로 참조할 수 없다.** 평가 Job이 쓸 경로가 없었다.

`.ai/rules/architecture.md`의 Batch 계약(Scheduler → Application Job → Domain Port)대로 나눈다.

- **Domain** — `WATCHING` 계획 조회와 조건부 전이(arm / 관측 기록)를 Domain port·서비스로 소유한다.
  신선도 판정(D14)과 verified 결속 요구(D19)는 이 Domain 로직 안에 있다
- **`apps:batch`** — `TradePreparationEvaluationJob`이 최신 premium 읽기 port와 위 Domain
  capability만 주입받아 조합한다. `JobExecutor`·typed `JobConfig` 계약을 따른다
- **`apps:api`** — Facade는 owner 요청 유스케이스(prepare·registerTarget·refresh·invalidate·조회)만
  소유한다. 전이 로직을 중복 구현하지 않는다 — 둘 다 같은 Domain 서비스를 쓴다

AC17의 검증을 domain unit에서 **batch 통합(scheduler → Job → 전이)**으로 올린다. domain unit은
보조로 남는다.

### D22. Recorded fixture는 production 배선에서 구조적으로 배제된다

**4R ISSUE-3 반영.** D19의 안전성은 "production에는 declared만 존재"에 의존하는데,
`RecordedBalanceAdapter`가 main 영역에 있으면 그 전제가 배선 실수 하나로 무너진다.

- `RecordedBalanceAdapter`는 **test source set(testFixtures/integrationTest)에만 둔다.**
  main classpath에 존재하지 않으므로 production bean이 될 수 없다
- production 배선에서 `BalanceReadPort` 구현이 `DeclaredBalanceAdapter`뿐임을 context test로
  검증한다 (AC20)
- 선례: batch rules의 "`test` profile은 실제 stream bean 대신 port fallback을 사용한다" — 같은
  패턴의 역방향이다

**버린 대안: non-production profile 격리.** profile 설정 실수로 켜질 수 있다. source set 배제는
classpath 수준이라 설정으로 우회되지 않는다.

### D23. 유일성의 범위는 활성 계획(`WATCHING`·`ARMED`) 전체다

**5R ISSUE-1 반영.** D16은 `WATCHING`만 유일하게 묶었다. 그런데 D15에 따라 `ARMED`는 무기한
남으므로, `ACTIVE` tracking이 없는 동안 새 `DRAFT`를 등록·평가해 **같은 owner에 `ARMED`가 여러 개**
생길 수 있었다. 이후 확인·실행 단계가 어느 것이든 소비할 수 있어 같은 자본에 복수의 durable
실행 후보가 남는다.

- unique index의 범위를 활성 상태 전체로 넓힌다 — `active_key = owner_id WHEN status IN
  ('WATCHING','ARMED')`
- **기존이 `WATCHING`이면** 새 등록이 한 트랜잭션에서 그것을 무효화하고 승격한다 (D11 그대로)
- **기존이 `ARMED`면 새 등록을 거절한다** (`ARMED_PLAN_EXISTS`). `ARMED`는 owner가 확인을 앞둔
  의도적 산출물이라 새 등록이 조용히 대체하면 안 된다. owner가 명시적으로 refresh·invalidate한
  뒤에만 새 후보를 만들 수 있다
- 애플리케이션 규칙이 틀려도 index가 두 번째 활성 계획의 커밋을 막는다 — D16과 같은 심층 방어

**버린 대안: `ARMED`도 last-wins로 자동 무효화.** 규칙은 단순해지지만, 확인 직전의 계획이 새 등록
하나로 소리 없이 사라진다. fail-closed 원칙과 `ARMED`의 의도성에 어긋난다.

## 3. 사이징 관계식

`ECO-5` 산출 문서 §2가 정본이며 여기서 재진술하지 않는다. 요지만 옮긴다.

```
R = B_k / (X · B_b)          두 계정 잔고 비율
L = R / (1 + P)              레버리지는 독립 변수가 아니라 R 의 함수다
Q = B_k / K                  양쪽 100% 투입 시 물량
```

캡은 빗썸 비중으로 환산해 판정한다.

```
효율 캡 60%   → 하단
레버 캡 7배   → 상단 87.7%
강제청산 거리  ≈ 1 / L
```

**캡 값의 출처.** 레버 캡 7배는 owner 결정(`ECO-5` §7). 효율 캡 60%는 owner 결정.
초기 배분 권고 빗썸 79~81%도 같은 문서가 소유한다. 이 설계는 값을 정하지 않고 설정으로 받는다.

## 4. 미해결 결정

| # | 항목 | 상태 |
|---|---|---|
| `TP-OPEN-1` | 빗썸 private API 한도와 잔고 엔드포인트 형태 | 미해결 — ⑥ 종료 전 확인 |
| ~~`TP-OPEN-2`~~ | 직전 포지션 정의 | **해소 (2026-08-26)** — D8. 최근 종료된 것 |
| ~~`TP-OPEN-3`~~ | `ARMED`에서 owner 확인 없을 때 거동 | **해소 (2026-08-28)** — D15. 무기한 유지, 시계 없음 |
| ~~`TP-OPEN-4`~~ | 프리미엄 조건 평가 신선도 | **해소 (2026-08-28)** — D14. 양방향 유계·pair 일치·fail-closed |
| `TP-OPEN-5` | `leverageBracket` — 명목 구간별 최대 레버리지 | 미해결 — 실계정 조회 필요 |
| ~~`TP-OPEN-6`~~ | 보유 포지션 존재 시 거동 | **해소 (2026-08-28)** — D13. 거절 (fail-closed) |
| `TP-OPEN-7` | 거래소 lot/step size·최소 주문 수량 실제 값 | 미해결 — 설정으로 받는다. 공개 `exchangeInfo` 조회 |
| `TP-OPEN-8` | 종료 목표 프리미엄의 소유처와 표현 — 열린 포지션(`Tracking`)의 속성으로 보나 스키마·계약 미정 | 미해결 — 종료 경로를 만드는 단위가 결정 |

## 5. 상위 spec 추적성

이 단위가 부분적으로 충족하는 상위 outcome. **완전 충족이 아니며** 각 outcome의 잔여는 Phase 3에 남는다.

| 상위 ID | 이 단위의 몫 | 잔여 |
|---|---|---|
| `P3-O2` | read-only 잔고 조회 계약과 port | order·position reconcile, 실어댑터 |
| `P3-O3` | decision·intent 생성 골격, 제출 비활성 | 전략 판정, 실계정 SHADOW (`ACT-3`) |
| `P3-O17` | 판정용 스냅샷 결속·drift 검증·주기 reconcile Job (D17) | configuration snapshot |
| `SAFE-3` | 잔고 freshness 라벨과 fail-closed | 지속 감시, `FENCE` 전이 |
| `SAFE-9` | 자본 부족 시 신규 진입 차단 판정 | `FENCE` 동반 전이 |
| `ECO-5` | 항목 1·2 산출값의 소비 | 항목 3·4 |

**상태축을 전이시키지 않는다.** 이 단위는 어떤 Phase의 종료 판정도 선언하지 않는다.
