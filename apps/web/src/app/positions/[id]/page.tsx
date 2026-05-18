'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter, useParams } from 'next/navigation';
import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { apiClient, ApiError } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import type { Position, PnlData } from '@/components/PositionList';

const formatKrw = (n: number) =>
  n.toLocaleString('ko-KR', { maximumFractionDigits: 0 });
const formatSigned = (n: number) =>
  `${n > 0 ? '+' : ''}${n.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}`;

export default function PositionDetailPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();
  const params = useParams();
  const id = params.id as string;

  const [position, setPosition] = useState<Position | null>(null);
  const [pnl, setPnl] = useState<PnlData | null>(null);
  const [loading, setLoading] = useState(true);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState('');

  const fetchPosition = useCallback(async () => {
    try {
      const data = await apiClient<Position>(`/positions/${id}`);
      setPosition(data);
    } catch {
      setError('포지션을 불러올 수 없습니다.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  const fetchPnl = useCallback(async () => {
    try {
      const data = await apiClient<PnlData>(`/positions/${id}/pnl`);
      setPnl(data);
    } catch {
      // PnL 조회 실패는 조용히 처리
    }
  }, [id]);

  useEffect(() => {
    if (!user) return;
    fetchPosition();
  }, [user, fetchPosition]);

  useEffect(() => {
    if (!user || !position || position.status !== 'OPEN') return;
    fetchPnl();
    const interval = setInterval(fetchPnl, 5000);
    return () => clearInterval(interval);
  }, [user, position, fetchPnl]);

  const handleClose = async () => {
    if (!confirm('포지션을 종료하시겠습니까?')) return;
    setClosing(true);
    try {
      await apiClient(`/positions/${id}/close`, { method: 'POST' });
      router.push('/positions');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message || '포지션 종료에 실패했습니다.');
      } else {
        setError('포지션 종료에 실패했습니다.');
      }
      setClosing(false);
    }
  };

  if (authLoading) {
    return (
      <div className="container mx-auto px-4 py-6">
        <p className="text-center text-muted-foreground">로딩 중...</p>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="container mx-auto px-4 py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <p className="mb-4 text-muted-foreground">
              포지션 상세를 보려면 로그인이 필요합니다.
            </p>
            <Link href="/login">
              <Button>로그인</Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-6">
        <p className="text-center text-muted-foreground">로딩 중...</p>
      </div>
    );
  }

  if (error && !position) {
    return (
      <div className="container mx-auto px-4 py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <p className="mb-4 text-red-500">{error}</p>
            <Link href="/positions">
              <Button variant="outline">목록으로 돌아가기</Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!position) return null;

  const profitable = pnl ? pnl.totalPnlKrw >= 0 : false;

  return (
    <div className="container mx-auto space-y-6 px-4 py-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">포지션 상세</h1>
        <Link href="/positions">
          <Button variant="outline" size="sm">
            목록으로
          </Button>
        </Link>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>{position.symbol}</span>
            <span
              className={`inline-flex rounded-full px-3 py-1 text-sm font-medium ${
                position.status === 'OPEN'
                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                  : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
              }`}
            >
              {position.status === 'OPEN' ? '열림' : '종료'}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="grid gap-4 md:grid-cols-2">
            <div className="rounded-lg border p-4">
              <p className="mb-3 text-sm font-medium text-muted-foreground">
                한국 (롱)
              </p>
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">거래소</span>
                  <span className="font-medium">{position.koreaExchange}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">수량</span>
                  <span className="font-medium">{position.koreaQuantity}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">진입가</span>
                  <span className="font-medium">
                    {formatKrw(position.koreaEntryPrice)} KRW
                  </span>
                </div>
              </div>
            </div>
            <div className="rounded-lg border p-4">
              <p className="mb-3 text-sm font-medium text-muted-foreground">
                해외 (숏)
              </p>
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">거래소</span>
                  <span className="font-medium">
                    {position.foreignExchange}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">수량</span>
                  <span className="font-medium">
                    {position.foreignQuantity}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">진입가</span>
                  <span className="font-medium">
                    {position.foreignEntryPrice.toLocaleString('en-US', {
                      maximumFractionDigits: 2,
                    })}{' '}
                    USD
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">레버리지</span>
                  <span className="font-medium">
                    {position.foreignLeverage}x
                  </span>
                </div>
              </div>
            </div>
          </div>

          <div className="grid gap-4 md:grid-cols-3 text-sm">
            <div>
              <p className="text-muted-foreground">진입 환율</p>
              <p className="text-lg font-semibold">
                {position.entryFxRate.toLocaleString('ko-KR', {
                  minimumFractionDigits: 2,
                  maximumFractionDigits: 2,
                })}{' '}
                KRW/USD
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">진입 프리미엄율</p>
              <p className="text-lg font-semibold">
                <span
                  className={
                    position.entryPremiumRate > 0
                      ? 'text-red-500'
                      : position.entryPremiumRate < 0
                        ? 'text-blue-500'
                        : ''
                  }
                >
                  {position.entryPremiumRate > 0 ? '+' : ''}
                  {position.entryPremiumRate.toFixed(2)}%
                </span>
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">관측 시각</p>
              <p className="text-lg font-semibold">
                {new Date(position.entryObservedAt).toLocaleString('ko-KR')}
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      {position.status === 'OPEN' && (
        <Card>
          <CardHeader>
            <CardTitle>손익 현황 (PnL)</CardTitle>
          </CardHeader>
          <CardContent>
            {pnl ? (
              <div className="space-y-4">
                <div
                  className={`rounded-lg p-4 text-center ${
                    profitable
                      ? 'bg-green-50 dark:bg-green-900/20'
                      : 'bg-red-50 dark:bg-red-900/20'
                  }`}
                >
                  <p className="text-sm text-muted-foreground">총 PnL (KRW)</p>
                  <p
                    className={`text-3xl font-bold ${
                      profitable ? 'text-green-600' : 'text-red-600'
                    }`}
                  >
                    {formatSigned(pnl.totalPnlKrw)}원
                  </p>
                  <p
                    className={`text-lg font-semibold ${
                      profitable ? 'text-green-600' : 'text-red-600'
                    }`}
                  >
                    {pnl.totalPnlPercent > 0 ? '+' : ''}
                    {pnl.totalPnlPercent.toFixed(2)}%
                  </p>
                  <p className="mt-2 text-xs text-muted-foreground">
                    프리미엄 차이{' '}
                    <span
                      className={
                        profitable ? 'text-green-600' : 'text-red-600'
                      }
                    >
                      {pnl.premiumDiff > 0 ? '+' : ''}
                      {pnl.premiumDiff.toFixed(2)}%p
                    </span>
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div className="rounded-lg border p-3">
                    <p className="text-muted-foreground">한국 PnL</p>
                    <p
                      className={`text-lg font-semibold ${
                        pnl.koreaPnl >= 0 ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      {formatSigned(pnl.koreaPnl)}원
                    </p>
                  </div>
                  <div className="rounded-lg border p-3">
                    <p className="text-muted-foreground">해외 PnL (KRW 환산)</p>
                    <p
                      className={`text-lg font-semibold ${
                        pnl.foreignPnlKrw >= 0
                          ? 'text-green-600'
                          : 'text-red-600'
                      }`}
                    >
                      {formatSigned(pnl.foreignPnlKrw)}원
                    </p>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <p className="text-muted-foreground">진입 프리미엄</p>
                    <p className="text-lg font-semibold">
                      {pnl.entryPremiumRate > 0 ? '+' : ''}
                      {pnl.entryPremiumRate.toFixed(2)}%
                    </p>
                  </div>
                  <div>
                    <p className="text-muted-foreground">현재 프리미엄</p>
                    <p
                      className={`text-lg font-semibold ${
                        profitable ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      {pnl.currentPremiumRate > 0 ? '+' : ''}
                      {pnl.currentPremiumRate.toFixed(2)}%
                    </p>
                  </div>
                </div>
                <p className="text-xs text-muted-foreground">
                  계산 시각:{' '}
                  {new Date(pnl.calculatedAt).toLocaleString('ko-KR')}
                </p>
              </div>
            ) : (
              <p className="py-4 text-center text-muted-foreground">
                PnL 데이터를 불러오는 중...
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {error && <p className="text-sm text-red-500">{error}</p>}

      {position.status === 'OPEN' && (
        <Button
          variant="destructive"
          className="w-full"
          onClick={handleClose}
          disabled={closing}
        >
          {closing ? '종료 중...' : '포지션 종료'}
        </Button>
      )}
    </div>
  );
}
