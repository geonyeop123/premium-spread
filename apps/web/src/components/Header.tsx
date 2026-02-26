'use client';
import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { Button } from '@/components/ui/button';

export function Header() {
  const { user, loading, logout } = useAuth();

  return (
    <header className="border-b">
      <div className="container mx-auto flex h-14 items-center justify-between px-4">
        <div className="flex items-center gap-6">
          <Link href="/" className="text-lg font-bold">
            Premium Spread
          </Link>
          <nav className="flex items-center gap-4 text-sm">
            <Link href="/" className="text-muted-foreground hover:text-foreground">
              대시보드
            </Link>
            {user && (
              <Link href="/positions" className="text-muted-foreground hover:text-foreground">
                포지션
              </Link>
            )}
          </nav>
        </div>
        <div className="flex items-center gap-2">
          {loading ? null : user ? (
            <>
              <span className="text-sm text-muted-foreground">{user.email}</span>
              <Button variant="outline" size="sm" onClick={() => logout()}>
                로그아웃
              </Button>
            </>
          ) : (
            <Link href="/login">
              <Button variant="outline" size="sm">
                로그인
              </Button>
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}
