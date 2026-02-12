# Batch Premium 리팩토링 Task Plan (TDD 기반)

## Goal
`PremiumScheduler`를 thin entrypoint로 축소하고, premium 실시간 계산 경로를
`interfaces -> application -> domain <- infrastructure`로 정렬한다.

## Scope
- 포함
  - premium 실시간 계산 경로 리팩토링
  - `JobExecutor` 연동
  - premium 관련 단위/회귀 테스트 보강
- 제외
  - ticker/fx 리팩토링
  - aggregation 전면 공통화

## Current Findings
- `PremiumScheduler`가 입력 검증, 분기(history), 저장, 메트릭, 락을 함께 수행
- 유스케이스 orchestration이 scheduler에 위치

## Target Design
1. `interfaces/scheduler/PremiumScheduler`: `premiumRealtimeJob.run()` 호출만 수행
2. `application/job/premium/PremiumRealtimeJob`: orchestration 전담
3. `domain/premium/*`: 입력 검증 정책, 계산 정책, history 정책 분리
4. `infrastructure/premium/*`: cache/repository gateway로 외부 I/O 유지

## TDD Workflow

### Phase 0: Baseline Capture
- [ ] 기존 PremiumScheduler 동작 시나리오 캡처 (missing_data, invalid_price, success, history)
- [ ] 기존 메트릭/lock/last-run 키 기록

### Phase 1: RED
- [ ] `PremiumRealtimeJobTest` 작성
  - [ ] missing_data -> skipped
  - [ ] invalid_price -> skipped
  - [ ] success -> save + saveToSeconds
  - [ ] open position true -> saveHistory
  - [ ] exception -> failure
- [ ] `PremiumInputPolicyTest` 작성
- [ ] `PremiumHistoryPolicyTest` 작성

### Phase 2: GREEN
- [ ] `PremiumRealtimeJob` 구현
- [ ] `PremiumInputPolicy`, `PremiumHistoryPolicy` 구현
- [ ] `PremiumScheduler`를 thin scheduler로 전환
- [ ] `JobExecutor`를 통한 lock/metrics/last-run 처리 연결

### Phase 3: REFACTOR
- [ ] scheduler 내부 비즈니스 분기 제거
- [ ] 로그/메트릭 태그 정리
- [ ] 중복 매핑/유틸 제거

### Phase 4: Verification Gate
- [ ] premium 관련 테스트 통과
- [ ] 기존 동작/키/주기 회귀 없음 확인
- [ ] `findings/progress` 문서 갱신 포인트 기록

## File Change Plan
- 주요 수정 대상
  - `apps/batch/src/main/kotlin/io/premiumspread/scheduler/PremiumScheduler.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/cache/PremiumCacheService.kt` (필요 시 adapter화)
- 신규 예상
  - `apps/batch/src/main/kotlin/io/premiumspread/application/job/premium/PremiumRealtimeJob.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/domain/premium/PremiumInputPolicy.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/domain/premium/PremiumHistoryPolicy.kt`
  - premium 관련 테스트 파일

## Acceptance Criteria
1. `PremiumScheduler`는 트리거 + job 호출만 수행
2. premium 검증/분기/조립 로직이 application/domain으로 이동
3. lock/metrics/last-run은 `JobExecutor` 경로로만 처리
4. 기존 기능 회귀 없음

## Assumptions
1. 스케줄 주기(1초), lock key, Redis key/TTL은 유지
2. premium history 저장 조건(open position)은 기존 정책 유지
