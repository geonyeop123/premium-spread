'use client';

import { useState, useEffect, useRef, useCallback } from 'react';
import {
  createChart,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import { apiClient } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

interface AggregationData {
  symbol: string;
  high: number;
  low: number;
  open: number;
  close: number;
  avg: number;
  count: number;
  observedAt: string;
  fxRate: number | null;
}

interface AggregationResponse {
  data: AggregationData[];
  hasMore: boolean;
}

interface ChartDataPoint {
  time: UTCTimestamp;
  value: number;
}

const INTERVALS = [
  { label: '1분', value: '1m', refreshMs: 10_000, rangeHours: 2, chunkHours: 2, maxHours: 24 },
  { label: '1시간', value: '1h', refreshMs: 60_000, rangeHours: 48, chunkHours: 48, maxHours: 720 },
  { label: '1일', value: '1d', refreshMs: 300_000, rangeHours: 720, chunkHours: 720, maxHours: 8760 },
] as const;

const KST_OFFSET_SEC = 9 * 60 * 60;

function truncateDate(date: Date, interval: string): Date {
  const d = new Date(date.getTime());
  d.setSeconds(0, 0);
  if (interval === '1h' || interval === '1d') d.setMinutes(0);
  if (interval === '1d') d.setHours(0);
  return d;
}

function toChartPoints(data: AggregationData[], field: 'close' | 'fxRate'): ChartDataPoint[] {
  return data
    .filter((d) => d.observedAt != null && d[field] != null)
    .map((d) => ({
      time: (Math.floor(new Date(d.observedAt).getTime() / 1000) + KST_OFFSET_SEC) as UTCTimestamp,
      value: d[field] as number,
    }));
}

function deduplicateAndSort(points: ChartDataPoint[]): ChartDataPoint[] {
  const map = new Map<number, ChartDataPoint>();
  for (const p of points) {
    map.set(p.time as number, p);
  }
  return Array.from(map.values()).sort((a, b) => (a.time as number) - (b.time as number));
}

export function PremiumChart() {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const premiumSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const fxSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const [activeInterval, setActiveInterval] = useState('1m');
  const [showPremium, setShowPremium] = useState(true);
  const [showFxRate, setShowFxRate] = useState(true);

  const premiumDataRef = useRef<ChartDataPoint[]>([]);
  const fxDataRef = useRef<ChartDataPoint[]>([]);
  const rawDataRef = useRef<AggregationData[]>([]);
  const loadedFromRef = useRef<Date>(new Date());
  const hasMoreRef = useRef(true);
  const isLoadingPastRef = useRef(false);
  const activeIntervalRef = useRef(activeInterval);

  useEffect(() => {
    activeIntervalRef.current = activeInterval;
  }, [activeInterval]);

  const updateSeriesData = useCallback(() => {
    if (premiumSeriesRef.current) {
      premiumSeriesRef.current.setData(showPremium ? premiumDataRef.current : []);
    }
    if (fxSeriesRef.current) {
      fxSeriesRef.current.setData(showFxRate ? fxDataRef.current : []);
    }
  }, [showPremium, showFxRate]);

  useEffect(() => {
    updateSeriesData();
  }, [showPremium, showFxRate, updateSeriesData]);

  const mergeData = useCallback((newRaw: AggregationData[], prepend: boolean) => {
    if (prepend) {
      rawDataRef.current = [...newRaw, ...rawDataRef.current];
    } else {
      const cutoffTime = newRaw.length > 0 ? newRaw[0].observedAt : null;
      const pastRaw = cutoffTime
        ? rawDataRef.current.filter((d) => d.observedAt < cutoffTime)
        : rawDataRef.current;
      rawDataRef.current = [...pastRaw, ...newRaw];
    }

    premiumDataRef.current = deduplicateAndSort(toChartPoints(rawDataRef.current, 'close'));
    fxDataRef.current = deduplicateAndSort(toChartPoints(rawDataRef.current, 'fxRate'));

    updateSeriesData();
  }, [updateSeriesData]);

  const fetchLatest = useCallback(async (interval: string) => {
    const config = INTERVALS.find((i) => i.value === interval)!;
    const to = new Date();
    const from = truncateDate(
      new Date(to.getTime() - config.rangeHours * 60 * 60 * 1000),
      interval,
    );

    try {
      const res = await apiClient<AggregationResponse>(
        `/premiums/aggregation/BTC?interval=${interval}&from=${from.toISOString()}&to=${to.toISOString()}`,
      );
      mergeData(res.data ?? [], false);
    } catch {
      // 다음 폴링에서 재시도
    }
  }, [mergeData]);

  const fetchPast = useCallback(async (interval: string) => {
    if (isLoadingPastRef.current || !hasMoreRef.current) return;

    const config = INTERVALS.find((i) => i.value === interval)!;
    const to = loadedFromRef.current;
    const from = new Date(to.getTime() - config.chunkHours * 60 * 60 * 1000);

    const maxFrom = new Date(Date.now() - config.maxHours * 60 * 60 * 1000);
    if (from <= maxFrom) {
      from.setTime(maxFrom.getTime());
    }

    isLoadingPastRef.current = true;
    try {
      const res = await apiClient<AggregationResponse>(
        `/premiums/aggregation/BTC?interval=${interval}&from=${from.toISOString()}&to=${to.toISOString()}`,
      );

      mergeData(res.data ?? [], true);
      loadedFromRef.current = from;

      if (!res.hasMore) hasMoreRef.current = false;
    } catch {
      // 무시
    } finally {
      isLoadingPastRef.current = false;
    }
  }, [mergeData]);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const chart = createChart(chartContainerRef.current, {
      layout: {
        background: { color: 'transparent' },
        textColor: '#999',
      },
      grid: {
        vertLines: { color: '#eee' },
        horzLines: { color: '#eee' },
      },
      width: chartContainerRef.current.clientWidth,
      height: 400,
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
      },
      rightPriceScale: {
        visible: true,
      },
      leftPriceScale: {
        visible: true,
      },
    });

    const premiumSeries = chart.addSeries(LineSeries, {
      color: '#2563eb',
      lineWidth: 2,
      priceScaleId: 'right',
      title: '김프(%)',
    });

    const fxSeries = chart.addSeries(LineSeries, {
      color: '#f59e0b',
      lineWidth: 2,
      priceScaleId: 'left',
      title: '환율',
    });

    chartRef.current = chart;
    premiumSeriesRef.current = premiumSeries;
    fxSeriesRef.current = fxSeries;

    let debounceTimer: ReturnType<typeof setTimeout> | null = null;
    chart.timeScale().subscribeVisibleLogicalRangeChange((logicalRange) => {
      if (logicalRange === null) return;
      if (logicalRange.from < 10) {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
          fetchPast(activeIntervalRef.current);
        }, 300);
      }
    });

    const handleResize = () => {
      if (chartContainerRef.current) {
        chart.applyOptions({ width: chartContainerRef.current.clientWidth });
      }
    };
    window.addEventListener('resize', handleResize);

    return () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      window.removeEventListener('resize', handleResize);
      chart.remove();
      chartRef.current = null;
      premiumSeriesRef.current = null;
      fxSeriesRef.current = null;
    };
  }, [fetchPast]);

  useEffect(() => {
    // 인터벌 전환 시 초기화
    rawDataRef.current = [];
    premiumDataRef.current = [];
    fxDataRef.current = [];
    const config = INTERVALS.find((i) => i.value === activeInterval)!;
    loadedFromRef.current = new Date(Date.now() - config.rangeHours * 60 * 60 * 1000);
    hasMoreRef.current = true;

    fetchLatest(activeInterval);
    const timer = setInterval(() => fetchLatest(activeInterval), config.refreshMs);
    return () => clearInterval(timer);
  }, [activeInterval, fetchLatest]);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>프리미엄 차트</CardTitle>
          <div className="flex items-center gap-3">
            <div className="flex gap-1">
              <Button
                variant={showPremium ? 'default' : 'outline'}
                size="sm"
                onClick={() => setShowPremium(!showPremium)}
                className={showPremium ? 'bg-blue-600 hover:bg-blue-700' : ''}
              >
                <span className="mr-1 inline-block h-2 w-2 rounded-full bg-blue-500" />
                김프
              </Button>
              <Button
                variant={showFxRate ? 'default' : 'outline'}
                size="sm"
                onClick={() => setShowFxRate(!showFxRate)}
                className={showFxRate ? 'bg-amber-500 hover:bg-amber-600' : ''}
              >
                <span className="mr-1 inline-block h-2 w-2 rounded-full bg-amber-500" />
                환율
              </Button>
            </div>
            <div className="flex gap-1">
              {INTERVALS.map(({ label, value }) => (
                <Button
                  key={value}
                  variant={activeInterval === value ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => setActiveInterval(value)}
                >
                  {label}
                </Button>
              ))}
            </div>
          </div>
        </div>
      </CardHeader>
      <CardContent>
        <div ref={chartContainerRef} />
      </CardContent>
    </Card>
  );
}
