# 차트 무한스크롤 + Redis 캐싱 구현 계획

## 전제 조건

| 인터벌 | 최대 과거 제한 | 최대 데이터 수 | Redis TTL |
|--------|-------------|-------------|-----------|
| 1분 (1m) | 24시간 | 1,440건 | 25시간 |
| 1시간 (1h) | 30일 | 720건 | 31일 |
| 1일 (1d) | 365일 | 366건 | 366일 |

---

# Part 1. 백엔드

## 1-1. Redis TTL 확장 + DAYS 추가

### 변경 파일: `modules/redis/.../RedisTtl.kt`

```kotlin
// Before
val MINUTES_DATA: Duration = Duration.ofHours(2)
val HOURS_DATA: Duration = Duration.ofHours(25)

// After
val MINUTES_DATA: Duration = Duration.ofHours(25)      // 24시간 + 1시간 버퍼
val HOURS_DATA: Duration = Duration.ofDays(31)          // 30일 + 1일 버퍼
val DAYS_DATA: Duration = Duration.ofDays(366)          // 365일 + 1일 버퍼
```

### 변경 파일: `modules/redis/.../AggregationTimeUnit.kt`

```kotlin
enum class AggregationTimeUnit(
    private val keyPrefix: String,
    val ttl: Duration,
) {
    SECONDS("premium:seconds", RedisTtl.SECONDS_DATA),
    MINUTES("premium:minutes", RedisTtl.MINUTES_DATA),
    HOURS("premium:hours", RedisTtl.HOURS_DATA),
    DAYS("premium:days", RedisTtl.DAYS_DATA),          // 신규
    ;

    fun keyFor(symbol: String): String = "$keyPrefix:${symbol.lowercase()}"
}
```

### 변경 파일: `modules/redis/.../RedisKeyGenerator.kt`

```kotlin
// 추가
fun premiumDaysKey(symbol: String): String =
    "premium:days:$symbol"
```

---

## 1-2. 배치 — 일별 집계 Redis Write-Through

### 변경 파일: `apps/batch/.../PremiumAggregationScheduler.kt`

현재 `dayJob`의 writer는 DB만 저장. Redis 저장 추가:

```kotlin
// Before
private val dayJob = AggregationJob<PremiumAggregation>(
    reader = { from, to -> premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, BTC, from, to) },
    writer = { agg, from, _ ->
        val dayAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault()).toLocalDate()
        aggregationRepository.saveDay(BTC, dayAt, agg)
        log.info("Aggregated day data: ...")
    },
    unit = ChronoUnit.DAYS,
)

// After
private val dayJob = AggregationJob<PremiumAggregation>(
    reader = { from, to -> premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, BTC, from, to) },
    writer = { agg, from, _ ->
        premiumCacheService.saveAggregation(AggregationTimeUnit.DAYS, BTC, from, agg)  // Redis 추가
        val dayAt = LocalDateTime.ofInstant(from, ZoneId.systemDefault()).toLocalDate()
        aggregationRepository.saveDay(BTC, dayAt, agg)
        log.info("Aggregated day data: ...")
    },
    unit = ChronoUnit.DAYS,
)
```

---

## 1-3. API — RepositoryImpl에 Cache→DB Fallback

### 변경 파일: `apps/api/.../infrastructure/premium/PremiumAggregationCacheReader.kt` (신규)

API 모듈에서 Redis 집계 ZSet을 읽는 CacheReader:

```kotlin
@Component
class PremiumAggregationCacheReader(
    private val redisTemplate: StringRedisTemplate,
) {
    fun findByInterval(
        symbol: String,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot>? {
        val timeUnit = when (interval) {
            "1m" -> AggregationTimeUnit.MINUTES
            "1h" -> AggregationTimeUnit.HOURS
            "1d" -> AggregationTimeUnit.DAYS
            else -> return null
        }

        val key = timeUnit.keyFor(symbol)
        val entries = redisTemplate.opsForZSet().rangeByScoreWithScores(
            key,
            from.toEpochMilli().toDouble(),
            to.toEpochMilli().toDouble(),
        )

        if (entries.isNullOrEmpty()) return null

        return entries.mapNotNull { entry ->
            val parts = entry.value?.split(":") ?: return@mapNotNull null
            if (parts.size < 6) return@mapNotNull null
            val timestamp = entry.score?.toLong()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null

            PremiumAggregationSnapshot(
                symbol = symbol.uppercase(),
                high = parts[0].toBigDecimalOrNull() ?: return@mapNotNull null,
                low = parts[1].toBigDecimalOrNull() ?: return@mapNotNull null,
                open = parts[2].toBigDecimalOrNull() ?: return@mapNotNull null,
                close = parts[3].toBigDecimalOrNull() ?: return@mapNotNull null,
                avg = parts[4].toBigDecimalOrNull() ?: return@mapNotNull null,
                count = parts[5].toIntOrNull() ?: return@mapNotNull null,
                observedAt = timestamp,
            )
        }
    }
}
```

### 변경 파일: `apps/api/.../infrastructure/premium/PremiumRepositoryImpl.kt`

```kotlin
// Before
override fun findAggregation(symbol: Symbol, interval: String, from: Instant, to: Instant): List<PremiumAggregationSnapshot> {
    return premiumAggregationQueryRepository.findByInterval(symbol.code, interval, from, to)
}

// After
override fun findAggregation(symbol: Symbol, interval: String, from: Instant, to: Instant): List<PremiumAggregationSnapshot> {
    // Cache 우선 조회
    val cached = premiumAggregationCacheReader.findByInterval(symbol.code, interval, from, to)
    if (cached != null) {
        log.debug("Aggregation cache hit: {} {} ({} entries)", symbol.code, interval, cached.size)
        return cached
    }

    // DB fallback
    log.debug("Aggregation cache miss, falling back to DB: {} {}", symbol.code, interval)
    return premiumAggregationQueryRepository.findByInterval(symbol.code, interval, from, to)
}
```

생성자에 `premiumAggregationCacheReader: PremiumAggregationCacheReader` 주입 추가.

---

## 1-4. API — 최대 범위 검증 + 시간 정규화

### 변경 파일: `apps/api/.../domain/premium/PremiumService.kt`

```kotlin
companion object {
    private val MAX_RANGE = mapOf(
        "1m" to Duration.ofHours(24),
        "1h" to Duration.ofDays(30),
        "1d" to Duration.ofDays(365),
    )
}

@Transactional(readOnly = true)
fun findAggregation(symbol: Symbol, interval: String, from: Instant, to: Instant): List<PremiumAggregationSnapshot> {
    val maxRange = MAX_RANGE[interval]
        ?: throw IllegalArgumentException("Invalid interval: $interval")

    // 최대 범위 clamp
    val clampedFrom = maxOf(from, to.minus(maxRange))

    // 시간 정규화 (interval 단위로 truncate)
    val truncatedFrom = when (interval) {
        "1m" -> clampedFrom.truncatedTo(ChronoUnit.MINUTES)
        "1h" -> clampedFrom.truncatedTo(ChronoUnit.HOURS)
        "1d" -> clampedFrom.truncatedTo(ChronoUnit.DAYS)
        else -> clampedFrom
    }
    val truncatedTo = when (interval) {
        "1m" -> to.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        "1h" -> to.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
        "1d" -> to.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
        else -> to
    }

    return premiumRepository.findAggregation(symbol, interval, truncatedFrom, truncatedTo)
}
```

---

## 1-5. API — 응답에 `hasMore` 메타데이터 추가

### 변경 파일: `apps/api/.../interfaces/api/premium/PremiumDtos.kt`

```kotlin
// 신규 wrapper
data class AggregationPage(
    val data: List<Aggregation>,
    val hasMore: Boolean,
)
```

### 변경 파일: `apps/api/.../interfaces/api/premium/PremiumController.kt`

```kotlin
@GetMapping("/aggregation/{symbol}")
fun getAggregation(
    @PathVariable symbol: String,
    @RequestParam interval: String,
    @RequestParam from: Instant,
    @RequestParam to: Instant,
): ResponseEntity<PremiumResponse.AggregationPage> {
    val maxRange = mapOf("1m" to Duration.ofHours(24), "1h" to Duration.ofDays(30), "1d" to Duration.ofDays(365))
    val hasMore = maxRange[interval]?.let { from > to.minus(it) } ?: true

    val results = premiumFacade.findAggregation(symbol, interval, from, to)
    return ResponseEntity.ok(
        PremiumResponse.AggregationPage(
            data = results.map { PremiumResponse.Aggregation.from(it) },
            hasMore = hasMore,
        )
    )
}
```

---

# Part 2. 프론트엔드

## 2-1. 상수 및 타입 변경

### 변경 파일: `apps/web/src/components/PremiumChart.tsx`

```typescript
// 기존 INTERVALS 확장
const INTERVALS = [
  { label: '1분', value: '1m', refreshMs: 10_000, rangeHours: 2, chunkHours: 2, maxHours: 24 },
  { label: '1시간', value: '1h', refreshMs: 60_000, rangeHours: 48, chunkHours: 48, maxHours: 720 },
  { label: '1일', value: '1d', refreshMs: 300_000, rangeHours: 720, chunkHours: 720, maxHours: 8760 },
] as const;

// API 응답 변경
interface AggregationResponse {
  data: AggregationData[];
  hasMore: boolean;
}
```

- `rangeHours`: 초기 로드 범위 (기존과 동일)
- `chunkHours`: 과거 데이터 한 번에 가져올 양
- `maxHours`: 최대 과거 제한

---

## 2-2. 상태 구조 변경

```typescript
export function PremiumChart() {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const [activeInterval, setActiveInterval] = useState('1m');

  // 신규 상태
  const allDataRef = useRef<ChartDataPoint[]>([]);        // 누적 데이터 (ref로 관리)
  const loadedFromRef = useRef<Date>(new Date());          // 로드된 가장 과거 시점
  const [hasMore, setHasMore] = useState(true);
  const [isLoadingPast, setIsLoadingPast] = useState(false);
```

`allDataRef`를 `useRef`로 관리하는 이유: `setData()` 호출 시 리렌더링 방지.

---

## 2-3. 폴링 (최신 데이터) — 기존 fetchData 수정

```typescript
const fetchLatest = useCallback(async (interval: string) => {
  const config = INTERVALS.find((i) => i.value === interval)!;
  const to = new Date();
  const from = new Date(to.getTime() - config.rangeHours * 60 * 60 * 1000);

  // from/to 정규화 (interval 단위 truncate)
  from.setSeconds(0, 0);
  if (interval === '1h') from.setMinutes(0);
  if (interval === '1d') { from.setMinutes(0); from.setHours(0); }

  try {
    const res = await apiClient<AggregationResponse>(
      `/premiums/aggregation/BTC?interval=${interval}&from=${from.toISOString()}&to=${to.toISOString()}`,
    );

    const KST_OFFSET_SEC = 9 * 60 * 60;
    const newPoints: ChartDataPoint[] = res.data.map((d) => ({
      time: (Math.floor(new Date(d.observedAt).getTime() / 1000) + KST_OFFSET_SEC) as UTCTimestamp,
      value: d.close,
    }));

    // 기존 과거 데이터 유지 + 최신 구간 교체
    const cutoff = (Math.floor(from.getTime() / 1000) + KST_OFFSET_SEC) as UTCTimestamp;
    const pastData = allDataRef.current.filter((p) => p.time < cutoff);
    allDataRef.current = [...pastData, ...newPoints];

    if (seriesRef.current) {
      seriesRef.current.setData(allDataRef.current);
    }
  } catch {
    // 다음 폴링에서 재시도
  }
}, []);
```

---

## 2-4. 과거 데이터 로드 (신규)

```typescript
const fetchPast = useCallback(async (interval: string) => {
  if (isLoadingPast || !hasMore) return;

  const config = INTERVALS.find((i) => i.value === interval)!;
  const to = loadedFromRef.current;
  const from = new Date(to.getTime() - config.chunkHours * 60 * 60 * 1000);

  // 최대 제한 체크
  const maxFrom = new Date(Date.now() - config.maxHours * 60 * 60 * 1000);
  if (from <= maxFrom) {
    from.setTime(maxFrom.getTime());
    setHasMore(false);
  }

  setIsLoadingPast(true);
  try {
    const res = await apiClient<AggregationResponse>(
      `/premiums/aggregation/BTC?interval=${interval}&from=${from.toISOString()}&to=${to.toISOString()}`,
    );

    const KST_OFFSET_SEC = 9 * 60 * 60;
    const pastPoints: ChartDataPoint[] = res.data.map((d) => ({
      time: (Math.floor(new Date(d.observedAt).getTime() / 1000) + KST_OFFSET_SEC) as UTCTimestamp,
      value: d.close,
    }));

    // 왼쪽에 prepend
    allDataRef.current = [...pastPoints, ...allDataRef.current];
    loadedFromRef.current = from;

    if (!res.hasMore) setHasMore(false);

    if (seriesRef.current) {
      seriesRef.current.setData(allDataRef.current);
    }
  } catch {
    // 무시
  } finally {
    setIsLoadingPast(false);
  }
}, [isLoadingPast, hasMore]);
```

---

## 2-5. lightweight-charts 드래그 이벤트 연동

차트 생성 useEffect 내부에 추가:

```typescript
useEffect(() => {
  if (!chartContainerRef.current) return;

  const chart = createChart(chartContainerRef.current, { /* 기존 옵션 */ });
  const series = chart.addSeries(LineSeries, { color: '#2563eb', lineWidth: 2 });

  chartRef.current = chart;
  seriesRef.current = series;

  // 드래그로 과거 데이터 로드 트리거
  chart.timeScale().subscribeVisibleLogicalRangeChange((logicalRange) => {
    if (logicalRange === null) return;

    // 차트 왼쪽 끝에 가까워지면 과거 데이터 fetch
    if (logicalRange.from < 10) {
      fetchPast(activeInterval);
    }
  });

  // ... resize handler, cleanup ...
}, []);
```

`logicalRange.from < 10`: 보이는 영역의 왼쪽 인덱스가 10 미만이면 과거 데이터 로드. 즉 사용자가 왼쪽 끝 근처까지 드래그하면 트리거.

---

## 2-6. 인터벌 전환 시 초기화

```typescript
useEffect(() => {
  // 상태 초기화
  allDataRef.current = [];
  const config = INTERVALS.find((i) => i.value === activeInterval)!;
  loadedFromRef.current = new Date(Date.now() - config.rangeHours * 60 * 60 * 1000);
  setHasMore(true);

  // 초기 데이터 로드 + 폴링 시작
  fetchLatest(activeInterval);
  const timer = setInterval(() => fetchLatest(activeInterval), config.refreshMs);
  return () => clearInterval(timer);
}, [activeInterval, fetchLatest]);
```

---

# Part 3. 테스트 계획

## 기존 테스트 현황 (영향받는 파일)

| 테스트 파일 | 유형 | 영향 |
|------------|------|------|
| `PremiumServiceTest.kt` | Unit | `findAggregation` 시그니처/로직 변경 → **수정 필요** |
| `PremiumFacadeTest.kt` | Unit | Facade는 위임만 → 변경 없음 |
| `PremiumRepositoryImplTest.kt` | Unit | `findAggregation` cache fallback 추가 → **수정 필요** |
| `PremiumControllerTest.kt` | WebMvc | 응답 형식 `AggregationPage`로 변경 → **수정 필요** |
| `PremiumControllerE2ETest.kt` | Integration | 기존에 aggregation E2E 없음 → 추가 권장 |
| `PremiumAggregationSchedulerTest.kt` | Unit (batch) | dayJob writer 변경 → **수정 필요** |
| `PremiumAggregationE2ETest.kt` | Integration (batch) | AggregateDay Redis 저장 검증 추가 → **수정 필요** |

---

## 3-1. PremiumServiceTest — 범위 검증 + 시간 정규화 테스트

### 변경 파일: `apps/api/src/test/.../domain/premium/PremiumServiceTest.kt`

기존 `FindAggregation` nested class에 케이스 추가:

```kotlin
@Nested
inner class FindAggregation {
    // 기존 테스트 유지 (위임 확인)

    @Test
    fun `from이 최대 범위를 초과하면 clamp된다`() {
        // given
        val now = Instant.now()
        val from = now.minus(Duration.ofHours(48))  // 1m 최대 24시간 초과
        val to = now

        every { premiumRepository.findAggregation(any(), any(), any(), any()) } returns emptyList()

        // when
        premiumService.findAggregation(Symbol("BTC"), "1m", from, to)

        // then — repository에 전달된 from이 24시간 이내로 clamp됨
        verify {
            premiumRepository.findAggregation(
                Symbol("BTC"), "1m",
                match { it >= to.minus(Duration.ofHours(24)) },
                any(),
            )
        }
    }

    @Test
    fun `from과 to가 interval 단위로 정규화된다`() {
        // given
        val now = Instant.parse("2026-03-03T10:30:45Z")
        val from = now.minus(Duration.ofHours(1))

        every { premiumRepository.findAggregation(any(), any(), any(), any()) } returns emptyList()

        // when
        premiumService.findAggregation(Symbol("BTC"), "1h", from, now)

        // then — from은 시간 단위 truncate, to는 다음 시간
        verify {
            premiumRepository.findAggregation(
                Symbol("BTC"), "1h",
                Instant.parse("2026-03-03T09:00:00Z"),  // truncated
                Instant.parse("2026-03-03T11:00:00Z"),  // next hour
            )
        }
    }

    @Test
    fun `지원하지 않는 interval이면 IllegalArgumentException`() {
        assertThatThrownBy {
            premiumService.findAggregation(Symbol("BTC"), "5m", Instant.now(), Instant.now())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

---

## 3-2. PremiumAggregationCacheReaderTest — 신규 Unit 테스트

### 신규 파일: `apps/api/src/test/.../infrastructure/premium/PremiumAggregationCacheReaderTest.kt`

기존 `PremiumCacheReaderTest.kt`와 동일한 패턴 (mockk StringRedisTemplate):

```kotlin
class PremiumAggregationCacheReaderTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val zSetOps = mockk<ZSetOperations<String, String>>()
    private val cacheReader = PremiumAggregationCacheReader(redisTemplate)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForZSet() } returns zSetOps
    }

    @Nested
    inner class FindByInterval {

        @Test
        fun `캐시에 데이터가 있으면 PremiumAggregationSnapshot 리스트 반환`() {
            // given
            val from = Instant.parse("2026-03-03T10:00:00Z")
            val to = Instant.parse("2026-03-03T10:05:00Z")
            val score = Instant.parse("2026-03-03T10:01:00Z").toEpochMilli().toDouble()

            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns setOf(
                DefaultTypedTuple("1.50:1.00:1.20:1.30:1.25:10", score),
            )

            // when
            val result = cacheReader.findByInterval("btc", "1m", from, to)

            // then
            assertThat(result).hasSize(1)
            assertThat(result!![0].high).isEqualByComparingTo("1.50")
            assertThat(result[0].low).isEqualByComparingTo("1.00")
            assertThat(result[0].open).isEqualByComparingTo("1.20")
            assertThat(result[0].close).isEqualByComparingTo("1.30")
            assertThat(result[0].count).isEqualTo(10)
            assertThat(result[0].symbol).isEqualTo("BTC")
        }

        @Test
        fun `캐시가 비어있으면 null 반환`() {
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            val result = cacheReader.findByInterval("btc", "1m", Instant.now(), Instant.now())

            assertThat(result).isNull()
        }

        @Test
        fun `잘못된 형식의 entry는 skip`() {
            val score = Instant.now().toEpochMilli().toDouble()
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns setOf(
                DefaultTypedTuple("invalid:data", score),        // parts < 6
                DefaultTypedTuple("1.5:1.0:1.2:1.3:1.25:10", score),  // valid
            )

            val result = cacheReader.findByInterval("btc", "1h", Instant.now().minusSeconds(3600), Instant.now())

            assertThat(result).hasSize(1)
        }

        @Test
        fun `지원하지 않는 interval이면 null 반환`() {
            val result = cacheReader.findByInterval("btc", "5m", Instant.now(), Instant.now())

            assertThat(result).isNull()
        }

        @Test
        fun `1d interval은 DAYS 키를 사용한다`() {
            every { zSetOps.rangeByScoreWithScores(any(), any(), any()) } returns emptySet()

            cacheReader.findByInterval("btc", "1d", Instant.now().minusSeconds(86400), Instant.now())

            verify { zSetOps.rangeByScoreWithScores("premium:days:btc", any(), any()) }
        }
    }
}
```

---

## 3-3. PremiumRepositoryImplTest — Cache→DB Fallback 테스트

### 변경 파일: `apps/api/src/test/.../infrastructure/premium/PremiumRepositoryImplTest.kt`

기존 `FindAggregation` nested class 수정:

```kotlin
@Nested
inner class FindAggregation {

    @Test
    fun `캐시 hit 시 DB를 조회하지 않는다`() {
        // given
        val snapshot = PremiumAggregationSnapshot(
            symbol = "BTC", high = "1.5".toBigDecimal(), low = "1.0".toBigDecimal(),
            open = "1.2".toBigDecimal(), close = "1.3".toBigDecimal(),
            avg = "1.25".toBigDecimal(), count = 10,
            observedAt = Instant.now(),
        )
        every { premiumAggregationCacheReader.findByInterval(any(), any(), any(), any()) } returns listOf(snapshot)

        // when
        val result = premiumRepositoryImpl.findAggregation(Symbol("BTC"), "1m", Instant.now(), Instant.now())

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].high).isEqualByComparingTo("1.5")
        verify(exactly = 0) { premiumAggregationQueryRepository.findByInterval(any(), any(), any(), any()) }
    }

    @Test
    fun `캐시 miss 시 DB fallback`() {
        // given
        every { premiumAggregationCacheReader.findByInterval(any(), any(), any(), any()) } returns null
        every { premiumAggregationQueryRepository.findByInterval(any(), any(), any(), any()) } returns emptyList()

        // when
        val result = premiumRepositoryImpl.findAggregation(Symbol("BTC"), "1h", Instant.now(), Instant.now())

        // then
        assertThat(result).isEmpty()
        verify(exactly = 1) { premiumAggregationQueryRepository.findByInterval(any(), any(), any(), any()) }
    }
}
```

---

## 3-4. PremiumControllerTest — 응답 형식 변경

### 변경 파일: `apps/api/src/test/.../interfaces/api/premium/PremiumControllerTest.kt`

기존 `GetAggregation` nested class 수정 — 응답이 `{ data: [...], hasMore: true }` 형태로 변경:

```kotlin
@Nested
inner class GetAggregation {

    @Test
    fun `집계 데이터를 AggregationPage로 반환한다`() {
        // given
        every { premiumFacade.findAggregation(any(), any(), any(), any()) } returns listOf(aggregationResult)

        // when & then
        mockMvc.perform(get("/api/v1/premiums/aggregation/BTC")
            .param("interval", "1m")
            .param("from", Instant.now().minus(Duration.ofHours(1)).toString())
            .param("to", Instant.now().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].symbol").value("BTC"))
            .andExpect(jsonPath("$.data[0].high").isNumber)
            .andExpect(jsonPath("$.hasMore").isBoolean)
    }

    @Test
    fun `from이 최대 범위 내이면 hasMore는 true`() {
        every { premiumFacade.findAggregation(any(), any(), any(), any()) } returns emptyList()
        val now = Instant.now()

        mockMvc.perform(get("/api/v1/premiums/aggregation/BTC")
            .param("interval", "1m")
            .param("from", now.minus(Duration.ofHours(12)).toString())  // 24h 이내
            .param("to", now.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasMore").value(true))
    }

    @Test
    fun `from이 최대 범위에 도달하면 hasMore는 false`() {
        every { premiumFacade.findAggregation(any(), any(), any(), any()) } returns emptyList()
        val now = Instant.now()

        mockMvc.perform(get("/api/v1/premiums/aggregation/BTC")
            .param("interval", "1m")
            .param("from", now.minus(Duration.ofHours(48)).toString())  // 24h 초과
            .param("to", now.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasMore").value(false))
    }
}
```

---

## 3-5. PremiumAggregationSchedulerTest (batch) — dayJob Redis 저장 검증

### 변경 파일: `apps/batch/src/test/.../scheduler/PremiumAggregationSchedulerTest.kt`

기존 `AggregateDay` nested class 수정:

```kotlin
@Nested
inner class AggregateDay {

    @Test
    fun `일별 집계 시 Redis와 DB 모두 저장한다`() {
        // given
        val agg = PremiumAggregation(symbol = "btc", high = ..., ...)
        every { premiumCacheService.aggregateData(AggregationTimeUnit.HOURS, any(), any(), any()) } returns agg
        every { premiumCacheService.saveAggregation(any(), any(), any(), any()) } just Runs
        every { aggregationRepository.saveDay(any(), any(), any()) } just Runs

        // when
        scheduler.aggregateDay()

        // then
        verify { premiumCacheService.saveAggregation(AggregationTimeUnit.DAYS, "btc", any(), agg) }  // Redis
        verify { aggregationRepository.saveDay("btc", any(), agg) }  // DB
    }
}
```

---

## 3-6. PremiumAggregationE2ETest (batch) — dayJob Redis 저장 E2E

### 변경 파일: `apps/batch/src/test/.../scheduler/PremiumAggregationE2ETest.kt`

기존 `AggregateDay` nested class에 Redis 검증 추가:

```kotlin
@Nested
inner class AggregateDay {

    @Test
    fun `일별 집계 데이터가 DB와 Redis에 저장된다`() {
        // given
        seedHoursData(...)

        // when
        scheduler.aggregateDay()

        // then — DB 검증 (기존)
        val saved = aggregationRepository.findLatestDay("btc")
        assertThat(saved).isNotNull
        assertThat(saved!!.count).isEqualTo(3)

        // then — Redis 검증 (신규)
        val redisKey = AggregationTimeUnit.DAYS.keyFor("btc")
        val entries = redisTemplate.opsForZSet().rangeByScore(redisKey, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)
        assertThat(entries).isNotEmpty
    }
}
```

---

## 테스트 요약

| 테스트 파일 | 변경 유형 | 검증 내용 |
|------------|----------|----------|
| `PremiumServiceTest` | 수정 | from clamp, 시간 정규화, 잘못된 interval 예외 |
| `PremiumAggregationCacheReaderTest` | **신규** | cache hit/miss, 잘못된 entry skip, interval→키 매핑 |
| `PremiumRepositoryImplTest` | 수정 | cache hit → DB 안 탐, cache miss → DB fallback |
| `PremiumControllerTest` | 수정 | `AggregationPage` 응답 형식, `hasMore` true/false |
| `PremiumAggregationSchedulerTest` | 수정 | dayJob Redis 저장 verify 추가 |
| `PremiumAggregationE2ETest` | 수정 | dayJob Redis 저장 E2E 검증 |

---

# Part 4. 수행 순서

```
Phase 1. 백엔드 인프라 (batch/redis 모듈)
├── 1-1. RedisTtl + AggregationTimeUnit + RedisKeyGenerator 수정
├── 1-2. dayJob writer에 Redis 저장 추가
├── 3-5. PremiumAggregationSchedulerTest 수정 (dayJob Redis verify)
├── 3-6. PremiumAggregationE2ETest 수정 (dayJob Redis E2E)
├── 컴파일 확인: ./gradlew :apps:batch:compileKotlin
└── 테스트 확인: ./gradlew :apps:batch:test

Phase 2. 백엔드 API
├── 1-3. PremiumAggregationCacheReader 생성 + PremiumRepositoryImpl 수정
├── 3-2. PremiumAggregationCacheReaderTest 신규
├── 3-3. PremiumRepositoryImplTest 수정 (cache fallback)
├── 1-4. PremiumService에 최대 범위 검증 + 시간 정규화
├── 3-1. PremiumServiceTest 수정 (clamp, 정규화, 예외)
├── 1-5. 응답 DTO에 AggregationPage wrapper 추가 + Controller 수정
├── 3-4. PremiumControllerTest 수정 (AggregationPage, hasMore)
├── 컴파일 확인: ./gradlew :apps:api:compileKotlin
└── 테스트 확인: ./gradlew :apps:api:test

Phase 3. 프론트엔드
├── 2-1. INTERVALS 상수 + AggregationResponse 타입 변경
├── 2-2. 상태 구조 변경 (allDataRef, loadedFromRef, hasMore)
├── 2-3. fetchLatest (폴링) 수정
├── 2-4. fetchPast (과거 로드) 신규
├── 2-5. subscribeVisibleLogicalRangeChange 연동
└── 2-6. 인터벌 전환 초기화
```

---

# 변경 파일 요약

| 영역 | 파일 | 변경 유형 |
|------|------|----------|
| redis 모듈 | `RedisTtl.kt` | 수정 (TTL 추가) |
| redis 모듈 | `AggregationTimeUnit.kt` | 수정 (DAYS 추가) |
| redis 모듈 | `RedisKeyGenerator.kt` | 수정 (premiumDaysKey 추가) |
| batch | `PremiumAggregationScheduler.kt` | 수정 (dayJob writer) |
| api infra | `PremiumAggregationCacheReader.kt` | **신규** |
| api infra | `PremiumRepositoryImpl.kt` | 수정 (cache fallback) |
| api domain | `PremiumService.kt` | 수정 (범위 검증 + 정규화) |
| api interfaces | `PremiumDtos.kt` (Response) | 수정 (AggregationPage) |
| api interfaces | `PremiumController.kt` | 수정 (hasMore 계산) |
| frontend | `PremiumChart.tsx` | 수정 (무한스크롤) |

## 테스트 파일 요약

| 영역 | 파일 | 변경 유형 |
|------|------|----------|
| api test | `PremiumServiceTest.kt` | 수정 (clamp, 정규화, 예외) |
| api test | `PremiumAggregationCacheReaderTest.kt` | **신규** (cache hit/miss, skip, 키 매핑) |
| api test | `PremiumRepositoryImplTest.kt` | 수정 (cache→DB fallback) |
| api test | `PremiumControllerTest.kt` | 수정 (AggregationPage, hasMore) |
| batch test | `PremiumAggregationSchedulerTest.kt` | 수정 (dayJob Redis verify) |
| batch test | `PremiumAggregationE2ETest.kt` | 수정 (dayJob Redis E2E) |
