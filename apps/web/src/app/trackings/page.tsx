'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { TrackingList } from '@/components/TrackingList';
import { RecordTrackingForm } from '@/components/RecordTrackingForm';
import { useTrackingList } from '@/hooks/useTrackings';

export default function TrackingsPage() {
  const { user, loading: authLoading } = useAuth();
  const { activeTrackings, history, summary, loading, reload } = useTrackingList(
    user?.id ?? null,
  );

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
              포지션 기록을 이용하려면 로그인이 필요합니다.
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
                전체 기록
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold">{summary.totalTrackings}</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">
                추적 중
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-green-600">
                {summary.activeTrackings}
              </p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-muted-foreground">
                종료됨
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-2xl font-bold text-gray-500">
                {summary.archivedTrackings}
              </p>
            </CardContent>
          </Card>
        </div>
      )}

      <Tabs defaultValue="open">
        <TabsList>
          <TabsTrigger value="open">추적 중</TabsTrigger>
          <TabsTrigger value="history">종료된 기록</TabsTrigger>
        </TabsList>
        <TabsContent value="open">
          <Card>
            <CardContent className="pt-6">
              {loading ? (
                <p className="py-8 text-center text-muted-foreground">
                  로딩 중...
                </p>
              ) : (
                <TrackingList trackings={activeTrackings} />
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
                <TrackingList trackings={history} />
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <RecordTrackingForm onSuccess={reload} />
    </div>
  );
}
