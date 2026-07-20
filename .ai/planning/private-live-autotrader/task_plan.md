# Premium Spread PRIVATE LIVE Autotrader Master Implementation Plan

> **Agent 실행 지침:** 각 Phase는 `work` 스킬을 기본 워크플로로 수행한다. Phase별 `phase-N-design.md`,
> `phase-N-plan.md`와 DoD가 승인·동결된 뒤 구현 단계에서 독립 `subagent-driven-development` 스킬로
> `lifecycle owner=work`, `mode=dispatch`를 전달해 task 분할·구현·Spec Review·Code Review를 수행한다. `work`는 설계·DoD,
> Phase acceptance commit/push, 최종 branch review·검증을 소유하고 독립 스킬은 구현 오케스트레이션만 소유한다. 동일
> 구현에 `superpowers:subagent-driven-development`를 함께 적용하지 않는다.
> 이 문서는 프로그램 전체의 범위·의존성·완료 조건을 고정하는 마스터 계획이다.
>
> **실행 기준:** 이 마스터 계획의 SSOT와 기준 소스는 주 작업트리의 최신 `dev`다. merge가 끝난
> `refactor/infrastructure-boundary` linked worktree는 후속 작업에 사용하지 않고, Phase별 새 branch/worktree는
> Phase -1과 9절의 순서·경계대로 생성한다.

**Goal:** 기존 Premium Spread 관측 플랫폼 위에 동일한 Kotlin 계산·실행 코어를 사용하는 `SIMULATION`, `PAPER`,
`PRIVATE_LIVE`를 순서대로 구축한다. 최종 제품은 한 명의 소유자와 한 쌍의 개인 거래소 계정만을 대상으로 실제 주문을
안전하게 집행하되, 실계좌 활성화는 법률·거래소·보안·전략·운영 게이트를 모두 통과한 별도 승인으로 제한한다.

**Architecture:** 기존 `apps → domain ← infrastructure` 경계를 유지한다. 공개 시장데이터와 전략 실행은
`apps:batch`와 `apps:strategy`가 담당하고, private exchange credential과 실주문은 신규 `apps:live-executor` 프로세스에만
존재한다. API/Strategy는 실주문 client를 직접 참조하지 않고 MySQL transactional outbox를 통해 실행 의도를 전달한다.
SIMULATION, PAPER, PRIVATE_LIVE는 같은 `StrategyEngine`, `RiskEngine`, `TradeCycle`, `PnlEngine`을 사용하며 adapter만
교체한다.

**Tech Stack:** Kotlin 2.0, Java 21, Spring Boot 3.5.16, MySQL 8, Redis 7, Testcontainers, Gradle multi-module,
Next.js, BigDecimal, JUnit 5

**V1 단순화 결정:** 애플리케이션이 직접 `raw/event/dataset/report` SHA나 배포 manifest digest를 계산·전파하지 않는다.
데이터와 실행 결과는 immutable ID, schema/version, ordered content 비교와 저장소의 version ID로 식별한다. Git commit ID,
OCI image digest, Gradle dependency verification, Flyway checksum처럼 플랫폼이 자체적으로 요구하는 무결성 정보는 그대로
사용하되 별도 애플리케이션 hash 체인을 만들지 않는다. GitHub Actions는 기존 GitHub-hosted Quality Gate만 사용하며
self-hosted/JIT runner, runner group, secret-bearing deploy workflow를 V1 범위에 넣지 않는다. 장기 soak와 testnet은
production 거래소 credential이 없는 validation host에서, migration·배포·production canary·activation은 PRIVATE_LIVE
host에서 operator가 host-local 명령으로 실행하고 append-only audit record를 남긴다. GitHub Actions에는 실제 exchange
credential을 제공하지 않는다.

---

## 0. 문서 상태와 핵심 판정

- 작성일 및 외부 자료 확인일: 2026-07-20 KST
- 소스 분석 기준: infrastructure-boundary merge가 반영된 주 작업트리의 최신 `dev`. 역사적 분석 commit은
  `2e75548e2dad5a2690a0ab6eec373e10aaf3562f`이며 구현 기준 commit은 Phase -1 `progress.md`에 새로 기록한다.
- 구현 시작 기준: linked refactor worktree가 아니라 최신 `dev`에서 만든 Phase별 feature worktree
- 현재 시스템: 프리미엄 관측·집계, 기본 비활성인 email threshold backend, 사용자가 입력한 포지션의 현재 snapshot 기반
  미실현 gross PnL 표시와 수동 상태 종료
- 목표 시스템: 개인 1인·거래소 계정 한 쌍·BTC 단일 전략의 제한된 PRIVATE_LIVE 자동매매
- 운영/스테이징: 현재 없음. Phase 9A 전에는 실주문을 활성화할 수 없으며, 고정 egress IP를 가진 private runtime을
  별도로 확보해야 한다.

### 0.1 가능성 판정

**결론은 `CONDITIONAL GO`다.** 기술 구현은 가능하지만 현재 즉시 LIVE를 켤 수 있다는 의미는 아니다.

| 판정축 | 결과 | 근거와 남은 조건 |
|---|---|---|
| 빗썸 현물 주문 자동화 | 가능 | 공식 API가 주문·취소·client order ID·private 체결 stream을 제공 |
| Binance USDⓈ-M 선물 주문 자동화 | 가능 | signed order/cancel/account/user-data API 제공 |
| 두 레그 원자적 체결 | 불가능 | 거래소 간 분산 transaction이 없으므로 partial fill·한쪽 실패·보상 주문이 필수 |
| 개인 본인 계정 사용 | 조건부 가능 | 자기 계산·자기 이익 거래는 원칙적으로 VASP로 보기 어렵다는 판례가 있으나 다른 법률·약관을 면제하지 않음 |
| 한국 거주자의 Binance 이용 | 미확정 | 계정·거주지·상품 자격과 당시 약관을 activation 시점에 공식 채널로 재확인해야 함 |
| PRIVATE_LIVE 운영 | 현재 activation NO-GO | 고정 IP, secret mount, alert/fail-closed·복구 계약, 전략 viability와 법률 검토가 아직 없어 지금 주문을 보낼 수 없다는 readiness 판정이며 최종 프로그램 상태는 아님 |
| 추후 SaaS | 별도 프로젝트 | 타인 계정·대가 수취·중개/대행은 개인용과 법적·보안·운영 경계가 달라 단순 feature flag로 확장 불가 |

### 0.2 제품 방향 결정

최종 제품 목표는 `SIMULATION + PAPER`에서 개인용 `PRIVATE_LIVE`로 변경한다. 다만 구현·운영 mode를 곧바로 `LIVE only`로
축소하지 않고 SIMULATION/PAPER를 삭제 불가능한 검증·회귀 gate로 유지하며 다음 승격 구조를 따른다.

```text
Stage A  SIMULATION → PAPER
         계산·데이터·상태 머신·실패 복구를 실제 자본 없이 증명
                           │ Gate A
                           ▼
Stage B  PRIVATE_LIVE_CODE_READY
         별도 executor, private read model, 실주문 adapter, reconcile, kill-switch 완성
                           │ Gate B: 별도 인간 승인
                           ▼
Stage C  PRIVATE_LIVE_ACTIVE
         shadow → Binance testnet → 최소 주문 canary → 제한된 자본 운영
```

Stage A는 폐기 가능한 임시 구현이 아니라 Stage B/C가 사용하는 실행 정본이다. Stage A를 통과하지 못하면 LIVE 코드를
활성화하지 않는다.

### 0.3 고정할 V1 거래 계약

- 소유자: `OwnerId` 한 명
- 계정: 소유자 본인의 Bithumb 계정 1개 + Binance 계정 1개
- 한국 leg: `KRW-BTC`, Bithumb spot
- 해외 leg: `BTCUSDT`, Binance USDⓈ-M linear perpetual
- 포지션: 한국 현물 long + 해외 선물 short
- 선물 margin mode: `ISOLATED`만 지원
- Binance position mode: `ONE_WAY`(`dualSidePosition=false`)만 지원
- symbol: BTC만 지원
- 자본 이동: 애플리케이션의 입금·출금·송금·wallet transfer API 사용 금지
- 초기 자금: 사용자가 각 거래소에 사전 배치하며 애플리케이션은 이를 이동시키지 않음
- 계정 사용: 두 V1 instrument는 이 프로그램이 독점하며 실행 중 수동 주문을 금지한다. activation baseline에서 양쪽
  open order와 Binance BTCUSDT position은 0이어야 한다. 기존 Bithumb BTC는 별도 non-strategy reserve baseline record로
  매도 가능 수량에서 제외하고, 이후 전략 fill로 귀속된 inventory만 청산할 수 있다.
- 운영자 인증: LIVE profile에서는 public registration을 비활성화하고 사전 지정된 operator member 한 명만
  `LIVE_OPERATOR` 권한을 가짐
- LIVE 민감 명령: account binding, risk budget 생성/변경, activation record 생성과 ARM은 operator OS identity의
  host-local Live Executor `control` entrypoint만 수행한다. API는 status·DISARM·KILL만 제공하며 risk-reducing
  DISARM/KILL은 로그인한 operator가 추가 인증 없이 실행할 수 있음
- SaaS: 다중 owner, 사용자별 API key 입력, 타인 자금, billing, 수익 배분은 구현하지 않음

이 계약을 Upbit, cross margin, 다중 symbol 또는 다른 선물 거래소로 바꾸는 것은 계획 변경 승인 사유다.

### 0.4 V1 보안 적정화 결정

V1의 위협 모델은 개인 owner 한 명, 전용 PRIVATE_LIVE VM 한 대, 외부 고객·내부 운영팀·규제형 non-repudiation 요구가
없는 환경이다. 우선 방어 대상은 exchange credential 유출, public ingress 침해, 중복/불명 주문, operator 실수와
process/host 장애다. 다중 tenant·악의적 내부 관리자·multi-region HA는 SaaS 또는 운용 자본 확대 때 다시 설계한다.

**V1에 유지하는 통제:** private order client의 Live Executor 격리, runtime UID만 읽는 mounted secret, 고정 egress IP,
출금/이체 권한 금지, host-local ARM, immutable activation/risk-budget record와 epoch/TTL fencing, deterministic client order ID,
transactional outbox·reconcile·fail-closed, DISARM/KILL/manual fallback, migration 전 backup과 일시적 DDL credential,
민감정보 masking과 off-host backup은 실제 자금 손실을 직접 줄이므로 유지한다.

**V1에서 단순화하는 통제:** 한 VM의 MySQL/Redis를 analytics/live schema·DB user·key prefix/consumer group으로 논리
분리하고 물리 instance 분리는 연기한다. container network는 ingress/internal 두 개, host identity는 operator와
unprivileged runtime 두 개로 제한한다. 별도 Ed25519 approval key, TOTP, `RuntimeRiskCap`, `apps:live-control`,
`infrastructure:live-control`, `apps:schema-migrator`는 만들지 않는다. 대신 Live Executor distribution의 서로 배타적인
`executor | control | migrate` host-local entrypoint와 OS 권한, DB activation record를 사용한다. API는
status·DISARM·KILL만 제공하고 ARM·budget 변경·credential 입력은 제공하지 않는다.

versioned off-host storage는 사용하되 object-lock/signature bundle을 의무화하지 않는다. raw/dataset은 viability 180일에
30일 여유를 둔 최소 210일, LIVE order/fill/audit와 redacted operation evidence는 최소 1년 보존한다. 24시간 상시 감시를
요구하는 대신 production entry는 operator가 관찰 가능한 bounded ARM session에서만 허용한다. 물리 DB/Redis 분리,
HSM/KMS, 다중 승인자, 24/7 on-call, object lock, multi-host HA는 SaaS 전환·운용 자본 확대·규제 요구가 생길 때 별도 Phase로
승격한다. validation host는 상시 staging이 아니라 operator workstation 또는 필요할 때만 켜는 임시 VM/compose project로
구성하고 production credential을 두지 않는다.

**수용하는 trade-off:** operator OS identity와 PRIVATE_LIVE host가 최종 trust root이므로 host 전체가 침해되면 별도
서명키가 없는 activation/audit record의 non-repudiation은 보장하지 못한다. 공유 MySQL/Redis는 공통 장애·contention
영역이고 versioned storage는 WORM이 아니므로 권한 있는 operator가 삭제할 가능성도 남는다. V1은 한 명이 자기 계정만
운영한다는 전제에서 이 위험을 수용하고, 출금 권한 제거·고정 IP·최소 주문/손실상한·off-host backup·host-local KILL로
실제 손실 범위를 제한한다. 전제가 바뀌거나 §11의 측정 trigger가 발생하면 완화한 통제를 별도 Phase로 승격한다.

## 1. 입력 문서와 공식 근거

### 1.1 프로젝트 정본

- `.ai/context/project-overview.md`
- `.ai/architecture/ARCHITECTURE_DESIGN.md`
- `.ai/instructions.md`
- `.ai/rules/architecture.md`
- `.ai/rules/batch.md`
- `.ai/rules/testing.md`
- `.ai/PROJECT_STATUS.md`
- `docs/runbooks/redis-contract.md`
- `docs/runbooks/observability-readiness.md`

### 1.2 외부 리서치 입력

로컬 입력 경로:

```text
/mnt/c/Users/yeop/IdeaProjects/item-research-team/research-premium-spread-autotrader
```

문서 우선순위는 `FINAL_RECOMMENDATION.md` → `IMPLEMENTATION_BRIEF.md` → `_workspace/01_requirements.md` → 나머지
workspace 문서 순이다. 리서치의 요구사항은 설계 입력으로 사용하지만 PoC 수익성, 법률 단정, 거래소 자격은 production
근거로 사용하지 않는다. PoC 코드는 fixture와 반례를 얻는 용도이며 그대로 포팅하지 않는다.

### 1.3 2026-07-20 공식 자료 확인 결과

- Bithumb은 [주문 API](https://apidocs.bithumb.com/reference/%EC%A3%BC%EB%AC%B8-%EC%9A%94%EC%B2%AD)에서
  `client_order_id`, IOC/FOK/Post-Only를 제공하고,
  [MyOrder private stream](https://apidocs.bithumb.com/reference/%EB%82%B4-%EC%A3%BC%EB%AC%B8-%EB%B0%8F-%EC%B2%B4%EA%B2%B0-myorder)에서
  접수·부분체결·완료·취소와 수수료를 전달한다.
- Bithumb 고객센터는 API가 시세·자산·매수·매도를 자동화하는 서비스라고 설명하고,
  [자동 차익거래 key 권한](https://support.bithumb.com/hc/ko/articles/53958408798745-API-Key%EB%A5%BC-%EC%B2%98%EC%9D%8C-%EB%B0%9C%EA%B8%89-%EB%B0%9B%EB%8A%94%EB%8D%B0-%EC%82%AC%EC%9A%A9%ED%95%A0-%EA%B8%B0%EB%8A%A5%EC%9D%84-%EC%84%A0%ED%83%9D%ED%95%98%EB%9D%BC%EA%B3%A0-%ED%95%B4%EC%9A%94)도
  자산조회·주문조회·주문하기로 제한하라고 안내한다.
- Binance 공식 [USDⓈ-M New Order](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade#new-order)는
  signed `POST /fapi/v1/order`, client order ID, IOC/FOK, reduce-only 등을 제공한다.
- Binance 공식 [API 보안 문서](https://developers.binance.com/en/docs/products/spot/rest-api)는 `TRADE`와 `USER_DATA`
  권한 분리, 서명, timestamp/recvWindow, rate limit을 설명하며, timeout/5xx의 실행 상태가 `UNKNOWN`일 수 있으므로
  user stream 또는 order query로 확인해야 한다고 명시한다.
- Binance의 [Notional and Leverage Brackets](https://developers.binance.com/docs/derivatives/usds-margined-futures/account/rest-api/Notional-and-Leverage-Brackets)는
  `USER_DATA` signed endpoint다. 따라서 public-only Phase에서 호출하지 않고 LIVE executor로 이동한다.
- 대법원 2024도10710 판결은 [자기 계산으로 자기 이익만을 위해 거래하는 일반 이용자](https://www.law.go.kr/LSW/precInfoP.do?precSeq=600579)는
  특별한 사정이 없으면 가상자산사업자로 보기 어렵지만, 불특정 다수 고객을 위해 대가를 받고 반복 거래하는 경우는
  원칙적으로 다르게 본다고 판시했다. 이는 개인 도구와 SaaS를 분리해야 할 근거이지 개별 LIVE 거래의 포괄적 적법성
  확인이 아니다.
- FIU는 [미신고 외국 가상자산사업자의 국내 앱 접속 차단](https://www.kofiu.go.kr/kor/notification/report_view.do?ntcnYardOrdrNo=368&seCd=0001)과
  이용자 보호 위험을 공지했다. 따라서 activation 시점의 Binance 한국 거주자 자격은 문서와 실제 계정 양쪽에서
  다시 확인해야 한다.
- 2026년 6월 2일 공포된 [외국환거래법 개정](https://law.go.kr/LSW/lsRvsDocListP.do?chrClsCd=010202&lsId=001525&lsRvsGubun=all)은
  2026년 12월 3일 시행 예정이며 국경 간 가상자산이전업무와 지급절차 제재를 추가한다. 하위법령은 2026년 9월
  입법예고 예정이므로 현재 문서에서 개인 델타 헤지의 법적 결론을 단정하지 않는다.
- GitHub Actions는 현재 저장소의 GitHub-hosted `Quality Gate`만 사용한다. self-hosted/JIT runner와 secret-bearing
  deploy workflow는 운영 선행투자와 보안 책임이 큰 별도 과제이므로 V1 범위에서 제외한다. 장기 검증과 PRIVATE_LIVE
  작업은 GitHub Actions가 아닌 operator 소유 host-local 절차로 수행한다.

법률·세무 판단은 이 문서의 범위가 아니다. 위 자료는 activation gate를 정의하기 위한 기술 설계 입력이다.

## 2. 현재 프로젝트 분석

### 2.1 재사용할 기반

| 기반 | 현재 구현 | 재사용 방식 |
|---|---|---|
| 모듈 경계 | API/Batch가 Domain compile 의존, adapter runtimeOnly 조립 | Strategy와 Live Executor에도 동일 규칙 적용 |
| 시장 identity | `MarketPair` | 상위 pair identity로 유지하되 `VenueInstrument`를 추가 |
| 프리미엄 계산 | `PremiumPolicy` | 관측용 계산에 유지하고 executable premium을 별도 추가 |
| 공개 데이터 | Binance/Bithumb WS, FX client, reconnect/readiness | bid/ask/quantity/mark/funding 수집으로 확장 |
| 배치 운영 | owner-token lock, lease renew, timeout, bounded alert | archive/import/recovery job에 재사용 |
| 영속성 | MySQL/Flyway, JDBC/JPA adapter, Redis | dataset, outbox, ledger, reconcile read model에 사용 |
| 내구성 작업 | claim token, `SKIP LOCKED`, stale recovery | backtest queue와 execution outbox에 개념 재사용 |
| 인증/소유권 | JWT refresh, member ownership | owner-scoped strategy/run/session/control API에 적용 |
| 관측성 | 구조화 로그, masking, readiness, Prometheus | Strategy/Executor 별 metric과 secret leak test 추가 |

Notification delivery의 at-least-once 처리 코드를 주문 코드로 복사하지 않는다. 외부 주문은 중복 요청이 실제 자본을
변경하므로 deterministic client order ID, 제출 불명 상태, REST/private stream reconcile이 별도로 필요하다.

### 2.2 자동매매 핵심 Gap

| Gap | 현재 상태 | owner Phase |
|---|---|---|
| fee/funding/slippage/net PnL | gross PnL만 계산 | Phase 1 |
| leverage/margin/liquidation | leverage 1~125 검증·저장만 하고 계산에는 미사용 | Phase 1, 7B |
| executable price/depth | Binance midpoint, Bithumb ticker close | Phase 0, 2 |
| durable replay data | raw 5분, history 1시간 후 Redis 만료 | Phase 0, 2 |
| strategy/backtest | 없음 | Phase 1, 3 |
| order/fill state machine | 없음 | Phase 1 |
| PAPER broker/recovery | 없음 | Phase 4 |
| private account/balance | 없음 | Phase 7B |
| real order/reconcile | 없음 | Phase 8B |
| pre-trade/live risk/kill | 없음 | Phase 1, 8B |
| trading execution audit/outbox | 없음 | Phase 1, 8A |

### 2.3 함께 수정할 기존 결함

1. Web 포지션 기본값 `UPBIT/BINANCE_FUTURES`가 Domain enum 및 수집 pair와 불일치한다.
2. `openAutoPosition()`은 외부 주문 없이 DB row만 만들기 때문에 이름이 실제 의미를 왜곡한다.
3. Bithumb ticker close와 Binance midpoint는 실제 매수/매도 가격이 아니다.
4. `USDT -> USD`를 1:1로 강제해 USDT/USD basis가 사라진다.
5. 각 leg/FX 시각과 cross-venue skew가 의사결정에 사용되지 않는다.
6. `premium_snapshot` migration은 있으나 production 저장 adapter가 없다.
7. `PremiumMetrics.updatePremium/updateFxRate` production 호출이 없어 gauge가 갱신되지 않는다.
8. 알림 구독 backend는 있지만 Web 관리 화면이 없다.
9. `foreignLeverage`는 PnL/risk 계산에 사용되지 않는다.
10. 미구성 운영환경용 `.github/workflows/deploy.yml`이 GitHub-hosted runner에 SSH/DB/JWT/SMTP secret을 전달하므로
    “GitHub Actions는 코드 검증만 수행”하는 V1 계약과 충돌한다.
11. `docker/infra-compose.yml`의 MySQL/Redis host port가 모든 interface에 publish되므로 production compose에서는 제거하거나
    loopback으로 제한해야 한다.
12. 현재 public member registration 정책은 LIVE profile에서 single operator allowlist로 차단해야 한다.
13. Quality Gate의 사용자 입력 SHA, `TARGET_SHA`, dependency `target_sha`/fingerprint/`SHA256SUMS` review bundle은 표준 Git
    commit provenance와 Gradle verification metadata 위에 중복된 custom orchestration이므로 Phase -1에서 제거해야 한다.

### 2.4 리서치 요구사항 처리표

| 요구 | 처리 | Phase |
|---|---|---|
| parameter sweep/민감도 | entry/exit/cost grid를 bounded experiment로 구현 | 3, 6 |
| funding·fee·slippage adverse stress | 독립 scenario와 복합 worst-case report | 3, 6 |
| staged partial entry/exit | 의도된 `TranchePlan`과 거래소 partial fill을 분리해 공통 실행 코어로 구현 | 1, 3, 4, 8 |
| Sharpe/MDD/ROIC | 동일 equity series에서 함께 산출 | 3 |
| FX hedge 비교 | hedge 없음/수동 hedge cost scenario를 비교하되 실제 FX 주문은 하지 않음 | 3, 6 |
| liquidation probability | historical breach frequency와 stress liquidation distance를 산출 | 3, 6 |
| Monte Carlo/VaR | historical VaR/Expected Shortfall까지만 V1에 포함; 확률모형 Monte Carlo는 별도 계획으로 defer | 6 |
| capital-cycle 제약 | 통화별 가용자본·환전비·한도·lead time을 versioned model로 구현 | 1, 3, 5 |

ML 예측과 확률모형 Monte Carlo는 모델 검증 없이 LIVE 안전 근거로 오용될 수 있어 V1 완료 조건에서 제외한다. defer
항목은 `.ai/planning/private-live-autotrader/deferred.md`에 이유, 선행조건, 후속 계획 ID를 기록한다.

## 3. 목표 아키텍처와 경계

### 3.1 모듈 구조

```text
premium-spread/
├── apps/
│   ├── api/                 # 소유자 query, status·DISARM·KILL; private client/ARM 없음
│   ├── batch/               # 공개 데이터 수집·집계·archive
│   ├── strategy/            # backtest-paper와 live-strategy를 별도 process profile로 실행
│   ├── live-executor/       # executor(read-only | order-capable-locked) | control | migrate
│   └── web/                 # SIM/PAPER/LIVE 상태·kill UI, LIVE arm 불가
├── domain/
│   ├── market/              # instrument, book, FX, mark, synchronized frame
│   ├── trading/
│   │   ├── execution/       # intent/order/fill/trade-cycle/state machine
│   │   ├── pnl/             # 단일 PnlEngine
│   │   ├── risk/            # risk limits, margin, kill policy
│   │   └── account/         # OwnerId, TradingAccountId, account snapshot port
│   ├── strategy/            # immutable BandStrategy version
│   ├── backtest/
│   └── paper/
├── infrastructure/
│   ├── common/              # migration, repository, outbox, ledger
│   ├── api/
│   ├── batch/               # public exchange/archive adapter
│   ├── strategy/            # dataset reader, queue, PAPER fill adapter
│   └── live/                # private REST/WS, request signing, control/migrate adapter
├── modules/{jpa,redis}/
├── supports/{logging,monitoring,email}/
└── architecture-tests/
```

### 3.2 강제 의존성 규칙

- `apps:*`는 compile-time에 Domain만 의존하고 각 infrastructure module은 `runtimeOnly`로 조립한다.
- `infrastructure:live`는 `apps:live-executor` distribution에서만 materialize할 수 있다. API/Batch/Strategy artifact에는
  private exchange client, exchange signing property와 order endpoint implementation이 없어야 한다.
- Live Executor의 `executor | control | migrate` entrypoint는 한 process에서 하나만 활성화한다. `executor`의 startup
  mode는 `PRIVATE_READ_ONLY | ORDER_CAPABLE_LOCKED` 중 하나다. 전자는 private 조회 bean만, 후자는 order bean까지
  materialize하지만 DB `DISABLED`·claim 0·local latch closed로 시작한다. 실제 credential과 private endpoint egress는
  Phase 9A의 해당 executor mode에만 주입한다. `control`은 HTTP server·exchange client 없이 host-local operator가
  실행하고, `migrate`는 exchange secret/client 없이 일시적 DDL credential만 사용한다.
- Live Executor는 public Web/API controller를 제공하지 않고 management endpoint만 host loopback/private network에 bind한다.
  public ingress에는 어떤 port도 연결하지 않는다.
- API와 Strategy는 `LiveOrderPort`를 호출할 수 없다. Strategy가 만든 executable `ExecutionIntent`는 transaction 안에서
  outbox에 저장되고 Live Executor가 claim한다. SHADOW 결과는 별도 `shadow_intent` table에 기록하며 executable outbox로
  승격하거나 복사할 수 없다.
- executable outbox는 `commandAuthority = EXPOSURE_INCREASING | CANCEL_OPEN_ORDER | REDUCE_VENUE_EXPOSURE |
  RECOVER_RESIDUAL`, `activationEpoch`, `activationRecordId`, `riskBudgetVersionId`, `decisionFrameId`, `expiresAt`을 가진다.
  신규 entry/tranche인 `EXPOSURE_INCREASING`만 현재 ARM epoch·activation record·risk-budget version이 일치하는 미만료 row로
  claim한다.
- `CANCEL_OPEN_ORDER`와 기존 cycle exit/flatten인 `REDUCE_VENUE_EXPOSURE`는 만료된 entry activation에 의존하지 않는다.
  Executor가 reconciled ledger/open order에서만 생성하며 전자는 주문 수량을 늘릴 수 없고 후자는 전략 귀속 수량 이하에서
  해당 venue의 absolute exposure를 줄여야 한다.
- 1차 leg 체결 뒤 hedge가 없는 residual에는 `RECOVER_RESIDUAL`만 사용한다. 미체결 반대 venue의 bounded hedge 또는 먼저
  체결된 venue의 bounded unwind 중 fresh quote·liquidity·cost 기준으로 worst-case risk가 낮은 경로를 선택한다. corrective
  quantity는 reconciled unmatched fill 이하이고 matched target/baseline을 교차할 수 없으며, 매 시도 후 residual delta와
  worst-case loss가 단조 감소해야 한다.
- cancel/venue-reduction/residual-recovery invariant를 claim과 HTTP submit 직전에 증명할 수 없으면 자동 주문 대신
  `FAILED_MANUAL_ACTION_REQUIRED`로 둔다.
- StrategyLive의 정상 청산 signal은 기존 open cycle을 가리키는 non-executable `CycleExitRequest`만 만든다. Executor가
  current reconcile과 귀속 수량을 확인해 `REDUCE_VENUE_EXPOSURE` command로 변환한다. Strategy/API는 cancel/recovery
  authority command를 직접 만들 수 없다.
- V1 entry `maxIntentAge` 기본값은 decision frame 시각부터 5초이며 policy version으로 고정한다. `expiresAt`은 생성 후
  연장할 수 없고 만료 entry intent는 새 market decision 없이 복제·재활성화할 수 없다.
- private exchange credential 원문은 Domain, MySQL, Redis, API response, 로그, metric, trace에 존재하지 않는다.
- account binding, risk-budget 생성/변경, activation record 생성, ARM은 operator OS identity의 host-local Live Executor
  `control` entrypoint만 수행한다. 별도 approval signing key나 TOTP를 사용하지 않고 immutable DB record,
  operator/reason/expiry, epoch와
  control/recovery generation으로 fencing한다. API/Web은 status·DISARM·KILL만 제공한다.
- production host는 `ingress`와 `internal` 두 Docker network만 사용한다. Nginx/API 외 service port는 publish하지 않고,
  exchange credential file은 container runtime UID 소유 `0400` 또는 `0600`, group/world permission 0으로 read-only
  mount한다. host preflight는 host/container UID 일치와 symlink 부재를 확인하며 operator가 필요 시 sudo로 임시 파일을
  같은 filesystem에 쓰고 atomic rename하는 rotation만 수행한다. runtime service account는 secret과
  deploy/control/migrate 명령 파일을 수정할 수 없다.
- MySQL 한 instance 안에 analytics/live schema와 `schema_migrator`, `analytics_runtime`, `api_runtime`, `live_strategy`,
  `live_executor`, `live_operator` 최소권한 user를 둔다. `schema_migrator`는 지속 공용 계정이 아니라 한
  실행마다 선택한 정확한
  target schema/history table에만 DDL을 허용하는 임시 user이며 완료 직후 revoke한다. `api_runtime`은 기존 analytics
  API 권한과 live redacted status 조회·전용 DISARM/KILL request table insert만 허용하고
  binding/budget/activation/ARM/order/outbox/DDL은 거절한다. 장기 runtime의
  DDL, 다른 schema write와 role 간 권한 상승을 grant test로 금지한다. live decision snapshot과 executable outbox는 live
  schema의 한 transaction이다.
- Redis 한 instance에서 public market frame을 한 번 publish하고 PAPER/LIVE가 서로 다른 consumer group·durable DB cursor를
  사용한다. shared stream의 consumer-group 이름은 Redis ACL 보안 경계가 아니므로 Batch만 XADD/XTRIM을 허용하고 두
  Strategy process는 제한된 shared read/ack ACL을 사용한다. private control/order key가 필요하면 별도 `live:*` prefix와
  ACL로 분리한다. trim은 두 cursor의 safety margin을 모두 만족해야 하며 gap·backpressure는 해당 mode만 PAUSE하지만
  Redis 전체 장애는 LIVE 신규 entry를 fail-closed한다.
- backtest/PAPER 부하는 bounded executor, 별도 DB pool과 container CPU/memory limit로 제한한다. live-strategy와
  live-executor는 Stage A process와 분리하지만 MySQL/Redis 물리 instance를 추가하지 않는다. local KILL latch와 exchange
  manual fallback은 Redis availability에 의존하지 않는다.
- StrategyBacktest/Paper는 live account/control/execution table을 read/write할 수 없고 StrategyLive만 activation-bound
  outbox와 shadow table을 쓴다. global market dataset에는 `OwnerId`를 넣지 않고 strategy/session/order/account 데이터는
  반드시 Owner scope를 가진다.
- LIVE profile은 `/members` public signup을 닫고 지정 owner 한 명만 `LIVE_OPERATOR`로 허용한다. 일반 Member 권한으로
  live account, order, audit, control read/write가 모두 거절된다.
- `apps:strategy`의 `backtest-paper`와 `live-strategy` profile은 별도 container/process로 실행하며 한 process에서 함께
  활성화할 수 없다. LIVE가 ARMED 또는 recovery 상태이면 active PAPER session은 0이어야 하고 PAPER start/resume API를
  거절한다. PAPER는 LIVE가 DISABLED/DISARMED이고 reconcile된 exposure가 0일 때만 다시 시작한다.

### 3.3 Runtime 흐름

```text
Public WS/REST/FX
       │
       ▼
   apps:batch ── gzip archive ───────────────────────────────▶ object storage
       ├── analytics schema ────────────────────────────────▶ apps:strategy[backtest-paper]
       └── Redis market frame
                 ├──────────────────────────────────────────▶ apps:strategy[backtest-paper]
                 └──────────────────────────────────────────▶ apps:strategy[live-strategy]
                                                                      │ decision + outbox
                                                                      ▼
                                                               MySQL live schema
                                                                      │ claim/fencing
                                                                      ▼
                                                              apps:live-executor
                                                 ┌────────────────────┴────────────────────┐
                                                 ▼                                         ▼
                                         Bithumb private API                       Binance private API
                                                 └────────────────────┬────────────────────┘
                                                                      ▼
                                                          live schema reconcile/audit
                                                                      ▼
                                                      apps:api query → apps:web
```

Live Executor는 제출 직전 공개 시세를 독립적으로 재확인한다. Strategy frame이 stale하거나 현재 executable edge가 risk
조건을 벗어나면 intent를 거절한다.

Host-local Live Executor `control` entrypoint는 immutable activation/control record를 MySQL live schema에 쓰고 local
KILL은 Unix domain socket/latch로 Executor에 전달한다. `control` entrypoint에는 exchange client bean이나 credential이
없다.

`LiveMarketGuardAdapter`는 Bithumb public orderbook, Binance public bookTicker/mark price와 USD/KRW·USDT/USD
source/freshness를 독립 조회한다. Strategy가 보낸 값을 신뢰해 재사용하지 않으며 direct quote timeout, FX stale, frame 대비
price deviation이면 fail-closed한다.

## 4. 핵심 Domain 계약

### 4.1 Instrument와 시장 관측

```kotlin
data class VenueInstrument(
    val exchange: Exchange,
    val exchangeSymbol: String,
    val base: Currency,
    val quote: Currency,
    val marketType: MarketType,
    val contractType: ContractType?,
    val settlementCurrency: Currency,
    val contractMultiplier: BigDecimal,
)

data class FxObservation(
    val base: Currency,
    val quote: Currency,
    val rate: BigDecimal,
    val source: ObservationSource,
    val observedAt: Instant,
    val ingestedAt: Instant,
)
```

`TopOfBook`, `MarkPriceObservation`, `FundingSettlement`, `InstrumentRuleSnapshot`, `MarginBracketSnapshot`은 각각 source,
effective time, ingested time, version을 가진다. `SynchronizedMarketFrame`은 다음을 포함한다.

- Bithumb bid/ask/quantity와 Binance bid/ask/quantity
- Binance mark price
- USD/KRW와 USDT/USD 각각의 `FxObservation`
- 각 observation age와 cross-venue skew
- `entryExecutablePremium`: Korea ask로 매수하고 Foreign bid로 매도할 때의 premium
- `exitExecutablePremium`: Korea bid로 매도하고 Foreign ask로 매수할 때의 premium
- instrument/rule identity와 frame event ID

PAPER/LIVE 신규 진입 기본 freshness는 book age 5초 이하, venue skew 2초 이하, FX age 35분 이하다. 값은 versioned
risk policy이며 완화하려면 새 policy와 승인이 필요하다.

### 4.2 공통 실행 상태 머신

`OrderIntent`, `VenueOrder`, `ExecutionFill`, `LedgerEntry`, `TradeCycle`은 Phase 1에서 먼저 만든다. Backtest/PAPER/LIVE가
동일한 상태 전이를 사용한다.

```text
TradeCycle
PLANNED → ENTRY_EXECUTING → OPEN → EXIT_EXECUTING → CLOSED
                │             │            │
                └──────→ COMPENSATING ─────┘
                              │
                       COMPENSATED | FAILED | KILLED

VenueOrder
CREATED → OUTBOXED → SUBMITTING → ACKNOWLEDGED → PARTIALLY_FILLED → FILLED
                         │              │                 │
                         ▼              └→ CANCEL_REQUESTED ─→ CANCELED | FILLED
               SUBMISSION_UNKNOWN
                         │ reconcile only
                         └→ ACKNOWLEDGED | PARTIALLY_FILLED | FILLED | REJECTED | SUBMISSION_UNRESOLVED
```

필수 규칙:

- intent ID와 venue별 client order ID는 deterministic하고 36자 이하 안정 형식이다.
- HTTP timeout/5xx는 실패가 아니라 `SUBMISSION_UNKNOWN`이다.
- `SUBMISSION_UNKNOWN`에서는 private stream과 REST query로 결과를 확인하기 전 같은 주문을 재전송하지 않는다.
- REST `NOT_FOUND` 한 번은 미접수를 증명하지 않는다. venue별 uncertainty window 동안 private event와 REST를 반복 확인한
  뒤에도 불명확하면 `SUBMISSION_UNRESOLVED`로 두고 DISARM하며 자동 재전송하지 않는다. V1 기본 해제는 operator의 수동
  거래소 확인과 새 승인으로만 가능하다.
- 거래소별 client order ID 허용문자·길이·유일성 범위·조회 가능 기간을 dated contract fixture로 고정한다. 동일 client ID
  재전송은 공식 보장이 있고 timeout-after-accept test가 안전성을 증명한 venue에서만 별도 승인할 수 있다.
- exchange ACK 전에 process가 죽어도 outbox와 client order ID로 복구한다.
- cancel/fill 경합에서 fill이 우선 사실이며 cancel 응답만으로 미체결을 가정하지 않는다.
- exactly-once network delivery나 절대적인 exactly-once economic effect를 주장하지 않는다. deterministic ID, unique
  fill/event, quarantine과 reconciliation으로 문서화된 거래소 보장 범위에서 중복 economic effect를 방지·탐지한다.
- append-only audit event는 기존 event를 수정하지 않고 보정 event를 추가한다.

PAPER session 상태도 별도 정본으로 둔다.

```text
CREATED → RUNNING → PAUSED_MANUAL | PAUSED_DATA_GAP | PAUSED_RISK
   │          │             │
   │          └─────────────┴──→ RUNNING (fresh-frame + risk 재승인)
   └────────────────────────────→ STOPPING → STOPPED
모든 non-terminal state ────────→ KILLING → KILLED | FAILED
```

- pause는 신규 진입만 막고 risk 평가와 정상 exit는 계속한다.
- stop은 신규 진입을 막고 열린 cycle을 정상 exit 정책으로 종료한 뒤 `STOPPED`가 된다.
- kill은 신규 진입을 즉시 막고 cancel/compensation을 시작하며 잔여 exposure가 0인지 별도 기록한다.
- restart는 자동 resume하지 않고 ledger/cursor 복구, fresh-frame 연속성, risk 재승인을 요구한다.

리서치의 staged entry/exit는 거래소의 비의도적 partial fill과 구분한다. `TranchePlan`은 각 tranche의 executable-premium
threshold, 목표 수량, 최대 누적 exposure, timeout과 남은 목표 수량을 immutable하게 가진다. 각 tranche는 별도 child
intent/order lifecycle을 사용하고 이전 tranche의 두 leg hedge와 reconcile이 끝난 뒤 current risk를 재평가해야 다음
tranche로 진행한다. 한 주문의 partial fill은 해당 tranche 안에서만 처리한다.

### 4.3 PnL과 Risk

단일 public 계산 정본:

```kotlin
fun PnlEngine.evaluate(
    ledger: TradeLedger,
    valuation: ValuationContext,
): PnlBreakdown
```

`PnlBreakdown`은 한국/해외 realized·unrealized PnL, FX contribution, fill별 fee/slippage, 실제 settlement별 funding,
capital-cycle cost, gross/net PnL, capital at risk, ROIC, residual BTC/KRW delta, maintenance margin, liquidation distance를
분리한다. 금액 계산은 BigDecimal만 사용한다.

회계 항등식과 부호는 다음으로 고정한다.

```text
foreignPnlKrw = foreignPricePnlAtEntryFxKrw + fxContributionKrw
netPnlKrw = koreaPnlKrw
          + foreignPricePnlAtEntryFxKrw
          + fxContributionKrw
          + fundingPnlKrw
          - feeCostKrw
          - slippageCostKrw
          - capitalCycleCostKrw
```

- fee/slippage/capital-cycle cost는 breakdown에서 0 이상의 비용으로 저장하고 항등식에서 뺀다.
- funding은 수취가 양수, 지급이 음수인 signed posting이다.
- USDT 금액은 동일 observation set의 `USDT/USD → USD/KRW` 순서로 변환한다.
- FX contribution은 entry 기준 환율로 계산한 foreign price PnL과 valuation 환율 PnL의 차이며 foreign PnL에 중복 가산하지
  않는다.
- realized는 fill로 닫힌 수량, unrealized는 남은 수량만 평가하고 둘의 수량 합이 ledger position과 일치해야 한다.
- venue posting은 해당 통화 규칙으로 한 번 반올림하고, 내부 계산은 정해진 고정밀도 scale로 유지한 뒤 KRW report에서 최종
  반올림한다. 중간 단계마다 임의 반올림하지 않는다.

`RiskEngine`은 mode와 무관하게 다음을 평가한다.

- owner/account/instrument 일치
- current balance와 available margin
- max capital/notional/leverage/daily loss/order count
- stale/skew/price-deviation/residual-delta
- isolated margin 및 liquidation distance
- open order와 기존 position을 포함한 총 exposure
- circuit breaker와 kill state

`CapitalCycleConstraint`는 통화별 available capital, conversion spread/fee, transfer/withdraw limit, rebalance lead time,
one-time/per-cycle 비용을 가진 immutable version이다. SIM/PAPER는 이를 scenario input으로만 사용하고, LIVE에서는 자동
이동을 수행하지 않으며 사전 배치 잔고가 부족하면 신규 진입을 차단한다.

`LiveRiskBudget.maxDailyLossKrw`의 risk day는 UTC 00:00 경계로 고정한다. 당일 시작 equity와 intraday peak equity에서의
하락 중 더 큰 값을 사용하며 realized·unrealized PnL, fee, funding, slippage, capital-cycle cost와 unresolved order/residual
exposure의 보수적 executable-price valuation을 모두 포함한다. UTC 경계에서 full reconcile이 완료되고 unknown order와
account drift가 0일 때만 새 기준 equity를 확정한다. 아니면 reset하지 않고 `PAUSED_RECONCILIATION`을 유지한다.

`LiveRiskBudget`은 immutable `EmergencyRecoveryPolicyVersionId`를 반드시 참조한다. policy는 IOC price band, max
slippage/notional/attempt/unhedged time, hedge-vs-unwind 비교 규칙과 절대 global ceiling을 가진다. 각 open cycle과 recovery
command는 진입 승인 당시의 policy version ID를 보존한다. runtime config로 완화할 수 없고 상향은 새 activation record를
요구하며,
참조 version의 부재·불일치는 자동 emergency order가 아니라 manual-only 상태가 된다.

`LiveRiskBudget` version은 immutable ceiling이다. V1은 별도 `RuntimeRiskCap`이나 API budget 변경 endpoint를 만들지 않는다.
예산을 낮추거나 높이는 모든 변경은 먼저 DISARM한 뒤 host-local로 새 budget version과 activation record를 만들고
re-arm한다.
즉시 노출을 줄여야 하면 DISARM/KILL을 사용하며 기존 version을 runtime config로 덮어쓰지 않는다.

Executor가 소유하는 `LiveRiskMonitor`는 Strategy 추정값이 아니라 current reconciled ledger·private account snapshot·
executable valuation으로 budget을 지속 평가한다. exposure가 없는 pre-submit breach는 entry 거절과 원인별 PAUSE로 끝내지만,
열린 exposure가 있는 상태에서 daily-loss, capital/notional/leverage, liquidation-distance hard limit 또는
residual/unhedged-time deadline을 위반하면 자동 KILL을 요청한다. 단순 market-data stale/gap, rate-limit, auth 장애는 우선
신규 entry를 PAUSE하며, 이미 열린 residual이 emergency deadline을 넘을 때만 KILL/recovery로 승격한다. 불명 account/order
상태는 추측 flatten하지 않고 claim을 막은 뒤 reconcile 또는 manual fallback으로 보낸다.

자동 KILL은 사용자 KILL과 같은 durable `KillRequest` idempotency key를 사용하고 MySQL live schema에서 monotonic
`controlGeneration`과 `recoveryGeneration`을 한 transaction으로 열어 동일 coordinator를 호출한다. 동시 사용자/자동
trigger, duplicate event와 monitor restart가 하나의 recovery generation만 만들고 cancel/recovery economic effect를
중복시키지 않아야 한다. limit 판정 입력·근거·snapshot age·risk-budget version은 immutable audit event로 남긴다.

SIM/PAPER의 margin bracket은 승인된 versioned `RiskAssumptionSet`을 사용한다. account-adjusted Binance bracket은
USER_DATA가 필요하므로 Phase 7B의 private read adapter에서만 조회한다.

### 4.4 두 레그 실행 정책

V1은 유동성이 상대적으로 제한된 Bithumb leg를 IOC 또는 FOK로 먼저 실행하고, 실제 체결 수량만큼 Binance를 즉시
hedge하는 순차 정책을 기본으로 한다. 동시 주문은 원자성을 제공하지 않으므로 V1 기본값으로 사용하지 않는다.

- 진입: Bithumb spot buy → filled quantity만큼 Binance futures sell
- 청산: Bithumb spot sell → filled quantity만큼 Binance `reduceOnly` buy
- 두 거래소 rule로 수량을 먼저 정규화하고 residual delta 상한을 초과하면 시작하지 않음
- 1차 leg fill 후 2차 leg가 실패하면 제한 시간 안에 retry/reconcile하고, 상한을 넘으면 1차 leg를 보상 청산
- kill-switch는 신규 exposure를 막지만 cancel과 risk-reducing/reduce-only 주문은 허용
- 최대 unhedged time/quantity와 보상 가격 정책은 canary 전 사용자가 승인한 `LiveRiskBudget`에 필수 입력

이 정책은 PAPER failure injection과 shadow 결과로 검증한 뒤에만 확정한다. 실제 호가 품질이 반대 정책을 요구하면
Phase 8B 설계 변경 승인을 받는다.

## 5. 데이터·전송·무결성 계약

### 5.1 저장 책임

- Redis: current view와 bounded realtime transport만 담당하며 정본이 아니다.
- MySQL: 한 instance 안의 analytics/live schema로 dataset, strategy/run/session, outbox, account snapshot,
  order/fill/audit ledger의 정본을 논리 분리한다.
- gzip archive: 원시 공개 market payload의 immutable 장기 원본이다. collector local disk는 72시간 spool일 뿐 정본이
  아니며 versioning 가능한 off-host object storage의 object version과 append-only archive record를 정본으로 사용한다.
- decision snapshot: retention과 무관하게 모든 PAPER/LIVE intent마다 보존한다.
- migration은 `db/migration/analytics`와 `db/migration/live` track으로 분리한다. 같은 MySQL instance에서도 서로 다른
  schema와 `flyway_schema_history`를 사용한다. 기존 V1~V14와 신규 market/strategy/PAPER migration은 analytics track,
  live control/execution/reconcile은 live track에만 둔다.
- local/test와 Stage A는 현재 계약대로 API가 analytics track만 실행할 수 있다. PRIVATE_LIVE의 장기 runtime Flyway는
  disabled하고, Live Executor distribution의 `migrate --track=analytics|live` entrypoint만 일시적 DDL credential로
  `DatabaseTargetId`, 허용 host/schema/location/history table을 확인한 뒤 실행한다. 완료 후 credential을 unmount/revoke하고
  runtime은 schema compatibility만 readiness에서 확인한다. DDL credential은 두 schema 공용 계정이 아니라 선택한 한
  target schema와 history table에만 grant된 실행별 임시 user다.

Raw executable archive와 dataset input은 최소 210일 보존한다. 최초 24시간 실측 byte rate의 2배와 30% headroom으로
spool/object capacity를 산정하고 30%/15% 잔여 용량 alert를 둔다. 분기 1회와 Phase 6/activation 직전에 무작위 blob
restore·압축 해제·schema/row-count/ordered-content 검증을 수행한다. Object Lock/WORM은 V1 완료 조건이 아니다.
off-host confirmed sequence에 gap이 생기면 동일 event-time/source fidelity의 검증된 historical source로 메우지 않는 한
180일 clock을 다시 시작한다.

`archiveServiceStartedAt`과 LIVE용 `executableCoverageReadyAt`은 다르다. 후자는 Bithumb orderbook, Binance
bookTicker/mark/funding, USD/KRW, USDT/USD, instrument rule snapshot이 모두 archive·clock health·source version을 가진 첫
시각이다. 180일 `ExecutableCoverageClock`은 이 시각부터만 계산한다.

### 5.2 Migration 예약

| Track / Migration | 대상 | 책임 |
|---|---|---|
| `analytics/V15__create_market_data_archive.sql` | analytics schema | dataset, event/minute frame, funding/mark/rule assumption, coverage/gap |
| `analytics/V16__create_strategy_backtest.sql` | analytics schema | strategy version, cost/risk version, backtest queue/run/report |
| `analytics/V17__create_paper_execution.sql` | analytics schema | paper session, trade cycle, intent/order/fill/ledger/decision snapshot |
| `live/V1__create_live_control_and_account.sql` | live schema | owner/account binding, credential preflight, risk budget, live state/generation, account snapshot, shadow |
| `live/V2__create_live_execution_and_reconciliation.sql` | live schema | release candidate/deployment, activation record, exit request/outbox, exchange order mapping, private event cursor, reconciliation/audit incident |
| `live/V3__create_live_validation_and_evidence.sql` | live schema | validation run/segment/daily summary, operation/evidence reference |

`CommonInfrastructureAutoConfiguration`, auto-configuration imports, integration fixture schema와 cleanup table 목록을 migration과
같은 Phase에서 갱신한다. Phase 0에서 기존 analytics migration을 전용 location으로 옮길 때 existing schema history의
version/description/checksum이 그대로인지 dry-run/upgrade fixture로 확인하고 baseline/repair로 차이를 숨기지 않는다.
필수 negative test는 wrong target/track의 첫 DDL 전 실패, runtime DDL 거절과 Strategy role의 order/reconcile table 변경
거절로 한정한다.

### 5.3 Redis Stream 계약

V1은 Redis 한 instance의 `market:frame:v1:{canonicalPair}` stream 하나를 사용한다.

| consumer | group | durable cursor | gap 결과 |
|---|---|---|---|
| PAPER | `strategy-paper-v1` | MySQL analytics schema | PaperSession `PAUSED_DATA_GAP` |
| PRIVATE_LIVE | `strategy-live-v1` | MySQL live schema | execution control `PAUSED_DATA`, 신규 exposure 0 |

- Batch는 stable event ID, pair, frame time, archive record/object version과 payload를 한 번 publish한다.
- 두 consumer는 독립 pending/reclaim/cursor를 사용하고 stable event ID와 decision snapshot unique key로 redelivery를
  no-op 처리한다.
- 두 group은 장애 전파와 cursor를 논리적으로 분리할 뿐 보안 tenant 경계가 아니다. Redis ACL은 group 이름을 제한하지
  못하므로 consumer에는 shared stream의 read/ack에 필요한 command만 허용하고 XADD/XTRIM/XDEL/XGROUP/DEL은 허용하지
  않는다. group 생성·trim은 Batch/operator 책임이며 V1 single-owner 위협 모델에서 별도 stream fan-out은 만들지 않는다.
- trim은 최초 peak rate/payload size와 두 durable cursor safety margin을 모두 만족한 뒤 수행한다. memory threshold에
  닿으면 silent eviction이나 자동 cursor jump 대신 영향받는 mode를 PAUSE하고 alert한다.
- 별도 live Redis instance, 이중 fan-out과 instance별 chaos SLA 증명은 V1 완료 조건이 아니다. 실측 latency·contention이
  entry/recovery SLO를 반복 위반할 때 후속 인프라 Phase로 승격한다.

`docs/runbooks/redis-contract.md`에 key, ACL, group, trim, reclaim, DB cursor와 gap 복구 명령을 추가한다.

### 5.4 Event-time as-of 규칙

- minute frame boundary는 UTC 분 시작 시각이며 기대 frame 수는 요청 기간과 resolution에서 계산한다.
- 의사결정 시점 `T`의 관측은 `eventTime <= T`이면서 `ingestedAt <= T`인 record만 eligible하다.
- 같은 source에서는 `eventTime`, `sourceSequence`, `ingestedAt`, stable event ID 순으로 tie-break한다.
- realtime은 수신 당시 알 수 없었던 late event로 과거 decision을 수정하지 않는다.
- historical dataset의 late correction은 기존 manifest를 덮어쓰지 않고 새 dataset version을 만든다.
- watermark, maximum lateness, maximum age/skew를 manifest에 기록하고 기준 초과는 gap으로 센다.
- FX, mark, funding, rule/bracket 각각에 미래값·late값·tie-break를 바꾼 mutant fixture를 둔다.

### 5.5 Clock discipline

- collector와 PRIVATE_LIVE host는 UTC, chrony/NTP를 사용하고 offset·last-sync를 readiness/metric에 노출한다. 기본 경고는
  250ms, 신규 entry fail-closed는 500ms 또는 NTP unsynchronized이며 실제 Binance `recvWindow`보다 보수적으로 구성한다.
- collector 기준 초과 구간은 raw payload에 clock-degraded metadata를 남기고 viability dataset에서 gap/quarantine로
  처리한다. live-strategy/Executor 기준 초과는 intent 생성/submit을 막는다.
- retry/timeout/backoff/lease의 process elapsed time은 monotonic clock을 사용하고, event/economic/activation/expiry/daily-loss
  timestamp는 synchronized UTC wall clock을 사용한다.
- wall-clock backward/forward jump 또는 NTP unsynchronized 전환은 `PAUSED_DATA` 또는 `PAUSED_RECONCILIATION`을
  일으킨다. 5분 연속 정상 offset, fresh frame, full account/order reconcile 후 host-local 재승인 전에는 자동 resume하지 않는다.
- tests는 offset 경계, backward/forward jump, DST와 UTC day rollover, Binance server-time 차이와 outbox expiry가
  서로 다른 clock abstraction을 올바르게 사용하는지 검증한다.

### 5.6 결정론적 content 계약

custom SHA를 만들지 않아도 같은 입력을 재현하고 비교할 수 있어야 한다. archive/dataset/report는 immutable record ID,
schema/version, source range, ordered stable event ID, row count와 명시적 economic field를 저장한다. 비교 테스트는 정렬된
canonical row와 정규화된 BigDecimal/time field를 field-by-field로 비교하고 최초 불일치 위치를 출력한다.
`.part` 작성 → fsync → gzip trailer 검증 → atomic rename → object upload → object version 기록 순서를 지키며 crash recovery
test를 둔다. gzip CRC/size, object-storage version ID처럼 저장 계층이 기본 제공하는 값은 사용할 수 있지만 이를 별도
애플리케이션 hash chain으로 승격하지 않는다.

### 5.7 Release candidate와 evidence retention

`ReleaseCandidateRecord`는 `ReleaseCandidateId`로 식별하고 LIVE execution-critical set의 다음 명시적 필드를 가진다.

- optional display label, Quality Gate run ID/URL, `qgArtifactId`, 검증된 `dev` source commit ID와
  `releaseCandidateInputObjectVersionId`
- Batch/collector, live-strategy, Live Executor의 component별 OCI image digest와 dependency/security scan result. API의
  DISARM/KILL command/schema가 바뀐 candidate만 API OCI digest를 execution-critical set에 추가한다.
- strategy/config/dataset/cost/risk/emergency-recovery version ID, migration version, required runtime-boundary policy version
- operator 생성·승인 시각

Git commit ID와 OCI image digest는 Git/registry가 제공하는 release provenance로 유지하지만, 이 필드를 다시 합쳐 custom
deployment digest를 만들지 않는다. execution-critical component, live schema, strategy/risk/recovery policy 또는
runtime-boundary의 행동 계약이 바뀌면 새 `ReleaseCandidateId`와 activation evidence가 필요하다.
docs-only 변경과 LIVE command/schema 계약을 건드리지 않는 API/Web/analytics 변경은 해당 component deployment와 targeted
Quality Gate 결과만 새로 기록하고 유효한 SHADOW/canary/LIMITED evidence clock을 초기화하지 않는다. API의
DISARM/KILL/control command 계약이나 shared Domain code가 바뀌면 execution-critical 변경으로 분류한다. merged `dev`
Quality Gate가 green이면 production-credential-free validation host가 `ReleaseCandidateId`와 위 ordered field 중
`releaseCandidateInputObjectVersionId`를 제외한 값을 가진 `ReleaseCandidateInput`을 versioned storage에 기록한다. storage가
반환한 version ID는 Input 내부가 아니라 Phase 9A live V2 migration 뒤 생성하는 immutable `ReleaseCandidateRecord`에만
저장한다. operator는 Input과 Record를 field-by-field로 검증하며 별도 input/record digest는 만들지 않는다.

GitHub Actions artifact는 전송 cache일 뿐 정본이 아니다. soak/viability/failure-matrix는 최소 210일, LIVE redacted
operation evidence는 최소 1년 동안 versioned off-host storage에 보존한다. evidence record는 `EvidenceRecordId`, source
run/command ID·URL, object-storage version ID, 생성·복제 시각과 redaction schema를 가진다. 분기 1회와 activation 직전에
restore·schema·record identity를 검증한다. 신규 SBOM/provenance pipeline, Object Lock/WORM과 evidence 서명은 V1 필수가
아니며 외부 감사·SaaS 요구가 생길 때 추가한다.

## 6. PRIVATE_LIVE 보안·활성화 계약

### 6.1 Secret과 account 경계

- credential은 repository 밖 `/run/secrets/premium-spread/`에서 container runtime UID가 소유한 `0400` 또는 `0600`,
  group/world permission 0의 non-symlink file로 read-only mount하고 Live Executor를 같은 non-root UID로 실행한다.
  operator는 필요 시 sudo를 사용한 same-filesystem atomic rename으로만 rotate한다. 환경변수, application.yml, DB, Web
  입력으로 secret을 받지 않는다.
- Phase 7B/8 자동 검증은 fake/recorded credential만 사용한다. 실제 account 조회와 trade key는 Phase 9A의
  pre-credential operational prerequisite와 사용자 승인이 모두 PASS한 뒤 `DISABLED` 상태에서만 수행·발급·mount한다.
- Bithumb은 자산조회·주문조회·주문하기, Binance는 필요한 TRADE/USER_DATA만 허용한다. withdrawal/transfer 권한과
  입금·출금·송금·wallet transfer client/code path는 만들지 않는다.
- 양 거래소 key에 고정 egress IP allowlist를 적용하고 rotation/revoke runbook을 검증한다.
- `CredentialRef`는 secret 파일 위치의 opaque ID만 표현하며 secret 원문을 반환하지 않는다.
- `CredentialPreflightRecord`는 immutable `CredentialPreflightId`, provider key/account opaque ID 또는
  `AccountBindingId`, rotation generation, permission set, withdrawal/transfer=false, IP allowlist, observedAt,
  `API | OPERATOR_REVIEW` source와 redacted evidence record ID를 가진다. 별도 암호학적 서명을 만들지 않는다.
- key/account ID, rotation generation, permission 또는 IP allowlist가 예상 밖으로 바뀌면 기존 activation record와 epoch를
  revoke하고 `PAUSED_ACCOUNT_DRIFT`로 전환한다. 동일 account/permission의 계획된 key rotation은 `DISABLED`에서 수행하고
  fresh credential preflight·account reconcile·deployment/activation record/ARM만 다시 하며 같은 release candidate의
  유효 validation evidence는 유지한다.
- account binding, risk-budget 생성/변경, activation record 생성, ARM은 host-local Live Executor `control` entrypoint만
  수행한다. OS operator 권한과
  `live_operator` DB credential이 승인 경계이며 별도 TOTP seed, Ed25519 key와 signing service를 만들지 않는다.

### 6.2 활성화 상태

```text
DISABLED ──arm(CANARY, TTL≤2h)──→ ARMED_TTL
    │
    └────arm(LIMITED, TTL≤24h; COMPLETED_CANARY 필수)──→ ACTIVE_LIMITED
           ARMED_TTL | ACTIVE_LIMITED ──→ PAUSED_* ──DISARM──→ DISARMED
                                      └────────────→ DISARMED | KILLING → KILLED
                       DISARMED | KILLED ──host-local verified reset──→ DISABLED
```

- `SHADOW`는 control state가 아닌 `StrategyValidationMode`다. SHADOW 동안 `DISABLED`, latch closed, executable claim
  0을 유지하고 `shadow_intent`만 쓴다.
- UI/API는 status, `DISARM`, `KILL`만 제공한다. account binding, risk budget, activation record와 ARM은 host-local
  command만 허용한다.
- `LiveActivationRecord`는 immutable row와 revoke event로 저장한다. `activationMode = CANARY | LIMITED`,
  `targetActivationEpoch = current + 1`, observed control/recovery generation, `ReleaseCandidateId`, component OCI digest,
  current `DeploymentId`, account-binding ID, `CredentialPreflightId`, risk-budget/emergency-policy version ID, operator,
  reason, `alertReadyAt`, createdAt, validFrom과 expiresAt을 가진다.
- host-local ARM은 MySQL live schema에서 `state=DISABLED`, target-minus-one epoch, observed generation, validation readiness,
  mode-specific canary prerequisite, closed latch와 미만료 activation record를 한 CAS transaction으로 확인하고 target
  epoch를 연다. field 불일치나 중간 DISARM/KILL/reset/reconcile incident가 있으면 기존 record를 재사용하지 않는다.
- CANARY 완료는 최소 주문 entry/exit와 statement 대조, open/unresolved order·exposure·residual 0, host-local DISARM을 가진
  한 `COMPLETED_CANARY` run으로 정의한다. USER_KILL/AUTO_KILL terminal path는 fake/testnet에서 필수 검증하고 실자금으로
  고의 반복하지 않는다.
- LIMITED activation record 생성과 ARM은 같은 `ReleaseCandidateId`, account binding, execution-critical digest,
  risk-budget/emergency-policy를 참조하는 `COMPLETED_CANARY`가 있고 statement 100% 일치, unresolved order·exposure·
  residual·critical incident 0일 때만 허용한다. 이 guard는 host-local command와 ARM CAS transaction 양쪽에서 검증해
  CANARY 생략이나 다른 candidate의 결과 재사용을 거절한다.
- CANARY TTL은 최대 2시간, LIMITED TTL은 최대 24시간이다. TTL 만료는 자동 `DISARMED`로 전이하고 신규 exposure를
  차단한다. 다음 session은 fresh reconcile, 새 activation record와 host-local ARM을 요구한다.
- ARM 중 notification transport는 사용자에게 메시지를 반복 전송하지 않는 provider health/synthetic probe로 최소 5분마다
  current `alertTransportHealthyAt`을 갱신한다. 10분 이상 stale하거나 probe가 실패하면 `PAUSED_OPERATOR_UNAVAILABLE`로
  신규 entry를 차단한다. 이 값은 current control/metric과 segment summary에만 두고 append-only heartbeat row나 human
  presence acknowledgment는 만들지 않는다.
- DISARM/PAUSED/expiry는 신규 entry/tranche만 막고 기존 cycle의 exit/cancel/recovery/reconcile은 exposure가 0이 될 때까지
  계속한다. restart 후 자동 ACTIVE 복귀는 금지한다.
- `DISARMED | KILLED → DISABLED`는 HTTP/API나 deploy 명령으로 전이할 수 없다. host-local Live Executor
  `control reset-disabled`가 fresh full reconcile, open/unresolved order 0, 전략 exposure/residual 0,
  `EXPOSURE_VERIFIED`, incident acknowledge와 closed latch를 확인할 때만 허용한다.
- stale/gap, reconcile mismatch, baseline과 다른 order/position/balance, manual activity, rate-limit/auth 장애는 원인별
  `PAUSED_*`와 신규 claim 0을 만든다. exposure 0 → DISARM → verified reset → fresh activation record/ARM 외 direct
  resume는 없다.
- API KILL은 monotonic control generation의 high-priority command로 전달하고 Executor는 1초 안에 신규 claim을 멈춘다.
  host-local KILL은 Unix domain socket/latch로 제공하며 DB/API 장애와 무관하게 동작한다.
- KILL은 `REQUESTED → CLAIMS_BLOCKED → CANCELING → FLATTENING → EXPOSURE_VERIFIED`를 기록한다. 자동
  hedge/unwind/flatten은 fresh account·quote와 `EmergencyRecoveryPolicyVersionId`의 bounded IOC/slippage/notional/attempt
  제한 안에서만 수행하고, 불명확하면 `FAILED_MANUAL_ACTION_REQUIRED`와 거래소 수동 절차로 전환한다.

Live Executor는 ARM과 매 `EXPOSURE_INCREASING` claim/HTTP submit 직전에 activation record, release candidate/component,
account binding, credential preflight, risk-budget version, target/current epoch와 expiry를 다시 검증한다.

#### 6.2.1 Control API 계약

```text
GET  /api/v1/live/status
GET  /api/v1/live/accounts/current
GET  /api/v1/live/events?cursor=&limit=
POST /api/v1/live/control/disarm
POST /api/v1/live/control/kill
GET  /api/v1/live/control/{controlRequestId}
```

- 모든 endpoint는 owner-scoped `LIVE_OPERATOR`만 접근한다.
- command는 `Idempotency-Key`가 필수이며 `(OwnerId, commandType, key)` unique와 monotonic
  `controlGeneration`으로 같은 요청에 같은 결과를 반환한다.
- DISARM/KILL은 `202 Accepted`와 `controlRequestId`, generation, `REQUESTED`를 반환할 뿐 cancel/flatten 성공을
  뜻하지 않는다. query/status가 진행 상태와 residual exposure를 보여준다.
- ARM, account binding, risk-budget 생성/변경, credential 입력, step-up과 runtime-cap endpoint는 존재하지 않는다.
  route/architecture negative test로 고정한다.

### 6.3 Activation hard gate

다음 세 gate를 순서대로 평가한다. 아직 실행 시점이 오지 않은 뒤 gate만 `PENDING_*`일 수 있으며 현재 gate의
FAIL/UNKNOWN을 PASS로 간주하지 않는다.

**A. pre-candidate prerequisite** — `ReleaseCandidateInput` 준비 전에 모두 PASS:

- [ ] 2026년 12월 시행 외국환거래법 하위법령 반영 및 변호사/세무 전문가 확인
- [ ] Bithumb/Binance 최신 API 약관, 한국 거주자·USDⓈ-M 이용 자격 문서와 rate limit 변경 검토
- [ ] 고정 egress IP host와 secret mount preflight 계약 준비
- [ ] LIVE profile public registration 차단, single operator allowlist와 owner authorization 검증
- [ ] PAPER-start/LIVE-ARM race test
- [ ] pre-funded 자금 출처·해외계좌/세무 신고 의무 검토 기록
- [ ] Stage A 완료와 최소 180일 전략 viability gate 통과
- [ ] fake server order/reconcile, NORMAL_DISARM, USER_KILL, AUTO_KILL failure matrix 통과
- [ ] 승인된 `LiveRiskBudgetVersionId`와 `EmergencyRecoveryPolicyVersionId`
- [ ] `LiveActivationRecord` 생성·만료·revoke fake drill과 release candidate/account/budget version 일치
- [ ] merged `dev` Quality Gate green, component별 OCI digest와 기존 dependency/security scan green

**B. pre-credential operational prerequisite** — candidate record 등록 뒤 production key 발급 전에 모두 PASS:

- [ ] 사용자 본인의 별도 서면 승인과 허용 손실 금액
- [ ] candidate-bound SHADOW 유효 segment 합계 72시간과 executable submit 0
- [ ] Binance testnet order/reconcile, NORMAL_DISARM, USER_KILL, AUTO_KILL failure matrix 통과
- [ ] Bithumb public-data SHADOW/would-order diff와 수동 cancel/unwind runbook drill 통과
- [ ] backup restore/PITR drill이 production과 다른 `DatabaseTargetId`의 disposable schema/container만 사용하고 Executor
      network/credential이 연결되지 않음을 확인
- [ ] collector/PRIVATE_LIVE host NTP readiness와 clock-jump pause drill green

**C. credential-dependent prerequisite** — PRIVATE_READ_ONLY/locked preflight 뒤 activation record 생성과 ARM 직전에
모두 PASS이고 UNKNOWN/PENDING이 0:

- [ ] activation 당일 실제 계정의 Binance 한국 거주자·USDⓈ-M·API 이용 자격과 `canTrade` 확인
- [ ] current credential의 최소권한, withdrawal/transfer=false, 고정 IP 일치와 current `CredentialPreflightId`
- [ ] active PAPER session 0
- [ ] 양쪽 open order 0, Binance BTCUSDT position 0, Bithumb non-strategy reserve와 사전 배치 KRW/USDT baseline 고정
- [ ] actual account/order/position/balance/fee/bracket reconcile과 manual drift 0
- [ ] current `DeploymentId`, account binding, credential/risk/emergency-policy version과 candidate 일치
- [ ] ARM 전 15분 이내 test alert의 operator 수신 확인, session 중 5분 transport probe와 10분 freshness 초과 시
      `PAUSED_OPERATOR_UNAVAILABLE`, 수동 cancel/unwind 절차 확인

PRIVATE_LIVE V1은 24시간 유인 감시나 continuous presence heartbeat를 전제로 하지 않는다. operator가 일 1회 여는 최대
24시간 LIMITED window와 hard risk limit로 범위를 제한하고 alert 전달 실패가 감지되면 신규 entry를 중단한다. credential
없는 public-data collector만 24/7 운영한다.

한 항목이라도 `UNKNOWN`이면 해당 activation은 fail-closed다. 증거 성숙 대기는
`CODE_READY_PR_COMPLETE | PRIVATE_LIVE_PENDING`, 명시적 FAIL·사용자 반려·복구 불가능한 candidate 무효화는
`PRIVATE_LIVE_NO_GO`로 기록한다.

## 7. Phase 실행 계획

### Phase 의존성

```text
Phase -1 → 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7A → 7B → 8A → 8B
                                                                    │
                                                                    ▼
                                                            8C(code-ready)
                                                                    │
                                                                 dev merge
                                                                    ▼
validation host: Phase 6 장기 검증                     PRIVATE_LIVE host: 9A → 9B → 10(final)
                공통 Domain ────────┘
                raw archive clock 계속 실행
                Stage A 완료점: Phase 6
                Stage B 완료점: Phase 8B + 8C merge
                Stage C 활성화: 승인된 ReleaseCandidateId의 host-local Phase 9A/9B evidence
```

Phase 8B 뒤에는 gate 준비 여부와 무관하게 Phase 8C code-ready 문서와 본 기능 PR을 먼저 `dev`에 merge하고 상태를
`CODE_READY_PR_COMPLETE`로 둔다. Phase 9A는 별도 Git branch나 GitHub workflow가 아니라 최신 `dev`의 green Quality Gate
run과 registry OCI digest로 만든 `ReleaseCandidateInput/Record`를 입력으로 하는 operator-owned host-local 운영 절차다.
9A/9B 결과 문서만 일반 docs branch/PR로 반영한다. execution-critical code/config 수정이 필요하면 별도 code PR을 `dev`에
merge하고 기존 candidate/activation/evidence를 revoke한 뒤 새 `ReleaseCandidateId`로 처음부터 다시 수행한다. docs-only와
LIVE 계약에 영향 없는 API/Web/analytics 수정은 targeted Quality Gate와 deployment record만 갱신한다.

각 소프트웨어·문서 Phase acceptance commit은 하나의 독립 push 단위다. Phase 9A의 migration/deploy/validation/ARM은 Git
commit이 아니라 append-only operation/audit record를 남긴다. 상세 설계에서 파일이 추가될 수 있지만 아래 경계 밖 책임을
가져오면 마스터 계획 변경 승인이 필요하다.

### Phase -1: 기준선·feature worktree·CI 실행 계약

**목표:** 최신 `dev`에서 구현 기준과 Phase별 검증 경로를 고정하고 기존 GitHub-hosted Quality Gate만 사용한다.

**주요 파일:** `.github/workflows/{quality-gate,deploy}.yml`, `ci/quality-gate-contract-test.sh`,
`ci/{check-dependency-bootstrap-request,generate-dependency-bootstrap,validate-dependency-bootstrap-output,dependency-bootstrap-contract-test,dependency-fingerprint,verify-target-sha}.sh`,
`docker/deploy-contract-test.sh`,
`.ai/planning/private-live-autotrader/progress.md`, Phase -1 design/plan

**작업:**

- [ ] 최신 `dev`에서 `feat/private-live-autotrader` 전용 branch/worktree를 만들고 main worktree의 사용자 변경과 겹치지
  않는지 확인한다.
- [ ] baseline compile/test/architecture/Web/documentation 결과를 기록한다.
- [ ] draft PR을 `dev` 대상으로 즉시 생성해 모든 feature commit이 현재 GitHub-hosted `Quality Gate`의
  `pull_request` 검증을 받게 한다.
- [ ] 현재 미구성 운영환경을 전제로 SSH/production secret을 받는 `.github/workflows/deploy.yml`을 저장소에서 제거하고
  `docker/deploy-contract-test.sh`를 host-local deploy contract로 바꾼다. 기존 `docker/deploy.sh`의
  no-host-build/readiness/rollback 기능은 재사용한다.
- [ ] Quality Gate의 `workflow_dispatch` SHA input, `TARGET_SHA`, exact-target checkout/검증과 stale
  `refactor/infrastructure-boundary` push trigger를 제거한다. trigger는 PR 검증용 `pull_request`와 merge 결과 검증용
  `push: [dev, main]`만 둔다. PR run의 merge ref/head 정보는 review evidence이고 ReleaseCandidate 입력은 실제 merged
  `dev` push commit의 green run과 artifact만 허용한다.
- [ ] Quality Gate가 자체 `github.sha`로 artifact 이름·image tag와 OCI 표준
  `org.opencontainers.image.revision` label을 만들고 contract test가 세 값과 run artifact ID의 결속을 검증한다. 이는
  GitHub/OCI 표준 provenance이며 사용자 SHA 입력, custom fingerprint나 합성 digest를 만들지 않는다.
- [ ] custom dependency SHA orchestration을 제거한다. `target_sha`, dependency fingerprint, `SHA256SUMS`, bundle checksum과
  `verify-target-sha.sh`는 삭제하고, dependency 추가가 필요한 PR만 고정 내용 marker로 기존 GitHub-hosted Quality Gate에서
  표준 Gradle lock/verification metadata를 생성해 review artifact로 올린 뒤 의도적으로 실패하게 한다. feature push
  trigger나 별도 workflow는 만들지 않는다.
- [ ] 사람이 allowlisted Gradle lock/verification metadata diff를 검토한 follow-up commit에서 marker를 제거한다. 표준
  Gradle dependency verification checksum은 유지하되 자체 fingerprint/hash chain이나 candidate SHA 입력은 만들지 않는다.
  marker parser·artifact allowlist·shell contract test도 이 최소 PR-only 흐름만 검증한다.
- [ ] Strategy/Live 신규 job 추가 시 `quality-gate-contract-test.sh`의 job count와 exact command도 함께 갱신하도록 후속
  Phase 책임을 기록한다.
- [ ] 기존 GitHub-hosted Quality Gate 외 runner·deploy/soak/migration/activation workflow는 구현하지 않는다. 이를 위한
  신규 runner scanner나 workflow-path 보안 체계도 만들지 않는다.
- [ ] 장기 soak는 production-credential-free validation host, PRIVATE_LIVE 작업은 전용 host의 operator-controlled 명령으로
  수행하고 runtime service account는 그 명령 파일을 수정할 수 없게 하며
  GitHub Actions에는 exchange credential을 제공하지 않는 책임 경계를 `progress.md`와 runbook template에 기록한다.

**완료 조건:** baseline 실패와 신규 실패가 구분되고 PR run과 merged `dev` push run의 의미가 contract test로 구분된다.
dependency review marker의 단일 의도된 실패는 acceptance가 아니며 approved Gradle metadata follow-up run이 green일 때만
계속한다. 저장소에는 custom candidate/dependency SHA orchestration과 secret-bearing deploy workflow가 없고 이후 Phase의
수동 검증 증거 필드가 준비돼 있다.

**검증:**

```bash
./gradlew compileKotlin test architectureTest --offline --no-daemon
npm --prefix apps/web ci --include=optional
npm --prefix apps/web run lint
npm --prefix apps/web run build
bash ci/quality-gate-contract-test.sh
bash ci/dependency-bootstrap-contract-test.sh
bash docs/check-documentation.sh
```

**Acceptance commit:** `ci: PRIVATE LIVE 기능 브랜치 검증 계약 추가`

### Phase 0: 계약 정합성·시장 identity·raw archive clock

**목표:** 잘못된 V1 의미를 수정하고 이후 Phase가 사용할 시장 계약과 원본 데이터 보존을 먼저 시작한다.

**주요 파일:**

- `README.md`, `.ai/context/project-overview.md`, `.ai/architecture/ARCHITECTURE_DESIGN.md`, `.ai/PROJECT_STATUS.md`
- `apps/web/src/components/OpenPositionForm.tsx`
- Position facade/controller와 premium realtime/metrics wiring
- 신규 `domain/.../market/VenueInstrument.kt`, `MarketObservations.kt`, `MarketArchivePort.kt`
- 신규 `infrastructure/batch/.../archive/RawMarketPayloadEnvelopeV1.kt`, atomic gzip spool/object-storage adapter/properties/test
- Binance/Bithumb public WS client와 `BatchInfrastructureAutoConfiguration`
- public-data collector host-local deployment record/runbook, `.gitignore`, Phase 0 design/plan

**작업:**

- [ ] 전략 방향을 낮은 premium 진입 → 높은 premium 청산으로 통일한다.
- [ ] Web pair를 BITHUMB/BINANCE로 수정하고 `openAutoPosition`을 `openTrackedPosition`으로 변경한다. 기존 Position은
  `MANUAL_TRACKING` legacy 기능으로 명시하고 execution/order ledger로 재사용하지 않는다.
- [ ] premium/fx gauge를 실제 계산 성공 경로에 연결한다.
- [ ] 구현이 없는 `PremiumReadPort`/`PremiumWritePort`와 `premium_snapshot`의 실제 사용·data row를 조사한다. dead port는
  제거하고 table은 V15에서 명시적으로 legacy/migrate/drop 중 하나를 결정해 새 decision snapshot과 혼용하지 않는다.
- [ ] 기존 V1~V14를 `db/migration/analytics` track으로 이동하고 API/local/test Flyway location만 함께 바꾼다. 기존
  analytics DB의 기본 `flyway_schema_history`는 그대로 두며 기존 DB upgrade fixture에서 version/description/checksum이
  모두 동일하고 새 analytics DB bootstrap이 V1부터 재현되며 live location을 읽지 않는지 검증한다.
- [ ] `VenueInstrument`, `FxObservation`, `TopOfBook`, `MarkPriceObservation` 기본 불변식을 정의한다.
- [ ] Bithumb API/약관, 실제 계정 또는 사용자 제공 fee, KRW-BTC 최소 주문·tick/step, orderbook timestamp/quantity 품질,
  rate limit을 날짜·출처·검토자와 함께 `external-gates.md`에 기록하고 명시적 GO가 아니면 Phase를 중단한다.
- [ ] provider raw payload를 event/ingested time과 함께 versioned envelope로 보존한다.
- [ ] 기존 관측 계산을 유지하면서 Bithumb orderbook, Binance bookTicker/mark/funding과 USD/KRW raw feed를 병렬
  subscribe/archive해 executable feed 수집을 최대한 앞당긴다.
- [ ] bounded queue, disk threshold, daily rotation, `.part`/fsync/atomic rename와 gzip trailer/schema recovery를 구현한다.
- [ ] archive 실패는 기존 관측용 realtime 계산을 중단하지 않되 drop/disk/upload 오류를 alert하고 viability coverage gap과
  archive clock invalidation으로 기록한다.
- [ ] 72시간 local spool → immutable off-host object storage upload → confirmed manifest/cursor 순서와 idempotent retry를
  구현한다. primary object version과 append-only archive record의 retention을 최소 210일로 고정한다.
- [ ] 현재 운영/스테이징과 별개로 private key가 전혀 없는 24/7 public-data collector runtime을 만들고 registry의 Batch
  OCI digest를 host-local로 배포해 180일 archive clock을 즉시 시작한다.
- [ ] collector host chrony/NTP source와 250ms alert/500ms quarantine readiness를 구성하고 raw envelope/manifest에 clock
  health를 기록한다.
- [ ] collector host에서 registry의 Batch OCI digest를 확인한 뒤 operator-controlled `ops/deploy-public-collector.sh`로 배포하고
  deployment record·readiness·rollback 결과를 남긴다. GitHub Actions나 PRIVATE_LIVE credential을 사용하지 않는다.
- [ ] 최초 24시간 rate 기반 capacity/alert, host·disk·upload 장애, off-host object version audit와 restore·schema/content
  drill을 검증한다.
- [ ] 대체 historical source는 original event time/sequence, executable bid/ask/quantity, provenance/license를 증명하고 현재
  collector schema로 deterministic import한 ordered content가 일치할 때만 gap 대체를 허용한다.

**완료 조건:** 기존 UI/API 계약이 정합하고 공개 raw event가 immutable archive record와 object version으로 off-host
보존된다. 동일 object를 restore하면 schema·row count·ordered event content가 일치한다. 24/7 collector의 confirmed archive cursor·용량 alert·restore evidence가 시작됐지만 LIVE 180일 clock은 아직
`executableCoverageReadyAt` 전이면 0일이다. private exchange
credential/order endpoint는 아직 존재하지 않는다. Architecture 문서는 이 Phase에서 As-Is를 바꾸지 않고
`Target/Planned` 절만 추가한다.

**검증:** Domain/API/Batch/Monitoring unit test, archive crash test, architectureTest, Web lint/build, docs check.

**Acceptance commit:** `fix: LIVE 확장 전 시장 계약과 원본 보존 정합성 수정`

### Phase 1: 공통 실행 원장·PnL·Risk 코어

**목표:** 세 mode가 공유할 순수 Kotlin 실행·손익·위험 정본을 의존성 순서에 맞게 먼저 만든다.

**주요 파일:**

- `domain/.../trading/execution/{ExecutionModels,TradeLedger,TradeCycle,OrderStateMachine}.kt`
- `domain/.../trading/cost/{CostModels,FundingSettlement}.kt`
- `domain/.../trading/pnl/{PnlEngine,PnlModels}.kt`
- `domain/.../trading/risk/{RiskEngine,RiskModels,MarginBracketSnapshot}.kt`
- `domain/.../trading/account/{OwnerId,TradingAccountId}.kt`
- `domain/.../strategy/{BandStrategy,StrategyDefinition}.kt`
- 실제 architecture-test 정본인 `ArchitectureBoundaryTest`, `ArchitectureTarget`, dependency snapshot 관련 파일
- Phase 1 design/plan

**작업:**

- [ ] neutral fill/funding/bracket/ledger 타입을 PnlEngine보다 먼저 정의한다.
- [ ] `SUBMISSION_UNKNOWN`, cancel/fill race, compensation을 포함한 order/trade-cycle transition을 구현한다.
- [ ] 의도된 `TranchePlan`/child intent와 비의도적 exchange partial fill을 서로 다른 상태와 수량 불변식으로 구현한다.
- [ ] fee, slippage, funding, FX, capital-cycle, residual delta, isolated margin을 계산한다.
- [ ] executable entry/exit premium으로만 BandStrategy intent를 만든다.
- [ ] PoC 숫자를 golden fixture로 옮기되 Python 근사 코드는 복사하지 않는다.
- [ ] Domain trading package의 Spring/JPA/JDBC/Redis/HTTP import를 architecture test로 금지한다.
- [ ] `OwnerId + TradingAccountId + clientOrderId` scope uniqueness를 정의한다.
- [ ] `CostModelVersion`, `RiskPolicyVersion`, `CapitalCycleConstraintVersion`을 immutable aggregate로 정의한다.
- [ ] `EmergencyRecoveryPolicyVersionId`를 `LiveRiskBudget`과 TradeCycle snapshot에 포함하고 mutable
  config 우회를 금지한다.
- [ ] 회계 항등식, cost 부호, FX attribution, realized/unrealized 수량 보존, rounding invariant를 테스트한다.

**완료 조건:** 한 개의 state machine과 Pnl/Risk engine으로 deterministic ledger를 평가하며, tranche/partial-fill 수량,
BigDecimal/rounding과 idempotency invariant가 property/golden test로 고정된다.

**Acceptance commit:** `feat: 공통 실행 원장과 넷 손익 위험 코어 추가`

### Phase 2: public market adapter·Dataset·Realtime stream

**목표:** 실제 bid/ask/quantity/mark/funding 기반의 재현 가능한 dataset과 PAPER transport를 구축한다.

**주요 파일:**

- Binance/Bithumb public orderbook parser, Binance mark/funding client, `StablecoinBasisReadPort`, explicit quote conversion adapter
- gzip importer와 Binance Vision importer
- `db/migration/analytics/V15__create_market_data_archive.sql`, market repository adapters
- `CommonInfrastructureAutoConfiguration`, Batch auto-configuration/imports
- Redis Stream producer/consumer contract와 `docs/runbooks/redis-contract.md`
- dataset version/content/no-lookahead integration test, Phase 2 design/plan

**작업:**

- [ ] Bithumb ticker를 public orderbook으로 교체하고 Binance midpoint 손실을 제거한다.
- [ ] `Currency.USDT`와 별도 USDT/USD observation을 추가한다.
- [ ] USDT/USD source·freshness·gap policy를 version으로 저장한다. 승인된 실측 source가 없으면 SIM/PAPER에서만
  `CONFIGURED_SCENARIO(1.0)`와 0.99/1.01 stress를 표시해 실행하고, PRIVATE_LIVE 신규 진입은 차단한다.
- [ ] 모든 필수 feed와 clock/source/version이 off-host confirmed manifest에 처음 함께 존재한
  `executableCoverageReadyAt`을 기록해 180일 clock을 시작한다. USDT/USD 실측 source가 없으면 시작할 수 없다.
- [ ] mark/funding/rule assumption을 effective-time as-of join한다.
- [ ] SIM/PAPER margin bracket은 수동 승인된 immutable assumption version만 사용한다.
- [ ] `BinanceMarginBracketClient`는 이 Phase에서 만들지 않는다.
- [ ] raw event dataset과 minute screening dataset의 용도를 분리한다.
- [ ] execution-sensitive backtest는 raw/synchronized event frame만 사용한다.
- [ ] Redis의 `market:frame:v1:*` producer와 `strategy-paper-v1` group, analytics cursor의 trim/gap/redelivery 계약을
  구현한다. `strategy-live-v1` group과 live cursor schema는 예약하되 실제 consumer/wiring은 Phase 8A가 소유한다.

**완료 조건:** 미래 관측을 섞는 mutant가 실패하고 같은 archive import는 동일한 schema/version·row count·ordered
economic content를 만들며 손상/gap이 숨겨지지 않는다. `ExecutableCoverageClock`의 시작/중단/재시작 사유가 dataset
record에서 재현된다.

**Acceptance commit:** `feat: 실행 가능한 시장 데이터셋과 실시간 스트림 구축`

### Phase 3: Strategy app·Backtest·저장소 전역 편입

**목표:** 공통 state machine을 사용하는 비동기 결정론적 backtest runtime을 만든다.

**주요 파일:**

- `settings.gradle.kts`, root `build.gradle.kts`
- `apps/strategy` 전체, Dockerfile, application.yml, gradle lockfile
- `infrastructure/strategy` 전체, auto-configuration imports, gradle lockfile
- `db/migration/analytics/V16__create_strategy_backtest.sql`, repository/queue wiring와 fixture/cleanup
- architecture-tests의 실제 target/boundary/output/dependency/isolation 파일
- quality-gate unit/integration/security/docker job과 bootstrap allowlist
- Phase 3 design/plan

**작업:**

- [ ] Strategy `stage-a` process 8082/9082를 private-only로 추가하고 Batch deadline과 격리한다. Phase 3에서는
  backtest bean만 활성화하고 LIVE bean은 포함하지 않는다.
- [ ] thin `interfaces/scheduling` poller가 application job 하나만 호출하도록 하고 scheduling 기술을 Domain/Application에
  넣지 않는다. Strategy/Live에서 Flyway가 disabled임을 boot test로 고정한다.
- [ ] MySQL `SKIP LOCKED` + claim token backtest queue를 만든다.
- [ ] backtest executor는 concurrency 1/queue 16, 독립 DB pool budget 4를 사용하고 overload는 bounded rejection으로
  기록한다. PAPER executor와 그 SLA는 Phase 4에서 추가한다.
- [ ] stage-a container의 CPU/memory limit와 OOM/restart policy를 고정한다.
- [ ] next eligible quote/latency/bid-ask/top quantity를 사용하는 Historical adapter를 만든다.
- [ ] immutable cost/risk/capital-cycle version과 dataset/strategy version을 run 시작 시 snapshot한다.
- [ ] immutable `EngineVersionId` record에 source Quality Gate run, calculation schema version, serialization schema version을
  명시적 필드로 저장한다. Git commit ID는 CI provenance field로만 보존한다.
- [ ] immutable `ReportVersionId`, equity/MDD/Sharpe/historical VaR/Expected Shortfall, 비용/FX/risk breakdown과 모든 input
  version을 저장한다.
- [ ] exact run을 먼저 검증한 후 entry/exit parameter sweep을 최대 500조합, 동시 experiment 1개로 제한한다.
- [ ] funding/fee/slippage/USDT basis/leg-failure adverse scenario와 staged partial fill을 실행한다.
- [ ] tranche threshold/quantity/timeout을 strategy version에 고정하고, 각 tranche 전후 risk 재평가와 남은 목표수량을
  report에 남긴다.
- [ ] root coverage/security/artifact materialization/dependency scan에 신규 module을 추가한다.
- [ ] dependency lock/verification metadata는 승인된 bootstrap 흐름으로만 갱신한다.
- [ ] Strategy integration CI와 Docker contract를 추가하고 CI contract의 exact job count를 갱신한다.

**완료 조건:** 같은 dataset/config/engine은 run ID/generatedAt을 제외한 정규화 economic report의 모든 필드와 row 순서가
일치하고 Strategy 장애가 ingestion에 영향을 주지 않는다. 최대 backtest 부하에서 queue/pool 상한과 bounded rejection이
유지되고 PR head의 strict Quality Gate가 green이다.

**Acceptance commit:** `feat: 공통 실행 코어 기반 백테스트 런타임 추가`

### Phase 4: PAPER adapter·실패 복구·동일성

**목표:** 공개 realtime data에서 실제 실패 형태를 재현하는 PAPER execution을 완성한다.

**주요 파일:**

- PAPER session/risk contract와 Strategy orchestrator/recovery job
- deterministic PAPER execution/market event adapter
- `db/migration/analytics/V17__create_paper_execution.sql`, repository wiring/fixture/cleanup
- failure scenario와 Backtest/PAPER canonical ledger equivalence integration test
- Phase 4 design/plan

**작업:**

- [ ] latency, rejection, partial fill, insufficient quantity, duplicate/out-of-order event를 주입한다.
- [ ] PAPER executor를 fixed 2/queue 1024와 독립 DB pool budget 4로 추가해 backtest overload와 격리한다.
- [ ] 같은 `stage-a` process에 PAPER executor를 추가하되 backtest와 별도 bounded queue/DB pool을 사용한다. LIVE
  queue/executor bean은 없음을 boot test로 고정한다.
- [ ] 다중 tranche 사이의 threshold 이탈, 이전 hedge 미완료, timeout에서 다음 tranche가 시작되지 않는지 검증한다.
- [ ] 한 leg fill 후 hedge 실패 시 실제 반대 방향 compensating fill과 비용을 생성한다.
- [ ] process restart에서 non-terminal cycle을 ledger로 복구한다.
- [ ] stale/gap/capital/leverage/daily loss/kill을 fail-closed로 검증한다.
- [ ] PaperSession pause/stop/kill/resume post-condition과 fresh-frame 재승인을 integration test로 고정한다.
- [ ] 동일 ordered event와 deterministic fill 조건에서 Backtest/PAPER 전체 ledger를 비교한다.
- [ ] 최대 backtest 부하에서 PAPER queue/DB pool이 고갈되지 않고 bounded rejection과 pause/stop SLA가 유지되는지
  부하 테스트로 증명한다.

**완료 조건:** failure/restart/duplicate 시 economic effect가 중복되지 않고 잘못 OPEN/CLOSED로 끝나지 않으며 Stage A의
LIVE 선행 안전 계약을 충족한다. 최대 backtest 부하에서도 PAPER decision p99는 500ms 이하이고 PAPER kill 신규-intent
중단은 1초 이하임을 부하 테스트로 증명한다.

**Acceptance commit:** `feat: PAPER 체결 복구와 실행 동일성 완성`

### Phase 5: Dataset·Strategy·PAPER API/Web

**목표:** 소유자가 bounded query로 Stage A 전체 기능과 위험을 관리한다.

**필수 API:**

```text
GET    /api/v1/datasets
GET    /api/v1/datasets/{id}
POST   /api/v1/cost-models
GET    /api/v1/cost-models
POST   /api/v1/risk-policies
GET    /api/v1/risk-policies
POST   /api/v1/capital-cycle-constraints
GET    /api/v1/capital-cycle-constraints
POST   /api/v1/strategies
GET    /api/v1/strategies
POST   /api/v1/backtests
GET    /api/v1/backtests
GET    /api/v1/backtests/{id}/report
POST   /api/v1/backtests/{id}/cancel
POST   /api/v1/paper-sessions
GET    /api/v1/paper-sessions
GET    /api/v1/paper-sessions/{id}/report
GET    /api/v1/paper-sessions/{id}/events?cursor=&limit=
POST   /api/v1/paper-sessions/{id}/{pause|resume|stop|kill}
```

**작업:** owner scope, cursor pagination, downsampled equity, immutable cost/risk/capital-cycle model 생성·조회,
version/freshness/fee/funding/FX/risk 표시, notification UI, LIVE가 아닌 mode의 명확한 표시를 구현한다. Phase 9A 전 UI에
credential 입력 또는 LIVE arm control을 만들지 않는다.

**검증:** API ownership integration, Web `npm ci`/lint/build, documentation, architecture.

**완료 조건:** 모든 목록 API가 owner-scoped bounded pagination을 사용하고 report가 canonical ledger/version/freshness를
표시하며, 다른 owner 접근과 unbounded query가 거절된다. Web은 SIMULATION/PAPER를 실제 주문으로 오인시키지 않고
credential/ARM route·form이 없으며 API integration, lint, build, 문서 검증이 green이다.

**Acceptance commit:** `feat: 전략 검증과 PAPER 운용 화면 추가`

### Phase 6: Stage A E2E·전략 viability·안전 완료점

**목표:** SIM/PAPER를 독립적으로 쓸 수 있는 첫 완료점으로 만들고 LIVE 진행 여부를 수치로 판정한다.

**주요 파일:** Strategy image의 fixed soak entrypoint, `ops/validation/run-strategy-soak.sh`,
host-local soak run state/evidence schema와 Phase 6 design/plan

**작업:**

- [ ] PR head의 GitHub-hosted Quality Gate가 green인 뒤 생성된 Strategy OCI image digest와 source CI run ID를 선택한다.
  production-credential-free validation host는 registry에서 그 digest를 pull하며 Git checkout, host build, Gradle 실행을 하지 않는다.
- [ ] operator-controlled `run-strategy-soak.sh start|status|finalize|abort`가 transient systemd unit 또는 고정 compose profile을
  제어한다. read-only dataset mount, host socket/metadata/shared cache 접근 0, replay network deny, realtime public endpoint
  allowlist를 강제하고 exchange credential은 mount하지 않는다.
- [ ] setup/warm-up을 제외한 실제 관측 구간이 6시간 이상인지 monotonic timestamp로 증명한다. `RuntimeMaxSec`와
  supervised process timeout을 적용하고 success/failure 모두 durable evidence를 flush하되 중단·host reboot·partial run을
  PASS로 이어 붙이지 않는다.
- [ ] `SoakRunId`, `ReleaseCandidateId` 또는 source CI run ID, Strategy OCI digest, dataset/version ID, 시작·종료 시각,
  exit code, metric summary와 failure reason을 append-only DB record와 최소 210일 versioned storage에 보존한다.
  GitHub artifact는 정본으로 사용하지 않는다.
- [ ] evidence object를 새 환경에 restore해 object version, schema, run identity와 ordered report content를 검증한다.
- [ ] archive → dataset → backtest → report E2E와 realtime → PAPER → recovery → report E2E를 검증한다.
- [ ] MySQL/Redis 장애, restart, duplicate, out-of-order, stream trim을 주입한다.
- [ ] 최소 6시간 wall-clock soak와 24시간 상당 가속 replay를 모두 수행한다.
- [ ] soak 기준: 시작 안정화 후 heap +15% 이내, thread +5 이내, DB/Redis connection pool 상한 미초과,
  queue backlog가 부하 종료 10분 내 baseline으로 회수, unhandled error 0건.
- [ ] 최소 180일 dataset, coverage 99.5%, unexplained 5분 gap 0, train/validation/out-of-sample 분리를 평가한다.
- [ ] holdout을 보기 전에 immutable `StrategyViabilityPolicy`와 시간순 split을 고정한다. OOS는 최소 45일·완료 cycle
  30개를 요구하고, 부족하면 실패가 아니라 `PENDING_INSUFFICIENT_EVIDENCE`로 둔다.
- [ ] 180일 minute screening dataset과 별개로 최초 30일 연속 raw/synchronized TopOfBook을 PAPER fill calibration에
  사용하고, LIVE viability에는 최소 180일 연속 executable TopOfBook을 요구한다. Bithumb 과거 orderbook을 신뢰성 있게
  복원할 수 없으면 Phase 2의 `ExecutableCoverageClock`이 실제 180 calendar day를 채울 때까지 `PENDING_DATA`이며 Phase
  9A로 갈 수 없다.
- [ ] 180일 coverage는 collector local disk가 아니라 off-host confirmed archive record에서 계산하고, 임의 일자를 새
  환경에 restore해 schema·source range·row count·ordered economic content가 일치하는지 검증한다.
- [ ] adverse fee/funding/slippage와 leg-failure stress 후 OOS net edge를 기록한다.
- [ ] parameter sensitivity, Sharpe/MDD/ROIC, historical VaR/Expected Shortfall, liquidation-distance breach frequency와
  FX hedge 비용 scenario를 함께 기록한다.
- [ ] 기본·승인된 adverse-cost OOS net PnL이 모두 양수이고, MDD/daily loss가 사전 승인 상한 이내이며, liquidation·
  accounting/risk invariant breach가 0이고, 인접 parameter 조합의 60% 이상이 OOS net-positive일 때만 viability PASS로 둔다.
- [ ] 결과가 나쁘면 숨기지 않고 `NO_GO_STRATEGY` report를 만든다.

**완료 조건:** Stage A software completion과 strategy viability를 별도로 판정한다. 감독된 host-local 6시간 run의
`SoakRunId`, OCI digest, ordered-content restore evidence가 없거나 viability가 실패하면 Phase 7A/7B 코드 연구는 가능하지만
Phase 9A activation은 금지한다. CI runner 구축·폐기 증거는 완료 조건이 아니다.

**Acceptance commit:** `test: SIM PAPER 전체 경로와 LIVE 선행 게이트 검증`

### Phase 7A: Live Executor·host-local control·secret 격리

**목표:** private exchange credential을 아직 사용하지 않고, 기존 단일-host 운영 기반 위에 최소한의 LIVE 실행 경계를 만든다.

**주요 파일:**

- `apps/live-executor` Boot app/Dockerfile/config/lockfile와 `executor | control | migrate` entrypoint
- `infrastructure/live` module/auto-configuration/lockfile와 mounted-secret contract
- architecture/Quality Gate/security/dependency/Docker/deploy contract와 secret leak tests
- `docker/live-compose.yml`, `ops/live/{preflight,deploy,migrate,status,rollback}.sh`, private runtime runbook,
  Phase 7A design/plan

**작업:**

- [ ] 신규 module, auto-configuration import, architecture target와 root coverage/security/artifact/lock gate를 편입한다.
- [ ] GitHub-hosted Quality Gate에 Strategy/Live Executor/Web compile·test·security·Docker 검증을 추가한다. GitHub Actions는
  production exchange endpoint/credential, live DDL credential과 deploy/control 권한을 받지 않는다.
- [ ] Strategy/Live Executor image artifact는 Quality Gate run ID/artifact ID, `github.sha` image tag와
  `org.opencontainers.image.revision` label을 일치시킨다. custom application digest는 추가하지 않는다.
- [ ] `live-strategy`를 Stage A와 별도 container/profile로 패키징한다. Phase 7A에서는 live stream/intent bean 없이
  `LIVE_CODE_DISABLED`로만 boot한다.
- [ ] Live Executor distribution의 `executor | control | migrate` entrypoint와 executor의
  `PRIVATE_READ_ONLY | ORDER_CAPABLE_LOCKED` startup mode가 상호 배타적임을 boot test로 고정한다. `control`은
  bind/activation-create/arm/revoke/kill/reset-disabled command parsing과 live DB write/UDS client를 fake DB로 검증하고,
  `migrate`는 exchange client/secret 없이 target/track/location preflight만 수행한다.
- [ ] LIVE runtime의 Flyway는 disabled한다. `migrate` one-shot mode만 일시적 DDL credential을 사용하고 완료 뒤
  unmount/revoke한다. wrong target/track과 runtime DDL은 첫 DDL 전에 실패해야 한다.
- [ ] dedicated PRIVATE_LIVE VM 한 대를 정의하되 GitHub runner로 등록하지 않는다. host 내부는 `ingress`와 `internal`
  network만 사용하고 Live Executor/Strategy/MySQL/Redis port를 public publish하지 않는다. production compose의 MySQL/Redis
  host port는 제거하거나 관리 목적이면 loopback에만 bind한다.
- [ ] 기존 MySQL/Redis instance를 재사용하되 analytics/live schema, DB user, Redis prefix/consumer group을 논리 분리한다.
  DB role은 `schema_migrator | analytics_runtime | api_runtime | live_strategy | live_executor | live_operator`로 제한한다.
  `schema_migrator`는 매 실행의 한 target schema/history table에만 grant된 임시 user로 만들고 종료 후 revoke하며,
  cross-schema unauthorized write와 runtime DDL을 거절한다.
- [ ] `apps:api`는 별도 live operator credential/pool을 추가하지 않고 하나의 `api_runtime` DataSource를 사용한다. 이
  principal에는 기존 analytics API grant와 live schema의 redacted view SELECT·전용 DISARM/KILL request INSERT만 주고,
  DB CHECK/trigger와 repository test로 다른 command type 및 binding/budget/activation/order/outbox 접근을 거절한다.
- [ ] Redis `market:frame:v1:*`의 PAPER/LIVE group과 cursor를 운영상 분리하고 consumer가 XGROUP/XADD/XTRIM/DEL을
  실행하지 못하게 한다. group 이름은 ACL 경계가 아님을 문서화하고 Redis 장애/gap이 LIVE 신규 exposure를
  `PAUSED_DATA`로 만드는지 검증한다. 별도 live Redis와 dual fan-out은 만들지 않는다.
- [ ] backtest/PAPER queue·DB pool·container resource를 bounded하게 구성하고, 최대 부하에서 LIVE 신규 entry가 안전하게
  PAUSE되며 host-local KILL/manual fallback이 Redis에 의존하지 않는지 검증한다.
- [ ] exchange secret path, runtime UID 소유 `0400|0600`, group/world permission 0, symlink 금지, operator atomic rotation과
  누락/rotation/revoke fail-closed 계약을 만든다. Phase 7A/CI는 fake secret만 사용하고 실제 credential은 Phase 9A의
  `executor` mode에만 read-only mount하며 API/Batch/Strategy/control/migrate에는 mount하지 않는다.
- [ ] CI negative test는 source/image secret 0, real exchange endpoint 0, API/Batch/Strategy private order client 0,
  default order-capable profile disabled와 runtime Flyway disabled에 한정한다. 실제 file ownership, process user, firewall,
  secret mount와 DB grant는 PRIVATE_LIVE host의 `preflight.sh`가 검증한다.
- [ ] operator-controlled deploy 명령은 source Quality Gate run과 component OCI digest를 입력받아 production host의 Git
  checkout·Gradle/Docker build를 금지하고 `DISABLED`, claim 0, rollback readiness와 operation record를 남긴다. runtime
  service account에는 이 명령 파일의 write 권한이 없다.

**완료 조건:** fake configuration으로 Live Executor의 세 entrypoint와 두 executor mode가 상호 배타적으로 기동하고 private
call/order submit은 0건이다. 전용 VM의 no-public-ingress, non-root executor, mounted secret, 논리 DB/Redis 권한과
host-local deploy/control 경계가 CI contract와 `preflight.sh` 검사항목으로 고정된다. 실제 host 결과는 Phase 9A에서만
생성한다. 별도 approval key/TOTP, 물리 DB/Redis와 다중 network zone은 없다.

**Acceptance commit:** `chore: PRIVATE LIVE 실행 모듈과 최소 배포 경계 추가`

### Phase 7B: operator 인증·read-only 계정 reconcile 코드

**목표:** 실제 credential이나 PRIVATE_LIVE host를 사용하지 않고 지정 owner와 한 account pair의 private 조회·reconcile
계약을 fake/recorded protocol로 완성한다.

**주요 파일:**

- mounted-secret/credential-ref/account adapter와 Bithumb/Binance read-only client contract
- `LiveMarketGuardAdapter`의 public orderbook/bookTicker/mark와 FX current-view adapter
- `db/migration/live/V1__create_live_control_and_account.sql`, owner/account/credential-preflight/risk/control/shadow repository wiring
- LIVE profile auth/API negative test, account drift/reconcile integration test, Phase 7B design/plan

**작업:**

- [ ] 한 Owner/TradingAccountPair만 활성화할 수 있는 DB/app invariant를 만든다.
- [ ] LIVE profile에서 public member registration을 비활성화하고 기존 인증 위에 single `LIVE_OPERATOR` allowlist를
  구현한다. 별도 TOTP/step-up은 만들지 않는다.
- [ ] `executor --mode=PRIVATE_READ_ONLY`가 fake/recorded read-only credential에서 account/order/balance 조회 bean만
  materialize하고 order bean, executable outbox claim과 submit call graph는 0인지 boot/architecture test로 고정한다.
- [ ] immutable `CredentialPreflightRecord` schema와 생성 로직을 구현하고 fake provider key/account ID·rotation
  generation·permission/IP 변경이 activation revoke와 account drift pause를 일으키는지 검증한다.
- [ ] recorded fixtures로 account identity, key permission, position mode, isolated margin, balance, fee와 bracket을
  read/reconcile한다. CI와 개발 host에서는 real testnet/production endpoint와 credential을 사용하지 않는다.
- [ ] account binding 시 open order 0, Binance position 0, `ONE_WAY`/`ISOLATED`, Bithumb non-strategy reserve와 reserved
  KRW/USDT baseline record를 저장하고 전략 귀속 inventory를 별도 원장으로 관리하는 로직을 fake DB에서 검증한다.
- [ ] LiveMarketGuard가 Bithumb/Binance public executable quote·mark와 USD/KRW·USDT/USD freshness를 독립 조회하고
  timeout/stale/deviation을 fail-closed하는지 recorded/fake test로 검증한다.
- [ ] 다른 Member/Owner/TradingAccount ID의 snapshot/control/audit 접근을 거절하고 외부/manual order·position·예상 밖
  balance delta를 감지하면 `PAUSED_ACCOUNT_DRIFT`로 전환해 자동 resume하지 않는다.

**완료 조건:** fake/recorded Quality Gate에서 `PRIVATE_READ_ONLY` mode의 private 조회·account drift·owner isolation 계약이
통과하고 order bean/claim/submit과 real credential·real endpoint 호출은 0건이다. 실제 live migration, key mount,
`CredentialPreflightRecord`, account binding/baseline과 read-only reconcile evidence는 최종 merged `dev` candidate를 사용해
Phase 9A에서 처음 생성한다. secret 원문은 저장·로그·artifact에 나타나지 않는다.

**Acceptance commit:** `feat: 단일 operator와 계정 read only 검증 추가`

### Phase 8A: activation outbox·reconciler·fake execution

**목표:** 실제 거래소 order client 없이 자본 변경 명령의 내구성·fencing·불명 상태를 fake exchange로 먼저 완성한다.

**주요 파일:**

- `apps:strategy`의 `live-strategy` profile, Redis live consumer/cursor, SHADOW writer와 activation-bound intent producer
- live execution orchestrator, outbox claimer, submission-unknown reconciler와 kill coordinator
- `db/migration/live/V2__create_live_execution_and_reconciliation.sql`, release/deployment/activation/outbox/audit/incident
  repository wiring
- fake exchange server, Live API status/disarm/kill와 Web 비상정지 UI, Phase 8A design/plan

**작업:**

- [ ] `live-strategy`가 Redis `market:frame:v1:*`의 `strategy-live-v1` group과 MySQL live cursor를
  생성·reclaim하고 trim/gap을 pause한다. SHADOW는 `DISABLED`에서 `shadow_intent`만 쓰고, ARMED 상태는 decision
  snapshot+exposure-increasing outbox를 live schema의 한 transaction으로 저장한다.
- [ ] 정상 exit signal은 `CycleExitRequest`로만 저장하고 Executor가 reconcile 뒤 reduction command로 변환한다.
  Strategy DB role의 cancel/recovery/order/fill table write를 거절한다.
- [ ] ARM과 PAPER start/resume이 같은 owner control row를 lock해 active PAPER=0과 LIVE DISABLED 조건을 원자 검증한다.
- [ ] `ReleaseCandidateRecord`·`DeploymentRecord`·`LiveActivationRecord`의 FK와 immutable/revoke 계약을 구현한다. fake
  candidate/input artifact ID·object version, critical component digest와 deployment ID가 다르면 activation/ARM을 거절한다.
- [ ] host-local activation record가 `targetActivationEpoch=current+1`과 observed control/recovery generation을 기록하고
  ARM이 DISABLED·target-minus-one·generation·readiness·latch를 CAS로 검증하는지 테스트한다. 동시 ARM, record 생성 뒤
  DISARM/KILL/reset과 restart에서 최대 하나만 target epoch를 열고 나머지 submit은 0건이어야 한다.
- [ ] stable client order ID와 transactional outbox/fencing을 구현한다.
- [ ] `EXPOSURE_INCREASING` row는 activation epoch, activation-record ID, risk-budget version, decision-frame ID와 expiry가 현재
  activation과 일치할 때만 claim한다. DISARM/KILL/re-arm 뒤 과거 entry 실행은 0건이어야 한다.
- [ ] timeout-after-accept, crash-after-send-before-persist, cancel/fill race를 fake server에서 검증한다.
- [ ] `SUBMISSION_UNKNOWN`/REST `NOT_FOUND`를 reconciliation horizon까지 조회한 뒤 불명확하면
  `SUBMISSION_UNRESOLVED`로 DISARM한다. 새 client order ID로 blind retry하지 않는다.
- [ ] private-event cursor, REST full reconcile, unique event/fill과 account drift pause를 구현한다.
- [ ] DB control poll과 host-local KILL latch가 claim 차단·cancel·flatten·exposure 확인을 상태/metric/API로 노출한다.
- [ ] DISARMED/KILLED는 host-local `reset-disabled`만 fresh reconcile, open/unresolved order·exposure/residual 0,
  EXPOSURE_VERIFIED, incident acknowledge와 closed latch를 확인한 뒤 DISABLED로 전이한다.
- [ ] `PAUSED_*`에서는 기존 cycle exit/recovery만 허용하고 exposure 0 → DISARM → verified reset → fresh activation
  record/ARM 외 direct resume를 거절한다.
- [ ] `LiveRiskMonitor`가 reconciled ledger/account 기준 daily-loss·capital/notional/leverage·liquidation-distance와
  residual/unhedged deadline을 평가해 hard breach에는 자동 KILL, stale/gap/rate/auth에는 PAUSE를 여는지 검증한다.
- [ ] activation expiry/revoke/KILL 중에도 cancel/exit/bounded recovery가 귀속 수량과 portfolio-risk invariant 안에서
  동작하고 entry/exit의 모든 inter-leg crash 지점을 닫는지 fake failure matrix로 검증한다.
- [ ] Control API의 LIVE_OPERATOR/idempotency/202-progress와 ARM/bind/budget/credential/step-up/runtime-cap route 부재를
  integration test로 고정한다.

**완료 조건:** live-strategy가 Stage A process와 분리되고 논리 DB/Redis 권한 안에서 SHADOW와 executable outbox를 올바른
상태에만 만든다. fake runtime의 duplicate/stale/expired/shadow submit은 0건이고 activation이 만료돼도 bounded recovery는
막히지 않는다. DB/API 장애와 host-local KILL 모두 1초 안에 신규 exposure claim을 중단한다. production order client/key는
아직 없다.

**Acceptance commit:** `feat: LIVE activation outbox와 가짜 체결 복구 완성`

### Phase 8B: 실제 order client·pre-trade risk·간소화된 validation

**목표:** 실제 Bithumb/Binance protocol adapter를 완성하되 production trade key 없이 fake/recorded CI로 검증한다.

**주요 파일:**

- Bithumb v2 order/cancel/query/MyOrder/MyAsset adapter
- Binance USDⓈ-M order/cancel/query/user-data/time-sync adapter
- pre-submit market/account/risk revalidator, compensation/cancel/kill coordinator와 protocol failure matrix
- `db/migration/live/V3__create_live_validation_and_evidence.sql`, `LiveValidationRun`/segment/daily-summary/evidence schema,
  architecture/image/secret/source scan, Phase 8B design/plan

**작업:**

- [ ] private stream과 REST full reconciliation을 실제 protocol contract로 구현한다.
- [ ] exposure-increasing HTTP 전송 직전에 process-local kill latch와 current activation epoch/record/risk-budget
  version/expiry를 다시 읽어 claim 이후 KILL race를 차단한다. control DB read 실패나 불일치는 submit을 중단한다.
- [ ] LiveMarketGuard의 fresh public quote/mark/FX와 current account/risk를 submit 직전 다시 평가한다.
- [ ] NTP offset/jump/Binance server-time skew, 401/403, 429/418, 5xx와 websocket gap에서 신규 주문을 차단하고
  monotonic timeout은 wall-clock jump의 영향을 받지 않게 한다.
- [ ] protocol fixture로 snapshot stale/recovery, daily-loss·capital·leverage hard breach와 unhedged deadline을 주입해
  PAUSE/KILL 분류와 restart 후 idempotent recovery를 검증한다.
- [ ] 전략 fill에 귀속된 inventory/reserved balance만 주문하고 수동 drift, hedge 미완료와 tranche risk 실패 시 pause한다.
- [ ] kill 시 claim 중단, open order cancel, residual exposure, Binance reduce-only와 Bithumb 전략귀속 수량 상한의
  compensation을 끝까지 추적한다.
- [ ] fresh quote/account와 IOC slippage/notional/attempt 상한을 만족할 때만 bounded hedge/unwind/flatten을 하고,
  그 밖에는 `FAILED_MANUAL_ACTION_REQUIRED`로 전환한다.
- [ ] withdraw/transfer endpoint/code path가 없고 private client가 `infrastructure:live` 밖에 없음을 증명한다.
- [ ] `LiveValidationRun.mode = SHADOW | ACTIVE`와 `ValidationSegment`를 만든다. ACTIVE segment는 non-null
  `LiveActivationRecord`를 참조하고 그 record의 `activationMode = CANARY | LIMITED`로 세부 mode를 구분하며 SHADOW는
  activation record/epoch가 없고 `DISABLED`를 유지한다. segment는 run/candidate/epoch/risk-budget version/runtime boot ID,
  start/end 시각, 시작·종료 reconcile, metric summary와 incident reference를 저장한다. Prometheus health와 일별 summary를
  사용하고 60초 append-only heartbeat table은 만들지 않는다.
- [ ] health gap 5분 초과와 사전 기록된 planned restart의 boot ID 변경은 현재 segment만 INVALIDATED한다. zero-exposure
  full reconcile 후 같은 release candidate/config로 시작한 fresh segment는 별도로 계속할 수 있지만 invalid segment의
  기간/cycle은 합산하지 않는다. 예상 밖 control transition은 아래 critical failure로 분류한다.
- [ ] alert transport probe 성공 시각을 current metric/control state로 갱신하고 10분 stale/failure가 신규 claim을 PAUSE하는지
  fake notification adapter와 clock test로 검증한다. audit heartbeat table이나 반복 사용자 알림은 만들지 않는다.
- [ ] planned restart, metric gap과 operator abort처럼 경제 상태·risk invariant가 보존된 운영 중단만 위 segment-local
  INVALIDATED로 처리한다. unexpected fill, statement mismatch, unresolved order/submission, risk invariant breach,
  잘못된 cancel/flatten 또는 KILL/recovery 실패는 segment 제외로 숨길 수 없고 candidate를 `REVOKED` 또는
  `PRIVATE_LIVE_NO_GO`로 종결한다. 원인 수정 뒤 새 `ReleaseCandidateId`로 다시 검증한다.
- [ ] NORMAL_DISARM, USER_KILL, AUTO_KILL terminal path와 statement-zero 조건은 fake/testnet에서 모두 검증한다.
  production canary에서 AUTO_KILL을 고의 반복하는 완료 조건은 두지 않는다.
- [ ] 모든 CI/integration exchange call은 fake/recorded fixture만 사용하고 real testnet/production endpoint와 credential은
  Quality Gate에서 금지한다.
- [ ] `PRIVATE_READ_ONLY` mode는 credential preflight/read 외 order bean·outbox claim·submit 0건이어야 한다.
- [ ] `ORDER_CAPABLE_LOCKED`는 실제 order bean을 포함하되 배포 직후 DB `DISABLED`, claim 0, latch closed를 강제한다.
  current `ReleaseCandidateId`, OCI digest, account binding, `CredentialPreflightId`와 `LiveActivationRecord`를 검증한
  host-local ARM 뒤에만 claim할 수 있다.
- [ ] LIMITED activation-create/ARM이 same candidate/account/critical-digest/risk-policy의 valid `COMPLETED_CANARY` 없이는
  거절되고 다른 candidate·불일치 statement·unresolved/exposure/residual/critical incident가 있는 canary를 재사용하지
  못하는 negative test를 둔다.

**완료 조건:** Stage B의 실행 코드 계약이 완성되어 Phase 8C review 입력이 된다. default config/CI/일반 compose의
production 주문은 0건이고 trade key ref가 없으면 fail-closed한다. locked deploy만으로 claim/submit은 0건이며 host-local
ARM만 이를 연다. PR head의 Quality Gate, image/runtime secret scan과 fake/recorded failure matrix가 green이다. 이 시점에는
아직 `PRIVATE_LIVE_CODE_READY`나 `CODE_READY_PR_COMPLETE` 상태로 전이하지 않는다.

**Acceptance commit:** `feat: 기본 잠금된 PRIVATE LIVE 주문과 위험 차단 완성`

### Phase 8C: SaaS 경계·code-ready 문서 고정

**목표:** code-ready PR을 merge하기 전에 개인 제품을 다중 사용자 서비스로 구현하지 않았음을 고정하고 향후 분리 비용을
제한한다.

**작업:**

- [ ] Phase 7B/8B의 owner/account invariant와 cross-owner negative-test evidence를 code-ready 결과 문서에 연결한다.
- [ ] global market dataset과 owner-private execution data의 schema/권한·process 경계, single active account와
  operator-mounted credential 정책을 문서화한다.
- [ ] SaaS 전환 시 필요한 별도 항목을 ADR로 기록한다: 법률/인가, tenant provisioning, RBAC, per-tenant KMS/Vault,
  credential consent/revocation, quota, billing, support, privacy retention, incident response.
- [ ] architecture, project overview, runbook, PROJECT_STATUS를 Stage B 실제 구현과 동기화하되 PRIVATE_LIVE ACTIVE나 실제
  canary evidence가 있다고 쓰지 않는다.

**완료 조건:** 현재 artifact에 multi-tenant onboarding·타인 credential 입력·billing이 없음을 검증 evidence로 확인하고,
향후 SaaS는 별도 프로그램 승인 없이는 시작할 수 없게 고정한다. branch 문서는 Stage B 상태를
`PRIVATE_LIVE_CODE_READY`로 표시하고 PR merge를 미리 완료로 쓰지 않는다. remote `dev` merge와 merged commit의 Quality Gate를
확인한 뒤에만 release/progress record를 `CODE_READY_PR_COMPLETE`로 전이한다. 이미 명시적 gate가 실패했다면
`PRIVATE_LIVE_NO_GO`를 우선한다.

**Acceptance commit:** `docs: 개인 LIVE 코드 준비와 SaaS 분리 경계 기록`


### Phase 9A: ReleaseCandidate 고정과 host-local activation

**목표:** GitHub deploy workflow 없이 execution-critical component OCI digest와 policy/version을 `ReleaseCandidateId`로
고정하고 그 candidate에만 operator가 관찰 가능한 bounded LIVE session을 허용한다.

**입력과 운영 파일:**

- Phase 8B/8C에서 구현·merge된 `LiveValidationPolicy`, release/activation record schema와 operator runbook
- validation host의 `ops/validation/{release-candidate,strategy-soak,testnet}.sh`
- PRIVATE_LIVE host의 `ops/live/{backup,migrate,deploy,preflight,validate,status,rollback}.sh`
- Live Executor distribution의 host-only `control activation-create|arm|disarm|kill|reset-disabled|revoke`

**candidate 고정 작업:**

- [ ] merged `dev` push commit의 GitHub-hosted Quality Gate가 green인지 확인하고 그 run의 component image artifact만
  production-credential-free validation host로 받는다. host에는 artifact-read와 대상 registry repository write만 가능한
  단기 credential을 사용하며 Git checkout·host build·rebuild와 production secret은 금지한다.
- [ ] QG run/artifact ID, expected component/tag와 OCI revision label이 merged `dev` commit과 일치하는지 확인하고 archive를
  rebuild 없이 load/push한다. registry가 반환한 Batch/collector, live-strategy, Live Executor `repository@digest`를 source
  commit과 Quality Gate run ID/URL·artifact ID, strategy/config/dataset/cost/risk/emergency-policy version, migration version과
  required runtime-boundary policy version과 함께 `ReleaseCandidateInput`에 명시하고 versioned storage에 보존한다.
  API/Web digest와 실제 compose/network/firewall revision은 배포 시 별도 `DeploymentRecord`에 남긴다.
  다만 API의 DISARM/KILL command/schema가 이 candidate에서 바뀌었다면 API OCI digest도 execution-critical set에 넣는다.
  기존 dependency/security scan 결과를 연결하되 신규 SBOM/provenance pipeline은 완료 조건이 아니다.
- [ ] 같은 `ReleaseCandidateId`와 Strategy OCI digest로 Phase 6의 감독된 6시간 soak, accelerated replay와 failure
  regression을 validation host에서 다시 수행한다.
- [ ] 결과 확인 전에 `LiveValidationPolicy` version을 고정한다: 유효 SHADOW segment 합계 72시간, production 최소 주문
  canary 1회, 최소 7 calendar day에 걸친 completed LIMITED session과 자동 전략 완료 cycle 10개, funding settlement 1회,
  UTC daily-loss rollover 1회, statement mismatch/unresolved order/critical incident 0건. CANARY TTL은 최대 2시간,
  LIMITED TTL은 최대 24시간이다.
- [ ] candidate input, OCI digest, scan/soak/failure evidence, pre-candidate prerequisite와 rollback rule을 operator가
  field-by-field로 검토한다. 뒤 gate는 `PENDING_OPERATION | PENDING_CREDENTIAL`일 수 있지만 A gate 누락·불일치면 input을
  `REJECTED`로 기록하고 배포하지 않는다.
- [ ] PRIVATE_LIVE host는 operator와 unprivileged runtime 두 OS identity만 사용하고 Git checkout·Gradle/Docker build를
  허용하지 않는다.
- [ ] validation은 host-local `start|status|finalize|abort`와 supervised runtime으로 수행한다. 시작·종료 reconcile,
  metric/daily summary와 incident를 segment record로 남기며 장기 CI/SSH sleep이나 60초 audit heartbeat table을 사용하지 않는다.

**candidate 준비 완료 조건:** merged `dev` Quality Gate, 같은 `ReleaseCandidateId`의 soak/replay/failure regression과 모든
pre-candidate prerequisite가 PASS다. 뒤 gate만 `PENDING_OPERATION | PENDING_CREDENTIAL`일 수 있고 production runtime은
`DISABLED`, trade-key order submit은 0건이다. Phase 9A는 Git acceptance commit을 만들지 않고 operation/evidence record로
진행한다.

**activation 순서:**

1. encrypted backup/PITR point를 만들고 선택한 Live Executor OCI digest의 `migrate --track=live`를 실행한다.
   Flyway location/history/version/description/checksum과 DB grant를 확인한 뒤 DDL credential을 unmount/revoke한다.
   검토된 `ReleaseCandidateInput`을 field-by-field 재확인해 live schema의 immutable `ReleaseCandidateRecord`로 등록하고
   QG artifact ID와 input의 object-storage version ID를 함께 저장한다.
2. release candidate의 non-secret component set을 `DISABLED`로 배포하고 schema, DB grant, network와 claim 0을 확인한다.
   Live Executor image는 고정하되 production credential을 아직 mount하지 않는다.
3. host-local SHADOW validation을 실행해 유효 segment 합계 72시간, executable outbox/network submit 0, incident 0을
   확인하고 `ValidationRunId`로 FINALIZE한다.
4. production-credential-free validation host의 별도 compose project/system user와 testnet-only secret path에서 Binance USDⓈ-M
   testnet order lifecycle, NORMAL_DISARM, USER_KILL, AUTO_KILL, reconcile/rollback을 검증한다. production secret은 이
   host에 두지 않고 Bithumb production order는 0건이어야 한다.
5. production credential 없이 가능한 Bithumb would-order diff와 pre-credential operational prerequisite를 검토한다.
6. PRIVATE_LIVE host에서 backup restore/PITR를 production과 다른 `DatabaseTargetId`의 disposable schema/container에만
   수행한다. restore target에는 Executor network/credential을 연결하지 않고 production host/schema를 가리키면 첫 write
   전에 거절한다. 이어 Executor rollback, local/API KILL과 manual cancel/unwind runbook을 drill하고 zero-exposure
   verified reset을 확인한다. 사용자 승인·허용 손실을 포함한 B gate가 모두 PASS인지 확인한 뒤에만 step 7로 간다.
7. 사용자 승인 후에도 `DISABLED`를 유지한 채 production trade key를 최소권한·고정 IP로 발급/mount한다. 같은
   Live Executor OCI digest를 `executor --mode=PRIVATE_READ_ONLY`로 먼저 기동해 실제 account/order/position/balance/fee/
   bracket, Bithumb reserve와 KRW/USDT baseline을 reconcile하고 `CredentialPreflightRecord`·account binding을 만든 뒤
   mode를 종료한다. 과권한, withdrawal/transfer, IP mismatch 또는 manual drift면 key/activation/deployment를 revoke하고
   `PAUSED_ACCOUNT_DRIFT`로 둔다. 같은 candidate에서 credential을 교정한 뒤 fresh PRIVATE_READ_ONLY preflight/reconcile부터
   재시도할 수 있으며, adapter가 이를 잘못 판정한 코드 결함일 때만 code fix와 새 candidate가 필요하다.
8. 같은 OCI digest와 credential을 `executor --mode=ORDER_CAPABLE_LOCKED`로 배포해 `DeploymentId`를 만들고 DB
   `DISABLED`·claim 0·latch closed, runtime UID secret permission과 private egress를 확인한다. order bean은 존재하지만
   activation 전 submit은 0건이어야 한다.
9. locked deployment가 step 7의 current `CredentialPreflightRecord`와 같은 credential generation을 사용하고 그 record와
   account reconcile이 15분 이내인지 확인한다. alert test가 성공하고 operator가 15분 안에 수신을 확인한 `alertReadyAt`도
   기록한다. 연속 presence heartbeat/lease는 만들지 않으며 5분 transport probe 또는 10분 freshness 계약이 깨지면
   `PAUSED_OPERATOR_UNAVAILABLE`로 전이한다.
10. host-local `control activation-create`로 `ReleaseCandidateId`, execution-critical OCI digest, current `DeploymentId`,
    account binding, risk-budget/emergency-policy version, `CredentialPreflightId`, `alertReadyAt`, mode, target epoch,
    observed generation, CANARY 2시간 이하 expiry, operator/reason을 가진 `LiveActivationRecord`를 생성한다. 전체 Activation
    hard gate가 PASS이고 UNKNOWN/PENDING이 0인지 재확인한 뒤 fresh CANARY validation segment와 readiness를 열고
    `control arm --mode=CANARY`만 마지막 동작으로 epoch를 연다.
11. 거래소 최소 주문 canary entry/exit 한 cycle을 수행하고 host-local DISARM한다. ledger와 양 거래소 statement를
    수량·가격·fee·balance·position 기준 100% 대조하고 open/unresolved order·exposure·residual 0이면
    `COMPLETED_CANARY`로 FINALIZE한다.
12. canary 뒤 같은 candidate/policy에서 session마다 fresh reconcile·activation record·24시간 이하 LIMITED ARM으로
    `ACTIVE_LIMITED`를 운영한다. 유효 completed segment만 7 calendar day/10 cycle/funding/daily-rollover gate에
    합산하고 INVALIDATED segment는 제외한다.
13. alert delivery 실패, PAUSE/KILL/restart가 생기면 현재 session을 종료하고 zero-exposure reconcile 전까지 새 ARM을
    금지한다. execution-critical digest/policy/schema 또는 runtime-boundary 행동 계약 변경은 candidate를 revoke한다.
    동일 account/permission의 credential rotation과 같은 boundary policy 안의 IP/firewall revision은 activation만 revoke하고
    fresh deployment preflight·account reconcile·activation record/ARM을 요구하며 기존 validation clock은 유지한다.
    docs-only 또는 LIVE 계약과 무관한 API/Web/analytics 변경은 targeted verification/deployment record만 갱신한다.

어떤 GitHub workflow도 migration, deploy, testnet, validation, ARM이나 activation epoch/latch 변경을 수행하지 않는다.

**runtime 완료 조건:** production canary와 completed LIMITED segment의 intent/order/fill/fee/funding/balance/position이
statement와 100% 일치하고 7일/10 cycle/funding/daily rollover 조건을 충족하며 unresolved order·critical incident가 0건이다.
모든 결과는 같은 `ReleaseCandidateId`, OCI digest와 operation/validation/evidence record ID를 참조한다. 관측 기회가
부족하면 `PRIVATE_LIVE_PENDING`을 유지한다.

### Phase 9B: redacted LIVE evidence closing commit

**목표:** 실제 운용 artifact를 바꾸지 않고 `ReleaseCandidateId`의 redacted 결과와 Stage C 판정을 문서화한다.

**작업:** secret, account number, full exchange order ID를 제외한 activation/preflight/evidence record ID, statement 대조,
risk/incident summary와 `PRIVATE_LIVE_ACTIVE_COMPLETE | PRIVATE_LIVE_PENDING | PRIVATE_LIVE_NO_GO` 판정을 기록한다.
모든 row는 `ReleaseCandidateId`, component OCI digest, operation/validation run ID와 object-storage version ID를 참조한다.

**완료 조건:** ACTIVE 또는 NO_GO evidence가 실제 runtime release candidate를 참조한다. docs Quality Gate에서 image archive가
생성되더라도 registry publish, 새 `ReleaseCandidateRecord` 생성과 deploy를 수행하지 않는다. docs/security Quality Gate는
green이어야 한다. PENDING은 checkpoint일 뿐 제품 완료가 아니다.

**Final/checkpoint commit:** ACTIVE는 `docs: PRIVATE LIVE 활성화 완료 증거 기록`, NO_GO는
`docs: PRIVATE LIVE 활성화 반려 증거 기록`. PENDING은 `docs: PRIVATE LIVE 증거 대기 상태 기록` checkpoint다.

Git commit/push나 PR approval은 실제 주문 승인이 아니다. canary는 날짜·금액·`ReleaseCandidateId`,
account/risk/credential-preflight version을 포함한 host-local activation record를 요구한다.


### Phase 10: 최종 프로그램 상태 동기화

**목표:** Phase 9B의 immutable LIVE evidence 판정을 runtime artifact 변경 없이 프로젝트 정본 문서에 반영한다.

**작업:**

- [ ] Phase 9B의 `ReleaseCandidateId`, 실제 deployment ID, component별 OCI digest, activation/validation/evidence record ID,
  object-storage version ID와 final status를
  `.ai/PROJECT_STATUS.md`, architecture, project overview, operator runbook과 result 문서에 연결한다.
- [ ] secret/account/full order ID가 redacted됐고 docs QG image archive가 생기더라도 registry publish, candidate 생성과
  deploy가 수행되지 않는지 확인한다.
- [ ] ACTIVE는 Stage C 모든 row의 evidence를, NO_GO는 explicit failure·revoke·residual 0을 기록한다. PENDING은 남은 기간/
  cycle/재검토와 다음 STATUS/FINALIZE 절차만 기록하고 완료로 표현하지 않는다.

**완료 조건:** `PRIVATE_LIVE_ACTIVE_COMPLETE` 또는 종결된 `PRIVATE_LIVE_NO_GO`가 동일 9A release candidate와 9B evidence를 참조해
모든 정본 문서에 일치한다. PENDING commit은 checkpoint이며 이 Phase acceptance나 제품 목표 완료로 세지 않는다.

**Final/checkpoint commit:** ACTIVE는 `docs: 개인 LIVE 완료 상태와 SaaS 경계 동기화`, NO_GO는
`docs: 개인 LIVE 반려 상태와 SaaS 경계 동기화`. PENDING은 `docs: 개인 LIVE 검증 대기 상태 동기화` checkpoint다.

## 8. 전체 완료 조건

### 8.1 Stage A — SIMULATION/PAPER_READY

1. executable bid/ask, quantity, mark, FX, funding, rule version과 source time/NTP health가 보존된다.
2. 동일 archive input은 같은 schema/version·row count·ordered economic content를 만들며 no-lookahead mutant가 실패한다.
3. Backtest/PAPER가 같은 intent/state/fill/funding/PnL ledger를 만든다.
4. 의도된 tranche와 exchange partial fill이 분리되고 각 tranche 전후 hedge/risk 수량 불변식이 유지된다.
5. fee/slippage/funding/FX/capital-cycle/margin/residual delta가 report에 분리된다.
6. partial/reject/compensation/duplicate/restart/gap/kill failure matrix가 통과한다.
7. API/Web owner scope와 기존 auth/premium/position/notification 회귀 테스트가 통과한다.
8. source Quality Gate가 green이고 같은 Strategy OCI digest의 host-local Phase 6 soak가 PASS이며 `SoakRunId` evidence가
   storage version으로 restore된다.
9. raw archive가 off-host storage에서 restore되고 schema·source range·row count·ordered event content가 일치한다.
   최대 backtest 부하에서도 PAPER bounded queue/pool과 pause/stop SLA가 유지된다.

### 8.2 Stage B — PRIVATE_LIVE_CODE_READY

1. Live Executor distribution에 private client code가 포함되더라도 client bean/call graph, credential mount와 private
   egress는 `executor` mode에서만 활성화된다. control/migrate mode와 API/Batch/Strategy는 credential을 읽거나 private
   order endpoint를 호출할 수 없고 Executor public ingress는 0이다.
2. 한 Owner와 한 TradingAccountPair만 활성화할 수 있다.
3. LIVE profile의 public registration이 닫히고 single operator allowlist·owner isolation이 증명된다.
4. host-local control mode와 `live_operator` DB role만 account binding, risk budget, activation record와 ARM을 만들 수 있고
   해당 API route는 없다.
5. `schema_migrator | analytics_runtime | api_runtime | live_strategy | live_executor | live_operator` role의 DDL/cross-schema
   권한이 최소화된다. migrator는 실행별 한 target schema에만 허용되는 임시 user이고 one-shot migrate 외 DDL이 거절된다.
6. 단일 MySQL 안의 analytics/live schema·DB user와 Redis의 private key prefix가 권한 분리된다. shared market stream의
   PAPER/LIVE consumer group/cursor는 운영상 분리일 뿐 ACL 경계가 아니며 runtime XGROUP/XADD/XTRIM/DEL이 거절된다.
   Redis 장애/gap은 LIVE 신규 entry를 PAUSE하고 host-local KILL/manual fallback은 Redis에 의존하지 않는다.
7. backup/PITR → OCI-pinned migrate → Flyway/grant 검증 → read-only reconcile 순서, wrong-target 첫 DDL 전 거절과
   restore의 disposable target/Executor network 차단이 Testcontainers/fake host contract에서 검증된다. 실제 operator-host
   operation/evidence는 Phase 9A에서 생성한다.
8. withdrawal/transfer 권한과 endpoint가 없고 `CredentialPreflightId`, account-binding/risk-policy version이 activation
   record에 연결된다.
9. baseline/reserved/전략 귀속 inventory가 분리되고 manual account drift가 자동 pause를 일으킨다.
10. account/order/position/balance/bracket/private-event reconcile이 동작한다.
11. deterministic client order ID, activation-bound outbox/fencing과 `SUBMISSION_UNKNOWN` quarantine가 증명된다.
12. LiveMarketGuard가 public executable quote/mark/FX를 독립 재조회하고 stale/deviation을 차단한다.
13. stale epoch·expired·SHADOW entry의 production submit이 0건이고 HTTP 직전 epoch/latch 재검증이 KILL race를 막는다.
14. `ORDER_CAPABLE_LOCKED`의 host-local 배포는 DB `DISABLED`·claim 0·latch closed를 유지하고 host-local ARM만
    target epoch를 CAS로 연다.
15. LIMITED activation/ARM은 같은 candidate/account/critical digest/risk policy의 valid `COMPLETED_CANARY` 없이는
    거절된다.
16. activation 만료/KILL 중에도 cancel/reduce-venue/residual-recovery가 귀속 수량과 portfolio-risk invariant 안에서
    동작하고 모든 inter-leg crash 지점을 닫는다.
17. `LiveRiskMonitor`의 hard-limit 자동 KILL과 data/rate/auth PAUSE 분류가 증명되고 사용자 KILL과의
    race·duplicate·restart에도 하나의 recovery generation과 economic effect만 발생한다.
18. DB control/host-local kill과 manual fallback이 fail-closed다. DISARMED/KILLED/PAUSED는 verified reset과 fresh
    activation record/ARM 순서 외 재개가 거절된다.
19. default build/config/CI/compose는 실제 production 주문을 0건 발생시킨다.
20. Strategy/Live Executor module의 architecture, lock, coverage, integration, security와 Docker gate가 green이다.
21. NTP offset/jump에서 entry/submit이 멈추고 monotonic timeout과 UTC 회계 경계가 유지된다.
22. GitHub Actions는 GitHub-hosted `Quality Gate`만 사용하고 실제 exchange credential, deployment, migration, validation,
    activation 권한을 갖지 않는다.
23. live-strategy는 Stage A process와 분리되고 ARM/PAPER-start race에서 하나만 성공한다.
24. 정상 exit signal은 `CycleExitRequest`로 전달되고 Executor가 reconciled 귀속 수량 안에서 reduction command로
    변환하며 DISARM/activation 만료 후에도 기존 cycle 청산이 가능하다.
25. `PRIVATE_READ_ONLY`는 조회/preflight 외 order bean·outbox claim·submit 경로가 0건이다.
26. validation은 시작/종료 reconcile, metric/daily summary와 incident를 가진 segment로 기록된다. benign health
    gap/restart segment는 INVALIDATED되고 그 기간/cycle은 합산되지 않지만 economic mismatch, unresolved submission,
    risk/KILL/recovery invariant failure는 candidate revoke/NO_GO를 일으킨다.
27. alert transport는 5분 current health probe와 10분 freshness fail-closed를 가지며 stale/failure가 신규 claim을
    `PAUSED_OPERATOR_UNAVAILABLE`로 막는다. append-only heartbeat row와 continuous human presence lease는 없다.

### 8.3 Stage C — PRIVATE_LIVE_ACTIVE

1. Activation hard gate가 모두 PASS이고 UNKNOWN이 없다.
2. activation record와 runtime component set이 `ReleaseCandidateId`, deployment ID, component OCI digest,
   account-binding ID, `CredentialPreflightId`, `alertReadyAt`, risk/emergency-policy version과 mode별 TTL(CANARY≤2h,
   LIMITED≤24h)에 묶여 있다.
3. `candidate 등록/승인 → PRIVATE_READ_ONLY credential/account preflight → ORDER_CAPABLE_LOCKED 배포(DISABLED·claim 0) →
   DeploymentId와 preflight freshness/readiness/alert 확인 → activation record 생성 → control arm` 순서를 건너뛰지 않는다.
4. production 최소 주문 canary 한 cycle의 ledger가 statement와 수량·가격·fee·balance·position 기준 100% 일치하고
   `COMPLETED_CANARY`로 끝난다. same candidate/account/critical digest/risk policy의 이 record 없이는 LIMITED
   activation/ARM이 거절된다.
5. residual exposure가 승인 상한 이내이고 열린 unresolved order가 0건이다.
6. NORMAL_DISARM·USER_KILL·AUTO_KILL failure path는 fake/testnet에서 통과하고 production host-local DISARM/KILL/manual
   fallback readiness가 확인된다.
7. risk budget은 runtime/API에서 변경할 수 없고 DISARM 뒤 새 version/activation record만 변경을 허용한다.
8. 유효 completed LIMITED session이 최소 7 calendar day, 자동 cycle 10개, funding settlement 1회와 UTC daily rollover 1회를
   포함하며 statement mismatch와 critical incident가 0건이다. benign 운영 중단의 INVALIDATED segment만 합산하지 않을 수
   있고 economic/risk/recovery critical failure는 candidate revoke/NO_GO다.
9. 유효 SHADOW segment 합계가 72시간 이상이고 최종 evidence storage version을 restore했을 때 schema·run identity와
   ordered evidence content가 일치한다.
10. 유효 CANARY/LIMITED segment에는 10분을 넘는 alert transport health gap이 없다.

Stage C를 충족해야 PRIVATE_LIVE가 완수됐다고 판정한다. data/external evidence가 아직 성숙하지 않으면
`CODE_READY_PR_COMPLETE`, 9A를 시작했지만 기간/cycle을 기다리면 `PRIVATE_LIVE_PENDING`, 명시적 gate FAIL·사용자
반려·candidate 무효화이면 `PRIVATE_LIVE_NO_GO`다.

### 8.4 최종 프로그램 상태

| 상태 | 의미 |
|---|---|
| `CODE_READY_PR_COMPLETE` | Stage B와 code/docs PR은 완료됐지만 9A activation을 시작하지 않음 |
| `PRIVATE_LIVE_PENDING` | 같은 release candidate에서 명시적 FAIL 없이 shadow·canary·제한 session evidence를 수집 중 |
| `PRIVATE_LIVE_NO_GO` | 명시적 gate FAIL, 사용자 반려 또는 복구 불가능한 candidate 무효화로 activation을 종결 |
| `PRIVATE_LIVE_ACTIVE_COMPLETE` | Stage C의 모든 조건을 동일 `ReleaseCandidateId`로 통과한 유일한 제품 완료 상태 |

9A 시작 여부는 `ReleaseCandidateRecord`가 승인되고 첫 durable validation run이 생성된 시점으로 고정한다. PENDING
evidence가 충족되면 `docs/private-live-evidence-{validationRunId}` branch/PR에서 9B를 갱신한다. execution-critical
component/policy/schema 또는 runtime-boundary 행동 계약이 바뀌면 새 `ReleaseCandidateId`부터 다시 시작한다. equivalent
credential rotation과 비의미적 firewall/IP revision은 fresh deployment/preflight/activation만 요구하고, docs-only 또는
LIVE 계약과 무관한 API/Web/analytics 변경은 targeted Quality Gate와 deployment record만 갱신한다.

## 9. Commit·push·PR·CI 규칙

1. code-ready 목표 branch는 `feat/private-live-autotrader`, PR 대상은 `dev`다. Phase 8B와 8C를 마친 code-ready PR을
   먼저 merge한다. Phase 9A는 Git branch가 아닌 operator-host operation으로 수행하고, Phase 9B/10 결과만
   `docs/private-live-evidence-{validationRunId}` 같은 일반 docs branch/PR로 반영한다.
2. 소프트웨어·문서 Phase는 design/spec review → TDD 구현 → code review → fresh verification → acceptance commit → push
   순서를 지킨다. Phase 9A operation은 사전 승인된 runbook → preflight → 실행 → evidence review 순서를 따르고 Git
   acceptance commit을 만들지 않는다.
3. 한 acceptance commit은 한 Phase 책임만 포함한다.
4. 첫 draft PR 이후 각 acceptance push에 대응하는 PR `Quality Gate`가 green이어야 다음 소프트웨어 Phase로 진행한다.
   ReleaseCandidate에는 merge 뒤 `dev` push run만 사용하며 activation용 manual workflow는 만들지 않는다.
5. 신규 dependency의 고정 marker가 있는 PR run은 표준 Gradle lock/verification metadata review artifact를 만든 뒤
   의도적으로 red가 되는 유일한 예외다. 이 commit은 acceptance로 세지 않는다. custom target SHA/fingerprint/checksum
   bundle 없이 사람이 allowlisted Gradle diff를 검토하고 marker를 제거한 follow-up commit의 strict CI가 green이어야
   구현을 재개한다. 임의 Gradle download/refresh를 하지 않는다.
6. `progress.md`는 CI row와 operator procedure row를 분리한다.
   - CI row: phase, Git commit ID, Quality Gate check/run ID·URL, result, verifiedAt
   - operator row: phase, OperationRecordId, ReleaseCandidateId, component OCI digest, SoakRunId/ValidationRunId/
     EvidenceRecordId, storage version ID, result, operator, host, startedAt, finishedAt
7. Phase 9B 실제 주문 evidence는 secret, API key, account number, full exchange order ID를 포함하지 않는다.
8. 실제 운용 artifact는 `ReleaseCandidateId`와 execution-critical registry OCI digest로 고정한다. Batch/collector,
   live-strategy, Live Executor, shared execution Domain, live schema, strategy/risk/recovery policy와 runtime-boundary 행동
   계약 변경은 기존 activation/evidence를 revoke하고 새 candidate부터 수행한다. 동일 account/permission의 credential
   rotation과 같은 boundary policy 안의 firewall/IP revision은 fresh deployment/preflight/activation만 요구한다.
   docs-only 및 LIVE command/schema와 무관한 API/Web/analytics 변경은 targeted Quality Gate와 별도 deployment record만
   갱신한다.
9. docs-only 최종 PR의 Quality Gate까지 green이어야 PR 완료지만 이를 새 LIVE image로 자동 build/deploy하지 않는다.
10. 실제 canary 승인은 Git approval이나 merge approval로 대체할 수 없다.
11. PR merge/close는 최대 `CODE_READY_PR_COMPLETE`를 뜻한다. 최종 보고서에
    `PRIVATE_LIVE_ACTIVE_COMPLETE`가 없으면 제품 LIVE 완료라고 표현하지 않는다.

현재 required workflow 이름은 `Quality Gate`이며 변경 시 Phase 계획과 branch protection을 함께 갱신한다. GitHub
Actions는 코드 검증만 수행하고 실제 exchange credential과 deploy/migration/validation/activation 권한을 받지 않는다.

## 10. 공통 검증 매트릭스

| 검증 | Local | Integration/CI | 실거래 사용 |
|---|---:|---:|---:|
| Domain PnL/Risk/state invariant | O | O | 금지 |
| Architecture/artifact secret boundary | O | O | 금지 |
| Analytics/Live migration-track isolation·repository/outbox | O | Testcontainers wrong-target matrix | 금지 |
| Public parser/archive/versioned content | fixture | fake/recorded | public read only |
| Backtest no-lookahead | O | mutant | 금지 |
| PAPER failure/recovery | O | scenario matrix | 금지 |
| Live private adapter | fake | fake/recorded only | Phase 9A operator-host testnet/canary |
| Bithumb production order | 금지 | 금지 | 승인 canary만 |
| Owner/account isolation | O | API/repository negative | 금지 |
| Secret leak/log masking | O | image/runtime scan | redacted only |
| Kill/reconcile | fake | fake/recorded only | Phase 9A operator-host shadow/testnet/drill |
| Web | lint/build | CI artifact | 주문 arm 불가 |

Phase 공통 fresh verification:

```bash
./gradlew compileKotlin test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:verifyAnalyticsMigrations :infrastructure:common:verifyLiveMigrations :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest :apps:batch:integrationTest --offline --no-daemon
# 모듈 생성 후 추가
./gradlew :apps:strategy:integrationTest :apps:live-executor:integrationTest --offline --no-daemon
npm --prefix apps/web ci --include=optional
npm --prefix apps/web run lint
npm --prefix apps/web run build
bash ci/quality-gate-contract-test.sh
bash ci/local-offline-contract-test.sh
bash docker/deploy-contract-test.sh
bash docs/check-documentation.sh
git diff --check
```

실제 exchange production endpoint는 위 자동 검증에서 호출하지 않는다.

## 11. 중단 및 재승인 조건

1. infrastructure-boundary 기준이 `dev`에 반영되지 않았거나 경계 계약이 바뀜
2. analytics V15~V17 또는 live V1~V3 track/location/history/checksum 충돌
3. Bithumb/Binance API version, 한국 거주자 자격, 약관 또는 법령 변경
4. fixed egress IP/secret mount/출금 권한 제거를 보장할 수 없음
5. PRIVATE_LIVE 구현을 API/Batch/Strategy 안에 넣어야 한다는 요구 발생
6. cross margin, Upbit, 다중 symbol/account, 자동 자금이동 요구 추가
7. `SUBMISSION_UNKNOWN`을 blind retry하라는 요구 발생
8. Stage A viability 또는 failure matrix가 red인데 LIVE 활성화를 요구
9. 타인의 API key·자금·수익 배분·유료 서비스 요구 추가
10. canary 금액·레버리지·손실상한이 명시되지 않음
11. required CI 또는 migration/architecture/secret boundary test가 red
12. 공유 MySQL/Redis의 실측 contention으로 entry/reconcile/KILL SLO를 반복 위반함. 이 경우 activation을 중단하고
    물리 instance 분리를 별도 Phase로 승인한다.
13. 원격 ARM, 다중 operator, 타인 계정, 외부 감사·WORM 보존 요구가 추가됨. 이 경우 Ed25519/HSM, step-up/MFA,
    다중 승인과 강화된 evidence 모델을 별도 threat-model review 후 도입한다.

## 12. SaaS 전환에 대한 최종 판단

향후 SaaS를 고려할 수는 있지만 현재 PRIVATE_LIVE의 연장선에서 곧바로 제공할 수는 없다. 개인 도구는 operator가
자기 secret을 자기 runtime에 mount하고 자기 계산으로 거래한다. SaaS는 타인의 credential과 주문 권한을 보관·대행하고
장애와 손실을 tenant별로 격리해야 하므로 별도 법률·보안·운영 제품이다.

현재 미리 반영할 것은 `OwnerId`, `TradingAccountId`, owner-scoped uniqueness/repository, global market/private execution
분리뿐이다. 다음은 의도적으로 미리 만들지 않는다.

- 다중 tenant provisioning과 public signup 기반 LIVE
- 사용자 credential upload API
- tenant별 secret vault/KMS와 key rotation service
- RBAC, organization, billing, plan/quota
- 타인 자금 수탁, 투자일임·자문, copy trading, 수익 보장
- 고객별 자동 송금·출금·리밸런싱

SaaS 착수 전에는 최신 법률 자문, 거래소 third-party API 약관, VASP/투자 관련 등록 가능성, 개인정보 영향평가,
credential custody threat model과 별도 Master Plan 승인이 필요하다.

## 13. 문서 review 반영 체크

- [x] 제품 목표를 SIM/PAPER only에서 gated PRIVATE_LIVE로 변경
- [x] SIM/PAPER를 LIVE의 의무 선행 Stage로 유지
- [x] private credential/실주문을 별도 Live Executor로 격리
- [x] Phase 0/1/2/3의 type·state-machine 의존성 순서 교정
- [x] signed Binance margin bracket을 public Phase에서 제거
- [x] instrument/FX/mark/margin mode identity 명시
- [x] `SUBMISSION_UNKNOWN`, cancel/fill race, outbox/reconcile 추가
- [x] Redis Stream, immutable ID/version·ordered-content 비교, raw/minute dataset 역할 명시
- [x] 실제 architecture/auto-configuration/Gradle lock/CI/Docker 변경 책임 추가
- [x] owner/account scope는 준비하되 SaaS 구현은 분리
- [x] 코드 완료와 실제 LIVE activation 완료 조건 분리
- [x] 공식 자료의 확인 사실과 미확정 법률·거래소 조건 구분
- [x] SHADOW/outbox 분리와 activation record·epoch·budget·expiry fencing 추가
- [x] 기존 자산·수동 거래와 전략 귀속 inventory 분리, ONE_WAY/ISOLATED baseline 고정
- [x] unresolved submission 자동 재전송 금지와 human quarantine 명시
- [x] DB/API 독립 host-local KILL과 HTTP 직전 activation latch 재검증 추가
- [x] 과도한 TOTP/approval signing 체계 대신 host-local OS/DB 권한·epoch/TTL과 LIVE pause 상태 명시
- [x] LiveMarketGuard의 독립 public quote/mark/FX 재검증 경로 추가
- [x] GitHub-hosted Quality Gate와 operator-host soak/deploy/activation 책임 분리
- [x] custom candidate/dependency SHA orchestration과 secret-bearing deploy workflow 제거 책임 명시
- [x] Phase 7B fake/recorded code 검증과 Phase 9A 실제 credential/host operation 분리
- [x] single-host/operator trust root, shared MySQL/Redis와 non-WORM storage의 수용 trade-off 명시
- [x] Redis consumer group을 ACL 경계로 오인하지 않고 API/migrator DB 최소권한 보완
- [x] 상시 감시·8시간 재승인 대신 alert preflight와 CANARY 2시간/LIMITED 24시간 TTL로 적정화
- [x] 7A/7B/8A/8B code-ready commit과 9A host-local operation/9B evidence docs 단위 분리
- [x] LIVE viability에 실제 180일 executable archive calendar gate 명시
- [x] Stage C에 반복 cycle·funding·daily rollover·zero mismatch 완료 조건 추가
