# Premium -100.0000 버그 조사

## Goal
Premium 조회 시 LOW 또는 OPEN 값이 -100.0000으로 발생하는 원인을 파악하고, premium table에 잘못된 데이터가 저장되는 문제를 해결한다.

## Status
- 현재 단계: 구현 완료 (방안 C)

## Root Cause

**수학적 원인:** `koreaPrice = 0`일 때 프리미엄 공식이 정확히 `-100.0000`을 산출.

```
premiumRate = ((0 - foreignPrice*fxRate) / (foreignPrice*fxRate)) * 100 = -100.0000
```

**코드 원인:** 배치 서버의 `PremiumCalculator.calculate()`와 `PremiumScheduler.calculatePremium()`에서:
1. `tickerCacheService.get()` → ticker null 검사는 하지만 **price=0 검사를 하지 않음**
2. `PremiumCalculator.calculate()` → **입력 가격 검증 없이** 바로 나눗셈 수행
3. 결과적으로 price=0인 TickerData가 계산에 사용되어 -100.0000 발생

**발생 시나리오:** 거래소 API가 일시 장애/다운타임 중 가격을 0으로 반환하면, 캐시에 price=0으로 저장되고 프리미엄 계산에 그대로 사용됨.

## Phases

- [x] Phase 1: 원인 분석 — 완료
- [x] Phase 2: 수정 방안 결정 — 방안 C (다층 방어)
- [x] Phase 3: 구현 및 테스트 — 완료

## 수정 방안 (검토 필요)

### 방안 A: PremiumScheduler에서 가격 검증 (조기 차단)
```kotlin
// PremiumScheduler.calculatePremium() 내
if (bithumbTicker.price <= BigDecimal.ZERO || binanceTicker.price <= BigDecimal.ZERO || fxRate <= BigDecimal.ZERO) {
    log.warn("Invalid price detected - skip calculation")
    return@withLock
}
```
- 장점: 가장 상위에서 차단, 이후 모든 로직에 영향 없음
- 단점: 배치 서버에만 적용, API 서버 경로는 별도 방어 필요

### 방안 B: PremiumCalculator에서 입력 검증 (계산기 보호)
```kotlin
fun calculate(...): PremiumCacheData {
    require(koreaTicker.price > BigDecimal.ZERO) { "Korea price must be positive" }
    require(foreignTicker.price > BigDecimal.ZERO) { "Foreign price must be positive" }
    require(fxRate > BigDecimal.ZERO) { "FX rate must be positive" }
    ...
}
```
- 장점: 계산 로직 자체가 잘못된 입력을 거부
- 단점: 예외 발생 → 호출측에서 핸들링 필요

### 방안 C: 다층 방어 (A + B 조합) ← 권장
- PremiumScheduler에서 조기 return (로깅 + 메트릭)
- PremiumCalculator에서 require 검증 (방어적)
- API 서버의 `Premium.create()`에도 동일 검증 (이미 `Ticker.init`에서 price > 0 검증 있음)

## 관련 파일

| 파일 | 수정 필요 여부 |
|------|------------|
| `apps/batch/.../PremiumScheduler.kt` | 수정 (price > 0 검증 추가) |
| `apps/batch/.../PremiumCalculator.kt` | 수정 (입력 검증 추가) |
| `apps/batch/.../TickerCacheService.kt` | 검토 (price=0 반환 정책) |
| `apps/api/.../Premium.kt` | 검토 (이미 Ticker.init에서 price>0 검증 있음) |
