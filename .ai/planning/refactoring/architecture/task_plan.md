# Premium 아키텍처 리팩토링 Task Plan (TDD 기반)

## Goal
`premium` 조회/저장 책임을 `PremiumService` 중심으로 정리하고, `application` 계층에서 `infrastructure` 직접 의존을 제거한다.  
핵심 원칙은 “application은 Premium 조회만 요청하고, cache hit/miss + DB fallback은 infrastructure가 결정”이다.

## Scope
- 포함
  - `apps/api` 내 premium 관련 계층 정리
  - `application -> infrastructure` 직접 의존 제거(프리미엄 관련)
  - 최소 회귀 테스트 보강
- 제외
  - 장애 복구 고도화(재시도/서킷브레이커)
  - 동시성 제어/스탬피드 방지
  - 메트릭/알람 정책 확장
  - `ticker`, `position` 전면 확장 리팩토링

## Current Findings
- `PremiumCacheFacade`가 인프라 타입/리더를 직접 참조
- 조회 정책(캐시 우선 + fallback)이 application에 위치
- 설계 의도와 달리 책임 경계가 섞여 있음

## Target Design
1. `PremiumService`가 조회/저장 유스케이스의 단일 진입점
2. `PremiumRepository` 인터페이스는 domain에 유지
3. `PremiumRepositoryImpl`이 캐시/DB 조회 전략을 내부에서 수행
4. API 응답에서 source(cache/db) 노출 제거
5. `application` 계층의 인프라 import 0건 유지

## TDD Workflow

### Phase 0: Baseline Capture
- [ ] 기존 premium 관련 테스트 실행 및 결과 기록
- [ ] `application` 계층 인프라 import 현황 기록
- [ ] 변경 전 API 응답 스냅샷(현재 프리미엄 조회) 기록

### Phase 1: RED - 기대 동작 테스트 먼저 작성
- [ ] `PremiumService.findLatestBySymbol` 호출 시 저장소 계약 테스트 작성
- [ ] 저장소 단위 테스트 작성
  - [ ] 캐시 hit면 캐시값 반환
  - [ ] 캐시 miss + DB hit면 DB값 반환
  - [ ] 캐시 miss + DB miss면 null 반환
- [ ] API 회귀 테스트 작성
  - [ ] `/api/v1/premiums/current/{symbol}` 정상 조회
  - [ ] 없는 심볼 404
  - [ ] 응답에서 `source` 필드 비노출 검증

### Phase 2: GREEN - 최소 구현으로 테스트 통과
- [ ] `PremiumRepositoryImpl`에 cache->db fallback 로직 구현
- [ ] 필요 시 내부 전용 협력 컴포넌트 분리
  - [ ] 캐시 접근 컴포넌트
  - [ ] DB 접근 컴포넌트
- [ ] `PremiumCacheFacade` 제거 또는 얇은 위임으로 축소
- [ ] 컨트롤러/애플리케이션 경로를 `PremiumService` 중심으로 정리
- [ ] 실패 테스트가 모두 통과하도록 최소 수정

### Phase 3: REFACTOR - 구조 정리
- [ ] premium application 패키지에서 인프라 import 제거
- [ ] 네이밍 정리(Port/Adapter 접미사 미사용)
- [ ] 중복 매핑 제거 및 책임 주석 정리
- [ ] 테스트 가독성 정리(Given-When-Then 구조)

### Phase 4: Verification Gate
- [ ] premium 관련 단위/통합 테스트 재실행
- [ ] `rg "^import io\\.premiumspread\\.infrastructure\\." apps/api/src/main/kotlin/io/premiumspread/application` 결과 0건 확인
- [ ] API 계약(상태코드/필드) 회귀 확인
- [ ] 변경 내역을 `findings.md`, `progress.md`에 반영

## File Change Plan
- 수정 대상
  - `apps/api/src/main/kotlin/io/premiumspread/application/premium/PremiumCacheFacade.kt`
  - `apps/api/src/main/kotlin/io/premiumspread/domain/premium/PremiumService.kt`
  - `apps/api/src/main/kotlin/io/premiumspread/infrastructure/premium/PremiumRepositoryImpl.kt`
  - `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/premium/PremiumController.kt`
  - premium 관련 테스트 파일들
- 신규 가능
  - `apps/api/src/main/kotlin/io/premiumspread/infrastructure/premium/*` (내부 협력 컴포넌트)
  - premium 저장소/서비스 테스트 보강 파일

## Acceptance Criteria
1. `application` 계층에서 premium 관련 인프라 직접 import가 없다.
2. premium 최신 조회는 동작 동일(성공/404)이며 source 필드는 노출되지 않는다.
3. cache hit/miss + DB fallback은 infrastructure 내부에서만 결정된다.
4. premium 관련 테스트가 통과한다.

## Assumptions
1. 이번 작업은 premium 도메인 한정이다.
2. 캐시 TTL/키 정책은 기존 설정을 유지한다.
3. write-back 캐싱은 “가능하면 수행, 실패해도 조회 성공 우선”으로 단순 처리한다.
4. 아키텍처 명명은 Port/Adapter를 사용하지 않는다.

## Risks
- `PremiumCacheFacade` 제거 시 기존 테스트 의존 깨질 수 있음
- 응답 필드 변경(`source` 제거)에 따른 테스트 수정 필요
- 저장소 책임 증가로 구현 복잡도 상승 가능

## Out of Scope Note
이번 세션에서는 고급 정책(장애 복구, 동시성 제어, 운영 알람)은 구현하지 않는다.
