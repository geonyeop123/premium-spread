# WU-04 DB 쿼리 최적화 — 진행 상황

## 완료 항목

### 1. 인덱스 추가 마이그레이션 (V8)
- ticker 테이블: id+price+observed_at 커버링 인덱스 (JOIN 최적화)
- premium 테이블: symbol+observed_at DESC + ticker FK 커버링 인덱스
- ticker_minute/hour/day: exchange+symbol+currency+시간 DESC 복합 인덱스

### 2. 집계 테이블 currency 컬럼 추가
- ticker_minute, ticker_hour, ticker_day에 currency VARCHAR(10) NOT NULL 추가
- exchange 기준 백필: UPBIT/BITHUMB -> KRW, BINANCE -> USD, FX_PROVIDER -> KRW
- 배치 TickerAggregationRepository save 메서드에 currency 파라미터 추가
- TickerCacheService 집계 메서드에 currency 파라미터 전달
- TickerAggregationScheduler TARGETS에 currency 정보 추가

### 3. PremiumRepositoryImpl N+1 해소
- 기존: Premium 조회 후 ticker 3건 개별 조회 (4 쿼리)
- 변경: JPQL JOIN 쿼리로 1회 조회 (PremiumJpaRepository.findLatestSnapshotBySymbol)
- PremiumRepositoryImpl에서 TickerRepository 의존성 제거

### 4. TickerRepositoryImpl 하드코딩 제거
- 기존: aggregation fallback 시 `currency = "KRW"` 하드코딩
- 변경: TickerAggregationSnapshot에 currency 필드 추가, DB에서 읽기

## 테스트 결과
- `./gradlew compileKotlin` : BUILD SUCCESSFUL
- `./gradlew test` : BUILD SUCCESSFUL (전체 테스트 통과)
