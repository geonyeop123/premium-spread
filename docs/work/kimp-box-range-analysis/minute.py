"""분 단위 김프 분석: 환율 A(1,300원 고정) vs B(직전 1시간 환율), 일 스냅샷 대비 차이, 분 단위 전략 백테스트.

입력: $KIMP_DATA_DIR/min/{bithumb,binance_fut}.npy, fx_hourly.json, funding.json, now.json
출력: minute_results.json
"""
import json, os
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.environ.get("KIMP_DATA_DIR", HERE + "/data")
FIXED_FX = 1300.0
R = {"fixed_fx": FIXED_FX}

# ── 적재 · 정렬 ────────────────────────────────────────────────────────
def npy(name):
    a = np.load(f"{D}/min/{name}.npy")
    s = pd.Series(a[:, 1], index=pd.to_datetime(a[:, 0].astype(np.int64), unit="s", utc=True))
    return s[~s.index.duplicated()].sort_index()
kr, fu = npy("bithumb"), npy("binance_fut")
fxh = pd.Series({pd.Timestamp(t, tz="UTC") + pd.Timedelta(hours=1): p for t, p in json.load(open(f"{D}/fx_hourly.json"))}).sort_index()
fxh = fxh[~fxh.index.duplicated()]
R["fx_outliers_dropped"] = int(((fxh < 800) | (fxh > 2500)).sum())
fxh = fxh[(fxh >= 800) & (fxh <= 2500)]
fund = pd.Series({pd.Timestamp(t, unit="s", tz="UTC"): r for t, r in json.load(open(f"{D}/funding.json"))}).sort_index()

df = pd.concat({"kr": kr, "fu": fu}, axis=1, join="inner")
df = df[df.index >= fxh.index[0]]
fx_idx = fxh.index.searchsorted(df.index, side="right") - 1
df["fx"] = fxh.values[fx_idx]
df["fx_age_h"] = (df.index - fxh.index[fx_idx]).total_seconds() / 3600
df = df[df.fx_age_h <= 72]
df["pA"] = (df.kr / (df.fu * FIXED_FX) - 1) * 100
df["pB"] = (df.kr / (df.fu * df.fx) - 1) * 100
cumf = fund.cumsum()
fi = cumf.index.searchsorted(df.index, side="right") - 1
df["cumf"] = np.where(fi >= 0, cumf.values[np.clip(fi, 0, None)], 0.0)
R["coverage"] = {"bithumb": [str(kr.index[0]), str(kr.index[-1]), len(kr)], "binance_fut": [str(fu.index[0]), str(fu.index[-1]), len(fu)],
                 "joined": [str(df.index[0]), str(df.index[-1]), len(df)],
                 "expected_minutes": int((df.index[-1] - df.index[0]).total_seconds() // 60) + 1}
# 이상치: 분 단위 체결가 튐
bad = (df.pB.abs() > 40)   # 2021-04·05 폭락 때 25~29% 실측값이 있어 40% 로 둔다
R["outlier_minutes_abs_pB_gt40"] = int(bad.sum())
df = df[~bad]

# KST 날짜 (00:00 KST = 15:00 UTC 경계)
kst_day = (df.index + pd.Timedelta(hours=9)).normalize()
df["day"] = kst_day.tz_localize(None)

# ── 1. A vs B ─────────────────────────────────────────────────────────
def qs(x): return {k: float(x.quantile(q)) for k, q in (("q5", .05), ("q50", .5), ("q95", .95))} | {"mean": float(x.mean()), "min": float(x.min()), "max": float(x.max())}
yr = df.groupby(df.index.year)
R["yearly_AB"] = [[int(y), qs(g.pA), qs(g.pB), float(g.fx.mean())] for y, g in yr]
R["corr_AB_minute"] = float(np.corrcoef(df.pA, df.pB)[0, 1])
dly = df.groupby("day").agg(pA=("pA", "mean"), pB=("pB", "mean"), fx=("fx", "mean"))
R["corr_AB_daily_mean"] = float(np.corrcoef(dly.pA, dly.pB)[0, 1])
R["corr_A_fx_daily"] = float(np.corrcoef(dly.pA, dly.fx)[0, 1])
R["corr_B_fx_daily"] = float(np.corrcoef(dly.pB, dly.fx)[0, 1])
mon = df.resample("ME").agg({"pA": "mean", "pB": "mean", "fx": "mean"}).dropna()
R["monthly_AB"] = [[str(i.date())[:7], round(r.pA, 3), round(r.pB, 3), round(r.fx, 1)] for i, r in mon.iterrows()]

# ── 2. 분 vs 일 스냅샷 (B) ─────────────────────────────────────────────
snap = df[(df.index.hour == 15) & (df.index.minute == 0)].pB
rng = df.groupby("day").pB.agg(["min", "max", "mean", "std"])
rng["range"] = rng["max"] - rng["min"]
R["intraday"] = {"days": len(rng), "range_med": float(rng.range.median()), "range_p90": float(rng.range.quantile(.9)), "range_p99": float(rng.range.quantile(.99)),
                 "range_max": float(rng.range.max()), "range_max_day": str(rng.range.idxmax().date()), "std_med": float(rng["std"].median())}
R["dist_minute_B"] = qs(df.pB); R["dist_snapshot_B"] = qs(snap)
R["dist_minute_B"]["q1"] = float(df.pB.quantile(.01)); R["dist_minute_B"]["q99"] = float(df.pB.quantile(.99))
R["dist_snapshot_B"]["q1"] = float(snap.quantile(.01)); R["dist_snapshot_B"]["q99"] = float(snap.quantile(.99))
yr_rng = rng.groupby(pd.DatetimeIndex(rng.index).year).range.median()
R["intraday_range_yearly"] = [[int(y), float(v)] for y, v in yr_rng.items()]

# ── 3. 일별 기준선 (직전 N일의 분 단위 분위수) ───────────────────────────
days = np.array(sorted(df.day.unique()))
day_pos = np.searchsorted(days, df.day.values)            # 분 → 날짜 번호
starts = np.searchsorted(df.day.values, days)              # 날짜 → 첫 분 위치 (df 는 시간순이라 day 도 단조)
starts = np.append(starts, len(df))
def daily_thresholds(sig, N, qs_):
    """날짜 d 의 기준선 = [d-N, d) 날짜들의 모든 분 값 분위수. 앞 N일은 NaN."""
    out = np.full((len(days), len(qs_)), np.nan)
    v = df[sig].values
    for d in range(N, len(days)):
        seg = v[starts[d - N]:starts[d]]
        out[d] = np.quantile(seg, qs_)
    return out
TH = {}
for sig in ("pA", "pB"):
    for N, q in ((180, (.05, .25, .30, .50, .90, .95)), (90, (.05, .20, .90, .95)), (30, (.05, .95)), (365, (.05, .95))):
        TH[(sig, N)] = (q, daily_thresholds(sig, N, list(q)))
def thr(sig, N, q):
    qq, arr = TH[(sig, N)]
    return arr[:, qq.index(q)]

# ── 4. 현재 위치 ──────────────────────────────────────────────────────
now = json.load(open(f"{D}/now.json")) if os.path.exists(f"{D}/now.json") else None
last = df.iloc[-1]
pos = {}
for sig in ("pA", "pB"):
    rows = []
    for N in (30, 90, 180, 365):
        seg = df[sig].values[starts[len(days) - N]:starts[len(days)]]  # 오늘 포함 직전 N일
        q = np.quantile(seg, [.05, .25, .5, .75, .95])
        rows.append({"N": N, "q5": q[0], "q25": q[1], "q50": q[2], "q75": q[3], "q95": q[4],
                     "rank": float((seg < last[sig]).mean() * 100), "above_q95": float(last[sig] - q[4])})
    pos[sig] = {"value": float(last[sig]), "boxes": rows}
R["now"] = {"at": str(df.index[-1]), "kr": float(last.kr), "fu": float(last.fu), "fx": float(last.fx), "fx_age_h": float(last.fx_age_h), **pos}

# ── 5. 분 단위 전략 ───────────────────────────────────────────────────
K, F, FX, CF = df.kr.values, df.fu.values, df.fx.values, df.cumf.values
T = df.index.values
TEST0 = np.datetime64("2023-01-01T00:00")
i_test0 = np.searchsorted(T, TEST0)

def pnl(i, j):
    gross = ((K[j] - K[i]) + (F[i] - F[j]) * FX[j]) / K[i] * 100
    fxt = F[i] * (FX[j] - FX[i]) / K[i] * 100
    fnd = (CF[j] - CF[i]) * F[i] * FX[i] / K[i] * 100
    return gross, fxt, fnd

def backtest(sig, N, lo, hi, timeout_days, cost, start=0, end=None, delay=1):
    """신호가 난 분에서 delay 분 뒤 가격으로 체결한다(기본 다음 분)."""
    end = len(df) if end is None else end
    s = df[sig].values
    e_thr = thr(sig, N, lo)[day_pos]; x_thr = thr(sig, N, hi)[day_pos]
    ent = np.flatnonzero(s < e_thr); ext = np.flatnonzero(s >= x_thr)
    trades, i = [], start
    tmo = np.timedelta64(int(timeout_days * 1440), "m")
    while True:
        k = np.searchsorted(ent, i)
        if k >= len(ent) or ent[k] >= end - 1 - delay: break
        sgn = ent[k]; ei = sgn + delay                               # delay 분 뒤 체결
        m = np.searchsorted(ext, ei)
        xs = ext[m] if m < len(ext) else len(df) - 1 - delay
        to_i = np.searchsorted(T, T[ei] + tmo)
        why = "target"
        if to_i <= xs: xs, why = min(to_i, len(df) - 1 - delay), "timeout"
        if xs >= len(df) - 1 - delay: why = "open"
        xj = min(xs + delay, len(df) - 1)
        g, fx_, fd = pnl(ei, xj)
        trades.append((T[ei], T[xj], s[ei], s[xj], (T[xj] - T[ei]) / np.timedelta64(1, "D"), why, g, fx_, fd))
        i = xj + 1
    t = pd.DataFrame(trades, columns=["in", "out", "s_in", "s_out", "days", "why", "gross", "fx", "fund"])
    t["net_app"] = t.gross + t.fund - cost
    t["net_ex"] = t.net_app - t.fx
    t["net_full"] = t.net_app + t.fx          # USDT 증거금 재평가 (1배)
    return t

def summ(t, years):
    if t.empty: return {"n": 0}
    c = t[t.why != "open"]
    return {"n": len(t), "open": int((t.why == "open").sum()), "targets": float((t.why == "target").mean()),
            "per_year_ex": float(t.net_ex.sum() / years), "per_year_app": float(t.net_app.sum() / years), "per_year_full": float(t.net_full.sum() / years),
            "win_ex": float((t.net_ex > 0).mean()), "win_full": float((t.net_full > 0).mean()),
            "worst_ex": float(t.net_ex.min()), "worst_full": float(t.net_full.min()),
            "avg_days": float(t.days.mean()), "exposure": float(t.days.sum() / (years * 365)),
            "prem_y": float((t.gross - t.fx).sum() / years), "fx_y": float(t.fx.sum() / years), "fund_y": float(t.fund.sum() / years)}

test_years = (T[-1] - T[i_test0]) / np.timedelta64(1, "D") / 365
train_years = (T[i_test0] - T[starts[180]]) / np.timedelta64(1, "D") / 365
STRATS = {"S1": (180, .30, .50, 120), "S2": (90, .20, .90, 60)}
R["strategy"] = {}
for sname, (N, lo, hi, to) in STRATS.items():
    for sig in ("pB", "pA"):
        for cost in (0.3, 0.6):
            te = backtest(sig, N, lo, hi, to, cost, start=i_test0)
            tr = backtest(sig, N, lo, hi, to, cost, end=i_test0)
            key = f"{sname}_{sig}_cost{cost}"
            R["strategy"][key] = {"test": summ(te, test_years), "train": summ(tr, train_years)}
            if cost == 0.3:
                R["strategy"][key]["trades_test"] = [[str(pd.Timestamp(r["in"]))[:16], str(pd.Timestamp(r.out))[:16], round(r.s_in, 2), round(r.s_out, 2),
                                                      round(r.days, 1), r.why, round(r.gross - r.fx, 2), round(r.fx, 2), round(r.fund, 2), round(r.net_ex, 2), round(r.net_full, 2)]
                                                     for _, r in te.iterrows()]
    # 현재 기준선
    R["strategy"][f"{sname}_now"] = {sig: {"entry_below": float(thr(sig, N, lo)[-1]), "exit_at": float(thr(sig, N, hi)[-1])} for sig in ("pA", "pB")}
R["test_years"] = float(test_years); R["train_years"] = float(train_years)
# 체결 지연 민감도 (검증 구간, B 신호)
R["delay_sens"] = {}
for sname, (N, lo, hi, to) in STRATS.items():
    for cost in (0.3, 0.6):
        R["delay_sens"][f"{sname}_cost{cost}"] = {dl: summ(backtest("pB", N, lo, hi, to, cost, start=i_test0, delay=dl), test_years) for dl in (1, 5, 30, 60, 240)}

# 비교용: 일 스냅샷 결과 (strategy_results.json)
sr = f"{HERE}/strategy_results.json"
if os.path.exists(sr):
    S = json.load(open(sr))
    R["snapshot_ref"] = {"S1": S["candidates"]["A"]["test_cost0.3"], "S2": S["candidates"]["B"]["test_cost0.3"],
                         "S1_full_L1": S["fx_accept"]["A"]["cost0.3"]["L1"], "S2_full_L1": S["fx_accept"]["B"]["cost0.3"]["L1"]}

# 차트: 일별 A/B 평균 + 일중 범위(B)
R["chart_daily"] = [[str(d.date()), round(r.pA, 3), round(r.pB, 3), round(rng.loc[d, "min"], 3), round(rng.loc[d, "max"], 3)] for d, r in dly.iterrows()]

def clean(o):
    if isinstance(o, dict): return {str(k): clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [clean(v) for v in o]
    if isinstance(o, (np.floating, float)): return None if o != o else round(float(o), 4)
    if isinstance(o, (np.integer,)): return int(o)
    if isinstance(o, (np.bool_,)): return bool(o)
    return o
json.dump(clean(R), open(f"{HERE}/minute_results.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps({k: clean(v) for k, v in R.items() if k not in ("monthly_AB", "chart_daily")} | {"strategy": {k: {kk: vv for kk, vv in v.items() if kk != "trades_test"} for k, v in clean(R["strategy"]).items()}}, ensure_ascii=False)[:12000])

# ── 6. 그래프용 데이터 ─────────────────────────────────────────────────
# 최근 30일 10분 평균 김프(A·B) + S1·S2 기준선(B 신호, 일별 계단)
t30 = df[df.index >= df.index[-1] - pd.Timedelta(days=30)]
m10 = t30[["pA", "pB"]].resample("10min").mean().dropna()
dp = np.searchsorted(days, (m10.index + pd.Timedelta(hours=9)).normalize().tz_localize(None).values)
R["chart_30d"] = [[str(i)[:16], round(a, 3), round(b, 3),
                   round(float(thr("pB", 180, .30)[d]), 3), round(float(thr("pB", 180, .50)[d]), 3),
                   round(float(thr("pB", 90, .20)[d]), 3), round(float(thr("pB", 90, .90)[d]), 3)]
                  for (i, a, b), d in zip(m10.itertuples(), dp)]
# 검증 구간 누적 손익 곡선 (청산일 기준, 환율 제외 / 실계좌 1배): 분 단위 vs 일 스냅샷
def curve(rows, i_out, i_ex, i_full=None):
    out, ce, cf = [], 0.0, 0.0
    for r in sorted(rows, key=lambda r: r[i_out]):
        ce += r[i_ex]; cf += (r[i_full] if i_full is not None else 0)
        out.append([r[i_out][:10], round(ce, 3), round(cf, 3)])
    return out
R["curves"] = {}
for sname in ("S1", "S2"):
    mt = R["strategy"][f"{sname}_pB_cost0.3"]["trades_test"]          # [in,out,s_in,s_out,days,why,prem,fx,fund,net_ex,net_full]
    R["curves"][f"{sname}_minute"] = curve(mt, 1, 9, 10)
    if os.path.exists(sr):
        st = [t for t in S["candidates"]["A" if sname == "S1" else "B"]["trades_full"] if t[0] >= "2023-01-01"]  # [in,out,p_in,p_out,days,why,prem,fx,fund,net_app]
        rows = [[t[0], t[1], t[9] - t[7], t[9] + t[7]] for t in st]
        R["curves"][f"{sname}_snapshot"] = curve(rows, 1, 2, 3)
json.dump(clean(R), open(f"{HERE}/minute_results.json", "w"), ensure_ascii=False, indent=1)
