# Task Plan: Premium Spread Real-time System Architecture Design

## Goal
빗썸 BTC 현물, 바이낸스 BTC 선물, 환율(KRW/USD) 데이터를 수집하여 실시간 프리미엄을 계산하는 시스템 설계.
Redis 캐시 기반의 배치 서버와 API 서버 분리, 멀티모듈 구조, 보안, 관측성, 테스트 전략까지 포함.

## Deliverables
1. 아키텍처 다이어그램 (텍스트)
2. Redis 키/TTL 설계
3. 배치 스케줄/락 전략
4. 모듈 구조 (Gradle)
5. 리스크/대안 비교표

## Phases

### Phase 1: 현재 프로젝트 구조 분석
- Status: complete
- Tasks:
  - [x] 기존 모듈 구조 확인 (apps:api, modules:jpa)
  - [x] 도메인 모델 파악 (Premium, Ticker, Position)
  - [x] 현재 의존성 방향 확인

### Phase 2: 시스템 아키텍처 설계
- Status: complete
- Tasks:
  - [x] 전체 아키텍처 다이어그램 작성
  - [x] 데이터 흐름 설계
  - [x] 컴포넌트 역할 정의

### Phase 3: Redis 캐싱 전략 설계
- Status: complete
- Tasks:
  - [x] Redis 키 네이밍 규칙 정의
  - [x] TTL 정책 설계 (5초/15분)
  - [x] Open Position 기반 캐싱 정책

### Phase 4: 배치 스케줄링 및 동시성 제어
- Status: complete
- Tasks:
  - [x] 배치 스케줄 설계 (1초/10분)
  - [x] 분산 락 전략 (Redisson, leaseTime 2초)
  - [x] 장애 복구 정책

### Phase 5: 멀티모듈 구조 설계
- Status: complete
- Tasks:
  - [x] 모듈 분리 방안 (apps, modules, supports)
  - [x] 의존성 방향 정의
  - [x] infrastructure 패키지 구조 (persistence 제거)

### Phase 6: 보안 설계
- Status: complete
- Tasks:
  - [x] API Key 암호화 저장
  - [x] 시크릿 관리 (Vault/AWS SM)
  - [x] 로깅 마스킹 (supports/logging)
  - [x] 권한 최소화
  - [x] 키 로테이션 전략

### Phase 7: 관측성 및 운영
- Status: complete
- Tasks:
  - [x] 메트릭 설계 (Micrometer/Prometheus)
  - [x] 알람 정책
  - [x] 로깅 전략

### Phase 8: 테스트 전략
- Status: complete
- Tasks:
  - [x] 단위 테스트 (TDD)
  - [x] 통합 테스트
  - [x] E2E 테스트

### Phase 9: 리스크 분석 및 대안
- Status: complete
- Tasks:
  - [x] 리스크 식별
  - [x] 대안 비교표 작성

### Phase 10: 최종 문서화
- Status: complete
- Tasks:
  - [x] 설계 문서 작성 (ARCHITECTURE_DESIGN.md)
  - [x] 구현 Todo List 작성 (TaskCreate)
  - [x] 설계 변경 반영 (1초 갱신, 모듈 구조)

## Key Decisions
| Decision | Rationale | Date |
|----------|-----------|------|
| Redis를 캐시로 선택 | 낮은 지연시간, 분산 락 지원, TTL 지원 | 2026-01-29 |
| 배치 서버 분리 | API 서버 부하 격리, 독립 배포/스케일링 | 2026-01-29 |
| Redisson 분산 락 | Redis 기반 검증된 분산 락 구현체 | 2026-01-29 |
| AWS Secrets Manager | 키 로테이션 자동화, IAM 통합 | 2026-01-29 |
| 1초 갱신 주기 | Rate Limit 여유 (빗썸 15/s, 바이낸스 20/s) | 2026-01-29 |
| supports 루트 모듈 | Cross-cutting concerns 분리 (logging, monitoring) | 2026-01-29 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| (none) | - | - |
