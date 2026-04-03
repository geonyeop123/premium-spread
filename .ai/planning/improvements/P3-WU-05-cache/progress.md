# WU-05 캐시 개선 진행 상황

## 상태: 완료

## 완료 항목

### 1. TimeSeriesCacheSupport 추출 (modules/redis)
- `modules/redis/src/main/kotlin/io/premiumspread/redis/support/TimeSeriesCacheSupport.kt` 생성
- ZSet 시계열 저장 공통 유틸: add (중복 score 제거 + 삽입 + TTL + retention), rangeByTime, extractTimestamp
- 단위 테스트 작성: `modules/redis/src/test/kotlin/io/premiumspread/redis/support/TimeSeriesCacheSupportTest.kt`

### 2. TickerCacheService / PremiumCacheService 리팩토링
- 두 서비스 모두 `TimeSeriesCacheSupport` 주입받아 ZSet 저장/조회 로직 위임
- 기존 직접 `zSetOps.add` + `removeRangeByScore` + `expire` 패턴을 `timeSeriesCache.add()` 단일 호출로 대체
- 동일 score 중복 제거 로직 추가 (기존에는 없었음)
- 기존 테스트 업데이트하여 `TimeSeriesCacheSupport` 주입 반영

### 3. CacheWarmupService 구현
- `apps/api/src/main/kotlin/io/premiumspread/infrastructure/warmup/CacheWarmupService.kt` 생성
- `ApplicationReadyEvent` 시점에 PremiumService / TickerService 조회 호출로 DB fallback 경로 워밍업
- `WarmupProperties`로 symbols, exchanges 설정 외부화
- warmup 실패 시 서버 시작 차단하지 않음 (runCatching)
- test 프로필에서 warmup 비활성화
- 단위 테스트 작성: `apps/api/src/test/kotlin/io/premiumspread/infrastructure/warmup/CacheWarmupServiceTest.kt`

## 테스트 결과
- `./gradlew test` 전체 통과 (기존 batch 테스트 포함)
