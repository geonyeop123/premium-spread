'use client';

import { useState, useEffect, useCallback } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { apiClient } from '@/lib/api';

export interface Position {
  id: number;
  symbol: string;
  exchange: string;
  quantity: number;
  entryPrice: number;
  entryFxRate: number;
  entryPremiumRate: number;
  entryObservedAt: string;
  status: 'OPEN' | 'CLOSED';
}

interface PnlData {
  positionId: number;
  premiumDiff: number;
  entryPremiumRate: number;
  currentPremiumRate: number;
  isProfit: boolean;
  calculatedAt: string;
}

interface PositionListProps {
  positions: Position[];
}

const formatKrw = (n: number) => n.toLocaleString('ko-KR');
const formatDate = (iso: string) => new Date(iso).toLocaleString('ko-KR', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
});

export function PositionList({ positions }: PositionListProps) {
  const [pnlMap, setPnlMap] = useState<Record<number, PnlData>>({});

  const fetchPnls = useCallback(async () => {
    const openPositions = positions.filter((p) => p.status === 'OPEN');
    if (openPositions.length === 0) return;

    const results = await Promise.allSettled(
      openPositions.map((p) =>
        apiClient<PnlData>(`/positions/${p.id}/pnl`)
      ),
    );

    const newMap: Record<number, PnlData> = {};
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') {
        newMap[openPositions[i].id] = r.value;
      }
    });
    setPnlMap((prev) => ({ ...prev, ...newMap }));
  }, [positions]);

  useEffect(() => {
    fetchPnls();
    const timer = setInterval(fetchPnls, 5000);
    return () => clearInterval(timer);
  }, [fetchPnls]);

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
            <th className="pb-2 pr-4 font-medium text-right">수량</th>
            <th className="pb-2 pr-4 font-medium text-right">진입가격</th>
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
            return (
              <tr key={p.id} className="border-b last:border-0">
                <td className="py-3 pr-4 font-medium">{p.symbol}</td>
                <td className="py-3 pr-4 text-right">{p.quantity}</td>
                <td className="py-3 pr-4 text-right">
                  {formatKrw(p.entryPrice)} KRW
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
                    <span
                      className={`font-semibold ${
                        pnl.isProfit ? 'text-green-600' : 'text-red-600'
                      }`}
                    >
                      {pnl.premiumDiff > 0 ? '+' : ''}
                      {pnl.premiumDiff.toFixed(2)}%p
                    </span>
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
