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

type Mode = 'AUTO' | 'MANUAL';

export function OpenPositionForm({ onSuccess }: OpenPositionFormProps) {
  const [mode, setMode] = useState<Mode>('AUTO');
  const [symbol, setSymbol] = useState('BTC');
  const [koreaExchange, setKoreaExchange] = useState('UPBIT');
  const [koreaQuantity, setKoreaQuantity] = useState('');
  const [foreignExchange, setForeignExchange] = useState('BINANCE_FUTURES');
  const [foreignQuantity, setForeignQuantity] = useState('');
  const [foreignLeverage, setForeignLeverage] = useState('1');
  // MANUAL 전용
  const [koreaEntryPrice, setKoreaEntryPrice] = useState('');
  const [foreignEntryPrice, setForeignEntryPrice] = useState('');
  const [entryFxRate, setEntryFxRate] = useState('');
  const [entryObservedAt, setEntryObservedAt] = useState('');

  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [filling, setFilling] = useState(false);

  const resetManualFields = () => {
    setKoreaEntryPrice('');
    setForeignEntryPrice('');
    setEntryFxRate('');
    setEntryObservedAt('');
  };

  const fillCurrentData = async () => {
    setFilling(true);
    setError('');
    try {
      const data = await apiClient<PremiumData>(`/premiums/current/${symbol}`);
      setKoreaEntryPrice(String(data.koreaPrice));
      setForeignEntryPrice(String(data.foreignPrice));
      setEntryFxRate(String(data.fxRate));
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
      if (mode === 'AUTO') {
        await apiClient('/positions/auto', {
          method: 'POST',
          body: JSON.stringify({
            symbol,
            koreaExchange,
            koreaQuantity: parseFloat(koreaQuantity),
            foreignExchange,
            foreignQuantity: parseFloat(foreignQuantity),
            foreignLeverage: parseFloat(foreignLeverage),
          }),
        });
      } else {
        await apiClient('/positions/manual', {
          method: 'POST',
          body: JSON.stringify({
            symbol,
            koreaExchange,
            koreaQuantity: parseFloat(koreaQuantity),
            koreaEntryPrice: parseFloat(koreaEntryPrice),
            foreignExchange,
            foreignQuantity: parseFloat(foreignQuantity),
            foreignEntryPrice: parseFloat(foreignEntryPrice),
            foreignLeverage: parseFloat(foreignLeverage),
            entryFxRate: parseFloat(entryFxRate),
            entryObservedAt,
          }),
        });
      }
      setKoreaQuantity('');
      setForeignQuantity('');
      resetManualFields();
      onSuccess();
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setError(
            '현재 가격/환율 정보가 없거나 오래되었습니다. 잠시 후 다시 시도하세요.',
          );
        } else {
          setError(err.message || '포지션 생성에 실패했습니다.');
        }
      } else {
        setError('포지션 생성에 실패했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleModeChange = (next: Mode) => {
    if (next === mode) return;
    setMode(next);
    setError('');
    if (next === 'AUTO') {
      resetManualFields();
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center justify-between gap-2">
          <span>포지션 열기</span>
          <div className="flex items-center gap-2">
            <div className="inline-flex overflow-hidden rounded-md border">
              <Button
                type="button"
                variant={mode === 'AUTO' ? 'default' : 'outline'}
                size="sm"
                className="rounded-none border-0"
                onClick={() => handleModeChange('AUTO')}
              >
                AUTO
              </Button>
              <Button
                type="button"
                variant={mode === 'MANUAL' ? 'default' : 'outline'}
                size="sm"
                className="rounded-none border-0"
                onClick={() => handleModeChange('MANUAL')}
              >
                MANUAL
              </Button>
            </div>
            {mode === 'MANUAL' && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={fillCurrentData}
                disabled={filling}
              >
                {filling ? '불러오는 중...' : '현재 데이터 채우기'}
              </Button>
            )}
          </div>
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
              <Label htmlFor="foreignLeverage">해외 레버리지 (배)</Label>
              <Input
                id="foreignLeverage"
                type="number"
                step="any"
                min="1"
                placeholder="1"
                value={foreignLeverage}
                onChange={(e) => setForeignLeverage(e.target.value)}
                required
              />
            </div>
          </div>

          <div className="rounded-lg border p-4">
            <p className="mb-3 text-sm font-medium text-muted-foreground">
              한국 (롱)
            </p>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="koreaExchange">거래소</Label>
                <Input
                  id="koreaExchange"
                  value={koreaExchange}
                  onChange={(e) => setKoreaExchange(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="koreaQuantity">수량</Label>
                <Input
                  id="koreaQuantity"
                  type="number"
                  step="any"
                  placeholder="0.1"
                  value={koreaQuantity}
                  onChange={(e) => setKoreaQuantity(e.target.value)}
                  required
                />
              </div>
              {mode === 'MANUAL' && (
                <div className="col-span-2 space-y-2">
                  <Label htmlFor="koreaEntryPrice">진입가 (KRW)</Label>
                  <Input
                    id="koreaEntryPrice"
                    type="number"
                    step="any"
                    placeholder="50000000"
                    value={koreaEntryPrice}
                    onChange={(e) => setKoreaEntryPrice(e.target.value)}
                    required
                  />
                </div>
              )}
            </div>
          </div>

          <div className="rounded-lg border p-4">
            <p className="mb-3 text-sm font-medium text-muted-foreground">
              해외 (숏)
            </p>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="foreignExchange">거래소</Label>
                <Input
                  id="foreignExchange"
                  value={foreignExchange}
                  onChange={(e) => setForeignExchange(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="foreignQuantity">수량</Label>
                <Input
                  id="foreignQuantity"
                  type="number"
                  step="any"
                  placeholder="0.1"
                  value={foreignQuantity}
                  onChange={(e) => setForeignQuantity(e.target.value)}
                  required
                />
              </div>
              {mode === 'MANUAL' && (
                <div className="col-span-2 space-y-2">
                  <Label htmlFor="foreignEntryPrice">진입가 (USD)</Label>
                  <Input
                    id="foreignEntryPrice"
                    type="number"
                    step="any"
                    placeholder="40000"
                    value={foreignEntryPrice}
                    onChange={(e) => setForeignEntryPrice(e.target.value)}
                    required
                  />
                </div>
              )}
            </div>
          </div>

          {mode === 'MANUAL' && (
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
                <Label htmlFor="entryObservedAt">관측 시각</Label>
                <Input
                  id="entryObservedAt"
                  placeholder="2024-01-15T10:30:00Z"
                  value={entryObservedAt}
                  onChange={(e) => setEntryObservedAt(e.target.value)}
                  required
                />
              </div>
            </div>
          )}

          {error && <p className="text-sm text-red-500">{error}</p>}
          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting
              ? '생성 중...'
              : mode === 'AUTO'
                ? '포지션 열기 (AUTO)'
                : '포지션 열기 (MANUAL)'}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
