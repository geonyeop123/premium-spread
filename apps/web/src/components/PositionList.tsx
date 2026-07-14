'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { apiClient } from '@/lib/api';

export interface Position {
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
  status: 'OPEN' | 'CLOSED';
}

export interface PnlData {
  positionId: number;
  premiumDiff: number;
  entryPremiumRate: number;
  currentPremiumRate: number;
  koreaPnl: number;
  foreignPnlKrw: number;
  totalPnlKrw: number;
  koreaCurrentValue: number;
  totalPnlPercent: number;
  isProfit: boolean;
  calculatedAt: string;
}

interface PositionListProps {
  positions: Position[];
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

export function PositionList({ positions }: PositionListProps) {
  const [pnlMap, setPnlMap] = useState<Record<number, PnlData>>({});

  useEffect(() => {
    let active = true;
    const openPositions = positions.filter((p) => p.status === 'OPEN');

    const fetchPnls = async () => {
      if (openPositions.length === 0) return;

      const results = await Promise.allSettled(
        openPositions.map((p) =>
          apiClient<PnlData>(`/positions/${p.id}/pnl`)
        ),
      );
      if (!active) return;

      const newMap: Record<number, PnlData> = {};
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          newMap[openPositions[index].id] = result.value;
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
  }, [positions]);

  if (positions.length === 0) {
    return (
      <p className="py-8 text-center text-muted-foreground">
        포지션이 없습니다.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="pb-2 pr-4 font-medium">심볼</th>
            <th className="pb-2 pr-4 font-medium text-right">한국 수량</th>
            <th className="pb-2 pr-4 font-medium text-right">한국 진입가</th>
            <th className="pb-2 pr-4 font-medium text-right">진입 프리미엄</th>
            <th className="pb-2 pr-4 font-medium text-right">현재 PnL</th>
            <th className="pb-2 pr-4 font-medium">진입 시각</th>
            <th className="pb-2 pr-4 font-medium">상태</th>
            <th className="pb-2 font-medium"></th>
          </tr>
        </thead>
        <tbody>
          {positions.map((p) => {
            const pnl = pnlMap[p.id];
            const profitable = pnl ? pnl.totalPnlKrw >= 0 : false;
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
                  {p.status === 'OPEN' && pnl ? (
                    <div
                      className={`font-semibold ${
                        profitable ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      <div>
                        {pnl.premiumDiff > 0 ? '+' : ''}
                        {pnl.premiumDiff.toFixed(2)}%p
                      </div>
                      <div className="text-xs font-normal">
                        {formatSigned(pnl.totalPnlKrw)}원 (
                        {pnl.totalPnlPercent > 0 ? '+' : ''}
                        {pnl.totalPnlPercent.toFixed(2)}%)
                      </div>
                    </div>
                  ) : p.status === 'OPEN' ? (
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
                      p.status === 'OPEN'
                        ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                        : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400'
                    }`}
                  >
                    {p.status === 'OPEN' ? '열림' : '종료'}
                  </span>
                </td>
                <td className="py-3">
                  <Link href={`/positions/${p.id}`}>
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
