import json, os, sys
import numpy as np, pandas as pd
from statsmodels.tsa.stattools import adfuller, kpss
import statsmodels.api as sm
import warnings; warnings.filterwarnings("ignore")

D = os.environ.get("KIMP_DATA_DIR", os.path.dirname(os.path.abspath(__file__)) + "/data")
R = {}

def ser(name):
    a = json.load(open(f"{D}/{name}.json"))
    s = pd.Series({pd.Timestamp(t, unit="s", tz="UTC"): p for t, p in a}).sort_index()
    return s[~s.index.duplicated()]

kr = ser("bithumb"); sp = ser("binance_spot"); fu = ser("binance_fut")
fxh = pd.Series({pd.Timestamp(t, tz="UTC"): p for t, p in json.load(open(f"{D}/fx_hourly.json"))}).sort_index()
fxh = fxh[~fxh.index.duplicated()]
R["fx_hourly_outliers_dropped"] = int(((fxh < 800) | (fxh > 2500)).sum())
fxh = fxh[(fxh >= 800) & (fxh <= 2500)]  # Twelve Data 오염 값 제거 (예: 2024-01-02 00~02 UTC 의 2.3)
# Twelve Data hourly bar timestamp = bar start -> close at +1h
fxh.index = fxh.index + pd.Timedelta(hours=1)
fxd = pd.Series({pd.Timestamp(v["datetime"], tz="UTC"): float(v["close"]) for v in json.load(open(f"{D}/fx_daily.json"))}).sort_index()
fxd = fxd[(fxd >= 800) & (fxd <= 2500)]
fxd.index = fxd.index + pd.Timedelta(hours=21)  # approx NY close of that date (~21-22 UTC)

idx = pd.date_range(max(kr.index[0], sp.index[0]), min(kr.index[-1], sp.index[-1]), freq="h")
fx = pd.concat([fxd[fxd.index < fxh.index[0]], fxh]).sort_index()
fx = fx[~fx.index.duplicated()]
fx_on = fx.reindex(idx.union(fx.index)).ffill().reindex(idx)
fx_age = pd.Series(fx.index, index=fx.index).reindex(idx.union(fx.index)).ffill().reindex(idx)
fx_stale_h = (idx.to_series() - fx_age).dt.total_seconds() / 3600

df = pd.DataFrame({"kr": kr.reindex(idx), "sp": sp.reindex(idx), "fu": fu.reindex(idx), "fx": fx_on, "fx_stale_h": fx_stale_h.values}, index=idx)
df["p_sp"] = (df.kr - df.sp * df.fx) / (df.sp * df.fx) * 100
df["p_fu"] = (df.kr - df.fu * df.fx) / (df.fu * df.fx) * 100
R["coverage"] = {k: [str(v.index[0]), str(v.index[-1]), int(len(v))] for k, v in {"bithumb": kr, "binance_spot": sp, "binance_fut": fu, "fx_hourly": fxh, "fx_daily": fxd}.items()}

# daily series at 15:00 UTC (= KST 00:00), only where FX fresh (<=72h, weekends ok)
def daily(col, start=None):
    x = df[df.index.hour == 15]
    x = x[x.fx_stale_h <= 72][col].dropna()
    if start: x = x[x.index >= start]
    return x

main = daily("p_fu", "2019-09-09")
longs = daily("p_sp")
R["n_daily_main"] = len(main); R["n_daily_long"] = len(longs)

def desc(x):
    q = x.quantile([.01, .05, .1, .25, .5, .75, .9, .95, .99])
    return {"mean": x.mean(), "std": x.std(), "min": x.min(), "max": x.max(), "argmin": str(x.idxmin().date()), "argmax": str(x.idxmax().date()), **{f"q{int(k*100)}": v for k, v in q.items()}}

R["desc_main"] = desc(main); R["desc_long"] = desc(longs)
yr = longs.groupby(longs.index.year).agg(["mean", "std", "min", "max", lambda s: s.quantile(.05), lambda s: s.quantile(.95), "count"])
yr.columns = ["mean", "std", "min", "max", "q5", "q95", "n"]
R["yearly"] = yr.round(2).reset_index().values.tolist()

# stationarity
def stat(x):
    a = adfuller(x, autolag="AIC"); k = kpss(x, regression="c", nlags="auto")
    y = x.values; b = sm.OLS(y[1:], sm.add_constant(y[:-1])).fit().params[1]
    hl = -np.log(2) / np.log(b) if 0 < b < 1 else float("inf")
    return {"adf_stat": a[0], "adf_p": a[1], "kpss_stat": k[0], "kpss_p": k[1], "ar1": b, "half_life_days": hl}
R["stat_main"] = stat(main); R["stat_long"] = stat(longs)
# rolling 1-year windows stationarity
roll = []
for y0 in range(2018, 2027):
    x = longs[(longs.index >= f"{y0}-01-01") & (longs.index < f"{y0+1}-01-01")]
    if len(x) > 60:
        s = stat(x); roll.append([y0, len(x), round(s["adf_p"], 4), round(s["half_life_days"], 1), round(x.mean(), 2)])
R["stat_yearly"] = roll

# out-of-sample box test: box from past N days [q5,q95]; fraction of next M days inside
def box_test(x, N, M, lo=.05, hi=.95):
    out = []
    for i in range(N, len(x) - M, 7):
        past = x.iloc[i - N:i]; fut = x.iloc[i:i + M]
        L, U = past.quantile(lo), past.quantile(hi)
        inside = ((fut >= L) & (fut <= U)).mean()
        out.append((x.index[i], L, U, U - L, inside, fut.max() > U + (U - L), fut.min() < L - (U - L)))
    t = pd.DataFrame(out, columns=["t", "L", "U", "W", "inside", "breakup", "breakdn"]).set_index("t")
    return t
bt = {}
for N, M in [(30, 7), (30, 30), (90, 30), (90, 90), (180, 90), (365, 90)]:
    t = box_test(main, N, M)
    bt[f"{N}->{M}"] = {"windows": len(t), "mean_inside": t.inside.mean(), "share_ge90": (t.inside >= .9).mean(), "share_lt50": (t.inside < .5).mean(),
                       "width_med": t.W.median(), "hard_break": (t.breakup | t.breakdn).mean()}
R["box_test"] = bt
R["box_test_expected_if_stationary"] = 0.90

# FX-fixed conditioning: classify forward windows by FX move
def fx_cond(N=90, M=30):
    x = main; fxs = df.fx.reindex(x.index)
    rows = []
    for i in range(N, len(x) - M, 7):
        past = x.iloc[i - N:i]; fut = x.iloc[i:i + M]
        L, U = past.quantile(.05), past.quantile(.95)
        f = fxs.iloc[i:i + M]
        fxr = (f.max() - f.min()) / f.iloc[0] * 100
        rows.append((fxr, ((fut >= L) & (fut <= U)).mean(), fut.std(), past.mean(), fut.mean()))
    t = pd.DataFrame(rows, columns=["fx_range_pct", "inside", "fut_std", "past_mean", "fut_mean"])
    t["bucket"] = pd.cut(t.fx_range_pct, [0, 1.5, 3, 5, 100], labels=["<1.5%", "1.5–3%", "3–5%", ">5%"])
    g = t.groupby("bucket", observed=True).agg(n=("inside", "size"), inside=("inside", "mean"), fut_std=("fut_std", "mean"), mean_shift=("fut_mean", lambda s: 0))
    g["mean_shift"] = t.groupby("bucket", observed=True).apply(lambda d: (d.fut_mean - d.past_mean).abs().mean())
    return g.round(3).reset_index().values.tolist(), np.corrcoef(t.fx_range_pct, t.inside)[0, 1]
R["fx_cond_90_30"], R["fx_cond_corr"] = fx_cond(90, 30)

# FX-only windows: periods where 30d FX range < 1.5%; premium range in those windows
w = []
fxd_main = df.fx.reindex(main.index)
for i in range(0, len(main) - 30, 7):
    f = fxd_main.iloc[i:i + 30]; p = main.iloc[i:i + 30]
    w.append(((f.max() - f.min()) / f.iloc[0] * 100, p.max() - p.min(), p.std()))
w = pd.DataFrame(w, columns=["fx_rng", "p_rng", "p_std"])
R["fx_quiet_windows"] = {"n_quiet": int((w.fx_rng < 1.5).sum()), "n_all": len(w),
                         "p_rng_med_quiet": w[w.fx_rng < 1.5].p_rng.median(), "p_rng_med_all": w.p_rng.median(),
                         "p_rng_q90_quiet": w[w.fx_rng < 1.5].p_rng.quantile(.9), "corr_fxrng_prng": np.corrcoef(w.fx_rng, w.p_rng)[0, 1]}

# hourly regression dP on dFX (fresh FX only, 2020+)
h = df[(df.index >= "2020-02-01") & (df.fx_stale_h <= 1)][["p_fu", "fx"]].dropna()
h = h.resample("h").last().dropna()
dP = h.p_fu.diff(); dF = h.fx.pct_change() * 100
m = pd.concat([dP, dF], axis=1).dropna(); m.columns = ["dP", "dF"]
m = m[(m.dF.abs() < 3)]
fit = sm.OLS(m.dP, sm.add_constant(m.dF)).fit(cov_type="HC1")
R["hourly_beta"] = {"beta": fit.params["dF"], "t": fit.tvalues["dF"], "r2": fit.rsquared, "n": int(fit.nobs)}
# daily level: 24h change
d24 = h.resample("D").last().dropna(); m2 = pd.concat([d24.p_fu.diff(), d24.fx.pct_change() * 100], axis=1).dropna(); m2.columns = ["dP", "dF"]
f2 = sm.OLS(m2.dP, sm.add_constant(m2.dF)).fit(cov_type="HC1")
R["daily_beta"] = {"beta": f2.params["dF"], "t": f2.tvalues["dF"], "r2": f2.rsquared, "n": int(f2.nobs)}

# "Fixed FX" counterfactual: hold FX at a constant (anchor) and see how premium path changes
anchor = df.fx.reindex(main.index)
fixed = (df.kr / (df.fu * 1) ).reindex(main.index)  # KRW per USDT implied
R["implied_rate_vs_fx_corr"] = np.corrcoef(fixed.dropna().values, anchor.reindex(fixed.dropna().index).values)[0, 1]

# simple box-trade backtest (gross, daily): enter when P < rolling90 q10, exit when P > rolling90 q90 or 60d timeout
def backtest(x, N=90, lo=.1, hi=.9, cost=0.3, timeout=60):
    trades, i = [], N
    while i < len(x):
        past = x.iloc[i - N:i]
        if x.iloc[i] < past.quantile(lo):
            tgt = past.quantile(hi); j = i + 1
            while j < len(x) and j - i <= timeout and x.iloc[j] < tgt: j += 1
            j = min(j, len(x) - 1)
            trades.append((x.index[i], x.index[j], x.iloc[i], x.iloc[j], (j - i), x.iloc[j] >= tgt))
            i = j + 1
        else:
            i += 1
    t = pd.DataFrame(trades, columns=["in", "out", "p_in", "p_out", "days", "hit"])
    t["gross"] = t.p_out - t.p_in; t["net"] = t.gross - cost
    return t
bt2 = backtest(main)
R["backtest"] = {"n": len(bt2), "hit_rate": bt2.hit.mean(), "gross_mean": bt2.gross.mean(), "gross_med": bt2.gross.median(), "net_mean_cost0.3": bt2.net.mean(),
                 "worst": bt2.gross.min(), "days_mean": bt2.days.mean(), "win_rate_net": (bt2.net > 0).mean()}

# monthly series for chart
mon = longs.resample("ME").agg(["mean", "min", "max"]).dropna()
R["monthly"] = [[str(i.date())[:7], round(r["mean"], 3), round(r["min"], 3), round(r["max"], 3)] for i, r in mon.iterrows()]
mf = df.fx.resample("ME").mean().reindex(mon.index)
R["monthly_fx"] = [round(v, 1) for v in mf.values]
# rolling 90d box for chart
rq = main.rolling(90).quantile(.05), main.rolling(90).quantile(.95)
R["rolling_box_daily"] = [[str(i.date()), round(v, 3), round(a, 3) if a == a else None, round(b, 3) if b == b else None] for (i, v), a, b in zip(main.items(), rq[0], rq[1])]

def clean(o):
    if isinstance(o, dict): return {k: clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [clean(v) for v in o]
    if isinstance(o, (np.floating, float)): return None if o != o else round(float(o), 4)
    if isinstance(o, np.integer): return int(o)
    return o
json.dump(clean(R), open(os.path.dirname(os.path.abspath(__file__)) + "/results.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps({k: v for k, v in clean(R).items() if k not in ("monthly", "monthly_fx", "rolling_box_daily")}, ensure_ascii=False, indent=1))
