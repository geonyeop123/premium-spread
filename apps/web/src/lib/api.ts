const API_BASE = process.env.NEXT_PUBLIC_API_URL || '/api/v1';
const ACCESS_TOKEN_STORAGE_KEY = 'premium-spread.access-token';

let accessToken: string | null = null;
let accessTokenHydrated = false;

function storage(): Storage | null {
  return typeof window === 'undefined' ? null : window.sessionStorage;
}

export function getAccessToken(): string | null {
  if (!accessTokenHydrated) {
    try {
      accessToken = storage()?.getItem(ACCESS_TOKEN_STORAGE_KEY) ?? null;
    } catch {
      accessToken = null;
    }
    accessTokenHydrated = true;
  }
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  accessTokenHydrated = true;

  try {
    const tokenStorage = storage();
    if (token) {
      tokenStorage?.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
    } else {
      tokenStorage?.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    }
  } catch {
    // 메모리 토큰은 유지하여 storage 접근이 제한된 환경에서도 현재 탭 인증을 보존한다.
  }
}

export async function apiClient<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const headers = new Headers(options?.headers);
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const token = getAccessToken();
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: 'include',
    headers,
  });

  const responseText = res.status === 204 ? '' : await res.text();
  if (!res.ok) {
    const error = parseJson(responseText);
    throw new ApiError(
      res.status,
      typeof error?.code === 'string' ? error.code : 'HTTP_ERROR',
      typeof error?.message === 'string' ? error.message : res.statusText,
    );
  }

  if (!responseText.trim()) {
    return undefined as T;
  }
  return JSON.parse(responseText) as T;
}

function parseJson(text: string): Record<string, unknown> | null {
  if (!text.trim()) return null;

  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
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
