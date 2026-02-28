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
}

interface ChartDataPoint {
  time: UTCTimestamp;
  value: number;
}

const INTERVALS = [
  { label: '1분', value: '1m', refreshMs: 10_000, rangeHours: 2 },
  { label: '1시간', value: '1h', refreshMs: 60_000, rangeHours: 48 },
  { label: '1일', value: '1d', refreshMs: 300_000, rangeHours: 720 },
] as const;

export function PremiumChart() {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const [activeInterval, setActiveInterval] = useState('1m');

  const fetchData = useCallback(async (interval: string) => {
    const config = INTERVALS.find((i) => i.value === interval)!;
    const to = new Date();
    const from = new Date(to.getTime() - config.rangeHours * 60 * 60 * 1000);

    try {
      const data = await apiClient<AggregationData[]>(
        `/premiums/aggregation/BTC?interval=${interval}&from=${from.toISOString()}&to=${to.toISOString()}`,
      );

      const KST_OFFSET_SEC = 9 * 60 * 60;
      const points: ChartDataPoint[] = data.map((d) => ({
        time: (Math.floor(new Date(d.observedAt).getTime() / 1000) + KST_OFFSET_SEC) as UTCTimestamp,
        value: d.close,
      }));

      if (seriesRef.current) {
        seriesRef.current.setData(points);
      }
    } catch {
      // 다음 폴링에서 재시도
    }
  }, []);

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
    });

    const series = chart.addSeries(LineSeries, {
      color: '#2563eb',
      lineWidth: 2,
    });

    chartRef.current = chart;
    seriesRef.current = series;

    const handleResize = () => {
      if (chartContainerRef.current) {
        chart.applyOptions({ width: chartContainerRef.current.clientWidth });
      }
    };
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  useEffect(() => {
    fetchData(activeInterval);
    const config = INTERVALS.find((i) => i.value === activeInterval)!;
    const timer = setInterval(() => fetchData(activeInterval), config.refreshMs);
    return () => clearInterval(timer);
  }, [activeInterval, fetchData]);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>프리미엄 차트</CardTitle>
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
      </CardHeader>
      <CardContent>
        <div ref={chartContainerRef} />
      </CardContent>
    </Card>
  );
}
