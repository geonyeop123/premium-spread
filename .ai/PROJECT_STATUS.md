# Project Status

> Last updated: 2026-02-06

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | premium + ticker 아키텍처 리팩토링 완료, TickerFacade 제거 (Controller→Service 직접 호출) |
| apps/batch | Active | Redis ZSet 기반 초당 데이터 저장 및 집계 구현 |
| modules/redis | Active | TickerAggregationTimeUnit 추가 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```
249fe00 refactor: ticker 조회 캐시 전략을 infrastructure로 이동, exchangerate 도메인 분리
2a33fa9 Merge pull request #13 from geonyeop123/fix/premium-negative-bug
109a53b fix: 프리미엄 -100.0000 버그 수정 (다층 방어)
```

## TODO

### In Progress

- [ ] Redis ZSet 기반 티커 집계 시스템 완성
  - [x] TickerCacheService에 ZSet 저장/조회/집계 메서드 추가
  - [x] TickerAggregationTimeUnit enum 추가
  - [ ] TickerAggregationScheduler 구현 (분/시간 집계)
  - [ ] DB 저장 스케줄러 구현

### Refactoring Backlog

- [x] ~~ticker 도메인: `TickerCacheFacade` → infrastructure 이동~~ → 완료
- [x] ~~exchangerate 도메인 분리~~ → `domain/exchangerate/` 신규 패키지
- [ ] position 도메인: `PositionFacade`의 `PositionCacheWriter` infrastructure 참조 정리

### Pending

- [ ] E2E Tests (API + Batch 연동)
- [ ] Production 설정 (application-prod.yml)
- [ ] Docker 설정 (Dockerfile, docker-compose)
- [ ] CI/CD 파이프라인

## Known Issues

- ~~Premium -100.0000 버그: koreaPrice=0일 때 발생~~ → 수정 완료 (다층 방어)

## Notes

- FX 캐시 TTL이 15분에서 31분으로 변경됨 (30분 스케줄 + 1분 버퍼)
- 설계 문서: `.ai/planning/refactoring/architecture/` (premium 리팩토링)
- 설계 문서: `.ai/planning/refactoring/ticker/` (ticker 집계)
