'use client';

import { useState } from 'react';
import { apiClient, ApiError } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
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

interface OpenPositionFormProps {
  onSuccess: () => void;
}

export function OpenPositionForm({ onSuccess }: OpenPositionFormProps) {
  const [symbol, setSymbol] = useState('BTC');
  const [exchange, setExchange] = useState('UPBIT');
  const [quantity, setQuantity] = useState('');
  const [entryPrice, setEntryPrice] = useState('');
  const [entryFxRate, setEntryFxRate] = useState('');
  const [entryPremiumRate, setEntryPremiumRate] = useState('');
  const [entryObservedAt, setEntryObservedAt] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [filling, setFilling] = useState(false);

  const fillCurrentData = async () => {
    setFilling(true);
    setError('');
    try {
      const data = await apiClient<PremiumData>(`/premiums/current/${symbol}`);
      setEntryPrice(String(data.koreaPrice));
      setEntryFxRate(String(data.fxRate));
      setEntryPremiumRate(String(data.premiumRate));
      setEntryObservedAt(data.observedAt);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message || '현재 데이터를 불러올 수 없습니다.');
      } else {
        setError('현재 데이터를 불러올 수 없습니다.');
      }
    } finally {
      setFilling(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await apiClient('/positions', {
        method: 'POST',
        body: JSON.stringify({
          symbol,
          exchange,
          quantity: parseFloat(quantity),
          entryPrice: parseFloat(entryPrice),
          entryFxRate: parseFloat(entryFxRate),
          entryPremiumRate: parseFloat(entryPremiumRate),
          entryObservedAt,
        }),
      });
      setQuantity('');
      setEntryPrice('');
      setEntryFxRate('');
      setEntryPremiumRate('');
      setEntryObservedAt('');
      onSuccess();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message || '포지션 생성에 실패했습니다.');
      } else {
        setError('포지션 생성에 실패했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between">
          <span>포지션 열기</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={fillCurrentData}
            disabled={filling}
          >
            {filling ? '불러오는 중...' : '현재 데이터 채우기'}
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="symbol">심볼</Label>
              <Input
                id="symbol"
                value={symbol}
                onChange={(e) => setSymbol(e.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="exchange">거래소</Label>
              <Input
                id="exchange"
                value={exchange}
                onChange={(e) => setExchange(e.target.value)}
                required
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="quantity">수량</Label>
              <Input
                id="quantity"
                type="number"
                step="any"
                placeholder="0.1"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="entryPrice">진입가격 (KRW)</Label>
              <Input
                id="entryPrice"
                type="number"
                step="any"
                placeholder="50000000"
                value={entryPrice}
                onChange={(e) => setEntryPrice(e.target.value)}
                required
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="entryFxRate">환율 (KRW/USD)</Label>
              <Input
                id="entryFxRate"
                type="number"
                step="any"
                placeholder="1350.00"
                value={entryFxRate}
                onChange={(e) => setEntryFxRate(e.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="entryPremiumRate">진입 프리미엄율 (%)</Label>
              <Input
                id="entryPremiumRate"
                type="number"
                step="any"
                placeholder="3.5"
                value={entryPremiumRate}
                onChange={(e) => setEntryPremiumRate(e.target.value)}
                required
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="entryObservedAt">관측 시각</Label>
            <Input
              id="entryObservedAt"
              placeholder="2024-01-15T10:30:00Z"
              value={entryObservedAt}
              onChange={(e) => setEntryObservedAt(e.target.value)}
              required
            />
          </div>
          {error && <p className="text-sm text-red-500">{error}</p>}
          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting ? '생성 중...' : '포지션 열기'}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
