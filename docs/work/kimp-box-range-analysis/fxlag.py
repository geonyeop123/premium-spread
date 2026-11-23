"""환율 급변 직후 김프 지연 회귀 이벤트 분석.

이벤트: 1시간 환율 변화 |ΔFX| ≥ 임계값인 정각 t (환율은 t 와 t-1h 모두 실측).
측정은 정각(환율이 새로 갱신된 시점)에서만 한다 — 정각 사이는 환율이 고정이라 왜곡된다.
매매: 환율 급등(김프 급락) 직후 t+1분에 한국 현물 Long + Binance 선물 Short, t+h 정각에 청산.
출력: fxlag_results.json
"""
import json, os
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.environ.get("KIMP_DATA_DIR", HERE + "/data")
def npy(n):
    a = np.load(f"{D}/min/{n}.npy"); s = pd.Series(a[:, 1], index=pd.to_datetime(a[:, 0].astype(np.int64), unit="s", utc=True))
    return s[~s.index.duplicated()].sort_index()
kr, fu = npy("bithumb"), npy("binance_fut")
px = pd.concat({"kr": kr, "fu": fu}, axis=1, join="inner")
fxh = pd.Series({pd.Timestamp(t, tz="UTC") + pd.Timedelta(hours=1): p for t, p in json.load(open(f"{D}/fx_hourly.json"))}).sort_index()
fxh = fxh[(fxh >= 800) & (fxh <= 2500)]; fxh = fxh[~fxh.index.duplicated()]
fxh = fxh[fxh.index >= px.index[0]]

H = (1, 3, 6, 24)
rows = []
fx_set = set(fxh.index)
for t, fx_t in fxh.items():
    t0 = t - pd.Timedelta(hours=1)
    if t0 not in fx_set or t not in px.index or t0 not in px.index: continue
    e = t + pd.Timedelta(minutes=1)
    if e not in px.index: continue
    fx0 = fxh[t0]; dfx = (fx_t / fx0 - 1) * 100
    p0 = (px.kr[t0] / (px.fu[t0] * fx0) - 1) * 100
    pt = (px.kr[t] / (px.fu[t] * fx_t) - 1) * 100
    K0, F0 = px.kr[e], px.fu[e]
    r = {"t": t, "dfx": dfx, "dp_now": pt - p0, "p_t": pt}
    for h in H:
        th = t + pd.Timedelta(hours=h)
        if th not in fx_set or th not in px.index:
            r[f"dp_{h}"] = np.nan; r[f"pnl_hedged_{h}"] = np.nan; r[f"pnl_real_{h}"] = np.nan; r[f"dfx_{h}"] = np.nan; continue
        fx1 = fxh[th]; K1, F1 = px.kr[th], px.fu[th]
        r[f"dp_{h}"] = (K1 / (F1 * fx1) - 1) * 100 - pt
        r[f"dfx_{h}"] = (fx1 / fx_t - 1) * 100
        gross = ((K1 - K0) + (F0 - F1) * fx1) / K0 * 100
        fxterm = F0 * (fx1 - fx_t) / K0 * 100
        r[f"pnl_hedged_{h}"] = gross - fxterm          # 환율 헤지 (김프 회귀분만)
        r[f"pnl_real_{h}"] = gross + fxterm            # 실제 계좌, Short 1배 (USDT 증거금 재평가 포함)
    rows.append(r)
ev = pd.DataFrame(rows).set_index("t")
R = {"n_hours": len(ev), "period": [str(ev.index[0]), str(ev.index[-1])]}

# 1. 즉시 반응과 이후 회귀 (회귀계수)
def beta(y, x):
    m = pd.concat([y, x], axis=1).dropna(); m = m[m.iloc[:, 1].abs() < 3]
    b = np.polyfit(m.iloc[:, 1], m.iloc[:, 0], 1)[0]; return float(b)
R["beta_now"] = beta(ev.dp_now, ev.dfx)
R["beta_after"] = {h: beta(ev[f"dp_{h}"], ev.dfx) for h in H}          # 이후 h시간 김프 변화 ~ 직전 1시간 ΔFX
R["fx_after"] = {h: beta(ev[f"dfx_{h}"], ev.dfx) for h in H}           # 환율 자체의 되돌림

# 2. 이벤트 버킷
def bucket(mask, name):
    d = ev[mask]; out = {"name": name, "n": int(len(d)), "per_year": float(len(d) / ((ev.index[-1] - ev.index[0]).days / 365)),
                         "dfx_mean": float(d.dfx.mean()), "dp_now_mean": float(d.dp_now.mean())}
    for h in H:
        dd = d[f"dp_{h}"].dropna()
        out[f"dp_{h}"] = float(dd.mean()); out[f"recover_{h}"] = float(-dd.mean() / d.dp_now.mean()) if d.dp_now.mean() else None
        out[f"dfx_{h}"] = float(d[f"dfx_{h}"].mean())
        for k in ("hedged", "real"):
            v = d[f"pnl_{k}_{h}"].dropna()
            out[f"{k}_{h}"] = {"mean": float(v.mean()), "med": float(v.median()), "p10": float(v.quantile(.1)), "p90": float(v.quantile(.9)),
                               "win_0.2": float((v > 0.2).mean()), "win_0.3": float((v > 0.3).mean()), "win_0.6": float((v > 0.6).mean()), "n": int(len(v))}
    return out
R["buckets"] = [bucket(ev.dfx >= .3, "환율 +0.3% 이상"), bucket(ev.dfx >= .5, "환율 +0.5% 이상"), bucket(ev.dfx >= 1.0, "환율 +1.0% 이상"),
                bucket(ev.dfx <= -.3, "환율 −0.3% 이하"), bucket(ev.dfx <= -.5, "환율 −0.5% 이하"),
                bucket(ev.dfx.abs() < .1, "환율 ±0.1% 미만(대조군)")]
R["dfx_dist"] = {"abs_ge_0.3_share": float((ev.dfx.abs() >= .3).mean()), "abs_ge_0.5_share": float((ev.dfx.abs() >= .5).mean()),
                 "abs_ge_1_share": float((ev.dfx.abs() >= 1).mean())}
# 3. 연도별 +0.3% 이벤트 수와 6시간 헤지 손익
up = ev[ev.dfx >= .3]
R["yearly_up"] = [[int(y), int(len(g)), float(g["pnl_hedged_6"].mean()), float(g["pnl_real_6"].mean())] for y, g in up.groupby(up.index.year)]
# 4. 최근 이벤트 예시 (최근 큰 것 5개)
big = ev[ev.dfx >= .5].tail(8)
R["examples"] = [[str(t)[:16], round(r.dfx, 2), round(r.dp_now, 2), round(r.dp_6, 2) if r.dp_6 == r.dp_6 else None,
                  round(r.pnl_hedged_6, 2) if r.pnl_hedged_6 == r.pnl_hedged_6 else None, round(r.pnl_real_6, 2) if r.pnl_real_6 == r.pnl_real_6 else None] for t, r in big.iterrows()]

def clean(o):
    if isinstance(o, dict): return {str(k): clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [clean(v) for v in o]
    if isinstance(o, (np.floating, float)): return None if o != o else round(float(o), 4)
    if isinstance(o, np.integer): return int(o)
    return o
json.dump(clean(R), open(f"{HERE}/fxlag_results.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps(clean(R), ensure_ascii=False, indent=1)[:9000])
