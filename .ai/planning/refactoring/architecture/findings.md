# Findings: Premium 아키텍처 리팩토링

## 요약
현재 `premium` 조회 경로에서 `application` 계층이 `infrastructure`를 직접 알고 있어,
희망 구조(`application -> domain <- infrastructure`)와 불일치가 발생한다.

## 확인된 사실

### 1) 직접 의존 지점
- `apps/api/src/main/kotlin/io/premiumspread/application/premium/PremiumCacheFacade.kt`
  - `PremiumCacheReader`, `CachedPremium`, `PremiumHistoryEntry`를 직접 import
- 동일 패턴이 `ticker`, `position`에도 존재하나 이번 범위는 `premium` 한정

### 2) 책임 경계 문제
- 조회 정책(cache hit/miss + DB fallback)이 application에 위치
- application이 저장소 구현 방식(캐시/DB)을 사실상 인지

### 3) 구조적으로 잘 유지된 부분
- domain 인터페이스 + infrastructure 구현 패턴 자체는 존재
  - `PremiumRepository` (domain)
  - `PremiumRepositoryImpl` (infrastructure)

## 합의된 설계 방향
1. `PremiumService`를 저장/조회 유스케이스의 단일 진입점으로 유지
2. cache 우선 + miss fallback 정책은 `PremiumRepositoryImpl` 내부로 이동
3. 명명에서 `Port`/`Adapter` 접미사는 사용하지 않음
4. API 응답에서 `source(cache/db)`는 노출하지 않음

## 구현 범위(이번 작업)
- 포함: premium 도메인 리팩토링, 최소 회귀 테스트
- 제외: 장애복구/동시성 제어/고급 운영정책

## 리스크
- `PremiumCacheFacade` 제거/축소 시 테스트 영향
- 응답 스키마(`source` 제거) 변경에 따른 테스트 수정 필요
- 저장소 구현 복잡도 증가 가능

## 완료 기준
1. premium 관련 `application -> infrastructure` import 0건
2. premium latest 조회 동작 유지(성공/404)
3. fallback 책임이 infrastructure 내부에 한정
4. 관련 테스트 통과
