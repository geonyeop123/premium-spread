"""현재 김프의 박스 내 위치, 위치별 조건부 기대값, 비용·펀딩비 포함 walk-forward 전략 검증.

손익은 앱 Tracking 공식(.ai/context/project-overview.md §4)을 따른다:
  koreaPnl = (K1 - K0) * q,  foreignPnlKrw = (F0 - F1) * q * FX1,  수익률 = 합 / (K0 * q) * 100
  + Short 펀딩비 수취(rate > 0 이면 받음) - 왕복 비용.
analyze.py 의 데이터 적재를 그대로 재사용한다.
"""
import contextlib, io, json, os, runpy, itertools
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.environ.get("KIMP_DATA_DIR", HERE + "/data")
with contextlib.redirect_stdout(io.StringIO()):
    G = runpy.run_path(f"{HERE}/analyze.py")
df, main = G["df"], G["main"]
S = {}

# ── 일별 가격 (KST 00:00 = 15:00 UTC) + 펀딩 ────────────────────────────
day = df.loc[main.index, ["kr", "fu", "fx"]].copy()
day["p"] = main
fund = pd.Series({pd.Timestamp(t, unit="s", tz="UTC"): r for t, r in json.load(open(f"{D}/funding.json"))}).sort_index()
cumf = fund.cumsum()                                   # 누적 펀딩률 (Short 수취 기준, 소수)
day["cumf"] = cumf.reindex(day.index.union(cumf.index)).ffill().reindex(day.index).fillna(0)

def trade_ret(i, j):
    """i 일에 진입, j 일에 청산 시 Long KR + Short Binance 선물 gross 수익률(%, 한국 진입 금액 대비)."""
    a, b = day.iloc[i], day.iloc[j]
    kpnl = b.kr - a.kr
    fpnl = (a.fu - b.fu) * b.fx
    fund_pct = (b.cumf - a.cumf) * a.fu * a.fx / a.kr   # 해외 명목가 대비 펀딩을 한국 진입금액 대비로 환산
    return (kpnl + fpnl) / a.kr * 100, fund_pct * 100

def fx_term(i, j):
    """gross 중 환율 노출 성분: F0*(FX1-FX0)/K0. 이 구조는 BTC 중립이지만 USD/KRW Long 이다."""
    a, b = day.iloc[i], day.iloc[j]
    return a.fu * (b.fx - a.fx) / a.kr * 100

# ── 1. 현재 위치 ──────────────────────────────────────────────────────
now = json.load(open(f"{D}/now.json"))
P = now["premium_pct"]; S["now"] = now
pos = []
for N in (30, 90, 180, 365):
    past = main.iloc[-N:]
    q = past.quantile([.05, .10, .25, .5, .75, .90, .95])
    rank = (past < P).mean() * 100
    z = (P - past.mean()) / past.std()
    pos.append({"N": N, "q5": q[.05], "q10": q[.10], "q25": q[.25], "q50": q[.5], "q75": q[.75], "q90": q[.90], "q95": q[.95],
                "mean": past.mean(), "std": past.std(), "rank": rank, "z": z,
                "above_q95": P - q[.95], "below_q5": q[.05] - P})
S["position"] = pos
S["last_30d"] = [[str(i.date()), round(v, 3)] for i, v in main.iloc[-30:].items()]
S["fund_recent"] = {"last_30d_sum_pct": fund[fund.index >= fund.index[-1] - pd.Timedelta(days=30)].sum() * 100,
                    "last_365d_sum_pct": fund[fund.index >= fund.index[-1] - pd.Timedelta(days=365)].sum() * 100,
                    "share_positive_365d": (fund[fund.index >= fund.index[-1] - pd.Timedelta(days=365)] > 0).mean()}
yf = fund.groupby(fund.index.year).sum() * 100
S["fund_yearly_pct"] = [[int(y), round(v, 2)] for y, v in yf.items()]

# ── 2. 위치별 조건부 기대값 (진입 후 H일 보유) ─────────────────────────
bins = [0, 10, 25, 50, 75, 90, 100.01]
labels = ["0–10", "10–25", "25–50", "50–75", "75–90", "90–100"]
cond = {}
for N in (90, 180):
    rows = []
    for i in range(N, len(day)):
        past = main.iloc[i - N:i]
        r = (past < main.iloc[i]).mean() * 100
        for H in (7, 14, 30, 60):
            if i + H < len(day):
                g, f = trade_ret(i, i + H)
                x = fx_term(i, i + H)
                rows.append((r, H, g, f, g + f, g - x + f, main.iloc[i + H] - main.iloc[i]))
    t = pd.DataFrame(rows, columns=["rank", "H", "gross", "fund", "tot", "tot_ex_fx", "dP"])
    t["b"] = pd.cut(t["rank"], bins, labels=labels, right=False)
    out = []
    for (b, H), d in t.groupby(["b", "H"], observed=True):
        out.append({"bucket": b, "H": H, "n": len(d), "indep": len(d) // H, "mean": d.tot.mean(), "med": d.tot.median(),
                    "p10": d.tot.quantile(.1), "p90": d.tot.quantile(.9), "fund": d.fund.mean(),
                    "win_0.3": (d.tot > 0.3).mean(), "win_0.6": (d.tot > 0.6).mean(),
                    "mean_ex_fx": d.tot_ex_fx.mean(), "p10_ex_fx": d.tot_ex_fx.quantile(.1), "win_ex_fx_0.3": (d.tot_ex_fx > 0.3).mean(),
                    "dP_mean": d.dP.mean(), "dP_med": d.dP.median()})
    cond[N] = out
S["conditional"] = cond
cur_rank = {p["N"]: p["rank"] for p in pos}
S["current_bucket"] = {N: labels[np.digitize(cur_rank[N], bins) - 1] for N in (90, 180)}

# ── 3. 전략 grid + walk-forward ───────────────────────────────────────
TRAIN_END = pd.Timestamp("2023-01-01", tz="UTC")
idx_test0 = day.index.searchsorted(TRAIN_END)

_RQ = {}
def RQ(N, q):
    """직전 N일(당일 제외) 분위수. past = main.iloc[i-N:i] 의 quantile 과 같다."""
    if (N, q) not in _RQ:
        _RQ[(N, q)] = main.rolling(N).quantile(q).shift(1).values
    return _RQ[(N, q)]

def run(N, lo, hi, timeout, stop, cost, start=None, end=None):
    """past N일 분위 lo 아래 진입 → 분위 hi 도달 / timeout / stop(진입 박스 폭 배수 하락) 청산. 진입은 [start,end) 구간만."""
    trades, i = [], max(N, start or 0)
    end = end or len(day)
    while i < end and i < len(day) - 1:
        L, U = RQ(N, lo)[i], RQ(N, .95)[i] - RQ(N, .05)[i]
        if main.iloc[i] < L:
            j, why = i + 1, "timeout"
            while j < len(day) - 1 and j - i < timeout:
                if main.iloc[j] >= RQ(N, hi)[j]: why = "target"; break
                if stop and main.iloc[j] < main.iloc[i] - stop * U: why = "stop"; break
                j += 1
            g, f = trade_ret(i, j)
            trades.append((day.index[i], day.index[j], main.iloc[i], main.iloc[j], j - i, why, g, f, g + f - cost, fx_term(i, j)))
            i = j + 1
        else:
            i += 1
    return pd.DataFrame(trades, columns=["in", "out", "p_in", "p_out", "days", "why", "gross", "fund", "net", "fx"])

def summary(t, span_days):
    if t.empty: return {"n": 0}
    return {"n": len(t), "sum": t.net.sum(), "mean": t.net.mean(), "med": t.net.median(), "win": (t.net > 0).mean(),
            "worst": t.net.min(), "days_in": int(t.days.sum()), "exposure": t.days.sum() / span_days,
            "per_year": t.net.sum() / (span_days / 365), "fund_share": t.fund.sum() / max(t.net.sum() + 1e-9, 1e-9),
            "targets": (t.why == "target").mean(), "stops": (t.why == "stop").mean(),
            "fx_sum": t.fx.sum(), "fund_sum": t.fund.sum(), "prem_sum": (t.gross - t.fx).sum(), "cost_sum": (t.gross + t.fund - t.net).sum(),
            "per_year_ex_fx": (t.net.sum() - t.fx.sum()) / (span_days / 365)}

train_days = (TRAIN_END - day.index[0]).days
test_days = (day.index[-1] - TRAIN_END).days
grid = []
for N, lo, hi, to, st in itertools.product((60, 90, 180, 365), (.05, .10, .20, .30), (.5, .7, .9), (30, 60, 120), (None, 1.0)):
    for cost in (0.3, 0.6):
        tr = run(N, lo, hi, to, st, cost, end=idx_test0)
        te = run(N, lo, hi, to, st, cost, start=idx_test0)
        grid.append({"N": N, "lo": lo, "hi": hi, "timeout": to, "stop": st, "cost": cost,
                     "train": summary(tr, train_days), "test": summary(te, test_days)})
S["grid_size"] = len(grid) // 2

def pick(cost):
    g = [x for x in grid if x["cost"] == cost and x["train"].get("n", 0) >= 8]
    g.sort(key=lambda x: x["train"]["per_year"], reverse=True)
    return g
for cost in (0.3, 0.6):
    g = pick(cost)
    S[f"top_train_cost{cost}"] = g[:10]
    tests = [x["test"].get("per_year", 0) for x in g]
    S[f"test_dist_cost{cost}"] = {"all_configs": len(g), "test_positive": float(np.mean([v > 0 for v in tests])),
                                  "test_median_per_year": float(np.median(tests)),
                                  "top10_test_median": float(np.median(tests[:10])), "top10_test_min": float(np.min(tests[:10])),
                                  "test_positive_ex_fx": float(np.mean([x["test"].get("per_year_ex_fx", 0) > 0 for x in g])),
                                  "test_median_per_year_ex_fx": float(np.median([x["test"].get("per_year_ex_fx", 0) for x in g])),
                                  "top10_test_median_ex_fx": float(np.median([x["test"].get("per_year_ex_fx", 0) for x in g[:10]]))}

# 대표 전략(사전 지정: 지난 분석과 같은 90일·P10→P90·60일, 손절 1배) 의 test 거래 목록
rep = run(90, .10, .90, 60, 1.0, 0.3, start=idx_test0)
S["rep_test"] = summary(rep, test_days)
S["rep_trades"] = [[str(r["in"].date()), str(r.out.date()), round(r.p_in, 2), round(r.p_out, 2), int(r.days), r.why,
                    round(r.gross, 2), round(r.fund, 2), round(r.net, 2), round(r.fx, 2)] for _, r in rep.iterrows()]

# 기준선: test 기간 내내 헤지 보유 (2023-01-01 진입 → 마지막 날 청산), 비용 1회
g, f = trade_ret(idx_test0, len(day) - 1)
x = fx_term(idx_test0, len(day) - 1)
S["hold_test"] = {"fx": x, "prem": g - x, "per_year_ex_fx": (g - x + f - 0.3) / (test_days / 365), "gross": g, "fund": f, "net": g + f - 0.3, "per_year": (g + f - 0.3) / (test_days / 365), "days": test_days}
# 펀딩만 받는 경우(김프 변화 0 가정)의 test 연환산
S["fund_only_test_per_year"] = f / (test_days / 365)

def clean(o):
    if isinstance(o, dict): return {str(k): clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [clean(v) for v in o]
    if isinstance(o, (np.floating, float)): return None if o != o else round(float(o), 4)
    if isinstance(o, (np.integer,)): return int(o)
    if isinstance(o, (np.bool_,)): return bool(o)
    return o
json.dump(clean(S), open(f"{HERE}/strategy_results.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps({k: clean(v) for k, v in S.items() if k not in ("conditional", "last_30d", "rep_trades", "top_train_cost0.6")}, ensure_ascii=False, indent=1)[:9000])

# ── 4. 추천 후보의 주변 파라미터 robustness · 비용 민감도 · 현재 기준선 ─────────
cands = {"A": (180, .30, .50, 120, None), "B": (90, .20, .90, 60, None)}
S["candidates"] = {}
for name, (N, lo, hi, to, st) in cands.items():
    c = {"params": [N, lo, hi, to, st]}
    for cost in (0.3, 0.6, 1.0):
        c[f"test_cost{cost}"] = summary(run(N, lo, hi, to, st, cost, start=idx_test0), test_days)
        c[f"train_cost{cost}"] = summary(run(N, lo, hi, to, st, cost, end=idx_test0), train_days)
    nb = []
    Ns = sorted({max(60, N // 2), N, min(365, N * 2)})
    los = sorted({round(max(.05, lo - .1), 2), lo, round(lo + .1, 2)})
    his = sorted({round(max(.5, hi - .2), 2), hi, round(min(.9, hi + .2), 2)})
    for N2, lo2, hi2, to2 in itertools.product(Ns, los, his, (to // 2, to)):
        if lo2 >= hi2: continue
        s_ = summary(run(N2, lo2, hi2, to2, None, 0.3, start=idx_test0), test_days)
        nb.append([N2, lo2, hi2, to2, s_.get("n", 0), s_.get("per_year_ex_fx", 0), s_.get("worst", 0)])
    c["neighbors"] = nb
    c["neighbors_pos"] = float(np.mean([x[5] > 0 for x in nb]))
    c["neighbors_med"] = float(np.median([x[5] for x in nb]))
    c["neighbors_min"] = float(np.min([x[5] for x in nb]))
    past = main.iloc[-N:]
    c["now_entry_below"] = past.quantile(lo); c["now_exit_at"] = past.quantile(hi)
    full = run(N, lo, hi, to, st, 0.3)
    last = full.iloc[-1] if len(full) else None
    c["last_trade"] = None if last is None else [str(last["in"].date()), str(last.out.date()), last.why, round(last.net, 2)]
    c["trades_full"] = [[str(r["in"].date()), str(r.out.date()), round(r.p_in, 2), round(r.p_out, 2), int(r.days), r.why,
                         round(r.gross - r.fx, 2), round(r.fx, 2), round(r.fund, 2), round(r.net, 2)] for _, r in full.iterrows()]
    S["candidates"][name] = c
# 자본 환산: 한국 매수금 1 + 해외 증거금 1/L. 한국 진입금 대비 수익률을 이 배수로 나누면 자본 대비가 된다.
S["capital_factor"] = {L: 1 + 1 / L for L in (1, 2, 3)}

json.dump(clean(S), open(f"{HERE}/strategy_results.json", "w"), ensure_ascii=False, indent=1)

# ── 5. 증거금 위험 · 차트 데이터 ──────────────────────────────────────
for name, (N, lo, hi, to, st) in cands.items():
    full = run(N, lo, hi, to, st, 0.3)
    ups = []
    for _, r in full.iterrows():
        seg = df.fu[(df.index >= r["in"]) & (df.index <= r.out)].dropna()
        ups.append(seg.max() / seg.iloc[0] - 1)
    S["candidates"][name]["btc_max_rally_pct"] = float(np.max(ups) * 100)
    S["candidates"][name]["btc_rally_p90_pct"] = float(np.quantile(ups, .9) * 100)
r180_30 = main.rolling(180).quantile(.30).shift(1); r180_50 = main.rolling(180).quantile(.50).shift(1)
r90_20 = main.rolling(90).quantile(.20).shift(1); r90_90 = main.rolling(90).quantile(.90).shift(1)
tail = main.iloc[-365:]
S["chart_1y"] = [[str(i.date()), round(v, 3), round(r180_30[i], 3), round(r180_50[i], 3), round(r90_20[i], 3), round(r90_90[i], 3)] for i, v in tail.items()]
json.dump(clean(S), open(f"{HERE}/strategy_results.json", "w"), ensure_ascii=False, indent=1)
print({n: (round(c["btc_max_rally_pct"], 1), round(c["btc_rally_p90_pct"], 1)) for n, c in S["candidates"].items()})
