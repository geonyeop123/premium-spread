# Batch Ticker 리팩토링 Task Plan (TDD 기반)

## Goal
`TickerScheduler`와 `TickerAggregationScheduler`를 thin entrypoint로 축소하고,
ticker 수집/집계 경로를 `interfaces -> application -> domain <- infrastructure`로 정렬한다.

## Scope
- 포함
  - ticker 수집 경로 리팩토링
  - ticker aggregation 경로를 공통 `AggregationJob` 기반으로 전환
  - ticker 관련 단위/회귀 테스트 보강
- 제외
  - premium/fx 수집 리팩토링
  - Redis key/TTL 정책 변경

## Current Findings
- `TickerScheduler`가 병렬 호출 orchestration + 저장 + 메트릭/락/last-run 수행
- `TickerAggregationScheduler`가 minute/hour/day 로직을 반복 구현

## Target Design
1. `interfaces/scheduler/TickerScheduler`: `tickerIngestionJob.run()`만 수행
2. `application/job/ticker/TickerIngestionJob`: 외부 ticker 수집 orchestration 담당
3. `interfaces/scheduler/TickerAggregationScheduler`: 분/시간/일 엔트리포인트만 유지
4. `application/job/aggregation/AggregationJob`: ticker minute/hour/day 집계 로직 공통화
5. `domain/ticker/*`: 집계 유효성/정책 및 시간창 정책 사용

## TDD Workflow

### Phase 0: Baseline Capture
- [ ] ticker 수집/집계 메트릭, lock key, last-run key 캡처
- [ ] minute/hour/day 집계 결과 샘플 캡처

### Phase 1: RED
- [ ] `TickerIngestionJobTest` 작성
  - [ ] 병렬 호출 성공 시 saveAll/saveToSeconds 수행
  - [ ] 예외 시 failure
  - [ ] lock 미획득 시 skipped
- [ ] `AggregationJobTest`(ticker config) 작성
  - [ ] minute/hour/day 각각 no_data -> skipped
  - [ ] minute/hour/day 각각 success -> writer 호출

### Phase 2: GREEN
- [ ] `TickerIngestionJob` 구현
- [ ] `TickerScheduler` thin scheduler 전환
- [ ] `TickerAggregationScheduler`를 config 기반 공통 job 호출로 전환
- [ ] ticker 전용 `AggregationConfig` 세트(minute/hour/day) 추가

### Phase 3: REFACTOR
- [ ] 반복되는 minute/hour/day 분기 코드 제거
- [ ] TARGETS 정책을 config/domain으로 이동
- [ ] 중복 로그/메트릭 코드 정리

### Phase 4: Verification Gate
- [ ] ticker 수집/집계 테스트 통과
- [ ] 기존 집계 저장 대상(ZSet/DB) 회귀 없음
- [ ] 기존 주기/lock/metrics 회귀 없음

## File Change Plan
- 주요 수정 대상
  - `apps/batch/src/main/kotlin/io/premiumspread/scheduler/TickerScheduler.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/scheduler/TickerAggregationScheduler.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/cache/TickerCacheService.kt` (필요 시 adapter화)
- 신규 예상
  - `apps/batch/src/main/kotlin/io/premiumspread/application/job/ticker/TickerIngestionJob.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/application/job/aggregation/*` (ticker config)
  - ticker 관련 테스트 파일

## Acceptance Criteria
1. `TickerScheduler`는 트리거 + job 호출만 수행
2. `TickerAggregationScheduler`는 엔트리포인트만 유지하고 로직은 공통 job 사용
3. ticker minute/hour/day 집계 비즈니스 중복 로직 제거
4. 기존 기능/주기/저장 경로 회귀 없음

## Assumptions
1. 수집 대상 거래소/심볼 정책은 기존 기본값(BITHUMB/BINANCE, BTC) 유지
2. 집계 크론/lease 설정은 기존 값 유지
