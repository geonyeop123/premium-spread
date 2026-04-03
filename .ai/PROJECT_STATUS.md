# Project Status

> Last updated: 2026-03-03

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | MVP 1 백엔드 완료 (인증, 포지션 CRUD, 이력/요약, 프리미엄 집계 API) |
| apps/batch | Active | 1초/30분 수집 + 1분/1시간/1일 집계, Docker UTC 기준 저장 |
| apps/web | Active | Next.js 16 + shadcn/ui + TradingView Charts, 대시보드/포지션/인증 UI |
| modules/redis | Active | AggregationTimeUnit(DAYS 추가), TTL 확장 (1m→25h, 1h→31d, 1d→366d) |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```text
feat: 차트 무한스크롤 + Redis 캐싱 + 과도한 API 호출 수정
docs: README.md 최신화
feat: 타임존 수정 + 차트 KST 변환 + Docker Compose 분리
```

## TODO

### Completed
- [x] 차트 무한스크롤 (드래그로 과거 데이터 조회, 최대 범위: 1m=24h, 1h=30d, 1d=365d)
- [x] Redis 집계 캐싱 (cache→DB fallback, AggregationTimeUnit.DAYS 추가)

### Pending
- [ ] 회원 인증 Phase 2: JWT 전환 검토

## Known Issues

- Batch Docker 컨테이너는 `ZoneId.systemDefault()` = UTC로 동작 (로컬 개발 시 KST와 차이 주의)
- Docker app-compose로 띄운 컨테이너와 bootRun이 동시 실행되면 포트 충돌 발생

## Notes

- FX 캐시 TTL: 31분 (30분 스케줄 + 1분 버퍼)
- 배치 수집 주기: ticker/premium 1초, FX 30분
- 집계 주기: 분/시간/일
- 프론트엔드 API 프록시: `next.config.ts` rewrites (`/api/*` → `localhost:8080`)
- Docker Compose 분리: `api-compose.yml`, `batch-compose.yml`, `web-compose.yml` (개별 배포 가능)
- 설계 문서: `.ai/architecture/ARCHITECTURE_DESIGN.md`
- MVP 1 계획서: `docs/plans/2026-02-26-mvp1-implementation.md`
