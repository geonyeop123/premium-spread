## 0. Goal

- 본 프로젝트는 멀티모듈 프로젝트로 **crypto-spread(거래소 간 스프레드/프리미엄 계산 및 포지션 시뮬레이션)** 서비스를 구현합니다.
- 아키텍처는 **Clean + Layered Architecture**를 기반으로 하며, 비즈니스 로직(도메인)이 핵심입니다.

---

## 1. Tech Stack

- Language : Kotlin 2.0.20, Java 21
- Framework : Spring Boot 3.4.4
- In-Memory : Redis

## 1. Layered Architecture (4 Layers)

### 1) interfaces

**역할**

- Controller와 해당 계층에서 사용되는 DTO(Request/Response) 포함
- `application`의 Facade를 호출하거나, 필요 시 `domain`의 Service를 호출

**규칙**

- HTTP/프레임워크 세부사항은 이 계층에 격리합니다.
- DTO는 외부 계약(입출력) 기준으로 설계합니다.
- Validation, Mapping(요청 DTO → Criteria/Command) 등은 여기서 처리해도 됩니다.
- 비즈니스 규칙/계산을 여기서 구현하지 않습니다.

---

### 2) application

**역할**

- Facade와 DTO(Criteria/Result) 포함
- 유스케이스 조합(오케스트레이션), 트랜잭션 경계, 권한/흐름 제어

**규칙**

- 비즈니스 규칙 자체는 `domain`에 위치시키고, `application`은 **흐름을 조립**합니다.
- `domain`이 필요로 하는 기능은 **인터페이스**로 의존하며 구현체는 `infrastructure`에 둡니다.
- `application` DTO는 “유스케이스 입력/출력 모델”이며, API 계약(interfaces DTO)과 분리합니다.

---

### 3) domain

**역할**

- Service와 DTO(Command/Domain), Repository(interface) 포함
- 핵심 비즈니스 로직, 정책, 계산, 상태 전이(포지션 오픈/마킹/클로즈 등)

**규칙**

- `domain`은 가능한 한 **프레임워크(스프링/JPA) 독립적**으로 작성합니다.
- `domain`은 `interfaces`, `application`, `infrastructure`를 의존하지 않습니다. (의존성 방향: 밖 → 안)
- Repository는 `domain`에 **인터페이스로만** 존재합니다.
- 도메인 DTO는 다음을 권장하며, Entity의 경우 prefix, suffix를 붙이지 않습니다.
    - `Command`: 도메인 행위(생성/변경)를 위한 입력 모델
    - `Domain`: 도메인 내부/결과 모델(VO 포함)

---

### 4) infrastructure

**역할**

- `domain`의 Repository 인터페이스 구현체(`RepositoryImpl`) 포함
- 외부 시스템(DB/Redis/외부 API/메시징 등) 구현

**규칙**

- 구현체는 `domain`의 Repository/Client 인터페이스를 구현합니다.
- 프레임워크/JPA/Redis/HTTP Client 세부 구현은 여기로 격리합니다.
- infrastructure는 도메인 규칙을 소유하지 않습니다(단순 I/O).

---

## 2. Dependency Rules (Clean Architecture)

- 핵심 원칙: **비즈니스 로직이 외부를 모른다.**
- 의존성 방향:
    - `interfaces` → `application` → `domain`
    - `infrastructure` → `domain` (implements ports)
    - `application`은 `domain`에 의존하며, 외부 기능은 `domain` 을 통해 사용
- 즉, 도메인이 필요한 기능을 **인터페이스로 정의**하고, 외부가 그 인터페이스를 **구현**합니다.

---

## 3. Naming & DTO Conventions

### DTO 네이밍

- `interfaces`: `XxxRequest`, `XxxResponse`
- `application`: `XxxCriteria`, `XxxResult`
- `domain`: `XxxCommand`, `XxxDomain`(또는 의미 있는 VO/Result 명칭)

### 예시 흐름

- `interfaces` Request → Mapper → `application` Criteria → Facade → `domain` Command/Service → 결과 (
  Entity/VO) → `application` Result → `interfaces` Response

---

## 4. Testing Policy (TDD Friendly)

### 목표

- 테스트는 **도메인 규칙의 정확성**과 **유스케이스 흐름**을 검증합니다.

### 도메인 테스트에서 Mock 사용 원칙

- 도메인 단위 테스트에서
    - **순수 계산/VO/정책**: Mock 없이 값 기반 테스트를 우선합니다.
    - **도메인 Service가 Repository 등 영속화 포트에 의존**하는 경우: 해당 포트는 **Mock/Fake로 대체 가능**합니다.
- 핵심은 “Mock 금지”가 아니라,
    - **비즈니스 규칙은 테스트로 고정**
    - **I/O는 포트 레벨에서 격리**

      입니다.
- 테스트 도구는 반드시 AssertJ 를 사용합니다.

### 테스트 레벨 가이드

- `domain`:
    - 계산/정책/상태전이 중심의 단위 테스트
    - Repository/외부 의존은 Mock/Fake로 대체 가능
- `application`:
    - 유스케이스 오케스트레이션 테스트
    - Mock/Fake을 활용한 단위테스트와, 실제 Bean을 사용하는 통합테스트 작성
- `interfaces`:
    - Controller 계약/매핑/Validation 중심 테스트(필요 시 slice)

---

## 5. Scope & Evolution Policy (변경 가능성을 전제로)

- “현재 단계에서 필요 없는 제한(예: 외부 API 금지, DB 금지)”은 **고정 규칙으로 두지 않습니다.**
- 대신 아래 원칙을 유지합니다:
    - 외부 연동이 필요해지면 `domain` interface 정의 → `infrastructure` 구현 추가
    - 도메인 규칙은 변하지 않도록 테스트로 보호
    - 각 계층 역할과 의존성 방향은 유지

---

## 6. Coding Guidelines

- Kotlin을 사용하며, 불변(immutable) 모델을 우선합니다.
- 도메인 계산은 가능한 한 **순수 함수/부작용 최소화**로 설계합니다.
- 과도한 추상화(불필요한 인터페이스/팩토리/헬퍼 남발)는 피합니다.
- 새로운 개념이 생기면 먼저 도메인 모델로 표현하고, 이후 infrastructures를 붙입니다.

---

## 7. Current Domain Focus (MVP)

- Market: 거래소/환율 스냅샷, 프리미엄 계산
- Position: 포지션(현물/선물) 상태, 평가손익, 청산(추후)

---

## 8. Output Expectations for Code Generation

- 각 변경은 “컴파일 가능 + 테스트 통과” 상태를 유지합니다.
- 신규 기능 추가 시:
    - API가 제공되는 경우, Mock API 및 E2E Test 작성 (Top-Bottom 개발 방식)
    - 이후 주요 비즈니스 로직 작성 (tdd-workflow)