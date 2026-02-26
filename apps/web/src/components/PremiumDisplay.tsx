'use client';

import { useState, useEffect } from 'react';
import { apiClient } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

interface PremiumData {
  symbol: string;
  premiumRate: number;
  koreaPrice: number;
  foreignPrice: number;
  foreignPriceInKrw: number;
  fxRate: number;
  observedAt: string;
}

export function PremiumDisplay() {
  const [data, setData] = useState<PremiumData | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    const fetchData = () => {
      apiClient<PremiumData>('/premiums/current/BTC')
        .then((d) => {
          setData(d);
          setError(false);
        })
        .catch(() => setError(true));
    };
    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, []);

  if (error)
    return (
      <Card>
        <CardContent className="py-6 text-center text-muted-foreground">
          데이터를 불러올 수 없습니다.
        </CardContent>
      </Card>
    );

  if (!data)
    return (
      <Card>
        <CardContent className="py-6 text-center text-muted-foreground">
          로딩 중...
        </CardContent>
      </Card>
    );

  const formatKrw = (n: number) => n.toLocaleString('ko-KR');
  const formatUsd = (n: number) =>
    n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const premiumColor =
    data.premiumRate > 0 ? 'text-red-500' : data.premiumRate < 0 ? 'text-blue-500' : '';

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between">
          <span>BTC 김치 프리미엄</span>
          <span className={`text-3xl font-bold ${premiumColor}`}>
            {data.premiumRate > 0 ? '+' : ''}
            {data.premiumRate.toFixed(2)}%
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <p className="text-muted-foreground">한국 가격</p>
            <p className="text-lg font-semibold">{formatKrw(data.koreaPrice)} KRW</p>
          </div>
          <div>
            <p className="text-muted-foreground">해외 가격</p>
            <p className="text-lg font-semibold">${formatUsd(data.foreignPrice)}</p>
          </div>
          <div>
            <p className="text-muted-foreground">해외 가격 (KRW 환산)</p>
            <p className="text-lg font-semibold">{formatKrw(data.foreignPriceInKrw)} KRW</p>
          </div>
          <div>
            <p className="text-muted-foreground">환율</p>
            <p className="text-lg font-semibold">{formatUsd(data.fxRate)} KRW/USD</p>
          </div>
        </div>
        <p className="mt-4 text-xs text-muted-foreground">
          마지막 업데이트: {new Date(data.observedAt).toLocaleString('ko-KR')}
        </p>
      </CardContent>
    </Card>
  );
}
