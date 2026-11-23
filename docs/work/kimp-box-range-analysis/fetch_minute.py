"""1분봉 수집: Bithumb KRW-BTC, Binance USDT-M 선물 BTCUSDT. 재시작하면 이어받는다.

출력: $KIMP_DATA_DIR/min/{bithumb,binance_fut}.npy  — shape (n, 2): [봉 종료 UTC epoch 초, 종가]
사용: python fetch_minute.py bithumb|fut|fxtop
"""
import json, os, sys, time, urllib.request, urllib.parse
from datetime import datetime, timezone, timedelta
import numpy as np

OUT = os.environ.get("KIMP_DATA_DIR", os.path.dirname(os.path.abspath(__file__)) + "/data")
MIN = f"{OUT}/min"; os.makedirs(MIN, exist_ok=True)
START = datetime(2020, 1, 27, tzinfo=timezone.utc)
NOW = datetime.now(timezone.utc).replace(second=0, microsecond=0)
KST = timedelta(hours=9)


def get(url, retries=8):
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "research-client/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa: BLE001
            print("retry", i, e, file=sys.stderr); time.sleep(2 * (i + 1))
    raise RuntimeError(url)


def save(name, rows):
    a = np.array(sorted(set(rows)), dtype=np.float64)
    np.save(f"{MIN}/{name}.npy", a)
    return a


def load(name):
    p = f"{MIN}/{name}.npy"
    return [tuple(r) for r in np.load(p)] if os.path.exists(p) else []


def bithumb():
    """최신 → 과거로 200봉씩. 이미 받은 구간은 건너뛴다(가장 오래된 시각부터 이어받기)."""
    rows = load("bithumb")
    to = datetime.fromtimestamp(min(r[0] for r in rows) - 60, timezone.utc) if rows else NOW
    newest = max(r[0] for r in rows) if rows else None
    pages = 0
    while to > START:
        q = urllib.parse.quote((to + KST).strftime("%Y-%m-%d %H:%M:%S"))
        d = get(f"https://api.bithumb.com/v1/candles/minutes/1?market=KRW-BTC&count=200&to={q}")
        if not isinstance(d, list) or not d:
            print("bithumb stop", d, file=sys.stderr); break
        oldest = None
        for c in d:
            s = datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc)
            rows.append((s.timestamp() + 60, float(c["trade_price"])))
            oldest = s if oldest is None or s < oldest else oldest
        if oldest >= to: break
        to = oldest; pages += 1
        if pages % 500 == 0:
            save("bithumb", rows); print("bithumb", pages, to.isoformat(), file=sys.stderr)
        time.sleep(0.06)
    # 처음 받은 뒤 새로 생긴 최신 구간 보충
    if newest is not None and newest < NOW.timestamp() - 60:
        to = NOW
        while to.timestamp() > newest:
            q = urllib.parse.quote((to + KST).strftime("%Y-%m-%d %H:%M:%S"))
            d = get(f"https://api.bithumb.com/v1/candles/minutes/1?market=KRW-BTC&count=200&to={q}")
            if not d: break
            for c in d:
                s = datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc)
                rows.append((s.timestamp() + 60, float(c["trade_price"])))
            to = min(datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc) for c in d)
            time.sleep(0.06)
    a = save("bithumb", rows); print("bithumb done", len(a), file=sys.stderr)


def fut():
    rows = load("binance_fut")
    t = int(max(r[0] for r in rows)) * 1000 if rows else int(START.timestamp() * 1000)
    end = int(NOW.timestamp() * 1000); pages = 0
    while t < end:
        d = get(f"https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT&interval=1m&startTime={t}&limit=1500")
        if not d: break
        rows += [(k[0] / 1000 + 60, float(k[4])) for k in d]
        t = d[-1][0] + 60_000; pages += 1
        if pages % 300 == 0:
            save("binance_fut", rows); print("fut", pages, file=sys.stderr)
        time.sleep(0.15)
    a = save("binance_fut", rows); print("fut done", len(a), file=sys.stderr)


def fxtop():
    """fx_hourly.json 의 마지막 이후 1시간봉만 보충 (Twelve Data 1~2회)."""
    key = os.environ["EXCHANGE_RATE_API_KEY"]
    p = f"{OUT}/fx_hourly.json"; rows = json.load(open(p))
    last = max(r[0] for r in rows)
    url = (f"https://api.twelvedata.com/time_series?symbol=USD/KRW&interval=1h&timezone=UTC"
           f"&start_date={urllib.parse.quote(last)}&outputsize=5000&apikey={key}")
    d = get(url); vals = d.get("values", [])
    have = {r[0] for r in rows}
    rows = [list(r) for r in rows] + [[v["datetime"], float(v["close"])] for v in vals if v["datetime"] not in have]
    json.dump(sorted(rows), open(p, "w")); print("fx added", len(vals), "last", max(r[0] for r in rows), file=sys.stderr)


if __name__ == "__main__":
    {"bithumb": bithumb, "fut": fut, "fxtop": fxtop}[sys.argv[1]]()
