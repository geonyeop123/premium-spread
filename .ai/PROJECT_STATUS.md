# Project Status

> Last updated: 2026-02-06

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | premium 아키텍처 리팩토링 완료 (cache→DB fallback을 infrastructure로 이동) |
| apps/batch | Active | Redis ZSet 기반 초당 데이터 저장 및 집계 구현 |
| modules/redis | Active | TickerAggregationTimeUnit 추가 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```
88864df Merge branch 'refs/heads/refactor/premium' into feature/premium
0eed724 test: TickerCacheFacade, PremiumCacheFacade 테스트 추가
d6eef34 feat: Redis ZSet 기반 티커 집계 인프라 구축
b396c2a refactor: PremiumCacheService 중복 코드 통합 (AggregationTimeUnit)
```

### Premium 아키텍처 리팩토링 (미커밋)

- `PremiumCacheFacade` 제거 → `PremiumRepositoryImpl`에 cache→DB fallback 통합
- `PremiumSnapshot` read model 도입 (domain)
- application 계층의 infrastructure 직접 참조 0건 달성
- API 응답에서 `source` 필드 제거
- 상세: `.ai/planning/refactoring/architecture/progress.md`

## TODO

### In Progress

- [ ] Redis ZSet 기반 티커 집계 시스템 완성
  - [x] TickerCacheService에 ZSet 저장/조회/집계 메서드 추가
  - [x] TickerAggregationTimeUnit enum 추가
  - [ ] TickerAggregationScheduler 구현 (분/시간 집계)
  - [ ] DB 저장 스케줄러 구현

### Refactoring Backlog

- [ ] ticker 도메인: `TickerCacheFacade` → 동일 패턴으로 infrastructure 이동
- [ ] position 도메인: `PositionFacade`의 infrastructure 참조 정리

### Pending

- [ ] E2E Tests (API + Batch 연동)
- [ ] Production 설정 (application-prod.yml)
- [ ] Docker 설정 (Dockerfile, docker-compose)
- [ ] CI/CD 파이프라인

## Known Issues

- 없음

## Notes

- FX 캐시 TTL이 15분에서 31분으로 변경됨 (30분 스케줄 + 1분 버퍼)
- 설계 문서: `.ai/planning/refactoring/architecture/` (premium 리팩토링)
- 설계 문서: `.ai/planning/refactoring/ticker/` (ticker 집계)
