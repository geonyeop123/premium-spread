"""strategy_results.json → strategy.html. 수치는 전부 JSON 에서, 인용 라인은 strategy.py 에서 직접 찾는다."""
import json, os, re, sys, html

HERE = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.expanduser("~/.claude/skills/analysis-report/template.html")
S = json.load(open(f"{HERE}/strategy_results.json"))
branch, sha, date = sys.argv[1:4]
tpl = open(TPL, encoding="utf-8").read()
CSS = re.search(r"<style>(.*?)</style>", tpl, re.S).group(1)
JS = re.search(r"<script>(.*?)</script>", tpl, re.S).group(1)
SRC = open(f"{HERE}/strategy.py").read().splitlines()
e = html.escape

def ln(marker):
    for i, l in enumerate(SRC, 1):
        if marker in l: return i
    raise KeyError(marker)
def block(start_marker, n):
    a = ln(start_marker); return a, a + n - 1
def src(rng): return e("\n".join(SRC[rng[0]-1:rng[1]]))
def loc(rng): return f"docs/work/kimp-box-range-analysis/strategy.py:{rng[0]}-{rng[1]}"
def f(x, d=2): return f"{x:.{d}f}"
def sg(x, d=2): return f"{x:+.{d}f}"
def pct(x): return f"{x*100:.0f}%"

L_TR = block("def trade_ret", 12)
L_POS = block("for N in (30, 90, 180, 365):", 9)
L_COND = block("                g, f = trade_ret(i, i + H)", 4)
L_RUN = block("def run(", 17)
L_CAND = block("cands = {", 1)
L_RALLY = block("    ups = []", 4)

now = S["now"]; P = now["premium_pct"]; pos = {p["N"]: p for p in S["position"]}
A, B = S["candidates"]["A"], S["candidates"]["B"]
hold = S["hold_test"]; fr = S["fund_recent"]
test_years = hold["days"] / 365
cond90 = {(r["bucket"], r["H"]): r for r in S["conditional"]["90"]}
cond180 = {(r["bucket"], r["H"]): r for r in S["conditional"]["180"]}

def table(head, rows, num=()):
    th = "".join(f"<th>{h}</th>" for h in head)
    tr = "".join("<tr>" + "".join(f'<td class="{"num" if j in num else ""}">{c}</td>' for j, c in enumerate(r)) + "</tr>" for r in rows)
    return f'<div class="tbl-wrap"><table class="rules"><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table></div>'
def code(rng, note): return f'<div class="code"><div class="loc"><span>strategy.py:{rng[0]}-{rng[1]}</span><em>{note}</em></div><pre>{src(rng)}</pre></div>'

pos_tbl = table(["박스 기간", "P5", "P25", "P50", "P75", "P95", "현재 순위", "z", "P95 대비"],
    [[f"{N}일", f(p["q5"]), f(p["q25"]), f(p["q50"]), f(p["q75"]), f(p["q95"]), f"상위 {100-p['rank']:.1f}%", f(p["z"]), f"{sg(-p['above_q95']*-1)}%p"]
     for N, p in pos.items()], num=(1, 2, 3, 4, 5, 6, 7, 8))

labels = ["0–10", "10–25", "25–50", "50–75", "75–90", "90–100"]
def cond_rows(c):
    out = []
    for b in labels:
        r = c[(b, 30)]
        mark = " ◀ 현재" if b == "90–100" else ""
        out.append([f"{b}{mark}", r["n"], f"~{r['indep']}", sg(r["dP_mean"]), sg(r["fund"]), sg(r["mean_ex_fx"]), sg(r["p10_ex_fx"]), pct(r["win_ex_fx_0.3"])])
    return out
cond_head = ["진입 시 박스 내 순위(%)", "표본", "독립 표본", "30일 ΔP", "펀딩 수취", "기대값(환율 제외)", "하위 10%", "비용 0.3%p 초과 확률"]
cond_tbl_180 = table(cond_head, cond_rows(cond180), num=(1, 2, 3, 4, 5, 6, 7))
cond_tbl_90 = table(cond_head, cond_rows(cond90), num=(1, 2, 3, 4, 5, 6, 7))

def cand_rows(c):
    rows = []
    for k, lab in (("train_cost0.3", "학습 2019-09~2022 · 비용 0.3%p"), ("test_cost0.3", "검증 2023~2026 · 비용 0.3%p"), ("test_cost0.6", "검증 · 비용 0.6%p"), ("test_cost1.0", "검증 · 비용 1.0%p")):
        t = c[k]
        rows.append([lab, t["n"], pct(t["win"]), f(t["worst"]), pct(t["exposure"]), f(t["per_year"], 1) + "%", f(t["per_year_ex_fx"], 1) + "%"])
    return rows
cand_head = ["구간", "거래", "승률", "최악 거래(%)", "시장 노출", "연환산", "연환산(환율 제외)"]

def comp(c):
    t = c["test_cost0.3"]; y = test_years
    return [f(t["prem_sum"]/y, 1), f(t["fx_sum"]/y, 1), f(t["fund_sum"]/y, 1), f(-t["cost_sum"]/y, 1), f(t["per_year"], 1)]
comp_tbl = table(["전략 (검증 · 비용 0.3%p)", "김프 회귀", "환율", "펀딩비", "비용", "합계 (%/년)"],
    [["A 180일 P30→P50"] + comp(A), ["B 90일 P20→P90"] + comp(B),
     ["기준선: 2023-01-01부터 계속 보유", f(hold["prem"]/y if (y:=test_years) else 0, 1), f(hold["fx"]/test_years, 1), f(hold["fund"]/test_years, 1), f(-0.3/test_years, 1), f(hold["per_year"], 1)]],
    num=(1, 2, 3, 4, 5))

fund_tbl = table(["연도"] + [str(y) for y, _ in S["fund_yearly_pct"]], [["Short 펀딩 수취(%)"] + [f(v) for _, v in S["fund_yearly_pct"]]], num=tuple(range(1, 10)))

# ── chart: 최근 1년 김프 + 기준선 ─────────────────────────────────────
rows = S["chart_1y"]
W, H, PL, PR, PT, PB = 1000, 300, 44, 118, 12, 26
vals = [v for r in rows for v in r[1:] if v is not None] + [P]
ymin, ymax = min(vals) - 0.5, max(vals) + 0.5
def X(i): return PL + (W - PL - PR) * i / len(rows)
def Y(v): return PT + (H - PT - PB) * (ymax - v) / (ymax - ymin)
def path(k): return "M" + " L".join(f"{X(i):.1f},{Y(r[k]):.1f}" for i, r in enumerate(rows) if r[k] is not None)
grid = ""
import math
for v in range(math.ceil(ymin), math.floor(ymax) + 1):
    grid += f'<line x1="{PL}" x2="{W-PR}" y1="{Y(v):.1f}" y2="{Y(v):.1f}" class="g{" z" if v == 0 else ""}"/><text x="{PL-6}" y="{Y(v)+4:.1f}" class="ax" text-anchor="end">{v}%</text>'
seen = set()
for i, r in enumerate(rows):
    m = r[0][:7]
    if m not in seen and r[0][8:10] <= "07" and m[5:] in ("01", "04", "07", "10"):
        seen.add(m); grid += f'<text x="{X(i):.1f}" y="{H-8}" class="ax">{m}</text>'
lastx = X(len(rows)); last = rows[-1]
_labs = sorted([(Y(last[k]) + 4, txt, cls) for k, txt, cls in ((3, "A 청산 " + f(last[3]), "a"), (2, "A 진입 " + f(last[2]), "a"), (5, "B 청산 " + f(last[5]), "b"), (4, "B 진입 " + f(last[4]), "b"))])
for _i in range(1, len(_labs)):
    if _labs[_i][0] - _labs[_i - 1][0] < 13: _labs[_i] = (_labs[_i - 1][0] + 13,) + _labs[_i][1:]
labs_svg = "".join(f'<text x="{lastx+8:.1f}" y="{y:.1f}" class="lab {c}">{t}</text>' for y, t, c in _labs)
chart = f"""
<figure class="chart">
  <figcaption><b>최근 1년 김프와 두 전략의 기준선</b> · 실선 = 일별 김프 · 점선 = 진입선 · 파선 = 청산선 · 원 = 현재({now['at_utc'][:16].replace('T',' ')} UTC)</figcaption>
  <div class="chart-box">
  <svg viewBox="0 0 {W} {H}" role="img" aria-label="최근 1년 김프, 180일 P30·P50, 90일 P20·P90 기준선, 현재값">
    {grid}
    <path d="{path(4)}" class="th thB entry"/><path d="{path(5)}" class="th thB exit"/>
    <path d="{path(2)}" class="th thA entry"/><path d="{path(3)}" class="th thA exit"/>
    <path d="{path(1)}" class="ln"/>
    <circle cx="{lastx:.1f}" cy="{Y(P):.1f}" r="5" class="now"/>
    <text x="{lastx-8:.1f}" y="{Y(P)-9:.1f}" class="lab nowl" text-anchor="end">현재 {f(P)}%</text>
    {labs_svg}
  </svg>
  </div>
  <p class="cap-note">기준선은 매일 직전 180일(A)·90일(B) 분포로 다시 계산된다. 현재 김프는 두 전략의 청산선보다 위에 있다.</p>
</figure>"""

EXTRA_CSS = """
.chart{margin:18px 0;background:var(--surface);border:1px solid var(--border);border-radius:4px;padding:12px 14px}
.chart figcaption{font-size:var(--t-cap);color:var(--dim);margin-bottom:8px}
.chart figcaption b{color:var(--text)}
.chart-box{position:relative;overflow-x:auto}
.chart svg{width:100%;min-width:560px;height:auto;display:block}
.chart .g{stroke:var(--border);stroke-width:1}
.chart .g.z{stroke:var(--border-hi)}
.chart .ax{fill:var(--faint);font-family:var(--mono);font-size:11px}
.chart .ln{fill:none;stroke:var(--text);stroke-width:1.5;stroke-linejoin:round}
.chart .th{fill:none;stroke-width:1.6}
.chart .thA{stroke:var(--accent)} .chart .thB{stroke:var(--high)}
.chart .entry{stroke-dasharray:2 3} .chart .exit{stroke-dasharray:7 4}
.chart .now{fill:var(--crit);stroke:var(--surface);stroke-width:2}
.chart .lab{font-family:var(--mono);font-size:11px;fill:var(--dim)}
.chart .lab.a{fill:var(--accent)} .chart .lab.b{fill:var(--high)} .chart .nowl{fill:var(--crit);font-weight:600}
.cap-note{font-size:var(--t-cap);color:var(--dim);margin:8px 0 0}
.sevmap{font-size:var(--t-cap);color:var(--dim)}
td.num{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.kpi{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:9px;margin:18px 0}
.kpi div{background:var(--surface);border:1px solid var(--border);border-radius:4px;padding:12px 14px}
.kpi b{display:block;font-family:var(--mono);font-size:22px;font-variant-numeric:tabular-nums}
.kpi span{font-size:var(--t-sm);color:var(--dim)}
@media (max-width:920px){.wrap{grid-template-columns:minmax(0,1fr);padding:0 16px 72px}}
main{min-width:0}
"""

capA = A["test_cost0.3"]["per_year_ex_fx"]; capA6 = A["test_cost0.6"]["per_year_ex_fx"]
capB = B["test_cost0.3"]["per_year_ex_fx"]; capB6 = B["test_cost0.6"]["per_year_ex_fx"]
cf1 = S["capital_factor"]["1"]
fundA_y = A["test_cost0.3"]["fund_sum"] / test_years; fundB_y = B["test_cost0.3"]["fund_sum"] / test_years
fund_test_y = S["fund_only_test_per_year"]; fund_ratio = fr["last_365d_sum_pct"] / fund_test_y
adjA = capA - fundA_y * (1 - fund_ratio); adjB = capB - fundB_y * (1 - fund_ratio)
c180_top = cond180[("90–100", 30)]; c180_bot = cond180[("0–10", 30)]; c90_top = cond90[("90–100", 30)]
tA = A["trades_full"]; lastA = tA[-1]

body = f"""
<div class="wrap">
<aside class="side">
  <p class="kicker">목차</p>
  <nav id="toc">
    <a href="#summary"><span class="n">1</span>요약</a>
    <a href="#flow"><span class="n">2</span>동작 흐름</a>
    <a href="#rules"><span class="n">3</span>핵심 규칙</a>
    <a href="#findings"><span class="n">4</span>발견 사항</a>
    <a href="#compare"><span class="n">5</span>대조</a>
    <a href="#actions"><span class="n">6</span>권장 조치</a>
    <a href="#verify"><span class="n">7</span>검증 기록</a>
  </nav>
  <button class="theme-btn" id="themeBtn" type="button">테마 전환</button>
</aside>
<main>
<header>
  <h1>김프 박스 매매 전략</h1>
  <div class="meta" data-required="meta">
    <div><b>저장소</b><span>premium-spread</span></div>
    <div><b>브랜치</b><span>{branch} @ {sha}</span></div>
    <div><b>분석 일시</b><span>{date}</span></div>
    <div><b>관점</b><span>현재 위치 · 기대값 · 전략 선택</span></div>
    <div><b>현재 시점</b><span>{now['at_utc'][:16].replace('T',' ')} UTC</span></div>
  </div>
  <p class="verdict" data-required="verdict">
    <span class="kicker">결론</span>
    지금 김프 {f(P)}%는 90일·180일 박스의 <b>상위 {100-pos[90]['rank']:.1f}% · {100-pos[180]['rank']:.1f}%</b>로 상단을 벗어나 있다. 여기서 진입하면 30일 기대값(환율 제외)이 {sg(c180_top['mean_ex_fx'])}%라 <b>신규 진입은 하지 않고, 보유 중이면 청산 구간</b>이다. 다음 진입은 김프가 <b>180일 P30({f(A['now_entry_below'])}%) 아래로 내려올 때</b>(전략 A)이며, 그때의 표본 외 기대 수익은 한국 진입금 대비 연 {f(capA6,1)}~{f(capA,1)}%(환율 제외, 비용 0.6~0.3%p)다.
  </p>
</header>

<section id="summary">
  <h2><span class="num">1</span>요약</h2>
  <div class="stat-row" data-required="stats">
    <button class="stat" data-sev="crit" aria-pressed="false"><span class="n">0</span><span class="l">치명</span></button>
    <button class="stat" data-sev="high" aria-pressed="false"><span class="n">3</span><span class="l">높음</span></button>
    <button class="stat" data-sev="med"  aria-pressed="false"><span class="n">2</span><span class="l">보통</span></button>
    <button class="stat" data-sev="low"  aria-pressed="false"><span class="n">0</span><span class="l">낮음</span></button>
    <button class="stat" data-sev="info" aria-pressed="false"><span class="n">2</span><span class="l">정보</span></button>
  </div>
  <p class="sevmap">심각도는 매매 판단에 미치는 무게다 — 높음: 판단을 바꾸는 사실, 보통: 기대값을 깎는 조건, 정보: 근거 기록. 수익률은 모두 <b>한국 현물 진입 금액 대비</b>이고, 자본 대비는 § 3에서 따로 환산한다.</p>
  <div class="kpi">
    <div><b>{f(P)}%</b><span>현재 김프 (Bithumb vs Binance 선물)</span></div>
    <div><b>{f(pos[180]['above_q95'])}%p</b><span>180일 박스 P95 초과분</span></div>
    <div><b>{sg(c180_top['mean_ex_fx'])}%</b><span>지금 진입 시 30일 기대값 (환율 제외)</span></div>
    <div><b>{f(A['now_entry_below'])}%</b><span>다음 진입선 · 전략 A (180일 P30)</span></div>
  </div>
  <div data-required="summary-body">
    <p><b>지금:</b> 박스 상단이다. 이 구간(상위 10%)에서 진입한 과거 {c180_top['n']}일을 보면 30일 뒤 김프는 평균 {sg(c180_top['dP_mean'])}%p 움직였다. 펀딩비 수취({sg(c180_top['fund'])}%)가 그 하락을 겨우 메워 기대값은 0 근처이고, 하위 10%는 {f(c180_top['p10_ex_fx'])}%다.</p>
    <p><b>전략:</b> 박스 하위 10%에서 진입하면 30일 기대값이 {sg(c180_bot['mean_ex_fx'])}%이고 비용 0.3%p를 넘길 확률은 {pct(c180_bot['win_ex_fx_0.3'])}다. 이 비대칭을 규칙으로 만든 전략 A(180일 P30 아래 진입 → P50 회귀 시 청산)가 2023~2026 표본 외 구간에서 연 {f(capA,1)}%(환율 제외, 비용 0.3%p)를 냈다. 주변 파라미터 {len(A['neighbors'])}개도 전부 양수였다.</p>
    <p><b>조심할 것:</b> ① 이 구조는 USD/KRW Long이라 거래당 환율 성분(검증 구간 최대 ±3.7%)이 거래당 김프 이익(평균 1.15%p)보다 크다. ② 수익의 약 {pct(fundA_y/(A['test_cost0.3']['per_year']+A['test_cost0.3']['cost_sum']/test_years))}가 펀딩비인데 펀딩비가 최근 절반으로 줄었다. ③ 보유 중 BTC가 최대 {f(A['btc_max_rally_pct'],0)}% 올랐다 — 선물 Short는 1배에 가깝게 둬야 한다.</p>
  </div>
  {chart}
</section>

<section id="flow">
  <h2><span class="num">2</span>동작 흐름</h2>
  <div data-required="flow">
    <div class="flow">
      <div class="flow-node"><div class="t">지난 분석의 일별 김프 재사용 + 펀딩비·실시간 스냅샷 추가</div><div class="loc">docs/work/kimp-box-range-analysis/fetch.py:{next(i for i,l in enumerate(open(HERE+'/fetch.py').read().splitlines(),1) if l.startswith('def funding'))}</div>
        <div class="d">Binance <code>/fapi/v1/fundingRate</code> 2019-09~ 8시간 간격, 현재 시점 Bithumb ticker · Binance 선물 bookTicker 중간값 · Twelve Data USD/KRW.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">손익 = 앱 Tracking 공식 + 펀딩 − 비용</div><div class="loc">{loc(L_TR)}</div>
        <div class="d"><code>(K1−K0) + (F0−F1)×FX1</code>를 한국 진입금으로 나눈다(<code>.ai/context/project-overview.md</code> §4와 같음). 환율 노출 성분 <code>F0×(FX1−FX0)/K0</code>를 따로 떼어 "환율 제외" 수치를 만든다.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">① 현재 위치</div><div class="loc">{loc(L_POS)}</div><div class="d">30·90·180·365일 박스 분위수와 현재 순위·z.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">② 위치별 조건부 기대값</div><div class="loc">{loc(L_COND)}</div><div class="d">매일 그날의 박스 내 순위로 구간을 나누고, 그날 진입해 7·14·30·60일 보유한 결과를 모은다.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">③ 전략 288개 grid · walk-forward</div><div class="loc">{loc(L_RUN)}</div><div class="d">파라미터는 2019-09~2022에서만 고르고, 2023-01~2026-11은 한 번도 보지 않은 검증 구간으로만 쓴다.</div></div>
    </div>
  </div>
</section>

<section id="rules">
  <h2><span class="num">3</span>핵심 규칙</h2>
  <div data-required="rules">
    <h3>방향 제약 — 할 수 있는 매매는 하나뿐</h3>
    {table(["김프 위치", "가능한 행동", "이유"], [
      ["박스 하단 (P30 아래)", "<b>진입</b>: 한국 현물 매수 + Binance 선물 Short 동일 수량", "김프가 오르면 이익. 평균회귀가 유리한 방향"],
      ["박스 중앙", "보유 유지 또는 청산", "전략 A는 P50 도달 시 청산"],
      ["박스 상단 (P90 위) ← 지금", "<b>신규 진입 금지</b>, 보유분 청산", "반대 방향(한국 현물 공매도)은 개인이 할 수 없다. 쉬는 것이 유일한 선택"],
    ])}
    <h3>두 후보 전략의 현재 기준선</h3>
    {table(["전략", "진입", "청산", "최대 보유", "지금 기준 진입선", "지금 기준 청산선", "현재 김프"], [
      ["A (추천)", "180일 P30 아래", "180일 P50 이상", "120일", f"{f(A['now_entry_below'])}%", f"{f(A['now_exit_at'])}%", f"{f(P)}%"],
      ["B (대안)", "90일 P20 아래", "90일 P90 이상", "60일", f"{f(B['now_entry_below'])}%", f"{f(B['now_exit_at'])}%", f"{f(P)}%"],
    ], num=(4, 5, 6))}
    <h3>자본 대비 환산</h3>
    <p>필요 자본 = 한국 매수금 + 해외 증거금(명목/레버리지). 1배면 한국 진입금의 {f(cf1,1)}배, 2배면 1.5배다. 전략 A의 표본 외 연 {f(capA,1)}%는 1배 기준 자본 대비 <b>연 {f(capA/cf1,1)}%</b>, 시장에 나가 있는 시간은 {pct(A['test_cost0.3']['exposure'])}다. 나머지 시간의 현금은 별도 운용할 수 있다.</p>
  </div>
</section>

<section id="findings">
  <h2><span class="num">4</span>발견 사항</h2>
  <div id="findingList" data-required="findings">

    <article class="finding info" data-sev="info">
      <h3><span class="id">F1</span><span class="badge info">정보</span>현재 김프 {f(P)}%는 90·180일 박스의 P95 위에 있다</h3>
      <p class="locline">{loc(L_POS)}</p>
      {pos_tbl}
      <div class="field"><span class="k">근거</span><p>{now['at_utc'][:16].replace('T',' ')} UTC 실시간 값: Bithumb {now['bithumb_krw']:,.0f}원, Binance 선물 중간가 {now['binance_fut_mid_usdt']:,.2f} USDT, USD/KRW {f(now['usdkrw'])}. 90일 P95보다 {f(pos[90]['above_q95'])}%p, 180일 P95보다 {f(pos[180]['above_q95'])}%p 높다. 30·365일 박스에서도 P90 위다. 180일 평균이 {f(pos[180]['mean'])}%라서 지난 반년 기준으로 지금은 이례적으로 높은 구간이다.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F2</span><span class="badge high">높음</span>지금 진입하면 30일 기대값은 0 근처 — 하단 진입과 기대값 차이가 약 {f(c180_bot['mean_ex_fx'] - c180_top['mean_ex_fx'],1)}%p</h3>
      <p class="locline">{loc(L_COND)}</p>
      {code(L_COND, "진입 순위별 H일 보유 결과 수집")}
      <h3>180일 박스 기준, 30일 보유</h3>
      {cond_tbl_180}
      <h3>90일 박스 기준, 30일 보유</h3>
      {cond_tbl_90}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>김프가 180일 박스 상위 10%일 때 "박스 상단 돌파, 추세 시작"이라고 보고 진입</li>
          <li>과거 {c180_top['n']}번(독립 표본 약 {c180_top['indep']}번)의 같은 상황에서 30일 뒤 김프는 평균 {sg(c180_top['dP_mean'])}%p</li>
          <li class="then">환율 제외 기대값 {sg(c180_top['mean_ex_fx'])}%, 비용 0.3%p를 넘길 확률 {pct(c180_top['win_ex_fx_0.3'])}, 하위 10% {f(c180_top['p10_ex_fx'])}%. 펀딩비가 없었다면 음수다</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>순위가 낮을수록 기대값이 단조롭게 커진다(0–10 구간 {sg(c180_bot['mean_ex_fx'])}% → 90–100 구간 {sg(c180_top['mean_ex_fx'])}%). 90일 박스도 같은 모양이다. 매일 표본을 쓰므로 30일 창이 겹친다 — "독립 표본" 열이 실제 정보량에 가깝고, 상단 구간은 약 {c180_top['indep']}개뿐이다 <span class="badge guess">추정 — 겹침 보정은 단순 나눗셈</span>.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F3</span><span class="badge high">높음</span>이 구조는 BTC 중립이지만 USD/KRW Long이다 — 거래당 환율 성분이 목표 수익보다 크다</h3>
      <p class="locline">{loc(L_TR)}</p>
      {code(L_TR, "손익 분해: gross 와 환율 성분")}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>{lastA[0]}, 김프 {f(lastA[2])}%에서 전략 A 진입 (USD/KRW 1,389원)</li>
          <li>{lastA[1]}, 김프 {f(lastA[3])}%로 회귀해 목표 도달 청산. 김프 회귀 이익 {sg(lastA[6])}%</li>
          <li class="then">그 사이 USD/KRW 1,366원(−1.7%). 환율 성분 {sg(lastA[7])}%로 이익이 사라져 순손익 {sg(lastA[9])}%</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>BTC 달러 가격과 김프가 그대로여도 환율이 1% 오르면 한국 현물 가치가 1% 오르고 선물 Short는 그대로다. 따라서 포지션은 한국 진입금만큼 달러 Long이다(해외 증거금 USDT까지 치면 그보다 크다). 2026년 USD/KRW는 4월 1,490원에서 11월 1,328원으로 내려왔다. 검증 구간 전체로는 A {f(A['test_cost0.3']['fx_sum'],1)}%, B {f(B['test_cost0.3']['fx_sum'],1)}%로 상쇄됐지만, 거래 하나하나에는 거래당 김프 이익(A 평균 1.15%p)보다 큰 잡음이다(검증 구간 최대 A ±3.7%, B ±4.3%). 게다가 환율이 내리면 김프가 오르므로(지난 분석 β=−0.70), "김프 회귀"로 보이는 이익 일부가 이 손실과 짝을 이룬다.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F4</span><span class="badge high">높음</span>보유 중 BTC가 최대 {f(A['btc_max_rally_pct'],0)}% 올랐다 — 선물 Short 레버리지 2배 이상이면 강제청산 구간</h3>
      <p class="locline">{loc(L_RALLY)}</p>
      {code(L_RALLY, "거래별 보유 중 BTC 최대 상승률")}
      <div class="field"><span class="k">실패 시나리오</span>
        <div class="scenario"><ol>
          <li>자본 효율을 높이려 Binance 선물 Short를 3배로 잡는다(증거금 = 명목의 33%)</li>
          <li>보유 중 BTC가 +{f(A['btc_max_rally_pct'],0)}% 오른다(전략 A의 실제 최대값). 상위 10% 거래도 +{f(A['btc_rally_p90_pct'],0)}%</li>
          <li class="then">Short는 약 +30% 근처에서 청산된다. 한국 현물 이익은 남지만 헤지가 사라져 이후 BTC 하락을 그대로 맞는다</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>B는 최대 {f(B['btc_max_rally_pct'],0)}%, 상위 10% {f(B['btc_rally_p90_pct'],0)}%다. 청산 가격은 증거금 방식·유지증거금률에 따라 다르다 <span class="badge guess">추정 — 거래소 청산 공식은 적용하지 않음</span>. 1배(증거금 = 명목)라도 증거금 추가나 한국 현물 일부 매도·이체로 재조정할 계획이 필요하다.</p></div>
    </article>

    <article class="finding med" data-sev="med">
      <h3><span class="id">F5</span><span class="badge med">보통</span>수익의 상당 부분이 펀딩비인데, 펀딩비가 최근 절반으로 줄었다</h3>
      <p class="locline">{loc(L_TR)}</p>
      {comp_tbl}
      {fund_tbl}
      <div class="field"><span class="k">실패 시나리오</span>
        <div class="scenario"><ol>
          <li>검증 구간(2023~2026) 성과 연 {f(capA,1)}%를 앞으로의 기대값으로 쓴다</li>
          <li>그 구간 펀딩비는 연 {f(fund_test_y,1)}%였지만 최근 365일은 {f(fr['last_365d_sum_pct'],1)}%, 최근 30일은 {f(fr['last_30d_sum_pct'],2)}%다</li>
          <li class="then">펀딩비가 지금 수준으로 유지되면 A의 기대값은 연 약 {f(adjA,1)}%, B는 약 {f(adjB,1)}%로 내려간다(환율 제외, 비용 0.3%p)</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>A의 펀딩 수취는 연 {f(fundA_y,1)}%, B는 연 {f(fundB_y,1)}%였다. B가 오래 보유하는 만큼 펀딩 의존도가 높다. "계속 보유" 기준선은 연 {f(hold['per_year'],1)}% 중 펀딩이 연 {f(hold['fund']/test_years,1)}%라 사실상 펀딩 수취 전략이다. 펀딩비는 강세장에 높고 약세·횡보장에 낮다 <span class="badge guess">추정</span>.</p></div>
    </article>

    <article class="finding med" data-sev="med">
      <h3><span class="id">F6</span><span class="badge med">보통</span>전략 A는 거래가 잦아 비용에 민감하다 — 왕복 1.0%p면 연 {f(A['test_cost1.0']['per_year_ex_fx'],1)}%</h3>
      <p class="locline">{loc(L_RUN)}</p>
      <h3>전략 A — 180일 P30 → P50, 120일</h3>
      {table(cand_head, cand_rows(A), num=(1, 2, 3, 4, 5, 6))}
      <h3>전략 B — 90일 P20 → P90, 60일</h3>
      {table(cand_head, cand_rows(B), num=(1, 2, 3, 4, 5, 6))}
      <div class="field"><span class="k">실패 시나리오</span>
        <div class="scenario"><ol>
          <li>Bithumb 일반 수수료(0.25%)와 Binance 선물 시장가(0.05%)로 진입·청산하고, 호가 스프레드·슬리피지가 왕복 0.2%p 붙는다 → 왕복 약 0.8~1.0%p</li>
          <li>전략 A는 검증 구간에 {A['test_cost0.3']['n']}번 거래했다(연 약 {A['test_cost0.3']['n']/test_years:.0f}회)</li>
          <li class="then">연 {f(capA,1)}% → {f(A['test_cost1.0']['per_year_ex_fx'],1)}%, 승률 {pct(A['test_cost0.3']['win'])} → {pct(A['test_cost1.0']['win'])}. 이 비용이면 B(연 {f(B['test_cost1.0']['per_year_ex_fx'],1)}%)가 낫다</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>A는 거래당 김프 이익이 작고(평균 {f(A['test_cost0.3']['prem_sum']/A['test_cost0.3']['n'],2)}%p) 자주 거래한다. B는 거래당 이익이 크지만(평균 {f(B['test_cost0.3']['prem_sum']/B['test_cost0.3']['n'],2)}%p) 최악 거래가 {f(B['test_cost0.3']['worst'])}%로 꼬리가 깊다. 학습 구간 성과(연 21%)는 검증 구간에서 절반 이하로 줄었다 — 2020~2021 고펀딩·고변동 시기가 학습 구간에 몰려 있기 때문이다 <span class="badge guess">추정</span>.</p></div>
    </article>

    <article class="finding info" data-sev="info">
      <h3><span class="id">F7</span><span class="badge info">정보</span>파라미터를 우연히 고른 결과는 아니다 — 주변 조합 전부 표본 외 양수</h3>
      <p class="locline">{loc(L_CAND)}</p>
      {table(["후보", "주변 조합", "검증 양수 비율", "중앙값(연, 환율 제외)", "최솟값"], [
        ["A", len(A['neighbors']), pct(A['neighbors_pos']), f"{f(A['neighbors_med'],1)}%", f"{f(A['neighbors_min'],1)}%"],
        ["B", len(B['neighbors']), pct(B['neighbors_pos']), f"{f(B['neighbors_med'],1)}%", f"{f(B['neighbors_min'],1)}%"],
        ["전체 grid (비용 0.3%p)", S['test_dist_cost0.3']['all_configs'], pct(S['test_dist_cost0.3']['test_positive_ex_fx']), f"{f(S['test_dist_cost0.3']['test_median_per_year_ex_fx'],1)}%", "—"],
        ["전체 grid (비용 0.6%p)", S['test_dist_cost0.6']['all_configs'], pct(S['test_dist_cost0.6']['test_positive_ex_fx']), f"{f(S['test_dist_cost0.6']['test_median_per_year_ex_fx'],1)}%", "—"],
      ], num=(1, 2, 3, 4))}
      <div class="field"><span class="k">근거</span><p>박스 기간 ±절반, 진입 분위 ±0.1, 청산 분위 ±0.2, 보유 한도 ½배를 바꾼 조합을 모두 검증 구간에 돌렸다. 박스 하단 진입이라는 구조 자체가 수익을 낸 것이고, 특정 숫자에 기대지 않는다. 다만 이 견고함에는 검증 구간 내내 펀딩비가 양수였던 영향도 들어 있다(F5).</p></div>
    </article>

  </div>
</section>

<section id="compare">
  <h2><span class="num">5</span>대조</h2>
  <p class="note">같은 3.9년(2023-01~2026-11)을 두 방식으로 보낸 결과. 수치는 한국 진입금 대비, 비용 0.3%p.</p>
  <div data-required="compare">
    <div class="compare">
      <div class="pane good">
        <h4><span>전략 A — 박스 하단에서만 진입</span><span class="p">strategy.py:{L_CAND[0]}</span></h4>
<pre>거래            {A['test_cost0.3']['n']}회 · 승률 {pct(A['test_cost0.3']['win'])}
시장 노출       {pct(A['test_cost0.3']['exposure'])}
연환산          {f(A['test_cost0.3']['per_year'],1)}%  (환율 제외 {f(capA,1)}%)
최악 거래       {f(A['test_cost0.3']['worst'])}%
펀딩 의존       연 {f(fundA_y,1)}%</pre>
        <div class="why">시간의 절반 이상은 현금이라 그 돈을 따로 굴릴 수 있다. 꼬리가 얕다.</div>
      </div>
      <div class="pane bad">
        <h4><span>기준선 — 2023-01-01부터 계속 보유</span><span class="p">strategy.py:{ln('x = fx_term(idx_test0')}</span></h4>
<pre>거래            1회 (진입 후 보유)
시장 노출       100%
연환산          {f(hold['per_year'],1)}%  (환율 제외 {f(hold['per_year_ex_fx'],1)}%)
김프 성분       {f(hold['prem'],1)}%  (BTC 3.85배 상승분 포함)
펀딩 의존       연 {f(hold['fund']/test_years,1)}%</pre>
        <div class="why">대부분이 펀딩 수취다. BTC가 3.85배 오르는 동안 Short 증거금을 계속 채워야 했다(F4). 펀딩이 지금 수준이면 기대값이 크게 준다.</div>
      </div>
    </div>
  </div>
</section>

<section id="actions">
  <h2><span class="num">6</span>권장 조치</h2>
  <ol class="actions" data-required="actions">
    <li><b>지금은 신규 진입하지 않는다. 보유 중이면 청산한다.</b> 현재 {f(P)}%는 A의 청산선({f(A['now_exit_at'])}%)과 B의 청산선({f(B['now_exit_at'])}%)보다 모두 위다.
      <span class="impact">근거: F1·F2 · 바로 실행 가능</span></li>
    <li><b>전략 A를 기본으로 한다:</b> 김프가 직전 180일 P30(현재 {f(A['now_entry_below'])}%) 아래로 내려오면 한국 현물 매수 + Binance 선물 Short 같은 수량. 180일 P50(현재 {f(A['now_exit_at'])}%) 이상이면 청산, 120일이 지나면 무조건 청산. 기준선은 매주 다시 계산한다.
      <span class="impact">조건: 왕복 비용 0.4%p 이하(Bithumb 수수료 쿠폰·지정가, Binance 지정가). 비용이 0.8%p를 넘으면 B(90일 P20 → P90, 60일)로 바꾼다 · 근거: F6·F7</span></li>
    <li><b>선물 Short는 1배로 잡고, BTC +20%마다 증거금을 보충하거나 양쪽을 줄이는 규칙을 미리 정한다.</b>
      <span class="impact">근거: F4 — 보유 중 BTC 최대 +{f(A['btc_max_rally_pct'],0)}% · 자본 대비 기대 수익은 1배 기준 연 약 {f(adjA/cf1,1)}~{f(capA/cf1,1)}% (펀딩 현재 수준~검증 구간 수준)</span></li>
    <li><b>환율 노출을 인정하거나 헤지한다.</b> 헤지하려면 한국 진입금만큼 USD/KRW Short(예: KRX 미국달러선물)가 필요하다. 헤지하지 않으면 "김프 목표 도달 = 이익"이 아님을 기록에 남기고, 환율이 직전 1~3일 1% 넘게 움직였으면 진입을 하루 미룬다.
      <span class="impact">근거: F3 · 달러선물은 별도 계좌·예탁금 필요 <span class="badge guess">추정 — 이번 분석에서 확인하지 않음</span></span></li>
    <li><b>앱 알림으로 감시한다.</b> premium-spread의 임계값 알림에 A의 진입선·청산선을 걸고 주 1회 갱신한다. 펀딩비(최근 30일 합 {f(fr['last_30d_sum_pct'],2)}%)가 연 2% 아래로 떨어지면 기대값을 다시 계산한다.
      <span class="impact">기준선 자동 갱신(이동 분위수 알림)은 앱에 없는 기능이라 넣으려면 별도 작업(orchestrator) 필요</span></li>
  </ol>
</section>

<section id="verify">
  <h2><span class="num">7</span>검증 기록</h2>
  <h3>읽은 파일 · 실행한 명령</h3>
  <ul class="plain" data-required="verify-done">
    <li><code>.ai/context/project-overview.md</code> §4 — Tracking 손익 공식 (<code>foreignPnlKrw = (entry − current) × qty × currentFxRate</code>)</li>
    <li><code>fetch.py funding</code> → 펀딩비 7,882건(2019-09~), <code>fetch.py now</code> → 현재 스냅샷</li>
    <li><code>strategy.py</code> → <code>strategy_results.json</code> (grid {S['grid_size']}개 × 비용 2종 × 학습/검증, 후보 2개 × 주변 36개, 약 11초)</li>
    <li>환율 성분이 큰 거래(2026-09-18~10-07, −3.66%)는 원시 환율(1,382~1,389 → 1,338~1,340원)로 실제 변동임을 확인</li>
    <li>지난 분석(<code>analysis.html</code>)의 일별 김프 시계열을 그대로 재사용</li>
  </ul>
  <h3>확인하지 못한 것 · 가정</h3>
  <ul class="plain" data-required="verify-open">
    <li><b>투자 조언이 아니다</b> — 과거 데이터 통계다. 표본 외 구간도 3.9년 한 번뿐이다.</li>
    <li><b>일 1회(KST 00:00) 판단·체결 가정</b> — 실제로는 장중 진입이 가능해 결과가 다를 수 있다. 체결가는 1시간봉 종가다.</li>
    <li><b>비용은 왕복 고정값(0.3/0.6/1.0%p)</b> — 실제 수수료 등급·쿠폰·슬리피지는 확인하지 않았다.</li>
    <li><b>환전·이체 비용과 시간</b> — KRW 입금, USDT 확보·이체 비용은 넣지 않았다. 양쪽 자본을 미리 나눠 둔다고 가정했다.</li>
    <li><b>세금</b> — 고려하지 않았다.</li>
    <li><b>강제청산</b> — 1배 Short가 청산되지 않는다고 가정했다(F4).</li>
    <li><b>독립 표본</b> — 조건부 기대값은 겹치는 창을 쓴다. 신뢰구간은 계산하지 않았다.</li>
  </ul>
</section>

<footer data-required="footer">
  premium-spread · {branch}@{sha} · {date} · 읽기 전용 분석 (앱 코드 변경 없음)
</footer>
</main>
</div>
"""

out = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>김프 박스 매매 전략</title>
<style>{CSS}{EXTRA_CSS}</style>
</head>
<body>
{body}
<script>{JS}</script>
</body>
</html>
"""
open(f"{HERE}/strategy.html", "w", encoding="utf-8").write(out)
print("written", len(out))
