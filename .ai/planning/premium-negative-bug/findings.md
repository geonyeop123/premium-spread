# Findings: Premium -100.0000 버그

## 근본 원인

### 수학적 원인

```
premiumRate = ((koreaPrice - foreignPrice * fxRate) / (foreignPrice * fxRate)) * 100
```

**koreaPrice = 0**이고 foreignPrice > 0일 때:
```
= ((0 - X) / X) * 100
= (-X / X) * 100
= -100.0000
```

→ 한국 거래소 가격이 0(또는 누락)이면 정확히 **-100.0000**이 계산됨.

### 검증 누락 지점 2곳

**1. 배치 서버 — `PremiumCalculator.kt` (apps/batch)**
```kotlin
fun calculate(koreaTicker: TickerData, foreignTicker: TickerData, fxRate: BigDecimal): PremiumCacheData {
    val foreignPriceInKrw = foreignTicker.price.multiply(fxRate)
    val premiumRate = priceDiff.divide(foreignPriceInKrw, ...) // ← zero check 없음
}
```

**2. API 서버 — `Premium.kt` (apps/api)**
```kotlin
private fun calculatePremiumRate(koreaPrice: BigDecimal, foreignPriceUsd: BigDecimal, fxRate: BigDecimal): BigDecimal {
    val foreignPriceInKrw = foreignPriceUsd.multiply(fxRate)
    return diff.divide(foreignPriceInKrw, ...) // ← zero check 없음
}
```

→ 두 곳 모두 **입력 가격이 0인지 검증하지 않음**

## 발생 경로

```
거래소 API → 가격 0 반환 (다운타임, 일시 장애 등)
    ↓
TickerCacheService.get() → price=0인 TickerData 반환 (검증 없음)
    ↓
PremiumCalculator.calculate() → premiumRate = -100.0000 계산
    ↓
PremiumCacheService.saveToSeconds() → Redis ZSet에 -100.0000 저장
    ↓
PremiumCacheService.aggregateSecondsData()
    ↓
low = rates.minOf { it } → -100.0000 (최솟값으로 선택됨)
open = rates.first()     → -100.0000 (첫 번째 값일 경우)
    ↓
premium_minute / premium_hour / premium_day 테이블에 저장
```

## 영향 범위

| 테이블 | 영향 컬럼 | 설명 |
|--------|----------|------|
| `premium_minute` | low, open | 분 단위 OHLC 집계 |
| `premium_hour` | low, open | 시간 단위 OHLC 집계 |
| `premium_day` | low, open | 일 단위 OHLC 집계 |
| Redis `premium:{symbol}:seconds` | raw value | 초 단위 원시 데이터 |
| Redis `premium:{symbol}` | current | 현재 프리미엄 |

## 관련 파일

| 파일 | 역할 | 문제 |
|------|------|------|
| `apps/batch/.../PremiumCalculator.kt` | 프리미엄 계산 | 가격 0 검증 없음 |
| `apps/api/.../Premium.kt` | 도메인 계산 | 가격 0 검증 없음 |
| `apps/batch/.../TickerCacheService.kt` | 캐시 읽기 | price=0 TickerData 반환 가능 |
| `apps/batch/.../PremiumScheduler.kt` | 스케줄러 | ticker null은 체크하지만 price=0은 통과 |
| `apps/batch/.../PremiumCacheService.kt` | 집계 | -100.0000을 정상값으로 집계에 포함 |
