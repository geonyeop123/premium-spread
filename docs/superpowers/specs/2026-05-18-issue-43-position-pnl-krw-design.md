# Position PnL 수식 KRW 손익 확장

- Issue: #43 (Parent: #40)
- Branch: `feat/issue-43-position-pnl-krw`
- Date: 2026-05-18
- Depends on: ✅ #41 (페어 모델 dev 머지), ✅ #42 (AUTO/MANUAL dev 머지)

## 배경

#41/#42를 통해 Position이 한국 long + 해외 short 페어 모델로 자리잡고 AUTO/MANUAL 두 진입 경로가 정리되었다. 그러나 `PositionFacade.calculatePnl`은 여전히 `premiumDiff(%p)`만 반환하며, 페어 진입가/수량/환율을 사용하는 실제 KRW 손익 계산이 없다. `quantity/entryPrice/entryFxRate`가 PnL에 활용되지 않는 죽은 필드 상태이다.

본 이슈는 PnL 수식을 **페어 기반 KRW 손익**으로 확장하고, 응답에 `koreaPnl/foreignPnlKrw/totalPnlKrw/koreaCurrentValue/totalPnlPercent`를 포함시킨다. 프론트엔드 표시는 #44에서 처리.

## 목표 / 스코프

### 포함

- `Position.calculatePnl(currentKoreaPrice, currentForeignPrice, currentFxRate, currentPremiumRate): PositionPnl` 신규 메서드 (4개 BigDecimal 인자, **PremiumSnapshot에 직접 의존하지 않음** — Facade가 snapshot을 분해해서 전달)
- 기존 `Position.calculatePremiumDiff(currentPremiumRate)` 제거
- `PositionPnl` data class 확장 — `koreaPnl`, `foreignPnlKrw`, `totalPnlKrw`, `koreaCurrentValue`, `totalPnlPercent` 추가
- `PositionResult.Pnl`, `PositionResponse.Pnl` 동일 필드 확장
- `PositionFacade.calculatePnl`을 `findLatestSnapshotBySymbol` 기반으로 교체
- 사용자 예시값(0.157/0.15) 회귀 테스트로 +1,808,138원, +9.73% 검증

### 제외 (후속 / 범위 외)

- 프론트엔드 PnL 카드 확장 → #44
- PremiumSnapshot 거래소 매칭 — symbol 기반 그대로 (Codex #42 P2 지적, premium 도메인 확장이 필요한 별도 작업)

## PnL 수식

### 입력

- Position 엔티티 (저장된 페어 진입 정보): `koreaEntryPrice`, `koreaQuantity`, `foreignEntryPrice`, `foreignQuantity`, `entryPremiumRate`
- `PremiumSnapshot` (현재 시세): `koreaPrice`, `foreignPrice`, `fxRate`, `premiumRate`, `observedAt`

### 수식 (한국 long + 해외 short 고정)

```
koreaPnl          = (currentKoreaPrice - koreaEntryPrice) * koreaQuantity
foreignPnlUsd     = (foreignEntryPrice - currentForeignPrice) * foreignQuantity      // short
foreignPnlKrw     = foreignPnlUsd * currentFxRate
totalPnlKrw       = koreaPnl + foreignPnlKrw
koreaCurrentValue = currentKoreaPrice * koreaQuantity
totalPnlPercent   = totalPnlKrw / koreaCurrentValue * 100
premiumDiff       = currentPremiumRate - entryPremiumRate    // 기존 유지 (%p)
isProfit          = totalPnlKrw > 0                          // 기존(premiumDiff < 0)에서 변경
calculatedAt      = Instant.now()
```

### 정밀도

- 모든 BigDecimal 중간 계산: 그대로
- `koreaPnl`, `foreignPnlKrw`, `totalPnlKrw`: KRW 정수 단위로 표시이지만 BigDecimal 그대로 (소수점 절사 X — 응답 시 클라이언트가 결정)
- `koreaCurrentValue`: KRW BigDecimal
- `totalPnlPercent`: scale 2, `RoundingMode.HALF_UP` (예: 9.73)
- `premiumDiff`: 기존 그대로 — `currentPremiumRate.subtract(entryPremiumRate)` (정밀도 그대로)

### 입력 검증 (calculatePnl 진입 시)

다음 인자가 0 이하이면 **`IllegalArgumentException` throw** (GlobalExceptionHandler가 400 처리):

- `currentKoreaPrice` ≤ 0
- `currentForeignPrice` ≤ 0
- `currentFxRate` ≤ 0

이는 잘못된 시세 데이터(예: 수집 장애로 0이 들어온 snapshot)가 PnL 계산을 통과해 0.00%처럼 정상 응답이 되는 것을 막는다. 정상 시세는 항상 양수.

`currentPremiumRate`는 음수 가능(역김프) — 검증 대상 아님.

### isProfit 시맨틱 변경

| 항목 | #42까지 | #43 |
|------|---------|------|
| `isProfit()` | `premiumDiff < 0` (김프 축소 = 이익) | `totalPnlKrw > 0` (실제 KRW 손익 양수) |

본 변경은 **breaking semantic** — 동일 데이터에 대해 결과가 달라질 수 있음. 다만 #44 머지 전까지 프론트는 `isProfit` 표시를 정확히 못 하고 있어(이미 #41/#42 시점부터 깨진 상태), 실 사용자 영향 없음.

### 사용자 예시 검증

| 항목 | 값 |
|------|-----|
| koreaEntryPrice | 161,493,792 |
| koreaQuantity | 0.157 |
| foreignEntryPrice | 118,100 |
| foreignQuantity | 0.15 |
| currentKoreaPrice | 118,326,000 |
| currentForeignPrice | 79,699.1 |
| currentFxRate | 1490.5 |

계산:
- `koreaPnl = (118,326,000 - 161,493,792) * 0.157 = -43,167,792 * 0.157 = -6,777,343.344`
- `foreignPnlUsd = (118,100 - 79,699.1) * 0.15 = 38,400.9 * 0.15 = 5,760.135`
- `foreignPnlKrw = 5,760.135 * 1490.5 = 8,585,481.2175`
- `totalPnlKrw = -6,777,343.344 + 8,585,481.2175 = 1,808,137.8735` ≈ **+1,808,138** ✓
- `koreaCurrentValue = 118,326,000 * 0.157 = 18,577,182`
- `totalPnlPercent = 1,808,137.8735 / 18,577,182 * 100 = 9.7330...` → setScale(2) = **9.73** ✓

회귀 테스트는 위 fixture를 정밀하게 사용한다.

## 도메인/레이어 변경

```
domain/position/
  Position.kt
    - calculatePremiumDiff(currentPremiumRate) 제거
    + calculatePnl(
        currentKoreaPrice: BigDecimal,
        currentForeignPrice: BigDecimal,
        currentFxRate: BigDecimal,
        currentPremiumRate: BigDecimal,
      ): PositionPnl 추가
      내부에서 인자 양수 검증 (위반 시 IllegalArgumentException → 400)
  PositionPnl.kt
    필드 확장: koreaPnl, foreignPnlKrw, totalPnlKrw, koreaCurrentValue, totalPnlPercent
    isProfit() 시맨틱 변경 (totalPnlKrw > 0)

application/position/
  PositionFacade.calculatePnl:
    findLatestBySymbol → findLatestSnapshotBySymbol로 교체
    snapshot을 분해해서 position.calculatePnl(...) 호출
  PositionDtos.PositionResult.Pnl:
    필드 확장 (위 5개 추가)

interfaces/api/position/
  PositionDtos.PositionResponse.Pnl:
    필드 확장 (위 5개 추가)
```

### 도메인 결합 정책

Position 엔티티는 **PremiumSnapshot에 직접 의존하지 않는다.** `calculatePnl`은 BigDecimal 4개를 받는 순수 도메인 메서드이며, snapshot 분해는 application(facade) 책임. 이로써 premium 도메인의 read-model 변경이 position 도메인으로 침투하지 않는다.

## API 응답

```http
GET /api/v1/positions/{id}/pnl

Response 200:
{
  "positionId": 42,
  "entryPremiumRate": "-10.12",
  "currentPremiumRate": "-0.39",
  "premiumDiff": "9.73",
  "koreaPnl": "-6777343.344",
  "foreignPnlKrw": "8585481.2175",
  "totalPnlKrw": "1808137.8735",
  "koreaCurrentValue": "18577182",
  "totalPnlPercent": "9.73",
  "isProfit": true,
  "calculatedAt": "..."
}
```

JSON 직렬화 시 BigDecimal은 그대로. 프론트엔드(#44)에서 소수점 처리 결정.

## 영향 범위

- `apps/api/src/main/kotlin/io/premiumspread/domain/position/Position.kt`
- `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionPnl.kt`
- `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`
- `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt` (PositionResult.Pnl)
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt` (PositionResponse.Pnl)
- `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionTest.kt` (calculatePremiumDiff → calculatePnl 회귀)
- `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt` (snapshot mock 기반)
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt`

## 테스트 전략

### Unit (Position)

- `calculatePnl` 정상 케이스 — 사용자 예시값(0.157/0.15) 입력 시 위 검증값 그대로 통과
- `calculatePnl` 양쪽 손실 / 양쪽 이익 등 boundary 케이스
- **`isProfit`과 `premiumDiff` 부호 불일치 케이스** — `premiumDiff > 0`인데 `totalPnlKrw < 0`(또는 그 반대)일 때 `isProfit`이 totalPnlKrw 기준임을 명시적으로 검증 (시맨틱 변경 회귀)
- `calculatePnl` 시세 양수 검증 — `currentKoreaPrice = 0` → IllegalArgumentException, `currentForeignPrice = 0` → IllegalArgumentException, `currentFxRate = 0` → IllegalArgumentException
- 음수 케이스도 동일

### Unit (Facade)

- `calculatePnl` 정상 — snapshot mock + position fixture
- `calculatePnl` — snapshot 없을 때 `PremiumNotFoundException` (메시지 일관성 유지)
- `calculatePnl` — 소유권 위반 시 `PositionNotFoundException`

### Integration (Controller)

- `GET /positions/{id}/pnl` 정상 — 응답에 신규 필드 모두 포함
- `GET /positions/{id}/pnl` — snapshot 없을 때 404
- `GET /positions/{id}/pnl` — 소유권 위반 시 404

## 위험 / 주의사항

- **`isProfit` 시맨틱 변경 (Breaking semantic)**: 같은 데이터에 대해 결과가 달라짐. 그러나 프론트는 #41/#42 시점부터 PnL 정확히 표시 불가 상태였으므로 실 사용자 영향 없음. 회귀 테스트로 premiumDiff와 totalPnlKrw 부호 불일치 케이스를 명시적으로 단언.
- **PnL 정확성 부분 해소 (완전 X)**: `findLatestBySymbol`(Premium 엔티티 — koreaPrice 없음) → `findLatestSnapshotBySymbol`(PremiumSnapshot — koreaPrice/foreignPrice/fxRate 있음)로 교체되어 페어 가격 기반 손익 계산이 가능해졌다. **그러나 snapshot은 여전히 symbol 단일 기준**이며 `Position.koreaExchange`/`foreignExchange`와 매칭되지 않을 수 있다 — 사용자가 BITHUMB 페어를 잡았는데 snapshot이 UPBIT 기반이면 그 가격이 들어간다. 거래소 매칭은 premium 도메인이 다중 거래소 지원으로 확장될 때 해소되는 별도 후속 작업. **본 PR은 "정확성 일부 해소"** — 단일 거래소 데이터 환경(현 dev 가정)에선 정확.
- **Snapshot 신선도**: 본 이슈에서는 PnL 계산 시 신선도 검증을 **추가하지 않음**. PnL 조회는 실시간 화면 갱신용이라 stale 응답이 차라리 낫다(클라이언트가 무한 폴링하지 않게). AUTO 진입 시점만 신선도 검증.
- **시세 양수 검증**: snapshot의 koreaPrice/foreignPrice/fxRate가 0 이하면 `IllegalArgumentException` throw. 이는 수집 장애로 0이 들어왔을 때 0.00%로 마스킹되지 않도록 하기 위함.
- **응답의 BigDecimal 소수점**: KRW는 정수가 자연스럽지만, 본 PR은 BigDecimal 그대로 응답. 프론트(#44)에서 `Math.round` 또는 `toFixed(0)` 처리.

## Out of Scope

- 프론트엔드 PnL 카드 확장 → #44
- PremiumSnapshot 거래소 매칭 (premium 도메인 확장) → 별도 후속 작업
