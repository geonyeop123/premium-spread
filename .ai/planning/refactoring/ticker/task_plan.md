# Ticker 아키텍처 리팩토링 Task Plan

## Goal

`TickerCacheFacade` (application)의 infrastructure 직접 참조를 제거하고,
cache→DB fallback 정책을 infrastructure의 RepositoryImpl 내부로 이동한다.
premium 리팩토링(`PremiumCacheFacade` → `PremiumRepositoryImpl`)과 동일한 패턴을 적용한다.

## Scope

- 포함
    - `apps/api` 내 ticker 관련 계층 정리
    - FX Rate 캐시 fallback도 함께 정리 (TickerCacheFacade에 포함되어 있으므로)
    - FX Rate를 별도 도메인 패키지로 분리 (`domain/exchangerate/`)
    - `application → infrastructure` 직접 의존 제거 (ticker 관련)
    - 최소 회귀 테스트 보강
- 제외
    - `position` 도메인 리팩토링 (`PositionFacade`의 `PositionCacheWriter`)
    - batch 서버 변경 (이미 완료된 집계 파이프라인)
    - 새로운 API endpoint 추가

## Current Findings

- `TickerCacheFacade`가 8건의 infrastructure import 보유
- cache→aggregation→DB fallback 정책이 application에 위치
- **TickerCacheFacade는 production에서 사용되지 않는 dead code** (소비자 0건)
- ticker와 FX rate 두 가지 책임이 하나의 Facade에 혼재

## Target Design

### 계층 규칙 (기존 확립)

```
interfaces → application → domain ← infrastructure

Facade(application) → Service(domain) → Repository(domain interface)
                                              └→ RepositoryImpl(infrastructure)
```

- **Facade는 Service만 주입** (Repository 직접 주입 금지)
- **Service는 자기 도메인의 Repository만 주입**
- **RepositoryImpl이 cache/DB 전략을 내부에서 결정**

### Ticker 조회 경로

```
Facade → TickerService → TickerRepository (domain interface)
                              └→ TickerRepositoryImpl: cache → aggregation(close) → DB
```

- `TickerSnapshot` read model 도입 (`domain/ticker/`)
- `TickerRepository`에 `findLatestSnapshotByExchangeAndSymbol()` 추가
- `TickerRepositoryImpl`이 cache→aggregation→DB 전략을 내부에서 수행

### FX Rate 조회 경로

```
Facade → ExchangeRateService → ExchangeRateRepository (domain interface)
                                    └→ ExchangeRateRepositoryImpl: cache → exchange_rate DB
```

- `ExchangeRateSnapshot` read model 도입 (`domain/exchangerate/`)
- `ExchangeRateRepository` 인터페이스 신규 (`domain/exchangerate/`)
- `ExchangeRateService` 신규 (`domain/exchangerate/`) — Repository 위임
- `ExchangeRateRepositoryImpl`이 cache→DB 전략을 내부에서 수행

### 완료 조건

1. `application` 계층에서 ticker 관련 infrastructure import 0건
2. cache→DB fallback은 infrastructure 내부에서만 결정
3. ticker 관련 테스트 통과
4. `TickerCacheFacade` 제거
5. FX Rate가 별도 도메인 패키지(`domain/exchangerate/`)로 분리

## 설계 판단 포인트

### Q1: TickerCacheFacade가 dead code인데 제거만 하면 되는가?

**아니오.** TickerCacheFacade의 fallback 로직 자체는 유용하다.
`PremiumRepositoryImpl`이 ticker enrichment에 `TickerRepository.findById()`를 사용하므로,
향후 ticker 조회 API가 추가되면 동일한 cache→DB fallback이 필요하다.
→ **로직은 TickerRepositoryImpl로 이동, Facade는 제거**

### Q2: FX Rate를 TickerRepository에 포함할 것인가, 별도로 분리할 것인가?

**도메인 레벨에서 완전 분리.**

- 데이터 소스가 다름: ticker 테이블 vs exchange_rate 테이블
- 캐시 키가 다름: `ticker:{exchange}:{symbol}` vs `fx:{base}:{quote}`
- 도메인 개념이 다름: 코인 시세 vs 환율

분리 근거가 domain 개념 차이에 기반하므로, domain 패키지도 분리해야 일관성이 유지된다.

```
domain/ticker/           ← 코인 시세
domain/exchangerate/     ← 환율
```

Facade가 Repository를 직접 주입받는 것은 아키텍처 위반이므로,
FX Rate도 `ExchangeRateService`(domain)를 경유해야 한다.

```
// Good: Facade → Service → Repository
Facade → ExchangeRateService → ExchangeRateRepository

// Bad: Facade → Repository 직접 주입
Facade → ExchangeRateRepository  ← 아키텍처 위반
```

### Q3: TickerSnapshot에 aggregation의 OHLC 데이터를 포함할 것인가?

**아니오.** 현재 TickerCacheFacade는 aggregation의 `close`만 price로 사용한다.
TickerSnapshot은 "현재 가격" 조회용이므로 단순 price만 포함.
OHLC는 별도 chart API에서 직접 aggregation 테이블을 조회하는 것이 적절.

## Phases

### Phase 0: Baseline Capture

- [ ] 기존 ticker 관련 테스트 실행 및 결과 기록
- [ ] `application` 계층 infrastructure import 현황 기록 (8건 + PositionFacade 1건)

### Phase 1: Domain — Read Model + Repository 인터페이스 + Service

**Ticker (기존 패키지 수정):**
- [ ] `TickerSnapshot` data class 생성 (`domain/ticker/`)
    - exchange, symbol, currency, price, volume?, observedAt
- [ ] `TickerRepository`에 `findLatestSnapshotByExchangeAndSymbol()` 메서드 추가
- [ ] `TickerService`에 snapshot 위임 메서드 추가
    - `findLatestSnapshot(exchange, symbol): TickerSnapshot?`

**ExchangeRate (신규 도메인 패키지):**
- [ ] `domain/exchangerate/` 패키지 생성
- [ ] `ExchangeRateSnapshot` data class 생성
    - baseCurrency, quoteCurrency, rate, observedAt
- [ ] `ExchangeRateRepository` 인터페이스 생성
    - `findLatestSnapshot(baseCurrency, quoteCurrency): ExchangeRateSnapshot?`
- [ ] `ExchangeRateService` 생성 — Repository 위임
    - `findLatestSnapshot(baseCurrency, quoteCurrency): ExchangeRateSnapshot?`

### Phase 2: Infrastructure 구현

- [ ] `TickerRepositoryImpl` 수정
    - `TickerCacheReader`, `TickerAggregationQueryRepository` 주입
    - `findLatestSnapshotByExchangeAndSymbol()` 구현: cache → aggregation(close) → DB
- [ ] `ExchangeRateRepositoryImpl` 생성 (`infrastructure/exchangerate/`)
    - `FxCacheReader`, `ExchangeRateQueryRepository` 주입
    - `findLatestSnapshot()` 구현: cache → exchange_rate DB

### Phase 3: Application 정리

- [ ] `TickerCacheFacade` 제거 (전체 삭제)
- [ ] `TickerCacheFacadeTest` 제거 (전체 삭제)
- [ ] `TickerCacheResult`, `FxRateCacheResult` 제거 (TickerCacheFacade.kt 내 정의)
- [ ] application 계층의 ticker 관련 infrastructure import 0건 확인

### Phase 4: 테스트 보강

- [ ] `TickerRepositoryImplTest` 작성
    - cache hit → snapshot 반환
    - cache miss + aggregation hit → close를 price로 사용
    - cache miss + aggregation miss + DB hit → DB 반환
    - all miss → null
- [ ] `ExchangeRateRepositoryImplTest` 작성
    - cache hit → snapshot 반환
    - cache miss + DB hit → DB 반환
    - all miss → null
- [ ] `ExchangeRateServiceTest` 작성
    - findLatestSnapshot 위임 검증

### Phase 5: Verification Gate

- [ ] ticker 관련 단위/통합 테스트 재실행
- [ ] `rg "^import io\\.premiumspread\\.infrastructure\\." apps/api/src/main/kotlin/io/premiumspread/application/ticker`
  결과 0건 확인
- [ ] 변경 내역을 `findings.md`, `progress.md`에 반영

## File Change Plan

### 신규 파일

| 파일 | 계층 | 역할 |
|------|------|------|
| `domain/ticker/TickerSnapshot.kt` | domain | read model |
| `domain/exchangerate/ExchangeRateSnapshot.kt` | domain | read model |
| `domain/exchangerate/ExchangeRateRepository.kt` | domain | Repository 인터페이스 |
| `domain/exchangerate/ExchangeRateService.kt` | domain | Repository 위임 서비스 |
| `infrastructure/exchangerate/ExchangeRateRepositoryImpl.kt` | infrastructure | cache→DB fallback |
| `test/.../TickerRepositoryImplTest.kt` | test | 단위 테스트 |
| `test/.../ExchangeRateRepositoryImplTest.kt` | test | 단위 테스트 |
| `test/.../ExchangeRateServiceTest.kt` | test | 단위 테스트 |

### 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `domain/ticker/TickerRepository.kt` | `findLatestSnapshotByExchangeAndSymbol()` 추가 |
| `domain/ticker/TickerService.kt` | snapshot 위임 메서드 추가 |
| `infrastructure/ticker/TickerRepositoryImpl.kt` | cache→aggregation→DB fallback 구현 |

### 삭제 파일

| 파일 | 이유 |
|------|------|
| `application/ticker/TickerCacheFacade.kt` | infrastructure 직접 참조 + dead code |
| `test/.../TickerCacheFacadeTest.kt` | 위 파일 삭제에 따른 테스트 제거 |

## Risks

- TickerCacheFacade 제거 시 향후 ticker 조회 API 추가 시 RepositoryImpl fallback이 올바르게 동작하는지 사전 검증 필요
- aggregation fallback에서 close 값을 price로 사용하는 기존 로직의 정확성

## Assumptions

1. TickerCacheFacade의 production 소비자가 없으므로 삭제해도 기능 영향 없음
2. FX Rate는 ticker와 별도 도메인으로 분리 (`domain/exchangerate/`)
3. Facade → Service → Repository 계층 규칙 준수 (Facade가 Repository 직접 주입 금지)
4. 기존 cache TTL/키 정책은 유지
5. TickerSnapshot은 "현재 가격 조회"용이며 OHLC는 포함하지 않음
