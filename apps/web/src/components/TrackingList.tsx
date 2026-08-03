'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { apiClient } from '@/lib/api';
import {
  NonOrderNotice,
  GrossPnlFootnote,
  DenominatorLabel,
  PriceBasisBadge,
} from '@/components/TrackingNotices';

export interface Tracking {
  id: number;
  symbol: string;
  koreaExchange: string;
  koreaQuantity: number;
  koreaEntryPrice: number;
  foreignExchange: string;
  foreignQuantity: number;
  foreignEntryPrice: number;
  foreignLeverage: number;
  entryFxRate: number;
  entryPremiumRate: number;
  entryObservedAt: string;
  status: 'ACTIVE' | 'ARCHIVED';
  closedAt: string | null;
  closePriceSource: string | null;
  hasConfirmedClose: boolean;
}

export interface GrossPnlData {
  trackingId: number;
  priceBasis: string;
  pnlBasis: string;
  observedAt: string;
  fxObservedAt: string;
  premiumRateDelta: number;
  entryPremiumRate: number;
  referencePremiumRate: number;
  koreaLegGrossPnlKrw: number;
  foreignLegGrossPnlKrw: number;
  totalGrossPnlKrw: number;
  koreaLegNotionalKrw: number;
  grossPnlPercentOfKoreaNotional: number;
  isGrossProfit: boolean;
  calculatedAt: string;
}

interface TrackingListProps {
  trackings: Tracking[];
}

const formatKrw = (n: number) =>
  n.toLocaleString('ko-KR', { maximumFractionDigits: 0 });
const formatSigned = (n: number) =>
  `${n > 0 ? '+' : ''}${n.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}`;
const formatDate = (iso: string) =>
  new Date(iso).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });

export function TrackingList({ trackings }: TrackingListProps) {
  const [grossPnlMap, setPnlMap] = useState<Record<number, GrossPnlData>>({});

  useEffect(() => {
    let active = true;
    const activeTrackings = trackings.filter((p) => p.status === 'ACTIVE');

    const fetchPnls = async () => {
      if (activeTrackings.length === 0) return;

      const results = await Promise.allSettled(
        activeTrackings.map((p) =>
          apiClient<GrossPnlData>(`/trackings/${p.id}/gross-pnl`)
        ),
      );
      if (!active) return;

      const newMap: Record<number, GrossPnlData> = {};
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          newMap[activeTrackings[index].id] = result.value;
        }
      });
      setPnlMap((previous) => ({ ...previous, ...newMap }));
    };

    const initialTimer = window.setTimeout(() => void fetchPnls(), 0);
    const pollingTimer = window.setInterval(() => void fetchPnls(), 5000);
    return () => {
      active = false;
      window.clearTimeout(initialTimer);
      window.clearInterval(pollingTimer);
    };
  }, [trackings]);

  if (trackings.length === 0) {
    return (
      <p className="py-8 text-center text-muted-foreground">
        기록이 없습니다.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto">
      <div className="mb-3 space-y-1">
        <NonOrderNotice />
        <GrossPnlFootnote />
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="pb-2 pr-4 font-medium">심볼</th>
            <th className="pb-2 pr-4 font-medium text-right">한국 수량</th>
            <th className="pb-2 pr-4 font-medium text-right">한국 진입가</th>
            <th className="pb-2 pr-4 font-medium text-right">진입 프리미엄</th>
            <th className="pb-2 pr-4 font-medium text-right">gross 손익 <DenominatorLabel /></th>
            <th className="pb-2 pr-4 font-medium">진입 시각</th>
            <th className="pb-2 pr-4 font-medium">상태</th>
            <th className="pb-2 font-medium"></th>
          </tr>
        </thead>
        <tbody>
          {trackings.map((p) => {
            const pnl = grossPnlMap[p.id];
            const profitable = pnl ? pnl.totalGrossPnlKrw >= 0 : false;
            return (
              <tr key={p.id} className="border-b last:border-0">
                <td className="py-3 pr-4 font-medium">{p.symbol}</td>
                <td className="py-3 pr-4 text-right">{p.koreaQuantity}</td>
                <td className="py-3 pr-4 text-right">
                  {formatKrw(p.koreaEntryPrice)} KRW
                </td>
                <td className="py-3 pr-4 text-right">
                  <span
                    className={
                      p.entryPremiumRate > 0
                        ? 'text-red-500'
                        : p.entryPremiumRate < 0
                          ? 'text-blue-500'
                          : ''
                    }
                  >
                    {p.entryPremiumRate > 0 ? '+' : ''}
                    {p.entryPremiumRate.toFixed(2)}%
                  </span>
                </td>
                <td className="py-3 pr-4 text-right">
                  {p.status === 'ACTIVE' && pnl ? (
                    <div
                      className={`font-semibold ${
                        profitable ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      <div>
                        {pnl.premiumRateDelta > 0 ? '+' : ''}
                        {pnl.premiumRateDelta.toFixed(2)}%p
                      </div>
                      <div className="text-xs font-normal">
                        {formatSigned(pnl.totalGrossPnlKrw)}원
                        <PriceBasisBadge priceBasis={pnl.priceBasis} observedAt={pnl.observedAt} /> (
                        {pnl.grossPnlPercentOfKoreaNotional > 0 ? '+' : ''}
                        {pnl.grossPnlPercentOfKoreaNotional.toFixed(2)}%)
                      </div>
                    </div>
                  ) : p.status === 'ACTIVE' ? (
                    <span className="text-muted-foreground">-</span>
                  ) : (
                    <span className="text-muted-foreground">종료</span>
                  )}
                </td>
                <td className="py-3 pr-4 text-muted-foreground">
                  {formatDate(p.entryObservedAt)}
                </td>
                <td className="py-3 pr-4">
                  <span
                    className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${
                      p.status === 'ACTIVE'
                        ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                        : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
                    }`}
                  >
                    {p.status === 'ACTIVE' ? '추적 중' : '종료됨'}
                  </span>
                </td>
                <td className="py-3">
                  <Link href={`/trackings/${p.id}`}>
                    <Button variant="outline" size="xs">
                      상세
                    </Button>
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
