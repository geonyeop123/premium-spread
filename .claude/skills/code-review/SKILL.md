---
name: code-review
description: "premium-spread 프로젝트 전용 코드 리뷰 스킬. PR 리뷰, 브랜치 변경사항 리뷰, 구현 완료 코드 리뷰를 수행한다. 아키텍처 규칙 위반, 보안 취약점, 성능 이슈, 도메인 로직 오류, 테스트 품질을 심층 분석한다. '코드 리뷰해줘', 'PR 리뷰', '변경사항 확인', '리뷰 요청', '코드 검토' 요청 시 반드시 이 스킬을 사용할 것. 단순 코드 읽기/설명과는 다르다 — 품질 판정과 수정 제안이 핵심."
---

# Code Review — premium-spread 전용 코드 리뷰

변경된 코드의 품질을 아키텍처·보안·성능·도메인 로직·테스트 관점에서 심층 리뷰하는 절차 가이드.

## 리뷰 시작 전 준비

### 1. 변경 범위 파악

```bash
# PR 또는 브랜치의 변경 파일 목록
git diff --name-only main...HEAD

# 변경 내용 확인
git diff main...HEAD

# 최근 커밋 히스토리
git log --oneline main...HEAD
```

변경 파일을 레이어별로 분류한다:
- `domain/` — 도메인 로직 변경 (가장 주의 깊게 리뷰)
- `infrastructure/` — 데이터 접근 변경
- `application/` — 유스케이스 변경
- `interfaces/` — API 인터페이스 변경
- `test/` — 테스트 변경

### 2. 프로젝트 규칙 확인

리뷰 기준이 되는 규칙 파일을 읽는다:
- `.ai/rules/architecture.md` — 의존성 방향, 주입 규칙
- `.ai/rules/naming.md` — DTO 패턴, Entity 네이밍
- `.ai/rules/testing.md` — 테스트 정책
- `.ai/rules/batch.md` — 배치 구조 (배치 변경 시)

## 리뷰 절차

### Pass 1: 아키텍처 위반 스캔 (자동화 가능)

가장 먼저, 기계적으로 확인 가능한 규칙 위반을 스캔한다.

```bash
# domain → infrastructure 의존 위반
grep -rn "import.*infrastructure" apps/api/src/main/kotlin/**/domain/

# Facade → Repository/CacheReader 직접 주입 위반
grep -rn "Repository\|CacheReader\|CacheWriter" apps/api/src/main/kotlin/**/application/

# assertEquals 사용 (AssertJ 사용해야 함)
grep -rn "assertEquals\|assertTrue\|assertFalse" apps/api/src/test/

# Entity에 @Enumerated 누락
# (Enum 타입 필드가 있는 Entity에서 확인)
```

### Pass 2: 도메인 로직 리뷰 (판단 필요)

변경된 domain 코드를 집중 리뷰한다:

1. **비즈니스 규칙 위치**: 계산/검증 로직이 domain Service/Entity에 있는가 (Facade나 Controller에 누출되지 않았는가)
2. **경계 조건**: 음수, 0, null, 빈 컬렉션, 최대값 처리
3. **예외 처리**:
   - 새 예외가 `DomainException`을 상속하는가
   - `GlobalExceptionHandler`의 `ERROR_MESSAGES`에 매핑이 추가되었는가
   - HTTP 상태 코드가 적절한가
4. **불변성**: `val` 사용, `data class` 활용
5. **순수 함수**: 부작용 최소화

### Pass 3: 경계면 교차 비교 (핵심)

DTO 변환 체인의 필드 매핑을 교차 검증한다. 이 부분에서 버그가 가장 많이 발생한다.

**입력 방향:**
```
Request.Create     →  Criteria.Create    →  Command.Create   →  Entity 생성자
  .toCriteria()         .toCommand()           Service.create()
```

각 변환 메서드에서:
- 필드가 누락되지 않았는가
- 타입 변환이 올바른가 (String → Enum, String → BigDecimal 등)
- 검증(@Valid, @NotBlank)이 적절한 레이어에 있는가

**출력 방향:**
```
Entity/Snapshot  →  Result.Detail       →  Response (JSON)
                     .from(entity)
```

- `from()` 메서드가 모든 필드를 매핑하는가
- nullable 필드 처리가 올바른가

### Pass 4: 보안 리뷰

1. **인증/인가**: 새 엔드포인트가 SecurityConfig의 permitAll에 잘못 포함되지 않았는가
2. **입력 검증**: @Valid + DTO 필드 어노테이션
3. **민감 데이터**: 비밀번호, 토큰이 Response에 포함되지 않는가
4. **SQL injection**: native query 사용 시 파라미터 바인딩 확인

### Pass 5: 성능 리뷰

1. **N+1 쿼리**: 연관 엔티티 로딩 방식 (LAZY + fetch join 또는 별도 쿼리)
2. **불필요한 호출**: 같은 데이터를 반복 조회하지 않는가
3. **캐시 활용**: 자주 조회되는 데이터에 캐시가 적용되었는가
4. **대량 처리**: 대량 insert/update 시 batch 처리

### Pass 6: 테스트 리뷰

1. **도구**: AssertJ 사용 확인
2. **커버리지**: 주요 경로 + 경계 조건 + 에러 경로
3. **격리**: 테스트 간 상태 공유 없음
4. **Integration**: `@Tag("integration")` + TestContainers Config Import
5. **테스트 이름**: 한글 행위 기술

### Pass 7: 컨벤션 확인

1. DTO inner class 패턴
2. Entity에 @Enumerated(EnumType.STRING)
3. http/*.http 샘플 갱신
4. 배치 변경 시 Scheduler→JobExecutor→Job 3단 분리

## 심각도 분류

| 심각도 | 기준 | 예시 |
|--------|------|------|
| **BLOCKER** | 머지하면 안 되는 결함 | 아키텍처 위반, 보안 취약점, 데이터 손실 가능성 |
| **MAJOR** | 반드시 수정해야 하지만 즉시 장애는 아님 | 도메인 로직 오류, N+1 쿼리, 테스트 누락 |
| **MINOR** | 수정하면 좋지만 선택적 | 컨벤션 불일치, 네이밍, 코드 스타일 |
| **SUGGESTION** | 개선 아이디어 | 더 나은 구현 방법, 리팩토링 기회 |
| **GOOD** | 칭찬할 만한 코드 | 깔끔한 설계, 좋은 테스트, 패턴 일관성 |

## 보고서 형식

```markdown
# 코드 리뷰 보고서

## 요약
- **리뷰 대상**: [PR/브랜치/파일 목록]
- **변경 파일 수**: N개
- **판정**: 승인 / 수정 후 승인 / 반려

| 심각도 | 건수 |
|--------|------|
| BLOCKER | 0 |
| MAJOR | 0 |
| MINOR | 0 |
| SUGGESTION | 0 |
| GOOD | 0 |

## 리뷰 상세

### [심각도] 제목
- **파일**: `path/to/File.kt:라인`
- **문제/칭찬**: 설명
- **수정 방향**: (BLOCKER/MAJOR만)

## 전체 평가
판정 근거와 종합 의견
```

## 판정 기준

| 판정 | 조건 |
|------|------|
| **승인** | BLOCKER 0건, MAJOR 0건 |
| **수정 후 승인** | BLOCKER 0건, MAJOR 1건 이상 |
| **반려** | BLOCKER 1건 이상 |
