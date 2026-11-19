"""Fetch hourly BTC prices (Bithumb KRW, Binance spot/futures USDT) and USD/KRW FX (Twelve Data)."""
import json, os, sys, time, urllib.request, urllib.parse
from datetime import datetime, timezone, timedelta

OUT = os.environ.get("KIMP_DATA_DIR", os.path.dirname(os.path.abspath(__file__)) + "/data")
os.makedirs(OUT, exist_ok=True)
START = datetime(2017, 8, 17, tzinfo=timezone.utc)
NOW = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
KEY = os.environ["EXCHANGE_RATE_API_KEY"]


def get(url, retries=6):
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "research-client/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa
            print("retry", i, e, file=sys.stderr)
            time.sleep(2 * (i + 1))
    raise RuntimeError(url)


def binance(base, path, name):
    rows, t = [], int(START.timestamp() * 1000)
    end = int(NOW.timestamp() * 1000)
    while t < end:
        d = get(f"{base}{path}?symbol=BTCUSDT&interval=1h&startTime={t}&limit=1000")
        if not d:
            break
        for k in d:
            rows.append((k[0] // 1000 + 3600, float(k[4])))  # close time (bar end), close price
        t = d[-1][0] + 3600_000
        time.sleep(0.1)
    json.dump(rows, open(f"{OUT}/{name}.json", "w"))
    print(name, len(rows), file=sys.stderr)


def bithumb():
    rows, to = [], NOW
    KST = timedelta(hours=9)
    while to > START:
        q = urllib.parse.quote((to + KST).strftime("%Y-%m-%d %H:%M:%S"))
        d = get(f"https://api.bithumb.com/v1/candles/minutes/60?market=KRW-BTC&count=200&to={q}")
        if not isinstance(d, list) or not d:
            print("bithumb stop", d, file=sys.stderr)
            break
        for c in d:
            s = datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc)
            rows.append((int(s.timestamp()) + 3600, float(c["trade_price"])))
        oldest = min(datetime.fromisoformat(c["candle_date_time_utc"]).replace(tzinfo=timezone.utc) for c in d)
        if oldest >= to:
            break
        to = oldest
        time.sleep(0.12)
    json.dump(sorted(set(rows)), open(f"{OUT}/bithumb.json", "w"))
    print("bithumb", len(rows), file=sys.stderr)


def fx():
    # daily for full window
    d = get(f"https://api.twelvedata.com/time_series?symbol=USD/KRW&interval=1day&start_date=2017-08-01&outputsize=5000&timezone=UTC&apikey={KEY}")
    json.dump(d.get("values", d), open(f"{OUT}/fx_daily.json", "w"))
    time.sleep(9)
    rows, s = [], datetime(2020, 1, 25, tzinfo=timezone.utc)
    while s < NOW:
        e = min(s + timedelta(hours=4900), NOW)
        url = (f"https://api.twelvedata.com/time_series?symbol=USD/KRW&interval=1h&timezone=UTC"
               f"&start_date={urllib.parse.quote(s.strftime('%Y-%m-%d %H:%M:%S'))}"
               f"&end_date={urllib.parse.quote(e.strftime('%Y-%m-%d %H:%M:%S'))}&outputsize=5000&apikey={KEY}")
        d = get(url)
        vals = d.get("values", [])
        if not vals:
            print("fx empty", s, d.get("message"), file=sys.stderr)
        for v in vals:
            rows.append((v["datetime"], float(v["close"])))
        s = e
        time.sleep(9)
    json.dump(rows, open(f"{OUT}/fx_hourly.json", "w"))
    print("fx_hourly", len(rows), file=sys.stderr)


if __name__ == "__main__":
    which = sys.argv[1]
    {"spot": lambda: binance("https://api.binance.com", "/api/v3/klines", "binance_spot"),
     "fut": lambda: binance("https://fapi.binance.com", "/fapi/v1/klines", "binance_fut"),
     "bithumb": bithumb, "fx": fx}[which]()
