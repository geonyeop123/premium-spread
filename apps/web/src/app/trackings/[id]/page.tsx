'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter, useParams } from 'next/navigation';
import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { apiClient, ApiError } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import type { Tracking, GrossPnlData } from '@/components/TrackingList';
import {
  NonOrderNotice,
  GrossPnlFootnote,
  DenominatorLabel,
  LeverageFootnote,
  PremiumDirectionNote,
  PriceBasisBadge,
  ConfirmUnavailableNotice,
  UnknownClosedAtNotice,
} from '@/components/TrackingNotices';

const formatKrw = (n: number) =>
  n.toLocaleString('ko-KR', { maximumFractionDigits: 0 });
const formatSigned = (n: number) =>
  `${n > 0 ? '+' : ''}${n.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}`;

export default function TrackingDetailPage() {
  const { user, loading: authLoading } = useAuth();
  const router = useRouter();
  const params = useParams();
  const id = params.id as string;

  const [tracking, setTracking] = useState<Tracking | null>(null);
  const [pnl, setPnl] = useState<GrossPnlData | null>(null);
  const [loading, setLoading] = useState(true);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState('');

  const fetchTracking = useCallback(async () => {
    try {
      const data = await apiClient<Tracking>(`/trackings/${id}`);
      setTracking(data);
    } catch {
      setError('기록을 불러올 수 없습니다.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  const fetchPnl = useCallback(async () => {
    try {
      const data = await apiClient<GrossPnlData>(`/trackings/${id}/gross-pnl`);
      setPnl(data);
    } catch {
      // PnL 조회 실패는 조용히 처리
    }
  }, [id]);

  useEffect(() => {
    if (!user) return;
    fetchTracking();
  }, [user, fetchTracking]);

  useEffect(() => {
    if (!user || !tracking || tracking.status !== 'ACTIVE') return;
    fetchPnl();
    const interval = setInterval(fetchPnl, 5000);
    return () => clearInterval(interval);
  }, [user, tracking, fetchPnl]);

  const handleClose = async () => {
    if (!confirm('이 기록을 종료하시겠습니까? 종료 시점 시세가 확정 저장되며 실제 주문은 발생하지 않습니다.')) return;
    setClosing(true);
    try {
      await apiClient(`/trackings/${id}/archive`, { method: 'POST' });
      router.push('/trackings');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message || '기록 종료에 실패했습니다.');
      } else {
        setError('기록 종료에 실패했습니다.');
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
              포지션 기록 상세를 보려면 로그인이 필요합니다.
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

  if (error && !tracking) {
    return (
      <div className="container mx-auto px-4 py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <p className="mb-4 text-red-500">{error}</p>
            <Link href="/trackings">
              <Button variant="outline">목록으로 돌아가기</Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!tracking) return null;

  const profitable = pnl ? pnl.totalGrossPnlKrw >= 0 : false;

  return (
    <div className="container mx-auto space-y-6 px-4 py-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">포지션 기록 상세</h1>
        <NonOrderNotice />
        <Link href="/trackings">
          <Button variant="outline" size="sm">
            목록으로
          </Button>
        </Link>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>{tracking.symbol}</span>
            <span
              className={`inline-flex rounded-full px-3 py-1 text-sm font-medium ${
                tracking.status === 'ACTIVE'
                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                  : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
              }`}
            >
              {tracking.status === 'ACTIVE' ? '추적 중' : '종료됨'}
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
                  <span className="font-medium">{tracking.koreaExchange}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">수량</span>
                  <span className="font-medium">{tracking.koreaQuantity}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">진입가</span>
                  <span className="font-medium">
                    {formatKrw(tracking.koreaEntryPrice)} KRW
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
                    {tracking.foreignExchange}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">수량</span>
                  <span className="font-medium">
                    {tracking.foreignQuantity}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">진입가</span>
                  <span className="font-medium">
                    {tracking.foreignEntryPrice.toLocaleString('en-US', {
                      maximumFractionDigits: 2,
                    })}{' '}
                    USD
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">레버리지</span>
                  <span className="font-medium">
                    {tracking.foreignLeverage}x
                  </span>
                </div>
                <LeverageFootnote />
              </div>
            </div>
          </div>

          <div className="grid gap-4 md:grid-cols-3 text-sm">
            <div>
              <p className="text-muted-foreground">진입 환율</p>
              <p className="text-lg font-semibold">
                {tracking.entryFxRate.toLocaleString('ko-KR', {
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
                    tracking.entryPremiumRate > 0
                      ? 'text-red-500'
                      : tracking.entryPremiumRate < 0
                        ? 'text-blue-500'
                        : ''
                  }
                >
                  {tracking.entryPremiumRate > 0 ? '+' : ''}
                  {tracking.entryPremiumRate.toFixed(2)}%
                </span>
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">종료 시각</p>
              <p className="text-lg font-semibold">
                {tracking.status === 'ARCHIVED'
                  ? tracking.closedAt
                    ? new Date(tracking.closedAt).toLocaleString('ko-KR')
                    : <UnknownClosedAtNotice />
                  : '-'}
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">관측 시각</p>
              <p className="text-lg font-semibold">
                {new Date(tracking.entryObservedAt).toLocaleString('ko-KR')}
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
          <CardHeader>
            <CardTitle>gross 손익</CardTitle>
            <GrossPnlFootnote />
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
                  <p className="text-sm text-muted-foreground">
                    총 gross 손익 (KRW) <PriceBasisBadge priceBasis={pnl.priceBasis} observedAt={pnl.observedAt} />
                  </p>
                  <p
                    className={`text-3xl font-bold ${
                      profitable ? 'text-green-600' : 'text-red-600'
                    }`}
                  >
                    {formatSigned(pnl.totalGrossPnlKrw)}원
                  </p>
                  <p
                    className={`text-lg font-semibold ${
                      profitable ? 'text-green-600' : 'text-red-600'
                    }`}
                  >
                    {pnl.grossPnlPercentOfKoreaNotional > 0 ? '+' : ''}
                    {pnl.grossPnlPercentOfKoreaNotional.toFixed(2)}%
                  </p>
                  <DenominatorLabel />
                  <PremiumDirectionNote />
                  <p className="mt-2 text-xs text-muted-foreground">
                    프리미엄 차이{' '}
                    <span
                      className={
                        profitable ? 'text-green-600' : 'text-red-600'
                      }
                    >
                      {pnl.premiumRateDelta > 0 ? '+' : ''}
                      {pnl.premiumRateDelta.toFixed(2)}%p
                    </span>
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div className="rounded-lg border p-3">
                    <p className="text-muted-foreground">한국 PnL</p>
                    <p
                      className={`text-lg font-semibold ${
                        pnl.koreaLegGrossPnlKrw >= 0 ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      {formatSigned(pnl.koreaLegGrossPnlKrw)}원
                    </p>
                  </div>
                  <div className="rounded-lg border p-3">
                    <p className="text-muted-foreground">해외 PnL (KRW 환산)</p>
                    <p
                      className={`text-lg font-semibold ${
                        pnl.foreignLegGrossPnlKrw >= 0
                          ? 'text-green-600'
                          : 'text-red-600'
                      }`}
                    >
                      {formatSigned(pnl.foreignLegGrossPnlKrw)}원
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
                      {pnl.referencePremiumRate > 0 ? '+' : ''}
                      {pnl.referencePremiumRate.toFixed(2)}%
                    </p>
                  </div>
                </div>
                <p className="text-xs text-muted-foreground">
                  계산 시각:{' '}
                  {new Date(pnl.calculatedAt).toLocaleString('ko-KR')}
                </p>
              </div>
            ) : (
              <ConfirmUnavailableNotice />
            )}
          </CardContent>
        </Card>

      {error && <p className="text-sm text-red-500">{error}</p>}

      {tracking.status === 'ACTIVE' && (
        <Button
          variant="destructive"
          className="w-full"
          onClick={handleClose}
          disabled={closing}
        >
          {closing ? '종료 중...' : '기록 종료'}
        </Button>
      )}
    </div>
  );
}
