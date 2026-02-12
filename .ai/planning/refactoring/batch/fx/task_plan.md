# Batch FX 리팩토링 Task Plan (TDD 기반)

## Goal
`ExchangeRateScheduler`를 thin entrypoint로 축소하고,
FX 수집 경로를 `interfaces -> application -> domain <- infrastructure`로 정렬한다.

## Scope
- 포함
  - FX 정기 수집/시작 시 1회 수집 경로 리팩토링
  - `JobExecutor` 기반 lock/metrics/last-run 통합
  - FX 관련 단위/회귀 테스트 보강
- 제외
  - premium/ticker 리팩토링
  - 환율 제공자/통화쌍 확장

## Current Findings
- `ExchangeRateScheduler`가 API 호출, 저장, 메트릭, 락, startup 분기를 직접 수행
- 유스케이스 orchestration이 scheduler에 위치

## Target Design
1. `interfaces/scheduler/ExchangeRateScheduler`
  - 정기 트리거/초기 트리거만 담당
  - 내부에서 `fxIngestionJob.run()` 호출
2. `application/job/fx/FxIngestionJob`
  - API 호출 + Redis 저장 + DB 저장 orchestration
3. `domain/fx/*`
  - 통화쌍 정책/입력 검증 정책(필요 시) 분리
4. `infrastructure/fx/*`
  - client/cache/repository I/O 구현 유지

## TDD Workflow

### Phase 0: Baseline Capture
- [ ] FX 주기(30분), startup 1회 실행 동작 캡처
- [ ] lock key, metrics, last-run 키 캡처

### Phase 1: RED
- [ ] `FxIngestionJobTest` 작성
  - [ ] success -> cache + db 저장
  - [ ] exception -> failure
  - [ ] lock 미획득 -> skipped
- [ ] startup 엔트리포인트 테스트 작성
  - [ ] startup 호출 시 동일 job 경로 사용 검증

### Phase 2: GREEN
- [ ] `FxIngestionJob` 구현
- [ ] `ExchangeRateScheduler` thin scheduler 전환
- [ ] startup 메서드가 공통 job 호출만 하도록 정리

### Phase 3: REFACTOR
- [ ] scheduler 내 중복 로깅/메트릭 처리 제거
- [ ] 정책 상수(통화쌍/주기 관련) 정리

### Phase 4: Verification Gate
- [ ] FX 테스트 통과
- [ ] 정기/초기 실행 동작 동일성 확인
- [ ] 기존 키/TTL/저장 경로 회귀 없음

## File Change Plan
- 주요 수정 대상
  - `apps/batch/src/main/kotlin/io/premiumspread/scheduler/ExchangeRateScheduler.kt`
  - `apps/batch/src/main/kotlin/io/premiumspread/cache/FxCacheService.kt` (필요 시 adapter화)
  - `apps/batch/src/main/kotlin/io/premiumspread/repository/ExchangeRateRepository.kt` (필요 시 port/adapter 정리)
- 신규 예상
  - `apps/batch/src/main/kotlin/io/premiumspread/application/job/fx/FxIngestionJob.kt`
  - FX 관련 테스트 파일

## Acceptance Criteria
1. `ExchangeRateScheduler`는 트리거 + job 호출만 수행
2. FX orchestration 로직이 application으로 이동
3. lock/metrics/last-run 처리 공통화
4. 기존 기능/주기/저장 경로 회귀 없음

## Assumptions
1. 환율 통화쌍은 기본 `USD/KRW` 유지
2. 수집 주기 30분, startup 1회 실행 정책 유지
