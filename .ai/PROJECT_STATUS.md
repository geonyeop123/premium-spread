# Project Status

> Last updated: 2025-02-05

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | 티커/환율 집계 테이블 조회 추가 |
| apps/batch | Active | Redis ZSet 기반 초당 데이터 저장 및 집계 구현 |
| modules/redis | Active | TickerAggregationTimeUnit 추가 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```
b396c2a refactor: PremiumCacheService 중복 코드 통합 (AggregationTimeUnit)
d4d3fc8 feat: Redis ZSet 기반 초당 데이터 저장 및 배치 집계 구현
0611756 docs: Redis ZSet 기반 쓰기 최적화 설계 문서 작성
```

## TODO

### In Progress

- [ ] Redis ZSet 기반 티커 집계 시스템 완성
  - [x] TickerCacheService에 ZSet 저장/조회/집계 메서드 추가
  - [x] TickerAggregationTimeUnit enum 추가
  - [ ] TickerAggregationScheduler 구현 (분/시간 집계)
  - [ ] DB 저장 스케줄러 구현

### Pending

- [ ] E2E Tests (API + Batch 연동)
- [ ] Production 설정 (application-prod.yml)
- [ ] Docker 설정 (Dockerfile, docker-compose)
- [ ] CI/CD 파이프라인

## Known Issues

- 없음

## Notes

- FX 캐시 TTL이 15분에서 31분으로 변경됨 (30분 스케줄 + 1분 버퍼)
- 설계 문서: `.ai/planning/refactoring/ticker/`
