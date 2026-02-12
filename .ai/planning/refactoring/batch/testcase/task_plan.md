# 테스트케이스 보강 TODO Plan (Scheduler 우선)

## 1. PremiumAggregationScheduler
- [ ] 점검: 기존 테스트 유무/커버 범위 확인
- [ ] 보강: 누락 시나리오 정의
  - [ ] `aggregateMinute/Hour/Day`가 올바른 `JobConfig`로 `jobExecutor.execute` 호출
  - [ ] 일간 집계 시 `saveDay` 경로 보장
  - [ ] `updateSummaryCache`의 `1m/10m/1h/1d` 분기 및 예외 처리 검증
- [ ] 작성: `PremiumAggregationSchedulerTest` 작성 및 통과

## 2. TickerAggregationScheduler
- [ ] 점검: `TARGETS` 반복 처리 및 실패 전파/skip 정책 테스트 유무 확인
- [ ] 보강
  - [ ] `aggregateMinute/Hour/Day` 실행 계약 검증
  - [ ] `runForAllTargets`의 `anySuccess`, `Failure 우선 반환`, `all skipped` 검증
- [ ] 작성: `TickerAggregationSchedulerTest` 작성 및 통과

## 3. PremiumScheduler
- [ ] 점검: 스케줄 메서드의 `jobExecutor + lock config` 검증 유무 확인
- [ ] 보강
  - [ ] `calculatePremium`에서 `premiumRealtimeJob.run()` 위임 보장
  - [ ] `jobName/lockKey/leaseTime` 계약 검증
- [ ] 작성: `PremiumSchedulerTest` 작성 및 통과

## 4. TickerScheduler
- [ ] 점검: 스케줄 실행 위임/락 설정 테스트 유무 확인
- [ ] 보강
  - [ ] 티커 수집 잡 위임 호출 검증
  - [ ] config 값(`lockKey`, `leaseTime`) 회귀 방지 테스트
- [ ] 작성: `TickerSchedulerTest` 작성 및 통과

## 5. ExchangeRateScheduler
- [ ] 점검: 환율 배치 스케줄 경로 테스트 유무 확인
- [ ] 보강
  - [ ] 환율 잡 위임 호출 및 `JobConfig` 계약 검증
  - [ ] 예외/skip 시 `jobExecutor` 반환 처리 검증
- [ ] 작성: `ExchangeRateSchedulerTest` 작성 및 통과

## 공통 완료 조건 (각 Scheduler별)
1. [ ] 정상/skip/failure 경로 모두 검증
2. [ ] `jobExecutor.execute` 인자(`jobName`, `lockKey`, `leaseTime`, `TimeUnit`) 단언
3. [ ] 하위 job 호출 횟수/인자 검증
4. [ ] 시간 단위 관련 로직(분/시/일) 경계 검증
5. [ ] 테스트 명명/구조 통일 후 전체 테스트 실행 통과
