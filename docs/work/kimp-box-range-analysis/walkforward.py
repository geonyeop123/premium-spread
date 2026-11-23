"""롤링 검증(walk-forward): 매년 직전 3년으로 파라미터를 고르고 다음 1년에 시험한다.

strategy.py 의 run()/summary() 와 같은 일 스냅샷 백테스트를 쓴다(손익: 앱 공식 + 펀딩 − 비용, 실계좌는 환율 항 ×2).
strategy.py 를 사본 폴더에서 실행해 저장소의 결과 파일을 덮어쓰지 않는다.
출력: walkforward_results.json
"""
import contextlib, io, itertools, json, os, runpy, shutil, tempfile
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
tmp = tempfile.mkdtemp()
for fn in ("analyze.py", "strategy.py"):
    shutil.copy(f"{HERE}/{fn}", tmp)
with contextlib.redirect_stdout(io.StringIO()):
    G = runpy.run_path(f"{tmp}/strategy.py")
run, day = G["run"], G["day"]
COST = 0.3

def idx(y): return int(day.index.searchsorted(pd.Timestamp(f"{y}-01-01", tz="UTC")))
def stats(t, years):
    if t.empty: return {"n": 0, "real": 0.0, "hedged": 0.0, "worst_real": 0.0}
    real = t.net + t.fx            # 실계좌 1배: 앱 공식 + 증거금 환율 재평가
    return {"n": len(t), "real": float(real.sum() / years), "hedged": float((t.net - t.fx).sum() / years),
            "worst_real": float(real.min()), "win_real": float((real > 0).mean())}

GRID = list(itertools.product((60, 90, 180, 365), (.05, .10, .20, .30), (.5, .7, .9), (30, 60, 120)))
FIXED = {"S2": (90, .20, .90, 60), "S1": (180, .30, .50, 120)}
rows, picks = [], []
for Y in (2023, 2024, 2025, 2026):
    tr0, tr1, te0 = idx(Y - 3), idx(Y), idx(Y)
    te1 = idx(Y + 1) if Y < 2026 else len(day)
    tr_years = (day.index[tr1 - 1] - day.index[tr0]).days / 365
    te_years = (day.index[te1 - 1] - day.index[te0]).days / 365
    scored = []
    for N, lo, hi, to in GRID:
        t = run(N, lo, hi, to, None, COST, start=max(tr0, N), end=tr1)
        if len(t) >= 5:
            scored.append((float((t.net + t.fx).sum() / tr_years), (N, lo, hi, to)))
    scored.sort(reverse=True)
    best = scored[0][1]
    top5 = [s[1] for s in scored[:5]]
    res = {"year": Y, "train": [Y - 3, Y - 1], "pick": list(best), "pick_train_real": scored[0][0],
           "pick_test": stats(run(*best, None, COST, start=te0, end=te1), te_years),
           "top5_test_real_mean": float(np.mean([stats(run(*p, None, COST, start=te0, end=te1), te_years)["real"] for p in top5])),
           "top5": [list(p) for p in top5]}
    for k, p in FIXED.items():
        res[k] = stats(run(*p, None, COST, start=te0, end=te1), te_years)
    # 같은 해 사후 최고(시험지를 보고 고른 경우) — 과적합이 얼마나 부풀리는지 보여주는 상한
    ex_post = max((stats(run(*p, None, COST, start=te0, end=te1), te_years)["real"], p) for p in GRID)
    res["ex_post_best"] = {"real": ex_post[0], "params": list(ex_post[1])}
    rows.append(res)

# 2023~2026 전체를 보고 고른 경우(사후 최적) vs 그 조합의 2019~2022 성과
full0 = idx(2023)
fy = (day.index[-1] - day.index[full0]).days / 365
ins = sorted(((stats(run(*p, None, COST, start=full0), fy)["real"], p) for p in GRID), reverse=True)[:5]
py = (day.index[full0 - 1] - day.index[0]).days / 365
out = {"cost": COST, "walkforward": rows,
       "insample_2023_2026_top5": [{"params": list(p), "real_2023_2026": r, "real_2019_2022": stats(run(*p, None, COST, end=full0), py)["real"]} for r, p in ins],
       "S2_2023_2026": stats(run(*FIXED["S2"], None, COST, start=full0), fy)}
json.dump(out, open(f"{HERE}/walkforward_results.json", "w"), ensure_ascii=False, indent=1, default=float)
for r in rows:
    print(r["year"], "pick", r["pick"], "train", round(r["pick_train_real"], 1), "→ test", {k: round(v, 2) for k, v in r["pick_test"].items()},
          "| top5 test", round(r["top5_test_real_mean"], 1), "| S2", round(r["S2"]["real"], 1), "S1", round(r["S1"]["real"], 1),
          "| ex-post best", round(r["ex_post_best"]["real"], 1), r["ex_post_best"]["params"])
print("in-sample 2023-26 top5:"); [print(" ", x) for x in out["insample_2023_2026_top5"]]
print("S2 2023-26", out["S2_2023_2026"])
shutil.rmtree(tmp)
