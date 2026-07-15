'use client';
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { apiClient, ApiError, setAccessToken } from './api';

interface User {
  id: number;
  email: string;
  nickname: string;
}

interface LoginResponse extends User {
  accessToken: string;
}

interface RefreshResponse {
  accessToken: string;
}

let sessionRestoreInFlight: Promise<User> | null = null;

function restoreAuthenticatedUser(): Promise<User> {
  if (sessionRestoreInFlight) return sessionRestoreInFlight;

  const restore = (async () => {
    try {
      return await apiClient<User>('/members/me');
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;

      setAccessToken(null);
      const refreshed = await apiClient<RefreshResponse>('/auth/refresh', {
        method: 'POST',
      });
      setAccessToken(refreshed.accessToken);
      return apiClient<User>('/members/me');
    }
  })();

  sessionRestoreInFlight = restore;
  void restore.then(
    () => {
      if (sessionRestoreInFlight === restore) sessionRestoreInFlight = null;
    },
    () => {
      if (sessionRestoreInFlight === restore) sessionRestoreInFlight = null;
    },
  );
  return restore;
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
    restoreAuthenticatedUser()
      .then(setUser)
      .catch(() => {
        setAccessToken(null);
        setUser(null);
      })
      .finally(() => setLoading(false));
  }, []);

  const login = async (email: string, password: string) => {
    const result = await apiClient<LoginResponse>('/members/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    setAccessToken(result.accessToken);
    setUser({ id: result.id, email: result.email, nickname: result.nickname });
  };

  const logout = async () => {
    try {
      await apiClient<void>('/auth/logout', { method: 'POST' });
    } finally {
      setAccessToken(null);
      setUser(null);
    }
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
