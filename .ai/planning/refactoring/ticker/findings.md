# Findings: Ticker 아키텍처 리팩토링

## 요약

`TickerCacheFacade` (application)이 infrastructure를 직접 참조하고 있으며,
cache→DB fallback 정책이 application에 위치하여 `application → domain ← infrastructure` 원칙에 위반된다.
premium 리팩토링과 동일한 패턴을 적용해야 한다.

## 확인된 사실

### 1) Infrastructure 직접 의존 지점

**`TickerCacheFacade.kt` (application) — 8건의 infrastructure import:**

```kotlin
import io.premiumspread.infrastructure.cache.CachedTicker
import io.premiumspread.infrastructure.cache.FxCacheReader
import io.premiumspread.infrastructure.cache.TickerCacheReader
import io.premiumspread.infrastructure.cache.CachedFxRate
import io.premiumspread.infrastructure.exchangerate.ExchangeRateQueryRepository
import io.premiumspread.infrastructure.exchangerate.ExchangeRateSnapshot
import io.premiumspread.infrastructure.ticker.TickerAggregationQueryRepository
import io.premiumspread.infrastructure.ticker.TickerAggregationSnapshot
```

**`PositionFacade.kt` (application) — 1건 (별도 범위):**

```kotlin
import io.premiumspread.infrastructure.cache.PositionCacheWriter
```

### 2) TickerCacheFacade 소비자 분석

| 소비자 | 파일 | 상태 |
|--------|------|------|
| Production 코드 | — | **없음 (dead code)** |
| 테스트 | `TickerCacheFacadeTest.kt` | 있음 |

→ **TickerCacheFacade는 현재 production에서 사용되지 않는 dead code**
→ Controller에 ticker 조회 endpoint도 없음 (POST ingest만 존재)

### 3) TickerCacheFacade의 두 가지 책임

TickerCacheFacade는 두 개의 **서로 다른 도메인**을 하나의 Facade에서 처리:

| 책임 | 캐시 소스 | DB fallback | 관련 테이블 |
|------|----------|-------------|-----------|
| Ticker 조회 | `TickerCacheReader` (ticker:{exchange}:{symbol}) | ticker_minute → ticker | ticker, ticker_minute |
| FX Rate 조회 | `FxCacheReader` (fx:{base}:{quote}) | exchange_rate | exchange_rate |

→ 이 두 책임을 분리하여 각각의 Repository에서 처리해야 한다.

### 4) 데이터 shape 비교

**Ticker 소스별:**

| 소스 | 필드 |
|------|------|
| Cache (`CachedTicker`) | exchange, symbol, currency, price, volume, timestamp |
| DB (`Ticker` entity) | exchange, quote(baseCode+currency), price, observedAt |
| Aggregation (`TickerAggregationSnapshot`) | exchange, symbol, OHLC, observedAt |

→ 세 소스의 shape이 다름 → **Read Model (TickerSnapshot) 도입 필요**
→ aggregation fallback 시 `close` 값을 price로 사용 (현재 로직)

**FX Rate 소스별:**

| 소스 | 필드 |
|------|------|
| Cache (`CachedFxRate`) | baseCurrency, quoteCurrency, rate, timestamp |
| DB (`ExchangeRateSnapshot`) | baseCurrency, quoteCurrency, rate, observedAt |

→ shape이 거의 동일 → **Read Model (FxRateSnapshot) 도입**으로 정규화 가능

### 5) 기존 TickerRepositoryImpl 상태

```kotlin
// 현재 — 순수 JPA 위임만 수행
class TickerRepositoryImpl(
    private val tickerJpaRepository: TickerJpaRepository,
) : TickerRepository {
    // save, findById, findLatest, findAllByExchangeAndSymbol
}
```

→ 캐시 연동 없음, 순수 DB 조회만 수행
→ premium의 `PremiumRepositoryImpl`처럼 캐시 fallback 로직을 여기로 이동해야 함

### 6) FX Rate 도메인 구조 부재

- 현재 FX Rate 전용 domain 인터페이스/Repository가 없음
- `ExchangeRateQueryRepository`가 infrastructure에 직접 존재 (JDBC)
- `FxCacheReader`가 infrastructure에 존재
- FX Rate 전용 domain Repository 인터페이스 도입 필요

## 관련 파일 맵

| 파일 | 계층 | 역할 | 수정 필요 |
|------|------|------|---------|
| `application/ticker/TickerCacheFacade.kt` | application | cache→DB fallback (위반) | 제거 |
| `test/.../TickerCacheFacadeTest.kt` | test | TickerCacheFacade 테스트 | 제거 |
| `domain/ticker/TickerRepository.kt` | domain | Repository 인터페이스 | 수정 (snapshot 메서드 추가) |
| `domain/ticker/TickerService.kt` | domain | 도메인 서비스 | 수정 (snapshot 위임 추가) |
| `infrastructure/ticker/TickerRepositoryImpl.kt` | infrastructure | JPA 위임만 | 수정 (cache→aggregation→DB fallback) |
| `infrastructure/ticker/TickerAggregationQueryRepository.kt` | infrastructure | ticker_minute JDBC 조회 | 유지 |
| `infrastructure/cache/TickerCacheReader.kt` | infrastructure | Redis ticker 캐시 | 유지 |
| `infrastructure/cache/FxCacheReader.kt` | infrastructure | Redis FX 캐시 | 유지 |
| `infrastructure/exchangerate/ExchangeRateQueryRepository.kt` | infrastructure | exchange_rate JDBC 조회 | 유지 |

## Premium 리팩토링과의 비교

| 항목 | Premium (완료) | Ticker (계획) |
|------|---------------|--------------|
| 위반 파일 | `PremiumCacheFacade` | `TickerCacheFacade` |
| infra import 수 | 3건 | 8건 |
| Production 소비자 | Controller가 사용 | **없음 (dead code)** |
| Read Model | `PremiumSnapshot` | `TickerSnapshot` + `FxRateSnapshot` (신규) |
| 책임 수 | 1개 (premium) | 2개 (ticker + FX rate) |
| fallback 단계 | 2단계 (cache→DB) | 3단계 (cache→aggregation→DB) |
