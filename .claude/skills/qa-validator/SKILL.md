---
name: qa-validator
description: "구현 품질 검증 스킬. 코드 구현 후 아키텍처 규칙 준수, 레이어 의존성 정합성, 네이밍 컨벤션, 테스트 통과를 검증한다. 구현 완료 후 '검증해줘', '리뷰해줘', '규칙 위반 확인', '품질 체크' 요청 시 반드시 이 스킬을 사용할 것. 코드 작성이 아닌 코드 리뷰/검증 요청에만 적용."
---

# QA Validator — 품질 검증 가이드

구현된 코드가 premium-spread 프로젝트의 아키텍처 규칙과 컨벤션을 준수하는지 검증하는 절차 가이드.

## 검증 관점: 경계면 교차 비교

핵심은 "파일이 있는가"가 아니라 **레이어 간 경계면에서 데이터 shape이 일치하는가**이다.

```
Request.Create → Criteria.Create → Command.Create → Entity 생성자
     ↓                ↓                 ↓                ↓
   (필드 A,B,C)   (필드 A,B,C)    (필드 A,B,C)    (필드 A,B,C)
   누락 없는가?    변환 정확한가?   매핑 누락 없는가?  타입 일치하는가?
```

```
Entity/Snapshot → Result.Detail → Response
       ↓               ↓             ↓
   (필드 X,Y,Z)    (필드 X,Y,Z)  (필드 X,Y,Z)
   from() 정확?    매핑 누락?     직렬화 문제?
```

## 검증 절차

### 1. 아키텍처 의존성 검증

변경된 파일의 import문을 검사한다:

```bash
# domain이 infrastructure를 import하는지 검사
grep -r "import.*infrastructure" apps/api/src/main/kotlin/**/domain/

# Facade가 Repository나 CacheReader를 직접 주입하는지 검사
grep -rn "Repository\|CacheReader\|CacheWriter" apps/api/src/main/kotlin/**/application/
```

**위반 패턴:**
| 위반 | 파일 위치 | import 대상 |
|------|----------|------------|
| domain → infrastructure | `domain/**/*.kt` | `infrastructure.*` |
| Facade → Repository | `application/**/*.kt` | `*Repository` (domain interface 제외) |
| Facade → CacheReader | `application/**/*.kt` | `*CacheReader`, `*CacheWriter` |

### 2. 네이밍 컨벤션 검증

- DTO가 inner class 패턴 사용: `{Name}Request.Create`, `{Name}Criteria.Open` 등
- Entity에 prefix/suffix 없음: `Position` (O), `PositionEntity` (X)
- Enum은 `@Enumerated(EnumType.STRING)`

### 3. 경계면 필드 매핑 검증

각 DTO 변환 메서드(toCriteria, toCommand, from)의 필드 매핑을 교차 확인한다:

1. Request → Criteria 변환: 모든 필드가 전달되는가
2. Criteria → Command 변환: 타입 변환이 올바른가
3. Command → Entity: 생성자 파라미터와 일치하는가
4. Entity/Snapshot → Result: from() 메서드가 모든 필드를 매핑하는가

### 4. Repository interface ↔ Impl 일치 검증

- interface에 선언된 모든 메서드가 Impl에 구현되었는가
- 메서드 시그니처(파라미터 타입, 반환 타입)가 일치하는가
- cache→DB fallback이 Impl 내부에서 처리되는가

### 5. 테스트 실행

```bash
# 전체 유닛 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :apps:api:test

# Integration 테스트 (Docker 필요)
./gradlew :apps:api:integrationTest
```

- AssertJ 사용 여부 확인
- Integration 테스트의 어노테이션 확인: `@Tag("integration")`, TestConfig Import

### 6. HTTP 샘플 갱신 확인

새 엔드포인트 추가 시 `http/api/{도메인}.http` 파일이 갱신되었는지 확인한다.

## 보고서 형식

```markdown
# QA 검증 보고서

## 요약
| 검증 항목 | 결과 | 이슈 수 |
|-----------|------|---------|

## 상세 결과

### 아키텍처 의존성
- [PASS/FAIL] 설명

### 네이밍 컨벤션
- [PASS/FAIL] 설명

### 경계면 매핑
- [PASS/FAIL] 설명

### 테스트
- [PASS/FAIL] 테스트 결과 (통과/실패 수)

### HTTP 샘플
- [PASS/FAIL] 설명

## 수정 권장 사항
1. [파일:라인] — 이슈 설명 — 수정 방향
```
