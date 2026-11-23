"""minute_results.json → minute.html. 수치는 JSON 에서, 인용 라인은 minute.py 에서 직접 찾는다. 차트는 인라인 SVG."""
import json, os, re, sys, html, math
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.expanduser("~/.claude/skills/analysis-report/template.html")
M = json.load(open(f"{HERE}/minute_results.json"))
branch, sha, today = sys.argv[1:4]
tpl = open(TPL, encoding="utf-8").read()
CSS = re.search(r"<style>(.*?)</style>", tpl, re.S).group(1)
JS = re.search(r"<script>(.*?)</script>", tpl, re.S).group(1)
SRC = open(f"{HERE}/minute.py").read().splitlines()
e = html.escape

def ln(marker):
    for i, l in enumerate(SRC, 1):
        if marker in l: return i
    raise KeyError(marker)
def blk(marker, n): a = ln(marker); return a, a + n - 1
def src(r): return e("\n".join(SRC[r[0]-1:r[1]]))
def loc(r): return f"docs/work/kimp-box-range-analysis/minute.py:{r[0]}-{r[1]}"
def code(r, note): return f'<div class="code"><div class="loc"><span>minute.py:{r[0]}-{r[1]}</span><em>{note}</em></div><pre>{src(r)}</pre></div>'
def f(x, d=2): return f"{x:.{d}f}"
def sg(x, d=2): return f"{x:+.{d}f}"
def pct(x): return f"{x*100:.0f}%"
def table(head, rows, num=()):
    th = "".join(f"<th>{h}</th>" for h in head)
    tr = "".join("<tr>" + "".join(f'<td class="{"num" if j in num else ""}">{c}</td>' for j, c in enumerate(r)) + "</tr>" for r in rows)
    return f'<div class="tbl-wrap"><table class="rules"><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table></div>'

L_P = blk('df["pA"] = ', 2)
L_TH = blk("def daily_thresholds", 9)
L_BT = blk("def backtest(sig", 27)
L_PNL = blk("def pnl(i, j):", 5)
L_OUT = blk("bad = (df.pB.abs()", 3)
L_DELAY = blk('R["delay_sens"] = {}', 5)

# ── 공통 SVG 라인 차트 ───────────────────────────────────────────────
CHARTS = []
def dnum(s): y, m, d = int(s[:4]), int(s[5:7]), int(s[8:10]); return date(y, m, d).toordinal() + (int(s[11:13]) / 24 + int(s[14:16]) / 1440 if len(s) > 10 else 0)
def line_chart(cid, title, series, bands=(), y_unit="%", height=300, ymin=None, ymax=None, xfmt="year", note=""):
    """series: [{name, cls, pts:[[date, v],...], dash}], bands: [{cls, lo:[[date,v]], hi:[[date,v]]}]"""
    W, H, PL, PR, PT, PB = 1000, height, 52, 16, 14, 28
    xs = [dnum(p[0]) for s in series for p in s["pts"]]
    vs = [p[1] for s in series for p in s["pts"] if p[1] is not None] + [p[1] for b in bands for k in ("lo", "hi") for p in b[k] if p[1] is not None]
    x0, x1 = min(xs), max(xs)
    lo_, hi_ = (min(vs) if ymin is None else ymin), (max(vs) if ymax is None else ymax)
    pad = (hi_ - lo_) * 0.06 or 1; lo_ -= pad if ymin is None else 0; hi_ += pad if ymax is None else 0
    X = lambda x: PL + (W - PL - PR) * (x - x0) / (x1 - x0)
    Y = lambda v: PT + (H - PT - PB) * (hi_ - v) / (hi_ - lo_)
    step = [s for s in (0.5, 1, 2, 5, 10, 20, 50, 100) if (hi_ - lo_) / s <= 7][0]
    g = ""
    v = math.ceil(lo_ / step) * step
    while v <= hi_:
        g += f'<line x1="{PL}" x2="{W-PR}" y1="{Y(v):.1f}" y2="{Y(v):.1f}" class="g{" z" if abs(v) < 1e-9 else ""}"/><text x="{PL-6}" y="{Y(v)+4:.1f}" class="ax" text-anchor="end">{v:g}{y_unit}</text>'
        v += step
    d0 = date.fromordinal(int(x0)); d1 = date.fromordinal(int(x1))
    if xfmt == "year":
        for yy in range(d0.year + 1, d1.year + 1):
            xx = X(date(yy, 1, 1).toordinal()); g += f'<line x1="{xx:.1f}" x2="{xx:.1f}" y1="{PT}" y2="{H-PB}" class="g"/><text x="{xx+3:.1f}" y="{H-9}" class="ax">{yy}</text>'
    else:
        o = int(x0) + 1
        while o < x1:
            dd = date.fromordinal(o)
            if dd.weekday() == 0:
                xx = X(o); g += f'<line x1="{xx:.1f}" x2="{xx:.1f}" y1="{PT}" y2="{H-PB}" class="g"/><text x="{xx+3:.1f}" y="{H-9}" class="ax">{dd.month}/{dd.day}</text>'
            o += 1
    body = ""
    for b in bands:
        top = [(X(dnum(p[0])), Y(p[1])) for p in b["hi"] if p[1] is not None]
        bot = [(X(dnum(p[0])), Y(p[1])) for p in b["lo"] if p[1] is not None]
        body += f'<path class="{b["cls"]}" d="M' + " L".join(f"{a:.1f},{c:.1f}" for a, c in top) + " L" + " L".join(f"{a:.1f},{c:.1f}" for a, c in reversed(bot)) + ' Z"/>'
    for s in series:
        pts = [(X(dnum(p[0])), Y(p[1])) for p in s["pts"] if p[1] is not None]
        body += f'<path class="ln {s["cls"]}{" dash" if s.get("dash") else ""}{" dot" if s.get("dot") else ""}" d="M' + " L".join(f"{a:.1f},{c:.1f}" for a, c in pts) + '"/>'
    legend = "".join(f'<span class="lg"><i class="sw {s["cls"]}{" dash" if s.get("dash") else ""}{" dot" if s.get("dot") else ""}"></i>{e(s["name"])}</span>' for s in series)
    data = {"x0": x0, "x1": x1, "lo": lo_, "hi": hi_, "W": W, "H": H, "PL": PL, "PR": PR, "PT": PT, "PB": PB, "unit": y_unit,
            "s": [{"n": s["name"], "c": s["cls"], "x": [round(dnum(p[0]), 4) for p in s["pts"]], "d": [p[0] for p in s["pts"]], "v": [p[1] for p in s["pts"]]} for s in series]}
    CHARTS.append((cid, data))
    return f"""<figure class="chart" id="{cid}"><figcaption><b>{title}</b></figcaption><div class="legend">{legend}</div>
<div class="chart-box"><svg viewBox="0 0 {W} {H}" role="img" aria-label="{e(title)}">{g}{body}<line class="xh" x1="-9" x2="-9" y1="{PT}" y2="{H-PB}"/></svg><div class="tip" hidden></div></div>
{f'<p class="cap-note">{note}</p>' if note else ''}</figure>"""

HOVER_JS = """
(function(){
  var C = __CHARTS__;
  C.forEach(function(pair){
    var fig=document.getElementById(pair[0]), D=pair[1]; if(!fig) return;
    var svg=fig.querySelector('svg'), tip=fig.querySelector('.tip'), xh=fig.querySelector('.xh');
    function X(x){return D.PL+(D.W-D.PL-D.PR)*(x-D.x0)/(D.x1-D.x0);}
    function near(arr,x){var lo=0,hi=arr.length-1;while(hi-lo>1){var m=(lo+hi)>>1;if(arr[m]<x)lo=m;else hi=m;}return Math.abs(arr[lo]-x)<Math.abs(arr[hi]-x)?lo:hi;}
    svg.addEventListener('mousemove',function(ev){
      var r=svg.getBoundingClientRect(), px=(ev.clientX-r.left)/r.width*D.W;
      if(px<D.PL||px>D.W-D.PR){tip.hidden=true;return;}
      var x=D.x0+(px-D.PL)/(D.W-D.PL-D.PR)*(D.x1-D.x0), h='', head='';
      D.s.forEach(function(s){ if(!s.x.length) return; var i=near(s.x,x); if(!head) head=s.d[i]; var v=s.v[i];
        h+='<div><i class="sw '+s.c+'"></i>'+s.n+' <b>'+(v===null?'—':v.toFixed(2)+D.unit)+'</b></div>'; });
      tip.innerHTML='<div class="th">'+head+'</div>'+h; tip.hidden=false;
      var left=(px/D.W)*r.width; tip.style.left=Math.max(0,Math.min(left+12,r.width-210))+'px'; tip.style.top='6px';
      xh.setAttribute('x1',px); xh.setAttribute('x2',px);
    });
    svg.addEventListener('mouseleave',function(){tip.hidden=true;xh.setAttribute('x1',-9);xh.setAttribute('x2',-9);});
  });
})();
"""

EXTRA_CSS = """
:root{--sA:#c2560e;--sB:#0b6b74;--s1:#1f5fa8;--s2:#c2560e;--band:#0b6b74}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--sA:#f0954a;--sB:#4ec5cf;--s1:#7fb0ee;--s2:#f0954a;--band:#4ec5cf}}
:root[data-theme="dark"]{--sA:#f0954a;--sB:#4ec5cf;--s1:#7fb0ee;--s2:#f0954a;--band:#4ec5cf}
.chart{margin:18px 0;background:var(--surface);border:1px solid var(--border);border-radius:4px;padding:12px 14px}
.chart figcaption{font-size:var(--t-cap);color:var(--dim)} .chart figcaption b{color:var(--text)}
.legend{display:flex;flex-wrap:wrap;gap:6px 16px;margin:6px 0 8px;font-size:var(--t-sm);color:var(--dim)}
.lg{display:inline-flex;align-items:center;gap:6px}
.sw{display:inline-block;width:18px;height:0;border-top:2px solid currentColor;vertical-align:middle;margin-right:4px}
.sw.dash{border-top-style:dashed} .sw.dot{border-top-style:dotted}
.sw.cA,.ln.cA{color:var(--sA);stroke:var(--sA)} .sw.cB,.ln.cB{color:var(--sB);stroke:var(--sB)}
.sw.c1,.ln.c1{color:var(--s1);stroke:var(--s1)} .sw.c2,.ln.c2{color:var(--s2);stroke:var(--s2)}
.sw.cK,.ln.cK{color:var(--text);stroke:var(--text)} .sw.cF,.ln.cF{color:var(--dim);stroke:var(--dim)}
.chart-box{position:relative;overflow-x:auto}
.chart svg{width:100%;min-width:560px;height:auto;display:block}
.chart .g{stroke:var(--border);stroke-width:1} .chart .g.z{stroke:var(--border-hi)}
.chart .ax{fill:var(--faint);font-family:var(--mono);font-size:11px}
.chart .ln{fill:none;stroke-width:1.6;stroke-linejoin:round}
.chart .ln.dash{stroke-dasharray:6 4} .chart .ln.dot{stroke-dasharray:2 3}
.chart .bandB{fill:var(--band);opacity:.38}
#chRng .ln{stroke-width:1}
.chart .xh{stroke:var(--faint);stroke-width:1;stroke-dasharray:3 3}
.tip{position:absolute;pointer-events:none;background:var(--surface);border:1px solid var(--border-hi);border-radius:4px;
  padding:6px 9px;font-family:var(--mono);font-size:12px;line-height:1.55;color:var(--text);min-width:180px;z-index:2}
.tip .th{color:var(--dim);margin-bottom:2px} .tip .sw{width:12px}
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

# ── 데이터 꺼내기 ──────────────────────────────────────────────────────
cov = M["coverage"]; now = M["now"]; pA, pB = now["pA"], now["pB"]
bA = {b["N"]: b for b in pA["boxes"]}; bB = {b["N"]: b for b in pB["boxes"]}
st = M["strategy"]; ty = M["test_years"]
S1B3, S1B6 = st["S1_pB_cost0.3"]["test"], st["S1_pB_cost0.6"]["test"]
S2B3, S2B6 = st["S2_pB_cost0.3"]["test"], st["S2_pB_cost0.6"]["test"]
S1A3, S2A3 = st["S1_pA_cost0.3"]["test"], st["S2_pA_cost0.3"]["test"]
ref = M["snapshot_ref"]; ds = M["delay_sens"]
it = M["intraday"]; dm, dsn = M["dist_minute_B"], M["dist_snapshot_B"]
yAB = {r[0]: r for r in M["yearly_AB"]}
s1tr = st["S1_pB_cost0.3"]["trades_test"]
near = [t for t in s1tr if int(t[0][14:16]) in (0, 1)]
near_share = len(near) / len(s1tr)

# ── 차트 ─────────────────────────────────────────────────────────────
mon = M["monthly_AB"]
ch_ab = line_chart("chAB", "월평균 김프 — A(1,300원 고정) vs B(직전 1시간 환율)",
    [{"name": "A 1,300원 고정", "cls": "cA", "pts": [[m[0] + "-15", m[1]] for m in mon]},
     {"name": "B 시간 환율", "cls": "cB", "pts": [[m[0] + "-15", m[2]] for m in mon]}],
    note=f"두 선의 차이는 그 달 환율이 1,300원에서 얼마나 떨어져 있었는지와 거의 같다. 일평균 기준 A와 환율의 상관 {f(M['corr_A_fx_daily'])}, A와 B의 상관 {f(M['corr_AB_daily_mean'])}.")
ch_fx = line_chart("chFX", "USD/KRW 월평균 (A가 고정한 1,300원과 비교)",
    [{"name": "USD/KRW", "cls": "cK", "pts": [[m[0] + "-15", m[3]] for m in mon]},
     {"name": "A 가정 1,300원", "cls": "cA", "dash": True, "pts": [[mon[0][0] + "-15", 1300], [mon[-1][0] + "-15", 1300]]}], y_unit="원", height=220)
cd = M["chart_daily"]
ch_rng = line_chart("chRng", "일별 김프(B) — 하루 평균과 장중 최저~최고",
    [{"name": "하루 평균", "cls": "cB", "pts": [[r[0], r[2]] for r in cd]}],
    bands=[{"cls": "bandB", "lo": [[r[0], r[3]] for r in cd], "hi": [[r[0], r[4]] for r in cd]}],
    note=f"음영이 장중 범위다. 하루 범위 중앙값 {f(it['range_med'])}%p, 상위 10% {f(it['range_p90'])}%p, 상위 1% {f(it['range_p99'])}%p, 최대 {f(it['range_max'])}%p({it['range_max_day']}).")
c30 = M["chart_30d"]
ch_30 = line_chart("ch30", "최근 30일 김프(10분 평균)와 전략 기준선",
    [{"name": "B 김프", "cls": "cB", "pts": [[r[0], r[2]] for r in c30]},
     {"name": "S1 진입(180일 P30)", "cls": "c1", "dot": True, "pts": [[r[0], r[3]] for r in c30]},
     {"name": "S1 청산(180일 P50)", "cls": "c1", "dash": True, "pts": [[r[0], r[4]] for r in c30]},
     {"name": "S2 진입(90일 P20)", "cls": "c2", "dot": True, "pts": [[r[0], r[5]] for r in c30]},
     {"name": "S2 청산(90일 P90)", "cls": "c2", "dash": True, "pts": [[r[0], r[6]] for r in c30]}], xfmt="week",
    note=f"A(1,300원 고정) 김프는 같은 기간 {f(min(r[1] for r in c30))}~{f(max(r[1] for r in c30))}%로 B보다 약 3%p 위에 있어 이 축에 함께 그리지 않았다.")
cv = M["curves"]
ch_cv = line_chart("chCV", "검증 구간 누적 손익 — 실제 원화 계좌(Short 1배·환율 미헤지), 비용 0.3%p",
    [{"name": "S2 분 단위", "cls": "c2", "pts": [[r[0], r[2]] for r in cv["S2_minute"]]},
     {"name": "S2 일 스냅샷", "cls": "c2", "dash": True, "pts": [[r[0], r[2]] for r in cv["S2_snapshot"]]},
     {"name": "S1 분 단위", "cls": "c1", "pts": [[r[0], r[2]] for r in cv["S1_minute"]]},
     {"name": "S1 일 스냅샷", "cls": "c1", "dash": True, "pts": [[r[0], r[2]] for r in cv["S1_snapshot"]]}],
    note="청산일마다 그 거래의 손익(한국 진입금 대비 %)을 더한 누적값이다. 복리가 아닌 단순 합.")

def strat_rows(name, d3, d6, r):
    return [[f"{name} · 분 단위 · 비용 0.3%p", d3["n"], f"{f(d3['avg_days'],1)}일", f"{f(d3['per_year_ex'],1)}%", f"<b>{f(d3['per_year_full'],1)}%</b>", pct(d3["win_full"]), f(d3["worst_full"])],
            [f"{name} · 분 단위 · 비용 0.6%p", d6["n"], f"{f(d6['avg_days'],1)}일", f"{f(d6['per_year_ex'],1)}%", f"<b>{f(d6['per_year_full'],1)}%</b>", pct(d6["win_full"]), f(d6["worst_full"])],
            [f"{name} · 일 스냅샷 · 비용 0.3%p", r["n"] if "n" in r else "—", "—", f"{f(r['per_year_ex_fx'],1)}%", f"{f(r['per_year_full'],1)}%" if "per_year_full" in r else "—", pct(r.get('win_full', r.get('win', 0))), f(r.get('worst_full', r.get('worst', 0)))]]
S1ref = dict(ref["S1_full_L1"], n=ref["S1"]["n"]); S2ref = dict(ref["S2_full_L1"], n=ref["S2"]["n"])
strat_tbl = table(["전략 · 해상도 · 비용", "거래", "평균 보유", "연(환율 제외)", "연(실제 계좌 1배)", "승률(실계좌)", "최악 거래(실계좌 %)"],
                  strat_rows("S2", S2B3, S2B6, S2ref) + strat_rows("S1", S1B3, S1B6, S1ref), num=(1, 2, 3, 4, 5, 6))
delay_tbl = table(["체결 지연", "S2 비용 0.3%p", "S2 비용 0.6%p", "S1 비용 0.3%p", "S1 비용 0.6%p"],
    [[f"{d}분" if int(d) < 60 else f"{int(d)//60}시간", f"{f(ds['S2_cost0.3'][d]['per_year_full'],1)}%", f"{f(ds['S2_cost0.6'][d]['per_year_full'],1)}%",
      f"{f(ds['S1_cost0.3'][d]['per_year_full'],1)}%", f"{f(ds['S1_cost0.6'][d]['per_year_full'],1)}%"] for d in ("1", "5", "30", "60", "240")], num=(1, 2, 3, 4))
ab_tbl = table(["연도", "평균 환율", "A 중앙값", "A P5~P95", "B 중앙값", "B P5~P95"],
    [[y, f(r[3], 0), f(r[1]["q50"]), f"{f(r[1]['q5'])} ~ {f(r[1]['q95'])}", f(r[2]["q50"]), f"{f(r[2]['q5'])} ~ {f(r[2]['q95'])}"] for y, r in sorted(yAB.items())], num=(0, 1, 2, 3, 4, 5))
asig_tbl = table(["신호", "전략", "거래", "연(환율 제외)", "연(실제 계좌 1배)", "최악 거래(실계좌 %)"],
    [["B 시간 환율", "S2", S2B3["n"], f"{f(S2B3['per_year_ex'],1)}%", f"{f(S2B3['per_year_full'],1)}%", f(S2B3["worst_full"])],
     ["A 1,300원 고정", "S2", S2A3["n"], f"{f(S2A3['per_year_ex'],1)}%", f"{f(S2A3['per_year_full'],1)}%", f(S2A3["worst_full"])],
     ["B 시간 환율", "S1", S1B3["n"], f"{f(S1B3['per_year_ex'],1)}%", f"{f(S1B3['per_year_full'],1)}%", f(S1B3["worst_full"])],
     ["A 1,300원 고정", "S1", S1A3["n"], f"{f(S1A3['per_year_ex'],1)}%", f"{f(S1A3['per_year_full'],1)}%", f(S1A3["worst_full"])]], num=(2, 3, 4, 5))
pos_tbl = table(["박스", "A 값 / 백분위", "A P5 ~ P95", "B 값 / 백분위", "B P5 ~ P95"],
    [[f"{N}일", f"{f(pA['value'])}% / {bA[N]['rank']:.0f}번째", f"{f(bA[N]['q5'])} ~ {f(bA[N]['q95'])}", f"{f(pB['value'])}% / {bB[N]['rank']:.0f}번째", f"{f(bB[N]['q5'])} ~ {f(bB[N]['q95'])}"] for N in (30, 90, 180, 365)], num=(1, 2, 3, 4))
now_s1, now_s2 = st["S1_now"], st["S2_now"]

body = f"""
<div class="wrap">
<aside class="side">
  <p class="kicker">목차</p>
  <nav id="toc">
    <a href="#summary"><span class="n">1</span>요약</a><a href="#flow"><span class="n">2</span>동작 흐름</a>
    <a href="#rules"><span class="n">3</span>핵심 규칙</a><a href="#findings"><span class="n">4</span>발견 사항</a>
    <a href="#compare"><span class="n">5</span>대조</a><a href="#actions"><span class="n">6</span>권장 조치</a><a href="#verify"><span class="n">7</span>검증 기록</a>
  </nav>
  <button class="theme-btn" id="themeBtn" type="button">테마 전환</button>
</aside>
<main>
<header>
  <h1>분 단위 김프 재검증</h1>
  <div class="meta" data-required="meta">
    <div><b>저장소</b><span>premium-spread</span></div>
    <div><b>브랜치</b><span>{branch} @ {sha}</span></div>
    <div><b>분석 일시</b><span>{today}</span></div>
    <div><b>관점</b><span>환율 고정 A vs B · 분 단위 전략</span></div>
    <div><b>데이터</b><span>{cov['joined'][0][:10]} ~ {cov['joined'][1][:16]} UTC, 1분봉 {cov['joined'][2]:,}개</span></div>
  </div>
  <p class="verdict" data-required="verdict">
    <span class="kicker">결론</span>
    <b>A(1,300원 고정) 김프는 김프가 아니라 환율을 본다</b> — 환율이 1,080~1,560원을 오간 기간이라 A와 환율의 상관이 {f(M['corr_A_fx_daily'])}이고, A 신호로 매매하면 최악 거래가 {f(S2A3['worst_full'])}%까지 나온다. <b>B(시간 환율)로 분 단위 감시를 하면 S2(90일 P20→P90)는 일 스냅샷보다 낫고</b>(실제 계좌 연 {f(S2B3['per_year_full'],1)}% vs {f(S2ref['per_year_full'],1)}%, 4시간 늦게 체결해도 {f(ds['S2_cost0.3']['240']['per_year_full'],1)}%), <b>S1은 분 단위에서 무너진다</b>(연 {f(S1B3['per_year_full'],1)}%, 비용 0.6%p면 {f(S1B6['per_year_full'],1)}%).
  </p>
</header>

<section id="summary">
  <h2><span class="num">1</span>요약</h2>
  <div class="stat-row" data-required="stats">
    <button class="stat" data-sev="crit" aria-pressed="false"><span class="n">0</span><span class="l">치명</span></button>
    <button class="stat" data-sev="high" aria-pressed="false"><span class="n">3</span><span class="l">높음</span></button>
    <button class="stat" data-sev="med"  aria-pressed="false"><span class="n">1</span><span class="l">보통</span></button>
    <button class="stat" data-sev="low"  aria-pressed="false"><span class="n">0</span><span class="l">낮음</span></button>
    <button class="stat" data-sev="info" aria-pressed="false"><span class="n">2</span><span class="l">정보</span></button>
  </div>
  <p class="sevmap">심각도는 매매 판단에 미치는 무게다 — 높음: 판단을 바꾸는 사실, 보통: 결과를 깎는 조건, 정보: 근거 기록. 전략 이름은 지난 리포트의 전략 A → <b>S1</b>(180일 P30→P50, 120일), 전략 B → <b>S2</b>(90일 P20→P90, 60일)로 바꿨다. A·B는 이 리포트에서 환율 처리 방식만 뜻한다.</p>
  <div class="kpi">
    <div><b>{f(pB['value'])}%</b><span>현재 김프 B ({now['at'][:16]} UTC)</span></div>
    <div><b>{f(pA['value'])}%</b><span>같은 시각 A (1,300원 고정)</span></div>
    <div><b>{f(S2B3['per_year_full'],1)}%</b><span>S2 분 단위 · 실제 계좌 연환산 (검증 2023~)</span></div>
    <div><b>{f(it['range_med'])}%p</b><span>하루 장중 김프 범위 중앙값</span></div>
  </div>
  <div data-required="summary-body">
    <p><b>A vs B:</b> A는 연도별 중앙값이 2020년 {f(yAB[2020][1]['q50'])}% → 2026년 {f(yAB[2026][1]['q50'])}%로 22%p를 움직였다. 같은 기간 B는 −0.5~3.0%다. A의 움직임은 대부분 환율이다.</p>
    <p><b>분 vs 일:</b> 분포의 몸통(P5~P95)은 일 스냅샷과 거의 같지만(B {f(dm['q5'])}~{f(dm['q95'])}% vs {f(dsn['q5'])}~{f(dsn['q95'])}%), 꼬리는 훨씬 길다(최저 {f(dm['min'])}% vs {f(dsn['min'])}%, 최고 {f(dm['max'])}% vs {f(dsn['max'])}%). 하루에도 김프가 중앙값 {f(it['range_med'])}%p 움직인다.</p>
    <p><b>전략:</b> 거래가 드문 S2는 장중 고점·저점을 잡아 개선되고, 체결이 늦어져도 버틴다. 거래가 잦은 S1은 분 단위에서 {S1B3['n']}번 거래하며 비용과 환율 되돌림에 먹힌다. 지금 B는 {f(pB['value'])}%로 S2 청산선({f(now_s2['pB']['exit_at'])}%) 바로 아래, S1 진입선({f(now_s1['pB']['entry_below'])}%)과는 멀다.</p>
  </div>
  {ch_ab}
  {ch_fx}
</section>

<section id="flow">
  <h2><span class="num">2</span>동작 흐름</h2>
  <div data-required="flow">
    <div class="flow">
      <div class="flow-node"><div class="t">1분봉 수집 (이어받기 가능)</div><div class="loc">docs/work/kimp-box-range-analysis/fetch_minute.py:39-88</div>
        <div class="d">Bithumb <code>/v1/candles/minutes/1</code> {cov['bithumb'][2]:,}봉, Binance 선물 <code>/fapi/v1/klines 1m</code> {cov['binance_fut'][2]:,}봉. 환율은 기존 1시간봉에 2026-11-23까지 보충(Twelve Data 1회).</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">분 단위 김프 A·B</div><div class="loc">{loc(L_P)}</div>
        <div class="d">A = 한국가 / (선물가 × 1,300) − 1. B = 한국가 / (선물가 × 그 시각까지 가장 최근 1시간 환율) − 1. 두 거래소 분이 모두 있는 {cov['joined'][2]:,}분(기대 {cov['expected_minutes']:,}분의 {cov['joined'][2]/cov['expected_minutes']*100:.1f}%).</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">일별 기준선 = 직전 N일의 모든 분 값 분위수</div><div class="loc">{loc(L_TH)}</div>
        <div class="d">매일 KST 00:00에 다시 계산. 하루 안에서는 고정.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">분 단위 백테스트</div><div class="loc">{loc(L_BT)}</div>
        <div class="d">신호가 난 분의 다음 분 종가로 체결(지연 민감도는 5분~4시간). 손익은 지난 리포트와 같은 앱 공식 + 펀딩 − 비용, 실제 계좌는 USDT 증거금 재평가 포함.</div></div>
    </div>
  </div>
</section>

<section id="rules">
  <h2><span class="num">3</span>핵심 규칙</h2>
  <div data-required="rules">
    {table(["항목", "일 스냅샷 (지난 리포트)", "분 단위 (이번)"], [
      ["김프 표본", "매일 KST 00:00 1개", "매분 (하루 약 1,440개)"],
      ["환율", "그 시각의 1시간 환율", "A: 1,300원 고정 / B: 직전 1시간 환율"],
      ["기준선", "직전 N일의 일 스냅샷 N개 분위수", "직전 N일의 모든 분 값 분위수"],
      ["진입·청산 판단", "하루 한 번", "매분"],
      ["체결", "판단 시각의 1시간봉 종가", "다음 분 종가 (지연 민감도 별도)"],
      ["기간", "2017-08 ~ (선물 2019-09 ~)", "2020-01-27 ~ (1시간 환율 시작)"],
    ])}
    <h3>연도별 분포 — A vs B</h3>
    {ab_tbl}
  </div>
</section>

<section id="findings">
  <h2><span class="num">4</span>발견 사항</h2>
  <div id="findingList" data-required="findings">

    <article class="finding high" data-sev="high">
      <h3><span class="id">F1</span><span class="badge high">높음</span>A(1,300원 고정) 김프는 환율 지표다 — 박스로 쓰면 신호가 거꾸로 나온다</h3>
      <p class="locline">{loc(L_P)}</p>
      {code(L_P, "A 와 B 의 정의")}
      {asig_tbl}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>지금({now['at'][:16]} UTC) A 김프는 {f(pA['value'])}%로 180일 박스의 {bA[180]['rank']:.0f}번째 백분위 — 중간 아래라 "박스 하단 쪽"으로 읽혀 S1 진입선({f(now_s1['pA']['entry_below'])}%)에 거의 닿았다</li>
          <li>같은 시각 B 김프는 {f(pB['value'])}%로 180일 박스의 {bB[180]['rank']:.0f}번째 백분위 — 실제로는 박스 상단이다</li>
          <li class="then">A가 낮아 보이는 이유는 환율이 6월 1,554원에서 {f(now['fx'],0)}원으로 내려와서다. A 신호로 S2를 돌리면 최악 거래가 실제 계좌 {f(S2A3['worst_full'])}%(B 신호는 {f(S2B3['worst_full'])}%)</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>2020-04~2026-11 USD/KRW는 1,080~1,560원으로 44% 움직였다. 고정 환율 김프는 "한국 가격 / 해외 가격"에 상수를 곱한 값이라 환율 변화를 그대로 담는다. 일평균 A와 환율의 상관 {f(M['corr_A_fx_daily'])}, A와 B의 상관 {f(M['corr_AB_daily_mean'])}. A 신호 전략의 학습 구간 성과(S1 연 {f(st['S1_pA_cost0.3']['train']['per_year_full'],1)}%)는 원화가 강했던 2020~2021년에 A가 낮게 머문 덕분이다 <span class="badge guess">추정</span>.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F2</span><span class="badge high">높음</span>B 신호의 S2는 분 단위 감시로 좋아진다 — 체결이 4시간 늦어도 일 스냅샷보다 낫다</h3>
      <p class="locline">{loc(L_DELAY)}</p>
      {strat_tbl}
      {delay_tbl}
      {ch_cv}
      <div class="field"><span class="k">실패 시나리오</span>
        <div class="scenario"><ol>
          <li>분 단위 결과(연 {f(S2B3['per_year_full'],1)}%)를 그대로 기대값으로 쓰고, 알림을 받은 뒤 한두 시간 뒤에 수동으로 주문한다</li>
          <li>장중에 잠깐 찍힌 P90(청산)·P20(진입)은 몇 분~몇십 분 만에 사라지는 경우가 많다</li>
          <li class="then">60분 지연이면 연 {f(ds['S2_cost0.3']['60']['per_year_full'],1)}%, 비용 0.6%p까지 겹치면 {f(ds['S2_cost0.6']['60']['per_year_full'],1)}% — 여전히 일 스냅샷({f(S2ref['per_year_full'],1)}%)과 비슷하거나 낫지만 분 단위 수치의 2/3 수준이다</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>S2는 검증 구간에 {S2B3['n']}번(일 스냅샷 {S2ref['n']}번) 거래하고 평균 {f(S2B3['avg_days'],0)}일 보유한다. 환율 성분이 연 {f(S2B3['fx_y'],2)}%로 거의 0이라 "환율 제외"와 "실제 계좌"가 같다. 지연을 늘릴수록 수익이 단조롭게 줄어든다 — 이익 일부는 짧은 장중 극단값에서 나온다. 1분봉 종가는 실제 체결가가 아니므로 비용 0.6%p 줄을 기본값으로 읽는 편이 안전하다 <span class="badge guess">추정 — 호가 스프레드 미수집</span>.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F3</span><span class="badge high">높음</span>S1은 분 단위에서 맞지 않는다 — 환율 계단에 반응해 진입하고, 이익이 환율 되돌림과 상쇄된다</h3>
      <p class="locline">{loc(L_PNL)}</p>
      {code(L_PNL, "손익 분해: 김프 · 환율 · 펀딩")}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>B는 환율이 매시 정각에 바뀐다. 환율이 오르는 정각에 김프가 계단처럼 떨어져 S1 진입선(180일 P30) 아래로 내려간다</li>
          <li>검증 구간 S1 진입 {len(s1tr)}번 중 {len(near)}번({near_share*100:.0f}%)이 정각 직후 2분 안이었다(고르게 분포하면 3%)</li>
          <li class="then">환율이 되돌아가면 김프가 P50으로 "회복"해 김프 이익이 찍히지만 같은 크기의 환율 손실이 붙는다. 김프 이익 연 {sg(S1B3['prem_y'],1)}% · 펀딩 {sg(S1B3['fund_y'],1)}% · 비용 −{f(S1B3['n']*0.3/ty,1)}% · 환율 {sg(S1B3['fx_y'],1)}% × 2(한국 측 + USDT 증거금) → 실제 계좌 연 {f(S1B3['per_year_full'],1)}%</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>S1은 P30→P50으로 목표 폭이 좁아(현재 {f(now_s1['pB']['entry_below'])}% → {f(now_s1['pB']['exit_at'])}%) 장중 잡음만으로도 진입·청산이 반복된다. 평균 보유 {f(S1B3['avg_days'],1)}일, 거래 {S1B3['n']}회. "환율 제외" 연 {f(S1B3['per_year_ex'],1)}%는 환율 되돌림을 김프 이익으로 센 착시이고, 비용 0.6%p면 실제 계좌 {f(S1B6['per_year_full'],1)}%다. 일 스냅샷 S1(실제 계좌 연 {f(S1ref['per_year_full'],1)}%)은 하루 한 번만 보기 때문에 이 잡음을 덜 탔다.</p></div>
    </article>

    <article class="finding med" data-sev="med">
      <h3><span class="id">F4</span><span class="badge med">보통</span>분 단위 꼬리는 일 스냅샷보다 훨씬 길다 — 손절·증거금은 분 단위 극단값 기준으로 잡아야 한다</h3>
      <p class="locline">{loc(L_OUT)}</p>
      {code(L_OUT, "이상치 기준: 실측 극단값을 지우지 않도록 40%")}
      {ch_rng}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>2024-12-03 22:57~23:03 KST(비상계엄 선포 직후), Bithumb BTC가 1억 1,838만 원에서 1억 1,245만 원으로 떨어지는 동안 Binance 선물은 거의 그대로</li>
          <li>B 김프가 6분 만에 −10.2% → <b>−15.6%</b>. 일 스냅샷의 최저값은 −6.4%였다</li>
          <li class="then">일 스냅샷 분포로 손절선(예: −5%)을 잡았다면 장중에 체결되며 큰 손실이 확정된다. 2021-05-19 폭락 때는 반대로 +28.7%까지 튀었다</li>
        </ol></div></div>
      <div class="field"><span class="k">근거</span><p>B 분포 P1~P99는 분 {f(dm['q1'])}~{f(dm['q99'])}% vs 일 스냅샷 {f(dsn['q1'])}~{f(dsn['q99'])}%로 비슷하지만, 최저·최고는 {f(dm['min'])}/{f(dm['max'])}% vs {f(dsn['min'])}/{f(dsn['max'])}%다. 처음에 |김프|&gt;25%를 이상치로 잘랐다가 9분이 모두 2021-04·05 폭락의 연속된 실측값인 것을 확인하고 기준을 40%로 올렸다(제외 {M['outlier_minutes_abs_pB_gt40']}분).</p></div>
    </article>

    <article class="finding info" data-sev="info">
      <h3><span class="id">F5</span><span class="badge info">정보</span>현재 위치 — B는 박스 상단, A는 박스 중간 아래로 서로 다르게 읽힌다</h3>
      <p class="locline">{loc(blk('# ── 4. 현재 위치', 12))}</p>
      {pos_tbl}
      {ch_30}
      <div class="field"><span class="k">근거</span><p>{now['at'][:16]} UTC: Bithumb {now['kr']:,.0f}원, Binance 선물 {now['fu']:,.1f} USDT, 환율 {f(now['fx'])}원({f(now['fx_age_h'],1)}시간 전 값). 분 단위 기준으로 B는 180일 박스 상위 {100-bB[180]['rank']:.0f}%(지난 리포트의 일 스냅샷 기준 1.99%·상위 1.7%보다 조금 내려옴). S1 진입선 {f(now_s1['pB']['entry_below'])}% · 청산선 {f(now_s1['pB']['exit_at'])}%, S2 진입선 {f(now_s2['pB']['entry_below'])}% · 청산선 {f(now_s2['pB']['exit_at'])}%.</p></div>
    </article>

    <article class="finding info" data-sev="info">
      <h3><span class="id">F6</span><span class="badge info">정보</span>데이터 보존 위치와 커버리지</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/README.md:14-27</p>
      {table(["데이터", "기간(UTC)", "행 수"], [
        ["Bithumb 1분봉", f"{cov['bithumb'][0][:16]} ~ {cov['bithumb'][1][:16]}", f"{cov['bithumb'][2]:,}"],
        ["Binance 선물 1분봉", f"{cov['binance_fut'][0][:16]} ~ {cov['binance_fut'][1][:16]}", f"{cov['binance_fut'][2]:,}"],
        ["두 거래소 공통 분", f"{cov['joined'][0][:16]} ~ {cov['joined'][1][:16]}", f"{cov['joined'][2]:,}"],
      ], num=(2,))}
      <div class="field"><span class="k">근거</span><p>원시 데이터(약 117MB)는 <code>~/.local/share/premium-spread/kimp-box-range-analysis/data/</code>에 보존했다. 저장소에는 시간봉·일별 집계 압축 사본과 재수집 방법(README)을 커밋했다. 분봉 원본은 git 이력이 무거워져 커밋하지 않았다.</p></div>
    </article>

  </div>
</section>

<section id="compare">
  <h2><span class="num">5</span>대조</h2>
  <p class="note">같은 S2 규칙을 하루 한 번 볼 때와 매분 볼 때. 검증 구간 2023-01 ~ 2026-11, 비용 0.3%p, 실제 계좌(Short 1배·환율 미헤지).</p>
  <div data-required="compare">
    <div class="compare">
      <div class="pane bad">
        <h4><span>일 스냅샷 S2</span><span class="p">strategy.py</span></h4>
<pre>거래        {S2ref['n']}회
연환산      {f(S2ref['per_year_full'],1)}%
승률        {pct(S2ref['win_full'])}
최악 거래   {f(S2ref['worst_full'])}%</pre>
        <div class="why">KST 00:00에만 판단한다. 장중에 P20 아래로 내려갔다 올라온 기회를 놓친다.</div>
      </div>
      <div class="pane good">
        <h4><span>분 단위 S2 (다음 분 체결)</span><span class="p">minute.py:{L_BT[0]}</span></h4>
<pre>거래        {S2B3['n']}회
연환산      {f(S2B3['per_year_full'],1)}%   (60분 지연 {f(ds['S2_cost0.3']['60']['per_year_full'],1)}%)
승률        {pct(S2B3['win_full'])}
최악 거래   {f(S2B3['worst_full'])}%</pre>
        <div class="why">장중 극단값을 잡는다. 대신 최악 거래가 더 깊다 — 장중 급락 때 진입한 경우다.</div>
      </div>
    </div>
  </div>
</section>

<section id="actions">
  <h2><span class="num">6</span>권장 조치</h2>
  <ol class="actions" data-required="actions">
    <li><b>박스 판단에 A(고정 환율) 김프를 쓰지 않는다.</b> 기준은 B(그 시각 환율) 김프다. 앱의 김프 계산도 이미 B 방식이다.
      <span class="impact">근거: F1 · 바로 적용</span></li>
    <li><b>기본 전략을 S1에서 S2로 바꾼다:</b> 직전 90일 P20(현재 {f(now_s2['pB']['entry_below'])}%) 아래에서 진입, 직전 90일 P90(현재 {f(now_s2['pB']['exit_at'])}%) 이상이면 청산, 최대 60일. 장중 알림으로 감시하고 1시간 안에 체결하는 것을 목표로 한다.
      <span class="impact">기대: 실제 계좌 연 약 {f(ds['S2_cost0.6']['60']['per_year_full'],0)}~{f(S2B3['per_year_full'],0)}%(한국 진입금 대비, 자본 대비는 약 절반) · 근거: F2</span></li>
    <li><b>S1은 일 단위로만 쓰거나 버린다.</b> 분 단위로 돌리면 비용과 환율 되돌림에 먹힌다.
      <span class="impact">근거: F3</span></li>
    <li><b>환율이 정각에 1% 가까이 바뀐 직후 2~3분 신호는 무시한다.</b> 계단 효과로 생긴 가짜 신호일 가능성이 높다.
      <span class="impact">근거: F3 · 알림 로직에 넣으려면 별도 설계 필요</span></li>
    <li><b>손절·증거금 여유는 분 단위 극단값으로 잡는다.</b> 하루 안에 김프가 {f(it['range_p99'])}%p(상위 1%)까지 움직인다.
      <span class="impact">근거: F4</span></li>
    <li><b>정밀하게 다시 볼 때 1분 환율을 받는다.</b> Twelve Data 1분 환율은 2020-04-06부터 있고 약 500회 요청이 든다 — 이틀에 나눠 받는다.
      <span class="impact">이번 B는 한 시간 안 환율 변화(중앙값 0.05%, 상위 1% 0.43%)를 무시했다</span></li>
  </ol>
</section>

<section id="verify">
  <h2><span class="num">7</span>검증 기록</h2>
  <h3>읽은 파일 · 실행한 명령</h3>
  <ul class="plain" data-required="verify-done">
    <li><code>fetch_minute.py fut</code> → {cov['binance_fut'][2]:,}봉, <code>fetch_minute.py bithumb</code> → {cov['bithumb'][2]:,}봉(재시도 0회), <code>fxtop</code> → 1시간 환율 85봉 보충(Twelve Data 1회)</li>
    <li><code>minute.py</code> → <code>minute_results.json</code> (약 45초). 부분 데이터로 먼저 실행해 오류가 없음을 확인</li>
    <li>|김프|&gt;25% 9분을 원시 분봉 앞뒤 4분과 대조 — 2021-04-18, 2021-05-10, 2021-05-19 폭락의 연속된 실측값이라 기준을 40%로 수정</li>
    <li>2024-12-03 −15.6%, 2020-03-13 범위 21.6%p를 원시 분봉으로 확인</li>
    <li>S1 진입 시각 분포(정각 직후 비율)로 환율 계단 가설 확인</li>
  </ul>
  <h3>확인하지 못한 것 · 가정</h3>
  <ul class="plain" data-required="verify-open">
    <li><b>1분봉 종가 = 체결 가능 가격 가정</b> — 호가 스프레드·슬리피지는 비용(0.3/0.6%p)에 뭉뚱그렸다. 급변 구간에서는 더 클 수 있다.</li>
    <li><b>B의 환율은 한 시간 안에서 고정</b> — 정각 계단이 S1 결과를 왜곡했다(F3). 1분 환율로 다시 보면 S1이 덜 나빠질 수 있다.</li>
    <li><b>두 거래소 분 정렬</b> — 둘 중 한쪽에 체결이 없던 분({cov['expected_minutes']-cov['joined'][2]:,}분)은 빠졌다.</li>
    <li><b>기간</b> — 분 단위는 2020-01 이후만 가능해 학습 구간이 2.4년으로 짧다. 파라미터는 지난 리포트에서 정한 그대로 썼다.</li>
    <li><b>투자 조언이 아니다</b> — 과거 데이터 통계다.</li>
  </ul>
</section>

<footer data-required="footer">premium-spread · {branch}@{sha} · {today} · 읽기 전용 분석 (앱 코드 변경 없음)</footer>
</main>
</div>
"""

hover = HOVER_JS.replace("__CHARTS__", json.dumps([[c, d] for c, d in CHARTS], separators=(",", ":")))
out = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>분 단위 김프 재검증</title>
<style>{CSS}{EXTRA_CSS}</style>
</head>
<body>
{body}
<script>{JS}</script>
<script>{hover}</script>
</body>
</html>
"""
open(f"{HERE}/minute.html", "w", encoding="utf-8").write(out)
print("written", len(out))
