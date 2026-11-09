import { useState, useEffect, useCallback } from 'react';
import { apiClient } from '@/lib/api';
import type { Tracking, GrossPnlData } from '@/components/TrackingList';

export interface TrackingSummary {
  totalTrackings: number;
  activeTrackings: number;
  archivedTrackings: number;
}

export const GROSS_PNL_POLL_INTERVAL_MS = 5000;

// 조회 함수를 effect 안에 정의하고, cleanup 에서 cancelled 를 세워 늦게 도착한 응답을 버린다.
// (react-hooks/set-state-in-effect 를 피하면서 id·userId 변경 시 이전 응답이 덮어쓰는 것을 막는다.)

export function useTrackingList(userId: number | null) {
  const [activeTrackings, setActiveTrackings] = useState<Tracking[]>([]);
  const [history, setHistory] = useState<Tracking[]>([]);
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [reloadKey, setReloadKey] = useState(0);

  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (userId === null) return;
    let cancelled = false;
    const load = async () => {
      try {
        const [trackingsData, historyData, summaryData] = await Promise.all([
          apiClient<Tracking[]>('/trackings'),
          apiClient<Tracking[]>('/trackings/archived'),
          apiClient<TrackingSummary>('/trackings/summary'),
        ]);
        if (cancelled) return;
        setActiveTrackings(trackingsData);
        setHistory(historyData);
        setSummary(summaryData);
      } catch {
        // 조용히 처리한다 - 인증되지 않은 사용자일 수 있다. 기존 데이터는 유지한다.
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [userId, reloadKey]);

  return { activeTrackings, history, summary, loading, reload };
}

export function useTracking(id: string, userId: number | null) {
  const [tracking, setTracking] = useState<Tracking | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (userId === null) return;
    let cancelled = false;
    const load = async () => {
      try {
        const data = await apiClient<Tracking>(`/trackings/${id}`);
        if (!cancelled) setTracking(data);
      } catch {
        if (!cancelled) setError('기록을 불러올 수 없습니다.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [id, userId]);

  return { tracking, loading, error };
}

export function useGrossPnl(
  id: string,
  status: Tracking['status'] | null,
): GrossPnlData | null {
  const [pnl, setPnl] = useState<GrossPnlData | null>(null);

  useEffect(() => {
    if (status === null) return;
    let cancelled = false;
    const load = async () => {
      try {
        const data = await apiClient<GrossPnlData>(`/trackings/${id}/gross-pnl`);
        if (!cancelled) setPnl(data);
      } catch {
        // 409(확정 불가)는 정상 경로다. pnl 이 null 로 남고 화면이 ConfirmUnavailableNotice 를 보여준다.
        // 그 밖의 실패도 같은 표시가 되므로, 확정 여부는 Detail 의 hasConfirmedClose 가 정본이다.
      }
    };
    // ARCHIVED 도 한 번은 조회한다. 확정 손익(ARCHIVED_SNAPSHOT)은 불변이므로 주기 갱신은 하지 않는다.
    // 조회하지 않으면 확정된 값이 있는데도 화면이 "확정하지 못함" 을 보여준다.
    load();
    if (status !== 'ACTIVE') {
      return () => {
        cancelled = true;
      };
    }
    const interval = setInterval(load, GROSS_PNL_POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [id, status]);

  return pnl;
}
