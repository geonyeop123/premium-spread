# MVP 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 김치 프리미엄 실시간 모니터링 + 수동 포지션 관리 풀스택 웹 서비스를 단일 AWS 서버에 배포한다.

**Architecture:** Spring Boot 백엔드 (API + Batch) + Next.js 프론트엔드를 단일 EC2에 Docker Compose로 배포한다. 기존 4-layer 아키텍처(interfaces → application → domain ← infrastructure) 유지하면서 Position에 회원 소유 개념을 추가하고, SecurityConfig에서 인증을 보호하며, Next.js 웹 UI를 추가한다.

**Tech Stack:** Kotlin 2.0 / Spring Boot 3.4 / Spring Security (세션) / MySQL 8 / Redis 7 / Next.js 14+ / TailwindCSS + shadcn/ui / TradingView Lightweight Charts / Docker Compose / Nginx / AWS EC2 / GitHub Actions

---

## Phase 1: Position memberId 추가 + API 인증 보호

### Task 1-1: Position 테이블에 member_id 컬럼 추가

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V8__add_member_id_to_position.sql`

**Step 1: Flyway 마이그레이션 작성**

```sql
ALTER TABLE position
    ADD COLUMN member_id BIGINT NOT NULL AFTER status,
    ADD INDEX idx_position_member_id (member_id),
    ADD CONSTRAINT fk_position_member FOREIGN KEY (member_id) REFERENCES member(id);
```

**Step 2: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: 커밋**

```bash
git add apps/api/src/main/resources/db/migration/V8__add_member_id_to_position.sql
git commit -m "feat: Position 테이블에 member_id FK 추가 (V8)"
```

---

### Task 1-2: Position 엔티티에 memberId 필드 추가

**Files:**
- Modify: `apps/api/src/main/kotlin/io/premiumspread/domain/position/Position.kt`

**Step 1: Position 엔티티에 memberId 추가**

`Position` 클래스 생성자 파라미터에 추가:

```kotlin
@Column(name = "member_id", nullable = false)
val memberId: Long,
```

`create()` 팩토리에 `memberId: Long` 파라미터 추가, `Position(...)` 생성에 포함.

**Step 2: 컴파일 오류 수정**

Position.create() 호출부에 memberId 전달 필요:
- `PositionCommand.Create` — `memberId: Long` 추가
- `PositionService.create()` — command.memberId 전달
- `PositionCriteria.Open` — `memberId: Long` 추가
- `PositionFacade.openPosition()` — criteria.memberId → command.memberId
- `PositionRequest.Open` — memberId 불필요 (Controller에서 `@LoginMemberId`로 주입)
- `PositionController.open()` — `@LoginMemberId memberId: Long` 파라미터 추가, criteria에 memberId 세팅
- `PositionResult.Detail` — `memberId: Long` 추가

**Step 3: 도메인 레이어 변경**

**PositionCommand.kt:**
```kotlin
data class Create(
    val memberId: Long,
    val symbol: String,
    val exchange: Exchange,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryFxRate: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val entryObservedAt: Instant,
)
```

**Step 4: Application 레이어 변경**

**PositionCriteria (PositionDtos.kt):**
```kotlin
data class Open(
    val memberId: Long,
    val symbol: String,
    val exchange: Exchange,
    // ... 나머지 동일
)
```

**PositionResult.Detail:**
```kotlin
data class Detail(
    val id: Long,
    val memberId: Long,
    val symbol: String,
    // ... 나머지 동일
) {
    companion object {
        fun from(position: Position): Detail = Detail(
            id = position.id,
            memberId = position.memberId,
            // ... 나머지 동일
        )
    }
}
```

**Step 5: Interfaces 레이어 변경**

**PositionController.kt** — `open()` 메서드 변경:
```kotlin
@PostMapping
fun open(
    @LoginMemberId memberId: Long,
    @RequestBody request: PositionRequest.Open,
): ResponseEntity<PositionResponse.Detail> {
    val criteria = PositionCriteria.Open(
        memberId = memberId,
        symbol = request.symbol,
        exchange = Exchange.valueOf(request.exchange),
        // ... 나머지 동일
    )
    // ... 나머지 동일
}
```

**PositionResponse.Detail** — memberId 추가 불필요 (API 응답에 노출 불필요).

**Step 6: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL (테스트 컴파일은 아직 실패 가능)

---

### Task 1-3: Position 테스트 전체 수정

**Files:**
- Modify: `apps/api/src/test/kotlin/io/premiumspread/TestFixtures.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionTest.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionServiceTest.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/infrastructure/position/PositionRepositoryTest.kt` (integration)
- Modify: `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt` (integration)

**Step 1: TestFixtures — PositionFixtures에 memberId 추가**

```kotlin
object PositionFixtures {
    fun openPosition(
        memberId: Long = 1L,
        symbol: String = "BTC",
        // ... 나머지 기존과 동일
    ): Position {
        return Position.create(
            memberId = memberId,
            symbol = Symbol(symbol),
            // ... 나머지 동일
        ).withId(id)
    }
}
```

**Step 2: 도메인 테스트 수정**

`PositionTest.kt`, `PositionServiceTest.kt` — `Position.create()`와 `PositionCommand.Create()`에 `memberId = 1L` 추가.

**Step 3: Application 테스트 수정**

`PositionFacadeTest.kt` — `PositionCriteria.Open()`에 `memberId = 1L` 추가.

**Step 4: Controller 테스트 수정**

`PositionControllerTest.kt` — `@WebMvcTest`에서 `@LoginMemberId` 처리 필요.
- SecurityConfig의 `permitAll()`이므로 `@LoginMemberId`는 `LoginMemberArgumentResolver`가 처리
- 테스트에서 SecurityContext에 `CustomUserDetails` 세팅 필요
- 혹은 `WebMvcConfig`를 Import하여 `LoginMemberArgumentResolver` 등록

**Step 5: 단위 테스트 실행**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS

**Step 6: 커밋**

```bash
git commit -m "feat: Position에 memberId 추가 및 전체 테스트 수정"
```

---

### Task 1-4: Position 회원별 조회 기능 추가

**Files:**
- Modify: `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionRepository.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionService.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/position/PositionJpaRepository.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/position/PositionRepositoryImpl.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`

**Step 1: Repository에 회원별 조회 메서드 추가**

**PositionRepository.kt:**
```kotlin
fun findAllByMemberIdAndStatus(memberId: Long, status: PositionStatus): List<Position>
fun findAllOpenByMemberId(memberId: Long): List<Position> =
    findAllByMemberIdAndStatus(memberId, PositionStatus.OPEN)
```

**Step 2: JpaRepository에 쿼리 추가**

**PositionJpaRepository.kt:**
```kotlin
@Query("""
    SELECT p FROM Position p
    WHERE p.memberId = :memberId
      AND p.status = :status
      AND p.deletedAt IS NULL
    ORDER BY p.createdAt DESC
""")
fun findAllByMemberIdAndStatus(
    @Param("memberId") memberId: Long,
    @Param("status") status: PositionStatus,
): List<Position>
```

**Step 3: RepositoryImpl에 구현 추가**

**PositionRepositoryImpl.kt:**
```kotlin
override fun findAllByMemberIdAndStatus(memberId: Long, status: PositionStatus): List<Position> {
    return positionJpaRepository.findAllByMemberIdAndStatus(memberId, status)
}
```

**Step 4: Service에 회원별 조회 추가**

**PositionService.kt:**
```kotlin
@Transactional(readOnly = true)
fun findAllOpenByMemberId(memberId: Long): List<Position> {
    return positionRepository.findAllOpenByMemberId(memberId)
}
```

**Step 5: Facade에서 회원별 조회로 변경**

**PositionFacade.kt:**
기존 `findAllOpen()` → `findAllOpenByMemberId(memberId: Long)`:
```kotlin
@Transactional(readOnly = true)
fun findAllOpenByMemberId(memberId: Long): List<PositionResult.Detail> {
    return positionService.findAllOpenByMemberId(memberId)
        .map { PositionResult.Detail.from(it) }
}
```

**Step 6: Controller에서 `@LoginMemberId` 적용**

**PositionController.kt:**
```kotlin
@GetMapping
fun getAllOpen(@LoginMemberId memberId: Long): ResponseEntity<List<PositionResponse.Detail>> {
    val results = positionFacade.findAllOpenByMemberId(memberId)
    return ResponseEntity.ok(results.map { PositionResponse.Detail.from(it) })
}
```

`getById`, `getPnl`, `close` 에서도 회원 본인 확인 로직 추가:
- Facade에서 `findById` → `position.memberId != memberId` 이면 `PositionNotFoundException` throw

**Step 7: 테스트 수정 및 실행**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS

**Step 8: 커밋**

```bash
git commit -m "feat: 회원별 포지션 조회 기능 추가"
```

---

### Task 1-5: SecurityConfig 인증 보호 적용

**Files:**
- Modify: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/security/SecurityConfig.kt`
- Modify: `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`
- Modify: Controller E2E 테스트들

**Step 1: SecurityConfig 수정**

```kotlin
.authorizeHttpRequests {
    it.requestMatchers(
        AntPathRequestMatcher("/api/v1/members/register", "POST"),
        AntPathRequestMatcher("/api/v1/members/login", "POST"),
        AntPathRequestMatcher("/api/v1/premiums/**"),
        AntPathRequestMatcher("/api/v1/tickers/**"),
        AntPathRequestMatcher("/actuator/**"),
        AntPathRequestMatcher("/swagger-ui/**"),
        AntPathRequestMatcher("/v3/api-docs/**"),
    ).permitAll()
    it.anyRequest().authenticated()
}
```

**Step 2: 인증 실패 시 JSON 응답 추가**

SecurityConfig에 `exceptionHandling` 설정:
```kotlin
.exceptionHandling {
    it.authenticationEntryPoint { _, response, _ ->
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(
            response.writer,
            mapOf("code" to "UNAUTHORIZED", "message" to "로그인이 필요합니다."),
        )
    }
}
```

**Step 3: PositionControllerTest 수정**

`@WebMvcTest`에서 인증이 필요하므로, 테스트에서 `@WithMockUser` 또는 SecurityContext 세팅 필요:
- `spring-security-test` 의존성 확인 (spring-boot-starter-security에 포함)
- MockMvc 요청에 `.with(SecurityMockMvcRequestPostProcessors.user(customUserDetails))` 사용
- 또는 커스텀 SecurityContext 세팅 헬퍼 작성

**Step 4: E2E 테스트 수정**

PositionControllerE2ETest — 로그인 후 세션 쿠키로 요청:
- `createMember()` + `login()` 헬퍼 추가
- 모든 position 요청에 세션 쿠키 포함

PremiumControllerE2ETest, TickerControllerE2ETest — `permitAll` 이므로 변경 불필요.

**Step 5: 전체 테스트 실행**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS

**Step 6: 커밋**

```bash
git commit -m "feat: SecurityConfig 인증 보호 적용 (공개/인증 엔드포인트 분리)"
```

---

### Task 1-6: HTTP 샘플 파일 갱신 + Phase 1 통합 테스트

**Files:**
- Modify: `http/api/positions.http`
- Modify: `http/api/members.http`

**Step 1: HTTP 파일 갱신**

`positions.http`에 세션 쿠키 헤더 추가, `members.http`는 이미 최신.

**Step 2: 통합 테스트 실행**

Run: `./gradlew :apps:api:integrationTest`
Expected: All tests PASS

**Step 3: 커밋**

```bash
git commit -m "test: Phase 1 통합 테스트 통과 확인 및 HTTP 샘플 갱신"
```

---

## Phase 2: 수익률 API + 포지션 이력

### Task 2-1: 닫힌 포지션 조회 API (history)

**Files:**
- Modify: `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionRepository.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionService.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/position/PositionJpaRepository.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/infrastructure/position/PositionRepositoryImpl.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`

**Step 1: PositionRepository에 닫힌 포지션 조회 추가**

```kotlin
fun findAllClosedByMemberId(memberId: Long): List<Position> =
    findAllByMemberIdAndStatus(memberId, PositionStatus.CLOSED)
```

**Step 2: Service, Facade, Controller 추가**

**PositionFacade.kt:**
```kotlin
@Transactional(readOnly = true)
fun findAllClosedByMemberId(memberId: Long): List<PositionResult.Detail> {
    return positionService.findAllClosedByMemberId(memberId)
        .map { PositionResult.Detail.from(it) }
}
```

**PositionController.kt:**
```kotlin
@GetMapping("/history")
fun getHistory(@LoginMemberId memberId: Long): ResponseEntity<List<PositionResponse.Detail>> {
    val results = positionFacade.findAllClosedByMemberId(memberId)
    return ResponseEntity.ok(results.map { PositionResponse.Detail.from(it) })
}
```

**Step 3: 테스트 작성 및 실행**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS

**Step 4: 커밋**

```bash
git commit -m "feat: 닫힌 포지션 조회(history) API 추가"
```

---

### Task 2-2: 포지션 요약 API (summary)

**Files:**
- Modify: `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`
- Modify: `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt`

**Step 1: PositionResult.Summary DTO 추가**

```kotlin
data class Summary(
    val totalPositions: Int,
    val openPositions: Int,
    val closedPositions: Int,
)
```

**Step 2: Facade에 summary 메서드 추가**

```kotlin
@Transactional(readOnly = true)
fun getSummary(memberId: Long): PositionResult.Summary {
    val openCount = positionService.findAllOpenByMemberId(memberId).size
    val closedCount = positionService.findAllClosedByMemberId(memberId).size
    return PositionResult.Summary(
        totalPositions = openCount + closedCount,
        openPositions = openCount,
        closedPositions = closedCount,
    )
}
```

**Step 3: Controller에 summary 엔드포인트 추가**

```kotlin
@GetMapping("/summary")
fun getSummary(@LoginMemberId memberId: Long): ResponseEntity<PositionResponse.Summary> {
    val result = positionFacade.getSummary(memberId)
    return ResponseEntity.ok(PositionResponse.Summary.from(result))
}
```

**Step 4: 테스트 작성 및 실행**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS

**Step 5: 커밋**

```bash
git commit -m "feat: 포지션 요약(summary) API 추가"
```

---

### Task 2-3: HTTP 샘플 + Phase 2 테스트

**Files:**
- Modify: `http/api/positions.http`

**Step 1: HTTP 샘플 갱신**

```http
### 포지션 이력 (닫힌 포지션)
GET {{premium-api}}/api/v1/positions/history

### 포지션 요약
GET {{premium-api}}/api/v1/positions/summary
```

**Step 2: 전체 테스트**

Run: `./gradlew :apps:api:test && ./gradlew :apps:api:integrationTest`
Expected: All PASS

**Step 3: 커밋**

```bash
git commit -m "test: Phase 2 수익률 API 테스트 완료 및 HTTP 샘플 갱신"
```

---

## Phase 3: 실시간 데이터 전송 (Polling API)

> SSE 대신 polling을 선택한다. 이유: 단순한 구현, 프론트 구현 용이, 세션 기반 인증과 호환 좋음. 프론트에서 5초 간격 fetch.

### Task 3-1: 실시간 프리미엄 조회 API 보강

**Files:**
- 기존 `GET /api/v1/premiums/current/{symbol}` 활용 (이미 구현됨)

**Step 1: 확인**

이미 `PremiumController.getCurrent()` → `PremiumFacade.findLatestSnapshot()` → `PremiumSnapshot`으로 실시간 프리미엄 데이터를 제공 중. 프론트에서 5초 polling으로 호출하면 충분.

추가 필요: 응답에 환율, 한국가격, 해외가격이 이미 포함됨 (`PremiumSnapshot`).

**Step 2: 필요 시 집계 데이터 API 추가**

차트를 위한 집계 데이터 조회 API가 필요할 수 있음. 현재 `GET /api/v1/premiums/history/{symbol}?from=&to=` 가 있으나, 집계(분/시간/일) 데이터 조회 API는 없음.

**집계 데이터 조회 API 추가:**

```kotlin
// PremiumController.kt
@GetMapping("/aggregation/{symbol}")
fun getAggregation(
    @PathVariable symbol: String,
    @RequestParam interval: String, // "1m", "1h", "1d"
    @RequestParam from: Instant,
    @RequestParam to: Instant,
): ResponseEntity<List<PremiumResponse.Aggregation>> {
    val results = premiumFacade.findAggregation(symbol, interval, from, to)
    return ResponseEntity.ok(results)
}
```

이 부분은 기존 premium_minute/hour/day 테이블에서 데이터를 조회하는 구현 필요.

**Step 3: 테스트 및 커밋**

```bash
git commit -m "feat: 프리미엄 집계 데이터 조회 API 추가"
```

---

## Phase 4: Next.js 프로젝트 구성 + 인증 UI

### Task 4-1: Next.js 프로젝트 초기화

**Files:**
- Create: `apps/web/` 디렉터리 전체

**Step 1: 프로젝트 생성**

```bash
cd apps
npx create-next-app@latest web \
  --typescript \
  --tailwind \
  --eslint \
  --app \
  --src-dir \
  --import-alias "@/*" \
  --use-npm
```

**Step 2: shadcn/ui 설치**

```bash
cd apps/web
npx shadcn@latest init
```

**Step 3: 필요한 패키지 설치**

```bash
npm install lightweight-charts axios
```

**Step 4: Dockerfile 작성**

Create: `apps/web/Dockerfile`

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY apps/web/package*.json ./
RUN npm ci
COPY apps/web/ ./
RUN npm run build

FROM node:20-alpine
WORKDIR /app
RUN addgroup -S nodejs && adduser -S nextjs -G nodejs
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public
USER nextjs
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
```

**Step 5: next.config.ts에 standalone 출력 설정**

```typescript
const nextConfig = {
  output: 'standalone',
}
```

**Step 6: 커밋**

```bash
git commit -m "feat: Next.js 프로젝트 초기화 (TailwindCSS + shadcn/ui)"
```

---

### Task 4-2: API 클라이언트 + 인증 상태 관리

**Files:**
- Create: `apps/web/src/lib/api.ts`
- Create: `apps/web/src/lib/auth.ts`

**Step 1: API 클라이언트 작성**

```typescript
// apps/web/src/lib/api.ts
const API_BASE = process.env.NEXT_PUBLIC_API_URL || '/api/v1';

export async function apiClient<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    credentials: 'include', // 세션 쿠키 전송
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({}));
    throw new ApiError(res.status, error.code, error.message);
  }
  return res.json();
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
```

**Step 2: 인증 Context 작성**

```typescript
// apps/web/src/lib/auth.ts
'use client';
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { apiClient } from './api';

interface User {
  id: number;
  email: string;
  nickname: string;
}

interface AuthContextType {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<User>('/members/me')
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  const login = async (email: string, password: string) => {
    const user = await apiClient<User>('/members/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    setUser(user);
  };

  const logout = async () => {
    await apiClient('/members/logout', { method: 'POST' });
    setUser(null);
  };

  const register = async (email: string, password: string) => {
    await apiClient('/members/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, register }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be inside AuthProvider');
  return ctx;
}
```

**Step 3: 커밋**

```bash
git commit -m "feat: API 클라이언트 + 인증 상태 관리 구성"
```

---

### Task 4-3: 로그인 / 회원가입 페이지

**Files:**
- Create: `apps/web/src/app/login/page.tsx`
- Create: `apps/web/src/app/register/page.tsx`
- Modify: `apps/web/src/app/layout.tsx` (AuthProvider 래핑)

**Step 1: 레이아웃에 AuthProvider 적용**

```tsx
// apps/web/src/app/layout.tsx
import { AuthProvider } from '@/lib/auth';

export default function RootLayout({ children }) {
  return (
    <html lang="ko">
      <body>
        <AuthProvider>
          <Header />
          <main>{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
```

**Step 2: 로그인 페이지 구현**

shadcn/ui의 `Card`, `Input`, `Button`, `Label` 컴포넌트 사용.
- 이메일/비밀번호 폼
- 로그인 성공 시 `/` 리다이렉트
- 에러 메시지 표시

**Step 3: 회원가입 페이지 구현**

- 이메일/비밀번호 폼
- 가입 성공 시 `/login` 리다이렉트
- 중복 이메일 에러 표시

**Step 4: 커밋**

```bash
git commit -m "feat: 로그인/회원가입 페이지 구현"
```

---

### Task 4-4: 공통 레이아웃 (Header + Navigation)

**Files:**
- Create: `apps/web/src/components/Header.tsx`

**Step 1: Header 컴포넌트**

- 로고 (Premium Spread)
- 네비게이션: 대시보드, 포지션 (로그인 시)
- 로그인/로그아웃 버튼

**Step 2: 커밋**

```bash
git commit -m "feat: 공통 Header + Navigation 컴포넌트"
```

---

## Phase 5: 대시보드 + 차트

### Task 5-1: 대시보드 페이지 — 실시간 프리미엄 표시

**Files:**
- Modify: `apps/web/src/app/page.tsx`
- Create: `apps/web/src/components/PremiumDisplay.tsx`

**Step 1: 실시간 프리미엄 컴포넌트**

- 5초 간격 polling으로 `GET /api/v1/premiums/current/BTC` 호출
- 프리미엄율, 한국가격, 해외가격, 환율 표시
- 프리미엄 값에 따라 색상 변경 (양수: 빨강, 음수: 파랑)

**Step 2: 커밋**

```bash
git commit -m "feat: 대시보드 실시간 프리미엄 표시 컴포넌트"
```

---

### Task 5-2: 프리미엄 차트 (TradingView Lightweight Charts)

**Files:**
- Create: `apps/web/src/components/PremiumChart.tsx`

**Step 1: 차트 컴포넌트**

- TradingView Lightweight Charts 라인 차트
- 시간 간격 선택: 1분 / 1시간 / 1일
- 데이터: 집계 API 호출 (`GET /api/v1/premiums/aggregation/{symbol}`)
- 자동 갱신 (1분: 10초 간격, 1시간: 1분 간격, 1일: 5분 간격)

**Step 2: 커밋**

```bash
git commit -m "feat: TradingView 프리미엄 차트 컴포넌트"
```

---

## Phase 6: 포지션 관리 UI

### Task 6-1: 포지션 목록 페이지

**Files:**
- Create: `apps/web/src/app/positions/page.tsx`
- Create: `apps/web/src/components/PositionList.tsx`
- Create: `apps/web/src/components/OpenPositionForm.tsx`

**Step 1: 포지션 목록**

- 열린 포지션 목록 테이블
- 각 행: 심볼, 거래소, 수량, 진입가, 진입 프리미엄, 상태, 상세 링크
- 포지션 없을 때 empty state

**Step 2: 포지션 매수 폼**

- 심볼, 거래소, 수량, 진입가격, 환율, 프리미엄율, 관측시간 입력
- 현재 프리미엄 데이터로 자동 채움 (관측시간, 환율, 프리미엄율)

**Step 3: 커밋**

```bash
git commit -m "feat: 포지션 목록 + 매수 폼 UI"
```

---

### Task 6-2: 포지션 상세 + PnL 페이지

**Files:**
- Create: `apps/web/src/app/positions/[id]/page.tsx`

**Step 1: 포지션 상세**

- 포지션 정보 카드
- 실시간 PnL 표시 (5초 polling)
- 매도(close) 버튼

**Step 2: 포지션 이력 (히스토리) 탭**

- `/positions` 페이지에 "이력" 탭 추가
- `GET /api/v1/positions/history` 호출

**Step 3: 커밋**

```bash
git commit -m "feat: 포지션 상세 + PnL + 이력 UI"
```

---

## Phase 7: AWS 인프라 구성

### Task 7-1: Nginx 설정 + Docker Compose 통합

**Files:**
- Create: `docker/nginx/nginx.conf`
- Create: `docker/nginx/Dockerfile`
- Modify: `docker/app-compose.yml` (web + nginx 서비스 추가)

**Step 1: Nginx 설정**

```nginx
upstream api {
    server api:8080;
}

upstream web {
    server web:3000;
}

server {
    listen 80;
    server_name _;

    location /api/ {
        proxy_pass http://api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://web;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**Step 2: app-compose.yml에 web + nginx 추가**

```yaml
  web:
    build:
      context: ..
      dockerfile: apps/web/Dockerfile
    container_name: premium-spread-web
    environment:
      NEXT_PUBLIC_API_URL: /api/v1
    restart: unless-stopped
    networks:
      - premium-spread

  nginx:
    image: nginx:alpine
    container_name: premium-spread-nginx
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/conf.d/default.conf
      - ./certbot/www:/var/www/certbot
      - ./certbot/conf:/etc/letsencrypt
    depends_on:
      - api
      - web
    restart: unless-stopped
    networks:
      - premium-spread
```

**Step 3: 커밋**

```bash
git commit -m "feat: Nginx 리버스 프록시 + Docker Compose 통합"
```

---

### Task 7-2: 도메인 구매 + SSL 설정 가이드

**Files:**
- Create: `docs/deploy/aws-setup.md`

**Step 1: 도메인 구매 절차 문서화**

내용:
1. 도메인 등록 (가비아 or Route 53)
   - `.kr` 도메인: 가비아 추천 (연 ~15,000원)
   - `.com` 도메인: Route 53 ($12/년) 또는 Namecheap
2. EC2 인스턴스 생성 (t3.medium, Amazon Linux 2023)
3. Elastic IP 할당 및 연결
4. Security Group: 80, 443, 22 포트 오픈
5. DNS A 레코드 설정: 도메인 → Elastic IP
6. Docker + Docker Compose 설치
7. Let's Encrypt SSL 인증서 발급

```bash
# certbot으로 SSL 발급
docker run -it --rm \
  -v ./certbot/conf:/etc/letsencrypt \
  -v ./certbot/www:/var/www/certbot \
  certbot/certbot certonly \
  --webroot -w /var/www/certbot \
  -d yourdomain.com
```

8. Nginx SSL 설정 추가
9. certbot 자동 갱신 cron

**Step 2: 커밋**

```bash
git commit -m "docs: AWS 배포 가이드 작성 (EC2 + 도메인 + SSL)"
```

---

### Task 7-3: Production 설정 파일

**Files:**
- Create: `apps/api/src/main/resources/application-prd.yml`
- Create: `apps/batch/src/main/resources/application-prd.yml`

**Step 1: API application-prd.yml**

```yaml
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/premiumspread?useSSL=false&serverTimezone=Asia/Seoul
    username: ${MYSQL_USER}
    password: ${MYSQL_PWD}
  jpa:
    hibernate:
      ddl-auto: validate
  data:
    redis:
      host: ${REDIS_MASTER_HOST}
      port: ${REDIS_MASTER_PORT}

server:
  servlet:
    session:
      cookie:
        http-only: true
        secure: true
        same-site: lax
```

**Step 2: 커밋**

```bash
git commit -m "feat: Production 환경 설정 파일 추가"
```

---

## Phase 8: CI/CD + 최종 배포

### Task 8-1: GitHub Actions 워크플로우

**Files:**
- Create: `.github/workflows/deploy.yml`

**Step 1: 워크플로우 작성**

```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd /home/ec2-user/premium-spread
            git pull origin main
            docker compose -f docker/infra-compose.yml up -d
            docker compose -f docker/app-compose.yml up -d --build
            docker image prune -f
```

**Step 2: GitHub Secrets 설정 가이드**

필요 시크릿: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`

**Step 3: 커밋**

```bash
git commit -m "feat: GitHub Actions CI/CD 파이프라인 구성"
```

---

### Task 8-2: 최종 배포 및 검증

**Step 1: EC2 서버 초기 설정**

```bash
# EC2에서 실행
sudo yum update -y
sudo yum install docker git -y
sudo systemctl start docker
sudo usermod -aG docker ec2-user
# Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

**Step 2: 코드 배포**

```bash
git clone https://github.com/geonyeop123/premium-spread.git
cd premium-spread
docker compose -f docker/infra-compose.yml up -d
docker compose -f docker/app-compose.yml up -d --build
```

**Step 3: 배포 검증 체크리스트**

- [ ] `https://yourdomain.com` 접속 가능
- [ ] 로그인/회원가입 동작
- [ ] 실시간 프리미엄 표시
- [ ] 차트 데이터 렌더링
- [ ] 포지션 매수/매도/PnL 동작
- [ ] 모바일 반응형 기본 확인

**Step 4: 커밋**

```bash
git commit -m "chore: 최종 배포 검증 완료"
```

---

## Summary

| Phase | Task 수 | 핵심 산출물 |
|-------|---------|------------|
| Phase 1 | 6 tasks | Position memberId + SecurityConfig 인증 보호 |
| Phase 2 | 3 tasks | 수익률/이력 API |
| Phase 3 | 1 task | 집계 데이터 API (polling용) |
| Phase 4 | 4 tasks | Next.js 프로젝트 + 인증 UI |
| Phase 5 | 2 tasks | 대시보드 + TradingView 차트 |
| Phase 6 | 2 tasks | 포지션 관리 UI |
| Phase 7 | 3 tasks | Nginx + AWS + SSL |
| Phase 8 | 2 tasks | CI/CD + 최종 배포 |
| **Total** | **23 tasks** | |
