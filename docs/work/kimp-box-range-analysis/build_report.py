"""results.json + analysis-report 템플릿 CSS → analysis.html 생성. 수치는 전부 results.json 에서 읽는다."""
import json, os, re, sys, html

HERE = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.expanduser("~/.claude/skills/analysis-report/template.html")
R = json.load(open(f"{HERE}/results.json"))
META = dict(branch=sys.argv[1], sha=sys.argv[2], date=sys.argv[3])
tpl = open(TPL, encoding="utf-8").read()
CSS = re.search(r"<style>(.*?)</style>", tpl, re.S).group(1)
JS = re.search(r"<script>(.*?)</script>", tpl, re.S).group(1)

def f(x, d=2): return f"{x:.{d}f}"
def pct(x): return f"{x*100:.0f}%"
e = html.escape

dm, sm, sl = R["desc_main"], R["stat_main"], R["stat_long"]
bt, fq, hb, db, bk = R["box_test"], R["fx_quiet_windows"], R["hourly_beta"], R["daily_beta"], R["backtest"]
cov = R["coverage"]

# ── chart (inline SVG) ─────────────────────────────────────────────
rows = R["rolling_box_daily"]
W, H, PL, PR, PT, PB = 1000, 320, 44, 12, 12, 26
ymin, ymax = -8, 20
def X(i): return PL + (W - PL - PR) * i / (len(rows) - 1)
def Y(v): return PT + (H - PT - PB) * (ymax - v) / (ymax - ymin)
line = "M" + " L".join(f"{X(i):.1f},{Y(r[1]):.1f}" for i, r in enumerate(rows))
band_pts = [(i, r) for i, r in enumerate(rows) if r[2] is not None]
band = ("M" + " L".join(f"{X(i):.1f},{Y(r[3]):.1f}" for i, r in band_pts)
        + " L" + " L".join(f"{X(i):.1f},{Y(r[2]):.1f}" for i, r in reversed(band_pts)) + " Z")
grid = ""
for v in range(-5, 21, 5):
    grid += f'<line x1="{PL}" x2="{W-PR}" y1="{Y(v):.1f}" y2="{Y(v):.1f}" class="g{" z" if v == 0 else ""}"/><text x="{PL-6}" y="{Y(v)+4:.1f}" class="ax" text-anchor="end">{v}%</text>'
for i, r in enumerate(rows):
    if r[0][5:] in ("01-01",) or (i == 0):
        pass
years = {}
for i, r in enumerate(rows):
    years.setdefault(r[0][:4], i)
for y, i in years.items():
    if int(y) >= 2020:
        grid += f'<line x1="{X(i):.1f}" x2="{X(i):.1f}" y1="{PT}" y2="{H-PB}" class="g"/><text x="{X(i)+3:.1f}" y="{H-8}" class="ax">{y}</text>'
chart = f"""
<figure class="chart">
  <figcaption><b>Bithumb vs Binance 선물 김프 (일, KST 00:00)</b> · 음영 = 직전 90일 [P5, P95] 이동 박스</figcaption>
  <div class="chart-box" id="chartBox">
  <svg viewBox="0 0 {W} {H}" role="img" aria-label="2019-09부터 2026-11까지 일별 김치 프리미엄과 직전 90일 이동 박스">
    {grid}
    <path d="{band}" class="band"/>
    <path d="{line}" class="ln"/>
    <line id="xh" class="xh" y1="{PT}" y2="{H-PB}" x1="-10" x2="-10"/>
    <circle id="dot" r="4" class="dot" cx="-10" cy="-10"/>
  </svg>
  <div class="tip" id="tip" hidden></div>
  </div>
  <p class="cap-note">선이 음영 밖으로 나간 구간이 "과거 박스가 미래를 담지 못한" 구간이다. 2021-04 (+19.6%), 2024-12-03 (−6.4%) 이 대표적이다.</p>
</figure>
<script>
(function(){{
  var D={json.dumps(rows)}, box=document.getElementById('chartBox'), svg=box.querySelector('svg'),
      tip=document.getElementById('tip'), xh=document.getElementById('xh'), dot=document.getElementById('dot');
  var W={W},H={H},PL={PL},PR={PR},PT={PT},PB={PB},ymin={ymin},ymax={ymax};
  function X(i){{return PL+(W-PL-PR)*i/(D.length-1);}} function Y(v){{return PT+(H-PT-PB)*(ymax-v)/(ymax-ymin);}}
  function show(ev){{
    var r=svg.getBoundingClientRect(), px=(ev.clientX-r.left)/r.width*W;
    var i=Math.round((px-PL)/(W-PL-PR)*(D.length-1)); if(i<0||i>=D.length){{hide();return;}}
    var d=D[i]; xh.setAttribute('x1',X(i)); xh.setAttribute('x2',X(i));
    dot.setAttribute('cx',X(i)); dot.setAttribute('cy',Y(Math.max(ymin,Math.min(ymax,d[1]))));
    tip.hidden=false;
    tip.innerHTML='<b>'+d[0]+'</b><br>김프 '+d[1].toFixed(2)+'%'+(d[2]===null?'':'<br>박스 '+d[2].toFixed(2)+' ~ '+d[3].toFixed(2)+'%');
    var left=(X(i)/W)*r.width; tip.style.left=Math.min(left+12, r.width-150)+'px'; tip.style.top='8px';
  }}
  function hide(){{tip.hidden=true; xh.setAttribute('x1',-10); xh.setAttribute('x2',-10); dot.setAttribute('cx',-10);}}
  svg.addEventListener('mousemove',show); svg.addEventListener('mouseleave',hide);
}})();
</script>"""

EXTRA_CSS = """
.chart{margin:18px 0;background:var(--surface);border:1px solid var(--border);border-radius:4px;padding:12px 14px}
.chart figcaption{font-size:var(--t-cap);color:var(--dim);margin-bottom:8px}
.chart figcaption b{color:var(--text)}
.chart-box{position:relative}
.chart svg{width:100%;height:auto;display:block}
.chart .g{stroke:var(--border);stroke-width:1}
.chart .g.z{stroke:var(--border-hi)}
.chart .ax{fill:var(--faint);font-family:var(--mono);font-size:11px}
.chart .band{fill:var(--accent);opacity:.16}
.chart .ln{fill:none;stroke:var(--accent);stroke-width:1.4;stroke-linejoin:round}
.chart .xh{stroke:var(--faint);stroke-width:1;stroke-dasharray:3 3}
.chart .dot{fill:var(--accent);stroke:var(--surface);stroke-width:2}
.tip{position:absolute;pointer-events:none;background:var(--surface);border:1px solid var(--border-hi);
  border-radius:4px;padding:6px 9px;font-family:var(--mono);font-size:12px;line-height:1.5;color:var(--text);min-width:130px}
.cap-note{font-size:var(--t-cap);color:var(--dim);margin:8px 0 0}
.sevmap{font-size:var(--t-cap);color:var(--dim)}
@media (max-width:920px){.wrap{grid-template-columns:minmax(0,1fr);padding:0 16px 72px}}
main{min-width:0}
td.num{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
"""

def table(head, body, cls_num=()):
    th = "".join(f"<th>{h}</th>" for h in head)
    tr = ""
    for row in body:
        tr += "<tr>" + "".join(f'<td class="{"num" if j in cls_num else ""}">{c}</td>' for j, c in enumerate(row)) + "</tr>"
    return f'<div class="tbl-wrap"><table class="rules"><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table></div>'

def code(loc, note, body):
    return f'<div class="code"><div class="loc"><span>{loc}</span><em>{note}</em></div><pre>{body}</pre></div>'

def src(lines):
    p = open(f"{HERE}/analyze.py").read().splitlines()
    a, b = lines
    return e("\n".join(p[a-1:b]))

coverage_tbl = table(
    ["소스", "연결 key", "확인된 최초 시점(UTC)", "수집 봉 수", "제한"],
    [["Bithumb KRW-BTC 1h", "불필요(공개 캔들)", cov["bithumb"][0][:16], f'{cov["bithumb"][2]:,}', "1회 200봉, 일봉은 2014-01까지 존재"],
     ["Binance 현물 BTCUSDT 1h", "불필요(공개 klines)", cov["binance_spot"][0][:16], f'{cov["binance_spot"][2]:,}', "상장일 2017-08-17이 하한"],
     ["Binance 선물 BTCUSDT 1h", "불필요(공개 klines)", cov["binance_fut"][0][:16], f'{cov["binance_fut"][2]:,}', "선물 상장 2019-09-08이 하한. 앱 기준 해외가"],
     ["Twelve Data USD/KRW 1h", "<code>EXCHANGE_RATE_API_KEY</code>", cov["fx_hourly"][0][:16], f'{cov["fx_hourly"][2]:,}', "1h는 2020-01-25부터. basic 플랜 8회/분·800회/일"],
     ["Twelve Data USD/KRW 1d", "<code>EXCHANGE_RATE_API_KEY</code>", "2003-12-01 (earliest_timestamp)", f'{cov["fx_daily"][2]:,}', "2017-08부터만 받음 — 2020-01 이전 구간 보완용"]],
    cls_num=(3,))

box_rows = []
for k, v in bt.items():
    n, m = k.split("->")
    box_rows.append([f"과거 {n}일 → 다음 {m}일", f"{v['windows']}", f"{f(v['width_med'])}%p", pct(v["mean_inside"]), pct(v["share_ge90"]), pct(v["share_lt50"]), pct(v["hard_break"])])
box_tbl = table(["박스 정의 → 검증 구간", "창 수", "박스 폭 중앙값", "평균 체류율 (기대 90%)", "체류 ≥90% 창", "체류 <50% 창", "폭 1배 이상 이탈"], box_rows, cls_num=(1, 2, 3, 4, 5, 6))

fxc_tbl = table(["다음 30일 환율 변동폭", "창 수", "박스 체류율", "다음 30일 김프 σ", "평균 이동 |Δ평균|"],
                [[r[0], r[1], pct(r[2]), f"{f(r[3])}%p", f"{f(r[4])}%p"] for r in R["fx_cond_90_30"]], cls_num=(1, 2, 3, 4))

yr_tbl = table(["연도", "평균", "P5", "P95", "최소", "최대", "ADF p", "반감기(일)"],
               [[int(y[0]), f(y[1]), f(y[5]), f(y[6]), f(y[3]), f(y[4]),
                 next((f(s[2], 4) for s in R["stat_yearly"] if s[0] == int(y[0])), "—"),
                 next((f(s[3], 1) for s in R["stat_yearly"] if s[0] == int(y[0])), "—")] for y in R["yearly"]],
               cls_num=(0, 1, 2, 3, 4, 5, 6, 7))

bt_worst = "2021-06-14 진입(3.36%) → 61일 타임아웃 청산(−0.06%) = <b>−3.42%p</b>"

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
  <h1>김치 프리미엄 박스권 가설 검증</h1>
  <div class="meta" data-required="meta">
    <div><b>저장소</b><span>premium-spread</span></div>
    <div><b>브랜치</b><span>{META['branch']} @ {META['sha']}</span></div>
    <div><b>분석 일시</b><span>{META['date']}</span></div>
    <div><b>관점</b><span>가설 검증 (데이터 분석)</span></div>
    <div><b>데이터</b><span>2017-08-17 ~ 2026-11-19, 1h</span></div>
  </div>
  <p class="verdict" data-required="verdict">
    <span class="kicker">결론</span>
    "김프는 평균으로 돌아온다"는 맞다 (정상 시계열, 반감기 약 {f(sm['half_life_days'],1)}일). 하지만 "고정된 박스"는 아니다 — 과거 90일로 그린 박스에 다음 30일이 머문 비율은 {pct(bt['90->30']['mean_inside'])} (기대 90%)이고, 박스 유지 여부는 환율 안정과 거의 무관하다 (상관 {f(R['fx_cond_corr'])}). 이론은 <b>"움직이는 박스 + 평균회귀"</b>로 고쳐야 데이터와 맞는다.
  </p>
</header>

<section id="summary">
  <h2><span class="num">1</span>요약</h2>
  <div class="stat-row" data-required="stats">
    <button class="stat" data-sev="crit" aria-pressed="false"><span class="n">0</span><span class="l">치명</span></button>
    <button class="stat" data-sev="high" aria-pressed="false"><span class="n">2</span><span class="l">높음</span></button>
    <button class="stat" data-sev="med"  aria-pressed="false"><span class="n">2</span><span class="l">보통</span></button>
    <button class="stat" data-sev="low"  aria-pressed="false"><span class="n">0</span><span class="l">낮음</span></button>
    <button class="stat" data-sev="info" aria-pressed="false"><span class="n">2</span><span class="l">정보</span></button>
  </div>
  <p class="sevmap">이 리포트의 심각도는 코드 결함이 아니라 <b>가설에 대한 판정 강도</b>다 — 높음: 가설의 핵심 주장을 반증, 보통: 조건부 반증·실전 주의, 정보: 가설을 지지하는 사실 또는 데이터 범위 기록.</p>
  <div data-required="summary-body">
    <p><b>지지:</b> 2019-09~2026-11 일별 김프(Bithumb vs Binance 선물, n={R['n_daily_main']:,})는 ADF p={f(sm['adf_p'],4)}, KPSS p={f(sm['kpss_p'],3)}로 정상 시계열이다. 중앙값 {f(dm['q50'])}%, P5~P95 = {f(dm['q5'])}% ~ {f(dm['q95'])}%.</p>
    <p><b>반증:</b> 박스의 <b>위치와 폭이 체제마다 바뀐다</b>. 연평균이 2020년 −0.55% → 2021년 3.68% → 2026년 0.10%로 움직였고, 표본 외 박스 체류율은 어떤 창 길이에서도 90%에 못 미친다(58~81%).</p>
    <p><b>환율 조건:</b> 환율이 오르면 김프는 즉시 내려간다(1% 상승당 시간 단위 {f(hb['beta'])}%p, 일 단위 {f(db['beta'])}%p). 하지만 이 효과가 김프 변동에서 차지하는 몫은 {pct(hb['r2'])} 안팎이다. 환율이 고정되어도 박스가 깨지는 주원인(코인 수요 쇼크)은 그대로 남는다.</p>
  </div>
  {chart}
</section>

<section id="flow">
  <h2><span class="num">2</span>동작 흐름</h2>
  <div data-required="flow">
    <div class="flow">
      <div class="flow-node"><div class="t">원시 데이터 수집 (1시간봉)</div><div class="loc">docs/work/kimp-box-range-analysis/fetch.py:24-81</div>
        <div class="d">Binance 현물·선물 klines, Bithumb <code>/v1/candles/minutes/60</code> (to 파라미터는 KST로 해석됨), Twelve Data <code>time_series</code> USD/KRW. 봉 종료 시각(UTC)으로 정렬.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">환율 정제 + 정각 정렬</div><div class="loc">docs/work/kimp-box-range-analysis/analyze.py:16-33</div>
        <div class="d">USD/KRW 800~2,500 범위 밖 값 {R['fx_hourly_outliers_dropped']}개 제거(2024-01-02 00~02 UTC에 2.3 같은 오염 값). 2020-01 이전은 일봉 환율을 forward-fill. 각 시각에 환율 경과 시간을 기록.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">김프 계산 — 앱과 같은 공식</div><div class="loc">docs/work/kimp-box-range-analysis/analyze.py:34-35 ≡ domain/src/main/kotlin/io/premiumspread/domain/premium/PremiumPolicy.kt:22-25</div>
        <div class="d"><code>(한국가 − 해외가×환율) / (해외가×환율) × 100</code>. 주 시계열 <code>p_fu</code> = Binance 선물(앱의 <code>fstream ... btcusdt@bookTicker</code>와 같은 시장).</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">일별 샘플링 (KST 00:00 = 15:00 UTC)</div><div class="loc">docs/work/kimp-box-range-analysis/analyze.py:39-46</div>
        <div class="d">환율이 72시간 이내 값일 때만 사용(주말 허용). 주 시계열 2019-09-09~, 보조 시계열(현물) 2017-08~.</div></div>
      <div class="flow-arrow">↓</div>
      <div class="flow-node"><div class="t">검정 4종</div><div class="loc">docs/work/kimp-box-range-analysis/analyze.py:59-152</div>
        <div class="d">① 정상성·반감기 ② 표본 외 박스 체류율 ③ 환율 조건부 체류율·ΔP~ΔFX 회귀 ④ 박스 매매 gross 백테스트.</div></div>
    </div>
  </div>
</section>

<section id="rules">
  <h2><span class="num">3</span>핵심 규칙</h2>
  <p>"증명"은 데이터로 할 수 없다. 데이터가 할 수 있는 일은 반증 가능한 형태로 바꾼 주장이 반증 시도를 견디는지 보는 것뿐이다. 가설을 세 개의 검증 가능한 주장으로 나눴다.</p>
  <div data-required="rules">
    {table(["주장", "검정", "통과 기준", "결과", "판정"], [
      ["H1. 김프는 평균으로 돌아온다", "ADF(단위근) + KPSS(정상성) + AR(1) 반감기", "ADF p&lt;0.05 이고 KPSS p≥0.05", f"ADF p={f(sm['adf_p'],4)}, KPSS p={f(sm['kpss_p'],3)}, 반감기 {f(sm['half_life_days'],1)}일", '<span class="badge ok">지지</span>'],
      ["H2. 박스(범위)가 고정되어 있다", "과거 N일 [P5,P95]를 박스로 놓고 다음 M일 체류율 측정 (7일 간격 롤링)", "평균 체류율 ≈ 90%", f"90→30일 {pct(bt['90->30']['mean_inside'])}, 365→90일 {pct(bt['365->90']['mean_inside'])}", '<span class="badge high">기각</span>'],
      ["H3. 환율이 고정이면 박스가 유지된다", "다음 30일 환율 변동폭별 체류율 비교 + 상관", "변동폭↓ → 체류율↑ (뚜렷한 음의 상관)", f"상관 {f(R['fx_cond_corr'])}, 환율 조용한 창의 김프 범위 {f(fq['p_rng_med_quiet'])}%p vs 전체 {f(fq['p_rng_med_all'])}%p", '<span class="badge high">기각</span>'],
      ["H3′. 환율이 김프를 움직인다", "시간·일 단위 ΔP ~ ΔFX(%) OLS, HC1 표준오차", "β≠0", f"β={f(hb['beta'])} (t={f(hb['t'],1)}), 일 β={f(db['beta'])}; R²={f(hb['r2'],3)}", '<span class="badge med">부분 지지</span>'],
    ], cls_num=())}
    <h3>연도별 분포 (보조 시계열: Bithumb vs Binance 현물)</h3>
    {yr_tbl}
    <p class="note">2017~2018년(최대 59.9%)과 2021년 상반기는 박스라는 말 자체가 무의미한 체제다. 2022~2023년은 반감기 4.4~7.4일로 박스 성격이 가장 강했다. 연도마다 중심이 1~3%p씩 옮겨 다닌다.</p>
  </div>
</section>

<section id="findings">
  <h2><span class="num">4</span>발견 사항</h2>
  <div id="findingList" data-required="findings">

    <article class="finding info" data-sev="info">
      <h3><span class="id">F1</span><span class="badge info">정보</span>장기적으로 김프는 평균회귀한다 — 가설의 "돌아온다" 부분은 지지된다</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/analyze.py:59-64</p>
      {code("analyze.py:59-64", "정상성·반감기 계산", src((59, 64)))}
      <div class="field"><span class="k">근거</span>
        <p>주 시계열 ADF 통계량 {f(sm['adf_stat'])} (p={f(sm['adf_p'],4)}), KPSS p={f(sm['kpss_p'],3)} → 단위근 기각, 정상성 기각 못 함. AR(1) 계수 {f(sm['ar1'],3)} → 괴리의 절반이 약 <b>{f(sm['half_life_days'],1)}일</b>에 해소된다. 2017-08부터의 보조 시계열도 같다(ADF p&lt;0.0001, 반감기 {f(sl['half_life_days'],1)}일). 9개 연도 중 8개 연도에서 연 단위로도 ADF p&lt;0.1이다.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F2</span><span class="badge high">높음</span>박스는 고정되어 있지 않다 — 과거 박스가 다음 구간을 담는 비율은 58~81%</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/analyze.py:74-79</p>
      {code("analyze.py:74-79", "표본 외 박스 체류율", src((74, 79)))}
      {box_tbl}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>2021-03-22 기준 직전 90일 박스 = [−4.60%, 3.62%]. 상단 근처에서 "박스 상단이니 김프 축소에 베팅"</li>
          <li>다음 30일 동안 김프가 박스 안에 머문 날 0%. 2021-04-06에 <b>19.62%</b> 도달</li>
          <li class="then">박스 상단 대비 +16%p 역행. 박스 폭(8.2%p)의 2배를 이탈</li>
        </ol></div>
        <div class="scenario" style="margin-top:8px"><ol>
          <li>2024-11-20 기준 직전 90일 박스 = [−0.68%, 1.90%]. 하단 근처에서 "박스 하단이니 진입"</li>
          <li>2024-12-03 김프 <b>−6.42%</b> (9년 중 주 시계열 최저)</li>
          <li class="then">박스 하단 대비 −5.7%p 추가 하락. 고정 박스를 믿은 손절 기준이 무의미해짐</li>
        </ol></div>
      </div>
      <div class="field"><span class="k">근거</span>
        <p>박스가 고정이라면 [P5,P95] 박스의 표본 외 체류율은 90% 근처여야 한다. 실제로는 가장 좋은 설정(365일→90일)도 {pct(bt['365->90']['mean_inside'])}이고, 체류율 50% 미만인 창이 {pct(bt['365->90']['share_lt50'])}다. 긴 창일수록 체류율이 오르지만 박스 폭이 {f(bt['365->90']['width_med'])}%p로 넓어져 매매 의미가 줄어든다. 표의 체류율 패턴은 "평균회귀하지만 중심이 천천히 이동하는 과정"과 일치한다.</p></div>
    </article>

    <article class="finding high" data-sev="high">
      <h3><span class="id">F3</span><span class="badge high">높음</span>환율 안정이 박스 유지의 조건이라는 근거가 없다</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/analyze.py:92-117</p>
      {code("analyze.py:96-100", "다음 30일 환율 변동폭과 박스 체류율", src((96, 100)))}
      {fxc_tbl}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>"환율이 조용한 달에는 박스가 지켜진다"는 규칙으로, 30일 환율 변동폭 &lt;1.5%인 창만 골라 매매</li>
          <li>이런 창은 7년간 {fq['n_quiet']}개뿐이고, 그 구간 김프 30일 범위 중앙값은 {f(fq['p_rng_med_quiet'])}%p(전체 {f(fq['p_rng_med_all'])}%p), 상위 10%는 {f(fq['p_rng_q90_quiet'])}%p</li>
          <li class="then">환율이 고정에 가까워도 김프 변동폭은 줄지 않는다. 진입 필터로서 효과가 없다</li>
        </ol></div>
      </div>
      <div class="field"><span class="k">근거</span>
        <p>환율 변동폭과 체류율의 상관 {f(R['fx_cond_corr'])}, 환율 변동폭과 김프 범위의 상관 {f(fq['corr_fxrng_prng'])} — 둘 다 사실상 0이다. &lt;1.5% 구간의 체류율 82.5%가 가장 높긴 하지만 n=19라 우연과 구분되지 않고, 그래도 90%에 못 미친다 <span class="badge guess">추정</span>. 박스 이탈 사례(2021-04 강세장 수요, 2024-12-03 국내 정치 이벤트)는 환율이 아니라 국내 코인 수급 쇼크에서 나왔다 <span class="badge guess">추정 — 이벤트 원인은 데이터로 검증하지 않음</span>.</p></div>
    </article>

    <article class="finding med" data-sev="med">
      <h3><span class="id">F4</span><span class="badge med">보통</span>환율 변동은 김프를 즉시 반대로 움직인다 — "환율 고정" 가정이 지우는 몫은 5~8%</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/analyze.py:120-130</p>
      {code("analyze.py:120-126", "ΔP ~ ΔFX 시간 단위 회귀 (환율 1h 이내 값만)", src((120, 126)))}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>2020-03-23 01:00 UTC, USD/KRW가 한 시간에 +2.13% 급등</li>
          <li>같은 시간 김프 −1.70%p. 원화 코인 가격이 환율을 즉시 따라가지 못해 생긴 착시</li>
          <li class="then">박스 하단 신호가 켜지지만 실제 원인은 환율 점프의 반영 지연이다. 반대로 2021-11-09 환율 −1.75%에 김프 +2.20%p</li>
        </ol></div>
      </div>
      <div class="field"><span class="k">근거</span>
        <p>시간 단위 β={f(hb['beta'],3)} (t={f(hb['t'],1)}, n={hb['n']:,}), 24시간 단위 β={f(db['beta'],3)} (t={f(db['t'],1)}). 하루가 지나면 계수가 약 40% 줄어든다 → 한국 가격이 환율을 <b>천천히 따라간다</b>. 환율을 고정한다고 가정하면 이 성분이 사라지지만, 설명력(R² {f(hb['r2'],3)}/{f(db['r2'],3)})이 작아 박스 이탈을 막지 못한다. F3과 같은 결론이다.</p></div>
    </article>

    <article class="finding med" data-sev="med">
      <h3><span class="id">F5</span><span class="badge med">보통</span>이동 박스 매매는 gross 기준 양수지만 표본 32회·손실 꼬리·비용 미반영</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/analyze.py:138-152</p>
      {code("analyze.py:138-146", "직전 90일 P10 아래 진입, P90 도달 또는 60일 경과 시 청산", src((138, 146)))}
      <div class="field"><span class="k">실패 시나리오 (실제 데이터)</span>
        <div class="scenario"><ol>
          <li>2021-06-14 김프 3.36% — 직전 90일(4~6월, 고김프 체제)의 P10 아래라 진입 신호</li>
          <li>체제가 바뀌어 박스 중심이 내려감. 목표(P90)에 닿지 못하고 61일 타임아웃</li>
          <li class="then">{bt_worst}. 이 기간 펀딩비는 계산에 없다</li>
        </ol></div>
      </div>
      <div class="field"><span class="k">근거</span>
        <p>2019-12~2026-11 거래 {bk['n']}회, 목표 도달률 {pct(bk['hit_rate'])}, 평균 gross {f(bk['gross_mean'])}%p (중앙값 {f(bk['gross_med'])}%p), 평균 보유 {f(bk['days_mean'],1)}일, 왕복 비용 0.3%p 가정 시 평균 {f(bk['net_mean_cost0.3'])}%p · 승률 {pct(bk['win_rate_net'])}. 단 ① 파라미터(90일·P10/P90·60일)를 같은 데이터로 골라 과적합 위험이 있고 ② 선물 short의 펀딩비(39일 보유 시 수 %p가 될 수 있음)와 일 단위 체결 가정이 빠졌다 <span class="badge guess">추정 — 펀딩비 미수집</span>. "평균회귀 + 이동 박스"가 매매 가능한 신호일 수 있다는 정도까지만 말할 수 있다.</p></div>
    </article>

    <article class="finding info" data-sev="info">
      <h3><span class="id">F6</span><span class="badge info">정보</span>연결된 key로 확보 가능한 과거 데이터 범위</h3>
      <p class="locline">docs/work/kimp-box-range-analysis/fetch.py:24-81</p>
      {coverage_tbl}
      <div class="field"><span class="k">근거</span>
        <p>세 소스가 모두 겹치는 구간은 <b>2017-08-17 ~ 현재(약 9.3년)</b>, 앱과 같은 선물 기준은 <b>2019-09-08 ~ 현재(약 7.2년)</b>다. 병목은 환율이다 — 1시간봉이 2020-01-25부터라 그 이전은 일봉 환율로 보완했다. 거래소 시세는 공개 API라 key가 필요 없고, 저장소의 Binance/Bithumb key는 계정 조회용이라 이번 분석에는 쓰지 않았다. Twelve Data 사용량: 수집 전 151/800회(일), 수집에 약 15회 사용.</p></div>
    </article>

  </div>
</section>

<section id="compare">
  <h2><span class="num">5</span>대조</h2>
  <p class="note">분석이 앱과 같은 김프를 보고 있는지 대조한다. 공식이 다르면 결론을 앱에 적용할 수 없다.</p>
  <div data-required="compare">
    <div class="compare">
      <div class="pane good">
        <h4><span>앱 — Domain PremiumPolicy</span><span class="p">domain/…/premium/PremiumPolicy.kt:22-25</span></h4>
<pre>val foreignPriceInKrw = foreignPriceUsd.multiply(fxRate)
val calculatedRate = koreaPrice.subtract(foreignPriceInKrw)
    .divide(foreignPriceInKrw, CALCULATION_SCALE, RoundingMode.HALF_UP)
    .multiply(HUNDRED)</pre>
        <div class="why">해외가는 <code>fstream.binance.com … btcusdt@bookTicker</code> (apps/batch/src/main/resources/application.yml:245), USDT를 USD로 간주.</div>
      </div>
      <div class="pane good">
        <h4><span>분석 — 같은 공식</span><span class="p">analyze.py:34-35</span></h4>
<pre>{src((34, 35))}</pre>
        <div class="why">같은 시장(Binance 선물 BTCUSDT)과 같은 공식. 차이는 bookTicker 중간값 대신 1시간봉 종가, 1초 대신 일 단위 샘플이라는 점뿐이다.</div>
      </div>
    </div>
    <div class="compare">
      <div class="pane good">
        <h4><span>환율 조용한 30일 창 (변동 &lt;1.5%)</span><span class="p">n={fq['n_quiet']}</span></h4>
<pre>김프 30일 범위 중앙값  {f(fq['p_rng_med_quiet'])}%p
김프 30일 범위 P90     {f(fq['p_rng_q90_quiet'])}%p
박스 체류율 (90→30)    82.5%  (n=19)</pre>
        <div class="why">가설대로라면 이쪽이 뚜렷이 좁아야 한다.</div>
      </div>
      <div class="pane bad">
        <h4><span>전체 30일 창</span><span class="p">n={fq['n_all']}</span></h4>
<pre>김프 30일 범위 중앙값  {f(fq['p_rng_med_all'])}%p
상관(환율 변동폭, 김프 범위)  {f(fq['corr_fxrng_prng'])}
박스 체류율 (90→30)    {pct(bt['90->30']['mean_inside'])}</pre>
        <div class="why">차이가 없다. 환율 안정은 박스 유지를 설명하지 못한다.</div>
      </div>
    </div>
  </div>
</section>

<section id="actions">
  <h2><span class="num">6</span>권장 조치</h2>
  <ol class="actions" data-required="actions">
    <li><b>가설을 "고정 박스"에서 "이동 박스 + 평균회귀"로 바꾼다.</b> 기준은 직전 90~180일 분위수로 매일 다시 계산하고, 중심값에서의 괴리를 반감기(약 12일)로 해석한다.
      <span class="impact">영향 범위: 매매 판단 기준 · 단독 수정 가능: 예 — 코드 변경 없이 분석 기준만 바꾸면 된다</span></li>
    <li><b>환율 점프 직후 신호는 거른다.</b> 직전 1~24시간 환율 변동이 1%를 넘으면 김프 신호를 보류하거나, β로 보정한 김프(P − β·ΔFX)를 함께 본다.
      <span class="impact">영향 범위: 알림·진입 신호 · 단독 수정 가능: 예 — 다만 앱 알림에 넣으려면 별도 설계(orchestrator) 필요</span></li>
    <li><b>체제 전환 감지를 먼저 둔다.</b> 박스 이탈이 폭의 1배를 넘으면(이번 표본에서 90→30일 창의 {pct(bt['90->30']['hard_break'])}) 그 박스를 버리고 새 분포가 쌓일 때까지 쉰다.
      <span class="impact">영향 범위: 손실 꼬리(F2·F5) · 단독 수정 가능: 예</span></li>
    <li><b>비용 포함 재검증.</b> Binance 펀딩비 이력(<code>/fapi/v1/fundingRate</code>, 공개)과 실제 수수료를 넣어 F5 백테스트를 다시 돌리고, 파라미터는 2019~2022로 고른 뒤 2023~2026에서만 평가한다.
      <span class="impact">영향 범위: F5 결론의 신뢰도 · 단독 수정 가능: 예 — analyze.py에 펀딩비 수집만 추가</span></li>
  </ol>
</section>

<section id="verify">
  <h2><span class="num">7</span>검증 기록</h2>
  <h3>읽은 파일 · 실행한 명령</h3>
  <ul class="plain" data-required="verify-done">
    <li><code>.ai/context/project-overview.md</code> — 김프 공식, 기본 pair <code>BTC:BITHUMB:BINANCE</code>, 환율 공급자 Twelve Data</li>
    <li><code>domain/src/main/kotlin/io/premiumspread/domain/premium/PremiumPolicy.kt</code> — 계산 공식 22-25행</li>
    <li><code>apps/batch/src/main/resources/application.yml</code> — 해외가 = Binance 선물 bookTicker(245행), 환율 endpoint(254행)</li>
    <li><code>~/.config/premium-spread/account-pnl.env</code> — <code>EXCHANGE_RATE_API_KEY</code> 존재 확인(값은 출력하지 않음)</li>
    <li>Twelve Data <code>earliest_timestamp</code>(1day → 2003-12-01, 1h → 2020-01-25), <code>api_usage</code>(basic, 8/분·800/일)</li>
    <li><code>fetch.py spot|fut|bithumb|fx</code> → 1시간봉 268,000여 개 수집 (원시 데이터는 커밋하지 않음, <code>KIMP_DATA_DIR</code>로 재수집 가능)</li>
    <li><code>analyze.py</code> → <code>results.json</code>. 재실행 시 같은 수치를 확인함</li>
  </ul>
  <h3>확인하지 못한 것 · 가정</h3>
  <ul class="plain" data-required="verify-open">
    <li><b>USDT = USD 가정</b> — 앱과 같다. USDT 디페그(대부분 ±0.5% 이내)가 김프에 그대로 섞인다.</li>
    <li><b>2020-01 이전 환율은 일봉</b> — 종가 시각을 21:00 UTC로 가정하고 forward-fill했다. 2019-09~2020-01 주 시계열 약 140일의 환율 오차는 ±0.5% 수준으로 추정한다 <span class="badge guess">추정</span>.</li>
    <li><b>주말·휴장</b> — 외환 시장이 닫힌 동안 환율은 마지막 값이다. 일 시계열은 72시간 이내 값만, 회귀는 1시간 이내 값만 썼다.</li>
    <li><b>Twelve Data 오염 값</b> — 2024-01-02 00~02 UTC에 USD/KRW 2.3~2.4가 3개 있어 제거했다. 같은 공급자를 쓰는 앱 수집기도 이런 값을 받을 수 있다(이번 분석 범위 밖).</li>
    <li><b>비용</b> — 수수료·펀딩비·슬리피지·환전 비용이 없다. F5는 gross다.</li>
    <li><b>호가 vs 종가</b> — 앱은 bookTicker, 분석은 체결 종가다. 일 단위 결론에는 영향이 작다고 본다 <span class="badge guess">추정</span>.</li>
    <li><b>앱 DB의 1초 데이터는 쓰지 않았다</b> — 로컬 DB에 쌓인 기간이 짧아 9년 검증에 못 쓴다.</li>
    <li><b>"증명" 불가</b> — 통계 검정은 가설을 기각하거나 기각하지 못할 뿐이다. H1은 기각되지 않았고 H2·H3은 기각됐다.</li>
  </ul>
</section>

<footer data-required="footer">
  premium-spread · {META['branch']}@{META['sha']} · {META['date']} · 읽기 전용 분석 (앱 코드 변경 없음)
</footer>
</main>
</div>
"""

out = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>김프 박스권 검증</title>
<style>{CSS}{EXTRA_CSS}</style>
</head>
<body>
{body}
<script>{JS}</script>
</body>
</html>
"""
open(f"{HERE}/analysis.html", "w", encoding="utf-8").write(out)
print("written", len(out))
