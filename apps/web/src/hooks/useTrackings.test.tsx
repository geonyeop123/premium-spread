import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { apiClient, ApiError } from '@/lib/api';
import type { Tracking, GrossPnlData } from '@/components/TrackingList';
import {
  useTrackingList,
  useTracking,
  useGrossPnl,
  GROSS_PNL_POLL_INTERVAL_MS,
} from './useTrackings';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, apiClient: vi.fn() };
});

const mockApi = vi.mocked(apiClient);

const tracking = (id: number): Tracking => ({ id, symbol: `SYM${id}` }) as unknown as Tracking;
const pnlOf = (total: number): GrossPnlData =>
  ({ totalGrossPnlKrw: total }) as unknown as GrossPnlData;

/** 호출 시점을 테스트가 직접 제어하는 Promise. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  mockApi.mockReset();
});

describe('useTrackingList', () => {
  const summary = { totalTrackings: 3, activeTrackings: 2, archivedTrackings: 1 };

  function respondWith(active: Tracking[], archived: Tracking[], sum = summary) {
    mockApi.mockImplementation(async (path: string) => {
      if (path === '/trackings') return active;
      if (path === '/trackings/archived') return archived;
      if (path === '/trackings/summary') return sum;
      throw new Error(`unexpected path ${path}`);
    });
  }

  it('userId가 null이면 조회하지 않는다', () => {
    const { result } = renderHook(() => useTrackingList(null));
    expect(mockApi).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(true);
  });

  it('세 API 응답을 상태에 넣는다', async () => {
    respondWith([tracking(1)], [tracking(2)]);
    const { result } = renderHook(() => useTrackingList(10));
    await waitFor(() => expect(result.current.loading).toBe(false));

    const paths = mockApi.mock.calls.map((c) => c[0]);
    expect(paths).toHaveLength(3);
    expect(paths).toEqual(
      expect.arrayContaining(['/trackings', '/trackings/archived', '/trackings/summary']),
    );
    expect(result.current.activeTrackings).toEqual([tracking(1)]);
    expect(result.current.history).toEqual([tracking(2)]);
    expect(result.current.summary).toEqual(summary);
  });

  it('조회가 실패해도 loading이 끝난다', async () => {
    mockApi.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useTrackingList(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.activeTrackings).toEqual([]);
    expect(result.current.history).toEqual([]);
    expect(result.current.summary).toBeNull();
  });

  it('reload는 세 API를 다시 부른다', async () => {
    respondWith([tracking(1)], []);
    const { result } = renderHook(() => useTrackingList(10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockApi).toHaveBeenCalledTimes(3);

    respondWith([tracking(1), tracking(5)], []);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.activeTrackings).toHaveLength(2));
    expect(mockApi).toHaveBeenCalledTimes(6);
  });
});

describe('useTracking', () => {
  it('userId가 null이면 조회하지 않는다', () => {
    renderHook(() => useTracking('7', null));
    expect(mockApi).not.toHaveBeenCalled();
  });

  it('응답을 tracking에 넣는다', async () => {
    mockApi.mockResolvedValue(tracking(7));
    const { result } = renderHook(() => useTracking('7', 10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockApi).toHaveBeenCalledWith('/trackings/7');
    expect(result.current.tracking).toEqual(tracking(7));
    expect(result.current.error).toBe('');
  });

  it('실패하면 오류 문구를 낸다', async () => {
    mockApi.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useTracking('7', 10));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe('기록을 불러올 수 없습니다.');
    expect(result.current.tracking).toBeNull();
  });
});

describe('useGrossPnl', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const advance = (ms: number) =>
    act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });

  it('ACTIVE는 5초마다 다시 조회한다', async () => {
    mockApi.mockResolvedValue(pnlOf(1));
    renderHook(() => useGrossPnl('7', 'ACTIVE'));
    await advance(0);
    expect(mockApi).toHaveBeenCalledTimes(1);
    expect(mockApi).toHaveBeenCalledWith('/trackings/7/gross-pnl');
    await advance(GROSS_PNL_POLL_INTERVAL_MS);
    expect(mockApi).toHaveBeenCalledTimes(2);
    await advance(GROSS_PNL_POLL_INTERVAL_MS);
    expect(mockApi).toHaveBeenCalledTimes(3);
  });

  it('ARCHIVED는 한 번만 조회한다', async () => {
    mockApi.mockResolvedValue(pnlOf(1));
    renderHook(() => useGrossPnl('7', 'ARCHIVED'));
    await advance(10000);
    expect(mockApi).toHaveBeenCalledTimes(1);
  });

  it('status가 null이면 조회하지 않는다', async () => {
    renderHook(() => useGrossPnl('7', null));
    await advance(10000);
    expect(mockApi).not.toHaveBeenCalled();
  });

  it('언마운트하면 폴링이 멈춘다', async () => {
    mockApi.mockResolvedValue(pnlOf(1));
    const { unmount } = renderHook(() => useGrossPnl('7', 'ACTIVE'));
    await advance(0);
    const before = mockApi.mock.calls.length;
    unmount();
    await advance(10000);
    expect(mockApi).toHaveBeenCalledTimes(before);
  });

  it('409면 pnl이 null이다', async () => {
    mockApi.mockRejectedValue(new ApiError(409, 'CONFLICT', '확정 불가'));
    const { result } = renderHook(() => useGrossPnl('7', 'ARCHIVED'));
    await advance(0);
    expect(mockApi).toHaveBeenCalledTimes(1);
    expect(result.current).toBeNull();
  });
});

describe('늦은 응답', () => {
  it('목록 reload 중 이전 요청의 늦은 응답은 버린다', async () => {
    const sumOf = (n: number) => ({ totalTrackings: n, activeTrackings: n, archivedTrackings: 0 });
    const first = { a: deferred<Tracking[]>(), h: deferred<Tracking[]>(), s: deferred<unknown>() };
    const second = { a: deferred<Tracking[]>(), h: deferred<Tracking[]>(), s: deferred<unknown>() };
    const calls: Record<string, number> = {};
    mockApi.mockImplementation((path: string) => {
      calls[path] = (calls[path] ?? 0) + 1;
      const set = calls[path] === 1 ? first : second;
      const key = path === '/trackings' ? 'a' : path === '/trackings/archived' ? 'h' : 's';
      return set[key].promise as Promise<never>;
    });
    const { result } = renderHook(() => useTrackingList(1));
    act(() => result.current.reload());

    await act(async () => {
      second.a.resolve([tracking(2)]);
      second.h.resolve([tracking(20)]);
      second.s.resolve(sumOf(2));
    });
    await waitFor(() => expect(result.current.activeTrackings[0]?.id).toBe(2));

    await act(async () => {
      first.a.resolve([tracking(1)]);
      first.h.resolve([tracking(10)]);
      first.s.resolve(sumOf(1));
    });
    expect(result.current.activeTrackings[0].id).toBe(2);
    expect(result.current.history[0].id).toBe(20);
    expect(result.current.summary?.totalTrackings).toBe(2);
  });

  it('id가 바뀌면 이전 id의 tracking 응답은 버린다', async () => {
    const d1 = deferred<Tracking>();
    const d2 = deferred<Tracking>();
    mockApi.mockImplementation((path: string) =>
      (path === '/trackings/1' ? d1.promise : d2.promise) as Promise<never>,
    );
    const { result, rerender } = renderHook(({ id }) => useTracking(id, 10), {
      initialProps: { id: '1' },
    });
    rerender({ id: '2' });

    await act(async () => {
      d2.resolve(tracking(2));
    });
    await waitFor(() => expect(result.current.tracking?.id).toBe(2));

    await act(async () => {
      d1.resolve(tracking(1));
    });
    expect(result.current.tracking?.id).toBe(2);
  });

  it('id가 바뀌면 이전 id의 pnl 응답은 버린다', async () => {
    const d1 = deferred<GrossPnlData>();
    const d2 = deferred<GrossPnlData>();
    mockApi.mockImplementation((path: string) =>
      (path === '/trackings/1/gross-pnl' ? d1.promise : d2.promise) as Promise<never>,
    );
    const { result, rerender } = renderHook(({ id }) => useGrossPnl(id, 'ARCHIVED'), {
      initialProps: { id: '1' },
    });
    rerender({ id: '2' });

    await act(async () => {
      d2.resolve(pnlOf(222));
    });
    await waitFor(() => expect(result.current?.totalGrossPnlKrw).toBe(222));

    await act(async () => {
      d1.resolve(pnlOf(111));
    });
    expect(result.current?.totalGrossPnlKrw).toBe(222);
  });
});
