"""저장소에 커밋할 압축 데이터 사본 생성 → data_snapshot/

- 1시간봉 원본(bithumb, binance_spot, binance_fut, fx_hourly, fx_daily, funding) gzip
- 분봉에서 만든 1시간 단위 김프 요약(A·B 평균·최저·최고, 분 수) gzip CSV
분봉 원본은 크기 때문에 $KIMP_DATA_DIR/min 에만 둔다.
"""
import gzip, json, os, shutil
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.environ.get("KIMP_DATA_DIR", HERE + "/data")
OUT = f"{HERE}/data_snapshot"; os.makedirs(OUT, exist_ok=True)
for n in ("bithumb", "binance_spot", "binance_fut", "fx_hourly", "fx_daily", "funding", "now"):
    with open(f"{D}/{n}.json", "rb") as a, gzip.open(f"{OUT}/{n}.json.gz", "wb", compresslevel=9) as b:
        shutil.copyfileobj(a, b)

def npy(name):
    a = np.load(f"{D}/min/{name}.npy")
    s = pd.Series(a[:, 1], index=pd.to_datetime(a[:, 0].astype(np.int64), unit="s", utc=True))
    return s[~s.index.duplicated()].sort_index()
kr, fu = npy("bithumb"), npy("binance_fut")
fxh = pd.Series({pd.Timestamp(t, tz="UTC") + pd.Timedelta(hours=1): p for t, p in json.load(open(f"{D}/fx_hourly.json"))}).sort_index()
fxh = fxh[(fxh >= 800) & (fxh <= 2500)]; fxh = fxh[~fxh.index.duplicated()]
df = pd.concat({"kr": kr, "fu": fu}, axis=1, join="inner"); df = df[df.index >= fxh.index[0]]
df["fx"] = fxh.values[fxh.index.searchsorted(df.index, side="right") - 1]
df["pA"] = (df.kr / (df.fu * 1300.0) - 1) * 100
df["pB"] = (df.kr / (df.fu * df.fx) - 1) * 100
h = df.resample("h").agg({"pA": ["mean", "min", "max"], "pB": ["mean", "min", "max", "count"], "fx": "last"}).dropna()
h.columns = ["pA_mean", "pA_min", "pA_max", "pB_mean", "pB_min", "pB_max", "minutes", "fx"]
h.round(4).to_csv(f"{OUT}/premium_minute_to_hourly.csv.gz", index_label="hour_start_utc", compression={"method": "gzip", "compresslevel": 9})
for fn in sorted(os.listdir(OUT)):
    print(fn, os.path.getsize(f"{OUT}/{fn}"))
