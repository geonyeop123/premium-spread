---
name: frontend
description: "Next.js 프론트엔드 구현 스킬. apps/web에서 페이지, 컴포넌트, API 연동, 인증 흐름을 구현한다. Next.js 16 App Router + shadcn/ui + TradingView Charts 기반. '프론트엔드 구현', '페이지 추가', '컴포넌트', 'UI', '화면', 'web 작업', '프론트' 요청 시 반드시 이 스킬을 사용할 것. 백엔드 API 변경에 따른 프론트엔드 연동도 이 스킬의 범위."
---

# Frontend — Next.js 프론트엔드 구현 가이드

apps/web (Next.js 16 + shadcn/ui) 기반 프론트엔드를 구현하는 절차 가이드.

## 프로젝트 구조

```
apps/web/src/
├── app/                    # App Router
│   ├── layout.tsx          # Root Layout (providers, header)
│   ├── page.tsx            # 대시보드 (메인)
│   ├── login/page.tsx
│   ├── register/page.tsx
│   ├── positions/page.tsx
│   └── positions/[id]/page.tsx
├── components/
│   ├── Header.tsx
│   ├── OpenPositionForm.tsx
│   ├── PositionList.tsx
│   ├── PremiumChart.tsx
│   ├── PremiumDisplay.tsx
│   └── ui/                 # shadcn/ui (button, card, input, label, tabs)
└── lib/
    ├── api.ts              # API 클라이언트 (fetch wrapper)
    ├── auth.tsx             # 인증 Context + Provider
    └── utils.ts             # cn() 등 유틸리티
```

## 구현 절차

### 1. 페이지 추가

App Router 규칙을 따른다:

```
app/{route}/page.tsx         # 페이지 컴포넌트
app/{route}/layout.tsx       # 레이아웃 (필요 시)
app/{route}/loading.tsx      # 로딩 UI (필요 시)
```

```typescript
// app/new-route/page.tsx
export default function NewPage() {
  return (
    <div className="container mx-auto py-8">
      <h1 className="text-2xl font-bold mb-4">제목</h1>
      {/* 컴포넌트 */}
    </div>
  );
}
```

### 2. 컴포넌트 개발

**shadcn/ui 우선 사용:**
- `Card`, `Button`, `Input`, `Label`, `Tabs` 등 기존 ui/ 컴포넌트 활용
- 새 shadcn 컴포넌트 필요 시 `npx shadcn@latest add {component}`

**클라이언트 컴포넌트:**
```typescript
'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

interface Props { /* ... */ }

export function ComponentName({ prop }: Props) {
  // 상태, 이펙트, 이벤트 핸들러
  return <Card>{/* ... */}</Card>;
}
```

**서버 vs 클라이언트 판단:**
- 데이터 페칭만 → 서버 컴포넌트 (기본)
- useState, useEffect, 이벤트 핸들러 → `'use client'`
- 차트, 실시간 데이터 → `'use client'`

### 3. API 연동

`lib/api.ts`의 기존 패턴을 따른다:

```typescript
// lib/api.ts에 추가
export async function fetchNewData(params?: Params): Promise<ResponseType> {
  const response = await api.get('/api/v1/endpoint', { params });
  return response.data;
}

export async function createNewData(body: RequestType): Promise<ResponseType> {
  const response = await api.post('/api/v1/endpoint', body);
  return response.data;
}
```

**API 연동 체크리스트:**
- [ ] 백엔드 Response DTO와 TypeScript 타입이 일치하는가
- [ ] 인증 필요 API에 JWT 토큰이 자동 포함되는가 (api.ts의 interceptor 확인)
- [ ] 에러 핸들링이 기존 패턴과 통일되는가
- [ ] API URL이 백엔드 Controller의 @RequestMapping과 일치하는가

### 4. 인증 연동

`lib/auth.tsx`의 AuthContext를 사용한다:

```typescript
'use client';
import { useAuth } from '@/lib/auth';

export function ProtectedComponent() {
  const { user, isAuthenticated } = useAuth();
  if (!isAuthenticated) return <LoginRedirect />;
  return <div>{/* 인증된 사용자 UI */}</div>;
}
```

### 5. 스타일링

- Tailwind CSS 사용
- `cn()` 유틸리티로 조건부 클래스 결합
- 반응형: `sm:`, `md:`, `lg:` 프리픽스
- 다크모드: 필요 시 `dark:` 프리픽스

## 백엔드 API 변경 시 체크리스트

백엔드에서 새 엔드포인트가 추가되거나 기존 API가 변경된 경우:

1. `lib/api.ts`에 API 함수 추가/수정
2. TypeScript 타입 정의 (Response DTO 매핑)
3. 해당 API를 사용하는 컴포넌트 갱신
4. 에러 핸들링 갱신 (새 에러 코드가 있으면)
