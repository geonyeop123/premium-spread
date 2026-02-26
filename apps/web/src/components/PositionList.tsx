'use client';

import Link from 'next/link';
import { Button } from '@/components/ui/button';

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

interface PositionListProps {
  positions: Position[];
}

const formatKrw = (n: number) => n.toLocaleString('ko-KR');

export function PositionList({ positions }: PositionListProps) {
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
            <th className="pb-2 pr-4 font-medium">거래소</th>
            <th className="pb-2 pr-4 font-medium text-right">수량</th>
            <th className="pb-2 pr-4 font-medium text-right">진입가격</th>
            <th className="pb-2 pr-4 font-medium text-right">진입 프리미엄</th>
            <th className="pb-2 pr-4 font-medium">상태</th>
            <th className="pb-2 font-medium"></th>
          </tr>
        </thead>
        <tbody>
          {positions.map((p) => (
            <tr key={p.id} className="border-b last:border-0">
              <td className="py-3 pr-4 font-medium">{p.symbol}</td>
              <td className="py-3 pr-4">{p.exchange}</td>
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
          ))}
        </tbody>
      </table>
    </div>
  );
}
