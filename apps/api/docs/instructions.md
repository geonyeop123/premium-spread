# 김프(김치 프리미엄) 도메인 작업 지침 TODO 체크리스트

본 문서는 “기획/개발/디자인” 에이전트가 토론한 결과를 바탕으로, 구현을 시작하기 위한 **작업 지침 + TODO 체크리스트**입니다.  
목표는 **Ticker(시세/환율) 영구 저장**, **Premium(김프) 영구 저장**, **Position(포지션) 생성 및 차액 산출**을 **DDD/TDD** 관점에서
설계·구현하는 것입니다.

---

## 0) 공통 합의 사항 (토론 요약)

### 기획(PO) 관점 핵심 규칙

- 시스템은 다음 정보를 **영구 저장**한다.
    - 거래소 코인 가격 Ticker
    - 환율 Ticker (KRW/USD 등)
    - Premium (김치 프리미엄 결과)
- 김치프리미엄 계산은 반드시 아래 3개를 모두 가져야만 수행 가능하다.
    1) 한국 거래소 코인 Ticker
    2) 외국 거래소 코인 Ticker (코인 심볼이 한국과 동일해야 함)
    3) 환율 Ticker (USD→KRW 또는 KRW/USD 등 명확한 방향성 포함)
- Position(포지션)은 매수가(체결가)와 보유수량, 매수 당시 환율(혹은 매수 시점의 환율 기준)을 가진다.
- 현재 차액 계산은 **매수 환율과 별개로 “현재 환율 + 현재 프리미엄” 기준**으로 재평가 차이를 산출할 수 있어야 한다.

### 개발(Backend) 관점 설계 원칙

- 도메인 규칙은 가능하면 **도메인 모델 내부**로 밀어 넣는다(서비스가 조립만 하도록).
- 계산 불가능한 상태(한국/외국/환율 누락, 심볼 불일치, 통화 불일치 등)는 “조용히 0 반환”이 아니라 **명시적으로 실패**해야 한다.
- Ticker는 개념상 “시점의 관측값(Snapshot)”이므로 **불변(immutable)** 로 다루고, 조회시간(observedAt)은 필수.
- Premium은 세 Ticker의 조합 결과이므로, Premium이 생성될 때 **어떤 tickers로 계산했는지 추적 가능**해야 한다(감사/재현).

### 디자인(UX) 관점 최소 요구

- 초기 UI(또는 API 문서) 수준에서 사용자가 이해해야 할 것은 다음 3가지:
    1) “한국 vs 외국 거래소” 구분
    2) “통화(KRW/USD) 및 환율 방향성”
    3) “Premium 계산의 입력 3종(Ticker 2 + Fx 1)”
- 값 표시는 소수점 자리 정책을 명확히:
    - Premium: 소수점 2자리 반올림(정책으로 고정)
    - 가격/환율: 저장은 고정 소수(BigDecimal)로 하되 표시 정책은 별도(추후 UI/리포트에서 결정)

---

## 1) 도메인 모델 초안 (용어/경계)

### 1.1 Value Objects (VO)

- `Exchange` : UPBIT / BITHUMB / BINANCE / FX_PROVIDER(환율 제공자) 등
- `ExchangeRegion` : KOREA / FOREIGN (비즈니스 규칙에서 중요)
- `Currency` : KRW / USD
- `Quote` : (baseCurrency, quoteCurrency) 예: BTC/KRW, BTC/USD, USD/KRW
- `Symbol` : BTC, ETH 등 “코인 심볼”
- `ObservedAt` : 관측 시각(조회시간)
- `Money` 혹은 `DecimalAmount` : BigDecimal + scale/rounding rule 포함
- (선택) `TickerType` : COIN_PRICE / FX_RATE 등

### 1.2 Entities / Aggregates

- `Ticker`
    - 목적: “특정 거래소/마켓에서 특정 Quote의 관측값”을 저장
    - 식별(권장): `TickerId`(UUID) + (exchange, quote, observedAt) 유니크 인덱스 고려
    - 필드(최소):
        - exchange(거래소)
        - exchangeRegion(한국/외국)
        - quote(예: BTC/KRW, BTC/USD, USD/KRW)
        - price(BigDecimal)
        - observedAt(조회시간)
- `Premium`
    - 목적: (한국 코인 Ticker + 외국 코인 Ticker + 환율 Ticker)로 계산된 결과 저장
    - 필드(최소):
        - koreaTickerId
        - foreignTickerId
        - fxTickerId
        - premiumRate(BigDecimal, scale=2)
        - observedAt(계산 시점; 일반적으로 입력 티커들의 최신 관측시각을 기준으로 결정)
- `Position`
    - 목적: 매수가/수량/매수 당시 환율/매수 당시 프리미엄(선택) 등을 보유하고, 현재평가 차이를 계산
    - 필드(최소):
        - symbol
        - exchange(포지션을 어떤 거래소 기준으로 잡는지 명확히: 보통 “한국 거래소 기준” 또는 “외국 거래소 기준”)
        - quantity(BigDecimal)
        - entryPrice(BigDecimal)  // 매수가
        - entryFxRate(BigDecimal) // 매수 당시 환율 (정의: USD/KRW 등 방향성 포함)
        - entryObservedAt
        - (선택) entryPremiumRate(BigDecimal)
    - 도메인 행동(메서드):
        - `calculatePnl(currentPremium, currentFxRate, currentPrice?)` 등

---

## 2) 핵심 비즈니스 규칙(불변 조건) 정의

### 2.1 Premium 계산 규칙(필수)

- 입력:
    - 한국 거래소 코인 Ticker: quote가 `SYMBOL/KRW` 이어야 함(또는 KRW 기준이 시스템 정책이라면 강제)
    - 외국 거래소 코인 Ticker: quote가 `SYMBOL/USD` 이어야 함(정책)
    - 환율 Ticker: `USD/KRW` 이어야 함(정책)
- 규칙:
    - 한국/외국 Ticker의 `symbol`은 반드시 동일해야 한다.
    - 한국 Ticker의 `exchangeRegion = KOREA`, 외국 Ticker의 `exchangeRegion = FOREIGN`
    - Premium은 소수점 2자리 반올림(정책 고정)
- 산식(정책 예시):
    - foreignPriceInKRW = foreignPriceUSD * fx(USD/KRW)
    - premiumRate(%) = (koreaPriceKRW - foreignPriceInKRW) / foreignPriceInKRW * 100
- 실패 조건:
    - 심볼 불일치
    - quote 통화 불일치(예: 외국 Ticker가 KRW, 또는 FX가 KRW/USD 방향 등)
    - exchangeRegion 조건 위반
    - 0 또는 음수 가격(정책상 금지)

### 2.2 Position 차액 산정 규칙(최소)

- 포지션은 entry(매수) 정보와 current(현재) 정보를 비교한다.
- “현재 프리미엄 차액”은 최소 다음을 의미해야 한다:
    - `premiumDiff = currentPremiumRate - entryPremiumRate` (entryPremium을 저장하는 정책일 때)
    - 또는 entryPremium이 없으면, entry 당시 tickers로 재구성 가능해야 한다(저장 정책에 따라)
- 환율은 매수 환율과 현재 환율을 분리하여 보관/입력받는다.
    - PnL 산식은 정책으로 확정 필요(원화 기준 평가인지, 달러 기준인지)

---

## 3) 설계 결정 사항(결정/보류 항목)

### 3.1 Ticker 상속 여부

- 결론(권장): **상속 대신 단일 Ticker + 타입/Quote로 표현**
    - 이유: COIN_PRICE, FX_RATE는 결국 “Quote 관측값” 모델로 수렴한다.
    - 환율도 `USD/KRW` 같은 Quote로 통합 가능.
- 예외: 제공자별 파싱/수집이 복잡하여 모델을 분리하고 싶다면 “수집 DTO”를 분리하고, **도메인 엔티티는 통합**한다.

### 3.2 환율 방향성(매우 중요)

- 기본 정책(권장): FX Ticker는 **USD/KRW** 단방향으로 통일
    - 역방향(KRW/USD)은 조회 시 계산(1/x)로 파생
- 이 정책을 문서/테스트로 고정한다.

### 3.3 저장 스키마

- Ticker는 관측값이 누적되므로 데이터가 빠르게 증가한다.
    - MVP: 그대로 저장 + (exchange, quote, observedAt) 유니크/인덱스
    - 추후: 파티셔닝/보관정책(TTL, rollup) 고려

---

## 4) 개발 TODO 체크리스트 (DDD/TDD 순서)

## 4.1 도메인 레이어 (우선)

- [ ] `Currency`, `Symbol`, `Quote` VO 구현
    - [ ] Quote 생성 시 base/quote 통화 규칙(예: BTC/KRW, USD/KRW 등)을 명확히
- [ ] `Exchange`, `ExchangeRegion` 정의
    - [ ] 거래소별 region 매핑을 한 곳에서 관리(예: enum property)
- [ ] `Ticker` 엔티티 구현(불변 + 생성 팩토리)
    - [ ] 필수 필드: exchange, region, quote, price, observedAt
    - [ ] 가격 0/음수 방지
- [ ] `Premium` 엔티티 구현
    - [ ] `Premium.create(koreaTicker, foreignTicker, fxTicker)` 팩토리 메서드
    - [ ] 규칙 위반 시 도메인 예외(예: `InvalidPremiumInputException`)
    - [ ] premiumRate 계산 및 scale=2 반올림 적용
- [ ] `Position` 엔티티 구현
    - [ ] entryPrice, quantity, entryFxRate, entryPremiumRate(정책에 따라 필수/선택)
    - [ ] `calculatePremiumDiff(currentPremium)` 메서드
    - [ ] (추가) `calculatePnl(...)` 산식 확정 후 반영

## 4.2 애플리케이션 레이어 (UseCase)

- [ ] `TickerIngestUseCase` (외부 시세 수집 결과 저장)
    - [ ] 입력 DTO → 도메인 Ticker 변환
    - [ ] 저장 성공/중복 처리 정책(같은 exchange+quote+observedAt)
- [ ] `PremiumCreateUseCase`
    - [ ] (symbol, koreaExchange, foreignExchange, observedAt?) 등으로 필요한 tickers 로드
    - [ ] fx ticker 로드
    - [ ] Premium 생성 후 저장
- [ ] `PositionCreateUseCase`
    - [ ] 매수 시점 entry data 저장
    - [ ] entryPremium 저장 정책 결정(저장할 경우 premium 생성/조회 로직 포함)

## 4.3 인프라/DB 레이어

- [ ] Ticker 테이블 설계
    - [ ] PK: id(UUID)
    - [ ] columns: exchange, region, base_symbol, base_currency, quote_currency, price, observed_at
    - [ ] index: (exchange, base_symbol, base_currency, quote_currency, observed_at)
- [ ] Premium 테이블 설계
    - [ ] ticker FK(또는 tickerId 저장)
    - [ ] premium_rate(scale=2), observed_at
- [ ] Position 테이블 설계
    - [ ] entry_price, quantity, entry_fx_rate, entry_premium_rate, entry_observed_at 등

## 4.4 API 레이어(컨트롤러)

- [ ] Ticker 저장 API(내부 수집용) 또는 배치 엔드포인트
- [ ] Premium 조회 API (최신 프리미엄 / 구간 조회)
- [ ] Position 생성/조회 API
- [ ] 에러 응답 규격(도메인 예외 → HTTP 매핑) 정리

---

## 5) TDD 체크리스트 (필수 테스트 케이스)

### 5.0 공통 테스트 규칙

- [ ] 테스트 메서드명(표시명)은 한글로 작성한다.
- [ ] 도메인 VO/엔티티 초기화 규칙을 실패/성공 케이스로 검증한다(예: Symbol, Quote, Ticker).

### 5.1 Premium 도메인 테스트

- [ ] 정상 케이스: 한국(BTC/KRW), 외국(BTC/USD), FX(USD/KRW) → premiumRate 계산 정확
- [ ] 심볼 불일치: BTC vs ETH → 예외
- [ ] region 불일치: 한국 ticker가 FOREIGN → 예외
- [ ] quote 통화 불일치: 외국 ticker가 KRW 마켓 → 예외
- [ ] FX 방향 불일치: KRW/USD 제공 → 예외(또는 허용하고 변환 정책이면 변환 테스트)
- [ ] 가격 0/음수 → 예외
- [ ] 반올림 정책: scale=2, HALF_UP 고정 등 → 기대값 검증

### 5.2 Position 도메인 테스트

- [ ] entryPremium 존재 시: currentPremium과 diff 계산
- [ ] entryFxRate와 currentFxRate 분리 입력 시: PnL 산식(확정 후) 검증
- [ ] quantity 0/음수 방지

### 5.3 Repository/Integration 테스트 (선택 → 단계적으로)

- [ ] Ticker 저장 후 조회(최신 1건) 인덱스 활용 확인
- [ ] Premium 저장 및 tickerId 추적 가능 확인

---

## 6) Codex CLI 작업 지시 템플릿

아래 템플릿을 그대로 Codex CLI에 전달하여, **에이전트들이 토론 후 체크리스트 기반으로 커밋 단위 작업**을 진행하게 합니다.

### 6.1 작업 규칙(커밋 전략)

- 각 작업은 “테스트 → 구현 → 리팩토링” 1 사이클로 끝낸다.
- 커밋 메시지 규칙:
    - `test: ...` (도메인 규칙 테스트 추가)
    - `feat: ...` (도메인/유스케이스 기능)
    - `refactor: ...` (중복 제거/설계 개선)
- PR 단위는 “Premium 도메인 완결”, “Ticker 저장 완결” 등 기능 경계로 묶는다.

### 6.2 Codex CLI 명령 예시

- [ ] “Premium 도메인부터 TDD로 구현”
    - (지시문)
        - `domain/market` 패키지에 VO/Entity를 추가하고, `Premium.create()` 계산 규칙을 테스트부터 작성하라.
        - 테스트 케이스는 5.1 전부 포함하라.
        - 반올림 정책(scale=2)과 예외 타입을 명확히 하라.
- [ ] “Ticker 단일 엔티티로 통합”
    - (지시문)
        - 상속 없이 `Ticker` 하나로 coin/fx를 모두 표현하라.
        - Quote(USD/KRW 등)를 VO로 분리하고, 잘못된 Quote 조합을 생성 시점에 막아라.
- [ ] “Position은 계산 가능한 최소 도메인 기능만 먼저”
    - (지시문)
        - Position 생성과 premiumDiff 계산까지만 먼저 구현하고 테스트를 작성하라.
        - PnL은 산식 정책이 확정되기 전까지 TODO로 남기되, 확장 가능하도록 설계하라.

---

## 7) 오픈 이슈(결정 필요) 목록

- [ ] FX 제공자/거래소를 Exchange에 포함할지, 별도 `FxProvider`로 분리할지
- [ ] Premium의 observedAt을 “계산 시각”으로 할지 “입력 티커의 최대 observedAt”으로 할지
- [ ] Position의 기준 통화(평가통화): KRW 고정인지, 사용자 선택인지
- [ ] entryPremiumRate를 Position에 저장할지(재현성 vs 저장비용)

---

## 7-1) 결정사항

- [ ] FX 제공자/거래소를 Exchange에 포함한다.
- [ ] Premium의 observedAt는 입력 티커의 최대 observedAt”으로 한다.
- [ ] Position의 기준 통화(평가통화는 KRW 고정이다.
- [ ] entryPremiumRate를 Position에 저장한다.

---

## 8) 완료 기준(Definition of Done)

- [ ] Premium 도메인 규칙 테스트가 모두 통과한다.
- [ ] Premium 생성은 잘못된 입력을 모두 예외로 차단한다.
- [ ] Ticker/Premium/Position이 DB에 저장 가능하고, 최소 조회 API 또는 유스케이스로 재현 가능하다.
- [ ] 핵심 계산 로직이 애플리케이션 서비스가 아니라 도메인 모델에 위치한다.
- [ ] 코드 스타일/네이밍이 일관되고, “통화/방향성/region”이 모델에서 명확히 드러난다.

---

## 9) 작업 단위

- Commit 단위로 작업을 수행, confirm을 통해 다음 작업 수행 여부 판단

---
