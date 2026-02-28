'use client';

import { useState, useEffect, useCallback } from 'react';
import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { apiClient } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { PositionList, type Position } from '@/components/PositionList';
import { OpenPositionForm } from '@/components/OpenPositionForm';

interface PositionSummary {
  totalPositions: number;
  openPositions: number;
  closedPositions: number;
}

export default function PositionsPage() {
  const { user, loading: authLoading } = useAuth();
  const [openPositions, setOpenPositions] = useState<Position[]>([]);
  const [history, setHistory] = useState<Position[]>([]);
  const [summary, setSummary] = useState<PositionSummary | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    try {
      const [positionsData, historyData, summaryData] = await Promise.all([
        apiClient<Position[]>('/positions'),
        apiClient<Position[]>('/positions/history'),
        apiClient<PositionSummary>('/positions/summary'),
      ]);
      setOpenPositions(positionsData);
      setHistory(historyData);
      setSummary(summaryData);
    } catch {
      // silently handle - user may not be authenticated
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (user) {
      fetchData();
    }
  }, [user, fetchData]);

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
              포지션 관리를 이용하려면 로그인이 필요합니다.
            </p>
            <Link href="/login">
              <Button>로그인</Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="container mx-auto space-y-6 px-4 py-6">
      {summary && (
        <div className="grid grid-cols-3 gap-4">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">
                전체 포지션
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold">{summary.totalPositions}</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">
                열린 포지션
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-green-600">
                {summary.openPositions}
              </p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">
                종료 포지션
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-gray-500">
                {summary.closedPositions}
              </p>
            </CardContent>
          </Card>
        </div>
      )}

      <Tabs defaultValue="open">
        <TabsList>
          <TabsTrigger value="open">열린 포지션</TabsTrigger>
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>
        <TabsContent value="open">
          <Card>
            <CardContent className="pt-6">
              {loading ? (
                <p className="py-8 text-center text-muted-foreground">
                  로딩 중...
                </p>
              ) : (
                <PositionList positions={openPositions} />
              )}
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="history">
          <Card>
            <CardContent className="pt-6">
              {loading ? (
                <p className="py-8 text-center text-muted-foreground">
                  로딩 중...
                </p>
              ) : (
                <PositionList positions={history} />
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <OpenPositionForm onSuccess={fetchData} />
    </div>
  );
}
