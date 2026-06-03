# Flip History Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a resizable pop-out JFrame that shows an overview dashboard (aggregate stats + cumulative profit chart + per-item table) and a per-item drill-down (Wiki avgHigh/avgLow price lines with the player's buy/sell markers overlaid).

**Architecture:** Custom Java2D `JComponent` (`PriceChartComponent`) renders from a plain `ChartModel` data holder; a `HistoryDataService` singleton fetches and caches Wiki `/timeseries` data and loads flip records from disk; two view panels (`OverviewDashboardPanel`, `ItemDetailPanel`) build `ChartModel` objects and pass them to the renderer; a `FlipHistoryWindow` JFrame holds both views in a `CardLayout`; pure analytics (`FlipAnalytics`) and coordinate math (`ChartScale`) are independently unit-tested.

**Tech Stack:** Java 11, RuneLite 1.12.27, Lombok @Value/@Builder, Gson (transitive), OkHttp3 (transitive), JUnit 4.12, Guice (RuneLite's DI), custom Java2D — no new dependencies.

---

## File Map

**New files:**
```
src/main/java/com/exchangelens/tracker/FlipAnalytics.java
src/main/java/com/exchangelens/ui/ChartScale.java
src/main/java/com/exchangelens/ui/ChartModel.java
src/main/java/com/exchangelens/ui/PriceChartComponent.java
src/main/java/com/exchangelens/service/HistoryDataService.java
src/main/java/com/exchangelens/ui/OverviewDashboardPanel.java
src/main/java/com/exchangelens/ui/ItemDetailPanel.java
src/main/java/com/exchangelens/ui/FlipHistoryWindow.java
src/test/java/com/exchangelens/TimeseriesParsingTest.java
src/test/java/com/exchangelens/FlipAnalyticsTest.java
src/test/java/com/exchangelens/ChartScaleTest.java
```

**Modified files:**
```
src/main/java/com/exchangelens/api/WikiApiModels.java        (+ TimeseriesResponse, TimeseriesPoint)
src/main/java/com/exchangelens/api/WikiPriceClient.java      (+ fetchTimeseries)
src/main/java/com/exchangelens/ui/ExchangeLensPanel.java     (+ openHistoryChart button + setter)
src/main/java/com/exchangelens/ExchangeLensPlugin.java       (+ Provider<FlipHistoryWindow>, lazy open, dispose)
```

---

## Task 1: Timeseries models + parsing test

**Files:**
- Modify: `src/main/java/com/exchangelens/api/WikiApiModels.java`
- Create: `src/test/java/com/exchangelens/TimeseriesParsingTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/exchangelens/TimeseriesParsingTest.java`:

```java
package com.exchangelens;

import com.exchangelens.api.WikiApiModels;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.*;

public class TimeseriesParsingTest
{
    private final Gson gson = new Gson();

    @Test
    public void parsesTimeseriesResponse()
    {
        String json = "{\"data\":[{\"timestamp\":1748908800,\"avgHighPrice\":1234,"
            + "\"avgLowPrice\":1200,\"highPriceVolume\":500,\"lowPriceVolume\":300}]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);

        assertNotNull(resp.data);
        assertEquals(1, resp.data.size());
        WikiApiModels.TimeseriesPoint pt = resp.data.get(0);
        assertEquals(1748908800L, pt.timestamp);
        assertEquals(Integer.valueOf(1234), pt.avgHighPrice);
        assertEquals(Integer.valueOf(1200), pt.avgLowPrice);
        assertEquals(Integer.valueOf(500),  pt.highPriceVolume);
        assertEquals(Integer.valueOf(300),  pt.lowPriceVolume);
    }

    @Test
    public void nullPriceFieldsParsedAsNull()
    {
        String json = "{\"data\":[{\"timestamp\":1748908800,\"avgHighPrice\":null,"
            + "\"avgLowPrice\":null,\"highPriceVolume\":null,\"lowPriceVolume\":null}]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);

        WikiApiModels.TimeseriesPoint pt = resp.data.get(0);
        assertNull(pt.avgHighPrice);
        assertNull(pt.avgLowPrice);
        assertNull(pt.highPriceVolume);
        assertNull(pt.lowPriceVolume);
    }

    @Test
    public void parsesMultiplePoints()
    {
        String json = "{\"data\":["
            + "{\"timestamp\":1000,\"avgHighPrice\":100,\"avgLowPrice\":90,"
            + "\"highPriceVolume\":10,\"lowPriceVolume\":5},"
            + "{\"timestamp\":2000,\"avgHighPrice\":110,\"avgLowPrice\":95,"
            + "\"highPriceVolume\":20,\"lowPriceVolume\":8}"
            + "]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);
        assertEquals(2, resp.data.size());
        assertEquals(1000L, resp.data.get(0).timestamp);
        assertEquals(2000L, resp.data.get(1).timestamp);
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```
.\gradlew.bat test --tests "com.exchangelens.TimeseriesParsingTest"
```

Expected: compile error — `WikiApiModels.TimeseriesResponse` does not exist.

- [ ] **Step 3: Add models to WikiApiModels.java**

Open `src/main/java/com/exchangelens/api/WikiApiModels.java`. Add these two classes after the `MappingEntry` class, before the closing `}` of `WikiApiModels`:

```java
    public static class TimeseriesResponse
    {
        public java.util.List<TimeseriesPoint> data;
    }

    public static class TimeseriesPoint
    {
        public long    timestamp;
        public Integer avgHighPrice;    // boxed — null when no trades in bucket
        public Integer avgLowPrice;
        public Integer highPriceVolume;
        public Integer lowPriceVolume;
    }
```

- [ ] **Step 4: Run test — expect pass**

```
.\gradlew.bat test --tests "com.exchangelens.TimeseriesParsingTest"
```

Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/exchangelens/api/WikiApiModels.java
git add src/test/java/com/exchangelens/TimeseriesParsingTest.java
git commit -m "feat: add WikiApiModels.TimeseriesResponse + TimeseriesPoint with parsing tests"
```

---

## Task 2: fetchTimeseries in WikiPriceClient

**Files:**
- Modify: `src/main/java/com/exchangelens/api/WikiPriceClient.java`

No new unit test — network calls follow the same pattern as existing `fetchLatest`/`fetchFiveMinute`; parsing is covered by Task 1's test.

- [ ] **Step 1: Add fetchTimeseries method**

Open `src/main/java/com/exchangelens/api/WikiPriceClient.java`. Add this method after `fetchMapping()` and before the private helpers:

```java
    public WikiApiModels.TimeseriesResponse fetchTimeseries(int itemId, String timestep)
    {
        String url = BASE_URL + "timeseries?timestep=" + timestep + "&id=" + itemId;
        return fetch(url, WikiApiModels.TimeseriesResponse.class);
    }
```

- [ ] **Step 2: Run all existing tests — confirm nothing broke**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL, all prior tests still pass.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/api/WikiPriceClient.java
git commit -m "feat: add WikiPriceClient.fetchTimeseries"
```

---

## Task 3: FlipAnalytics + unit tests

**Files:**
- Create: `src/main/java/com/exchangelens/tracker/FlipAnalytics.java`
- Create: `src/test/java/com/exchangelens/FlipAnalyticsTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/exchangelens/FlipAnalyticsTest.java`:

```java
package com.exchangelens;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.OfferSnapshot;
import com.exchangelens.tracker.FlipAnalytics;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FlipAnalyticsTest
{
    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FlipRecord complete(int itemId, String name,
                                       long profit, double roi, long completedAt)
    {
        OfferSnapshot sell = OfferSnapshot.builder()
            .slot(0).itemId(itemId).itemName(name)
            .isBuy(false).postedPrice(0).quantity(1)
            .postedAt(completedAt - 10).completedAt(completedAt).totalSpent(0)
            .build();
        return FlipRecord.builder()
            .id("t").itemId(itemId).itemName(name).quantity(1)
            .buyPrice(1000).sellPrice(1000 + (int) profit)
            .totalNetProfit(profit).netProfitPerItem((int) profit)
            .roi(roi).tax(50L)
            .buyFillSeconds(10).sellFillSeconds(10).totalFlipSeconds(20)
            .startedAt(completedAt - 20).completedAt(completedAt)
            .accountName("acct").suggestionSource("manual")
            .sellOffer(sell)
            .build();
    }

    private static FlipRecord incomplete(int itemId, String name)
    {
        return FlipRecord.builder()
            .id("inc").itemId(itemId).itemName(name).quantity(1)
            .buyPrice(1000).totalNetProfit(9999L).roi(0.99)
            .build();
    }

    // ── totalProfit ───────────────────────────────────────────────────────────

    @Test
    public void totalProfitEmptyListIsZero()
    {
        assertEquals(0L, FlipAnalytics.totalProfit(Collections.emptyList()));
    }

    @Test
    public void totalProfitSumsCompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.1, 100),
            complete(4151, "Whip", 2000L, 0.2, 200));
        assertEquals(3000L, FlipAnalytics.totalProfit(flips));
    }

    @Test
    public void totalProfitSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.1, 100),
            incomplete(4151, "Whip"));
        assertEquals(1000L, FlipAnalytics.totalProfit(flips));
    }

    // ── totalTax ──────────────────────────────────────────────────────────────

    @Test
    public void totalTaxSumsCompleteFlips()
    {
        // complete() helper sets tax = 50L per flip
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 100L, 0.1, 100),
            complete(2, "B", 200L, 0.2, 200));
        assertEquals(100L, FlipAnalytics.totalTax(flips));
    }

    @Test
    public void totalTaxSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 100L, 0.1, 100),
            incomplete(1, "A"));
        assertEquals(50L, FlipAnalytics.totalTax(flips));
    }

    // ── winRate ───────────────────────────────────────────────────────────────

    @Test
    public void winRateEmptyIsZero()
    {
        assertEquals(0.0, FlipAnalytics.winRate(Collections.emptyList()), 0.001);
    }

    @Test
    public void winRateAllProfitableIs100()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 100),
            complete(2, "B", 200L, 0.05, 200));
        assertEquals(100.0, FlipAnalytics.winRate(flips), 0.001);
    }

    @Test
    public void winRateHalfProfitableIs50()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A",  500L, 0.1, 100),
            complete(1, "A", -100L, -0.1, 200));
        assertEquals(50.0, FlipAnalytics.winRate(flips), 0.001);
    }

    @Test
    public void winRateIgnoresIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 100),
            incomplete(1, "A"));  // not counted
        assertEquals(100.0, FlipAnalytics.winRate(flips), 0.001);
    }

    // ── bestFlip / worstFlip ──────────────────────────────────────────────────

    @Test
    public void bestFlipReturnsHighestProfit()
    {
        FlipRecord best = complete(1, "A", 9000L, 0.9, 300);
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            best,
            complete(1, "A", 5000L, 0.5, 200));
        assertEquals(best, FlipAnalytics.bestFlip(flips));
    }

    @Test
    public void worstFlipReturnsLowestProfit()
    {
        FlipRecord worst = complete(1, "A", -500L, -0.05, 100);
        List<FlipRecord> flips = Arrays.asList(
            worst,
            complete(1, "A", 2000L, 0.2, 200));
        assertEquals(worst, FlipAnalytics.worstFlip(flips));
    }

    @Test
    public void bestFlipNullOnEmptyList()
    {
        assertNull(FlipAnalytics.bestFlip(Collections.emptyList()));
    }

    // ── buildCumulativeSeries ─────────────────────────────────────────────────

    @Test
    public void cumulativeSeriesEmptyOnEmptyList()
    {
        assertEquals(0, FlipAnalytics.buildCumulativeSeries(Collections.emptyList()).length);
    }

    @Test
    public void cumulativeSeriesAccumulatesInOrder()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            complete(1, "A", 2000L, 0.2, 200));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(2,     series.length);
        assertEquals(100L,  series[0][0]);  // timestamp
        assertEquals(1000L, series[0][1]);  // cumulative profit
        assertEquals(200L,  series[1][0]);
        assertEquals(3000L, series[1][1]);
    }

    @Test
    public void cumulativeSeriesSortsByTimestamp()
    {
        // Inserted in reverse order — series should be sorted ascending by completedAt
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 300),
            complete(1, "A", 200L, 0.1, 100),
            complete(1, "A", 300L, 0.1, 200));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(100L, series[0][0]);
        assertEquals(200L, series[0][1]);
        assertEquals(200L, series[1][0]);
        assertEquals(500L, series[1][1]);
        assertEquals(300L, series[2][0]);
        assertEquals(1000L, series[2][1]);
    }

    @Test
    public void cumulativeSeriesSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            incomplete(1, "A"));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(1, series.length);
        assertEquals(1000L, series[0][1]);
    }

    // ── perItemSummary ────────────────────────────────────────────────────────

    @Test
    public void perItemSummaryGroupsByItemId()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip",    1000L, 0.1, 100),
            complete(4151, "Whip",    2000L, 0.2, 200),
            complete(1234, "Rune bar", 500L, 0.05, 300));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(2, summaries.size());
    }

    @Test
    public void perItemSummarySortedByProfitDescending()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "Low",  500L, 0.05, 100),
            complete(2, "High", 5000L, 0.5, 200));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(2,    summaries.get(0).itemId);
        assertEquals(5000L, summaries.get(0).totalProfit);
    }

    @Test
    public void perItemSummaryAggregatesCorrectly()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.10, 100),
            complete(4151, "Whip", 3000L, 0.30, 200));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(1,     summaries.size());
        FlipAnalytics.ItemSummary s = summaries.get(0);
        assertEquals(4151,  s.itemId);
        assertEquals("Whip", s.itemName);
        assertEquals(2,     s.flipCount);
        assertEquals(4000L, s.totalProfit);
        assertEquals(0.20,  s.avgRoi, 0.001);
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```
.\gradlew.bat test --tests "com.exchangelens.FlipAnalyticsTest"
```

Expected: compile error — `FlipAnalytics` does not exist.

- [ ] **Step 3: Write FlipAnalytics.java**

Create `src/main/java/com/exchangelens/tracker/FlipAnalytics.java`:

```java
package com.exchangelens.tracker;

import com.exchangelens.model.FlipRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FlipAnalytics
{
    private FlipAnalytics() {}

    public static long totalProfit(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .mapToLong(FlipRecord::getTotalNetProfit)
            .sum();
    }

    public static long totalTax(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .mapToLong(FlipRecord::getTax)
            .sum();
    }

    public static double winRate(List<FlipRecord> flips)
    {
        List<FlipRecord> complete = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .collect(Collectors.toList());
        if (complete.isEmpty()) return 0.0;
        long profitable = complete.stream()
            .filter(f -> f.getTotalNetProfit() > 0)
            .count();
        return (double) profitable / complete.size() * 100.0;
    }

    public static FlipRecord bestFlip(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .max(Comparator.comparingLong(FlipRecord::getTotalNetProfit))
            .orElse(null);
    }

    public static FlipRecord worstFlip(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .min(Comparator.comparingLong(FlipRecord::getTotalNetProfit))
            .orElse(null);
    }

    /**
     * Returns [timestamp (epoch s), cumulativeProfit] pairs, sorted ascending by timestamp.
     * Only includes completed flips (sellOffer != null).
     */
    public static long[][] buildCumulativeSeries(List<FlipRecord> flips)
    {
        List<FlipRecord> sorted = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .sorted(Comparator.comparingLong(FlipRecord::getCompletedAt))
            .collect(Collectors.toList());
        long[][] series = new long[sorted.size()][2];
        long running = 0;
        for (int i = 0; i < sorted.size(); i++)
        {
            running += sorted.get(i).getTotalNetProfit();
            series[i][0] = sorted.get(i).getCompletedAt();
            series[i][1] = running;
        }
        return series;
    }

    public static List<ItemSummary> perItemSummary(List<FlipRecord> flips)
    {
        Map<Integer, List<FlipRecord>> byItem = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .collect(Collectors.groupingBy(FlipRecord::getItemId));

        return byItem.entrySet().stream()
            .map(e ->
            {
                List<FlipRecord> g     = e.getValue();
                long  profit  = g.stream().mapToLong(FlipRecord::getTotalNetProfit).sum();
                double avgRoi = g.stream().mapToDouble(FlipRecord::getRoi).average().orElse(0.0);
                double avgFill = g.stream().mapToLong(FlipRecord::getTotalFlipSeconds).average().orElse(0.0);
                return new ItemSummary(e.getKey(), g.get(0).getItemName(),
                    g.size(), profit, avgRoi, avgFill);
            })
            .sorted(Comparator.comparingLong((ItemSummary s) -> s.totalProfit).reversed())
            .collect(Collectors.toList());
    }

    public static class ItemSummary
    {
        public final int    itemId;
        public final String itemName;
        public final int    flipCount;
        public final long   totalProfit;
        public final double avgRoi;
        public final double avgFillSeconds;

        public ItemSummary(int itemId, String itemName, int flipCount,
                           long totalProfit, double avgRoi, double avgFillSeconds)
        {
            this.itemId       = itemId;
            this.itemName     = itemName;
            this.flipCount    = flipCount;
            this.totalProfit  = totalProfit;
            this.avgRoi       = avgRoi;
            this.avgFillSeconds = avgFillSeconds;
        }
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```
.\gradlew.bat test --tests "com.exchangelens.FlipAnalyticsTest"
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run full suite — confirm no regressions**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add src/main/java/com/exchangelens/tracker/FlipAnalytics.java
git add src/test/java/com/exchangelens/FlipAnalyticsTest.java
git commit -m "feat: add FlipAnalytics pure functions with full unit tests"
```

---

## Task 4: ChartScale + unit tests

**Files:**
- Create: `src/main/java/com/exchangelens/ui/ChartScale.java`
- Create: `src/test/java/com/exchangelens/ChartScaleTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/exchangelens/ChartScaleTest.java`:

```java
package com.exchangelens;

import com.exchangelens.ui.ChartScale;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChartScaleTest
{
    // ── timeToPixelX ─────────────────────────────────────────────────────────

    @Test
    public void timeToPixelX_minTimeMapsToLeft()
    {
        assertEquals(50, ChartScale.timeToPixelX(100L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_maxTimeMapsToRight()
    {
        assertEquals(250, ChartScale.timeToPixelX(200L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_midpointMapsToCenter()
    {
        assertEquals(150, ChartScale.timeToPixelX(150L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_equalMinMaxReturnsLeft()
    {
        assertEquals(50, ChartScale.timeToPixelX(100L, 100L, 100L, 50, 250));
    }

    // ── valueToPixelY ─────────────────────────────────────────────────────────

    @Test
    public void valueToPixelY_maxValueMapsToTop()
    {
        // Screen Y is inverted: higher value → smaller Y
        assertEquals(10, ChartScale.valueToPixelY(200.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_minValueMapsToBottom()
    {
        assertEquals(210, ChartScale.valueToPixelY(0.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_midpointMapsToCenter()
    {
        assertEquals(110, ChartScale.valueToPixelY(100.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_equalMinMaxReturnsCenter()
    {
        assertEquals(110, ChartScale.valueToPixelY(100.0, 100.0, 100.0, 10, 210));
    }

    // ── autoScaleBounds ───────────────────────────────────────────────────────

    @Test
    public void autoScaleBounds_padsAboveAndBelow()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{100.0, 200.0}, 0.1);
        assertTrue("min should be padded below 100", bounds[0] < 100.0);
        assertTrue("max should be padded above 200", bounds[1] > 200.0);
    }

    @Test
    public void autoScaleBounds_emptyArrayReturnsUnitRange()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{}, 0.1);
        assertEquals(2, bounds.length);
        assertTrue(bounds[1] > bounds[0]);
    }

    @Test
    public void autoScaleBounds_singleValueHasRange()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{1000.0}, 0.1);
        assertTrue(bounds[1] > bounds[0]);
    }

    @Test
    public void autoScaleBounds_zeroPadReturnsTightBounds()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{100.0, 200.0}, 0.0);
        assertEquals(100.0, bounds[0], 0.001);
        assertEquals(200.0, bounds[1], 0.001);
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```
.\gradlew.bat test --tests "com.exchangelens.ChartScaleTest"
```

Expected: compile error — `ChartScale` does not exist.

- [ ] **Step 3: Write ChartScale.java**

Create `src/main/java/com/exchangelens/ui/ChartScale.java`:

```java
package com.exchangelens.ui;

public final class ChartScale
{
    private ChartScale() {}

    /**
     * Maps a timestamp (epoch seconds) to a pixel x-coordinate within [left, right].
     */
    public static int timeToPixelX(long timestamp, long minTime, long maxTime, int left, int right)
    {
        if (maxTime == minTime) return left;
        double ratio = (double) (timestamp - minTime) / (maxTime - minTime);
        return left + (int) Math.round(ratio * (right - left));
    }

    /**
     * Maps a value to a pixel y-coordinate within [top, bottom].
     * Screen Y is inverted: higher value → smaller Y (closer to top).
     */
    public static int valueToPixelY(double value, double minVal, double maxVal, int top, int bottom)
    {
        if (maxVal == minVal) return (top + bottom) / 2;
        double ratio = (value - minVal) / (maxVal - minVal);
        return bottom - (int) Math.round(ratio * (bottom - top));
    }

    /**
     * Returns [min, max] with symmetric padding applied as a fraction of the range.
     * If values is empty, returns [0.0, 1.0]. If all values are equal, uses the value
     * itself as the range base to avoid zero-range.
     */
    public static double[] autoScaleBounds(double[] values, double padFraction)
    {
        if (values.length == 0) return new double[]{0.0, 1.0};
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : values)
        {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range == 0) range = Math.abs(min) > 0 ? Math.abs(min) : 1.0;
        double pad = range * padFraction;
        return new double[]{min - pad, max + pad};
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```
.\gradlew.bat test --tests "com.exchangelens.ChartScaleTest"
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run full suite**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add src/main/java/com/exchangelens/ui/ChartScale.java
git add src/test/java/com/exchangelens/ChartScaleTest.java
git commit -m "feat: add ChartScale coordinate-math helpers with unit tests"
```

---

## Task 5: ChartModel data holder

**Files:**
- Create: `src/main/java/com/exchangelens/ui/ChartModel.java`

No unit test — pure data holder with no logic.

- [ ] **Step 1: Write ChartModel.java**

Create `src/main/java/com/exchangelens/ui/ChartModel.java`:

```java
package com.exchangelens.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holder passed to {@link PriceChartComponent}.
 * Panels build a ChartModel; the component renders it. No business logic here.
 */
public class ChartModel
{
    /** A single line series — timestamped (x, value) pairs. */
    public static class Series
    {
        public String  label;
        public long[]  timestamps;   // epoch seconds, parallel with values[]
        public double[] values;      // 0.0 treated as "no data" — skip this point
        public Color   color;
        public float   strokeWidth = 1.5f;
    }

    /** A single trade marker rendered as a triangle (▲ buy, ▼ sell). */
    public static class Marker
    {
        public long   timestamp;   // epoch seconds
        public double value;
        public Color  color;
        public boolean isUp;       // true = buy (▲), false = sell (▼)
        public String tooltip;
    }

    /** Connects a buy marker to its matched sell with a faint dashed line. */
    public static class Connection
    {
        public int buyMarkerIndex;
        public int sellMarkerIndex;
    }

    public List<Series>     series      = new ArrayList<>();
    public List<Marker>     markers     = new ArrayList<>();
    public List<Connection> connections = new ArrayList<>();

    /** Visible time window (epoch seconds). */
    public long xMin;
    public long xMax;

    /**
     * Value range. If both are 0.0, {@link PriceChartComponent} auto-scales
     * from all series values and marker values.
     */
    public double yMin;
    public double yMax;

    /** Optional volume bars — same length as the first series, or null. */
    public long[]   volumeTimestamps;
    public double[] highVolumes;
    public double[] lowVolumes;
}
```

- [ ] **Step 2: Run full suite — confirm it compiles**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/ui/ChartModel.java
git commit -m "feat: add ChartModel data holder"
```

---

## Task 6: PriceChartComponent (Java2D renderer)

**Files:**
- Create: `src/main/java/com/exchangelens/ui/PriceChartComponent.java`

No automated test — rendering is verified manually via the full window in Task 11.

- [ ] **Step 1: Write PriceChartComponent.java**

Create `src/main/java/com/exchangelens/ui/PriceChartComponent.java`:

```java
package com.exchangelens.ui;

import com.exchangelens.service.PriceFormat;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Java2D chart renderer. Accepts a {@link ChartModel}; panels set one
 * to trigger a repaint. No data fetching inside this class.
 */
public class PriceChartComponent extends JComponent
{
    private static final int MARGIN_LEFT   = 72;
    private static final int MARGIN_RIGHT  = 12;
    private static final int MARGIN_TOP    = 8;
    private static final int MARGIN_BOTTOM = 36;
    private static final int MARKER_HALF   = 6;  // half-size of marker triangle

    private static final Color BG         = new Color(0x1C1C1C);
    private static final Color GRID       = new Color(0x2A2A2A);
    private static final Color AXIS_LABEL = new Color(0x707070);
    private static final Color CROSSHAIR  = new Color(0x505050);
    private static final Color TOOLTIP_BG = new Color(0x2C2C2C);

    private static final DateTimeFormatter HOUR_FMT =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("MM/dd").withZone(ZoneId.systemDefault());

    private ChartModel model;
    private String     noDataMessage;
    private int        mouseX = -1, mouseY = -1;
    private String     hoveredTooltip;

    // Cached y-bounds from last paint (needed for hover hit-testing)
    private double paintYMin, paintYMax;

    public PriceChartComponent()
    {
        setOpaque(true);
        addMouseMotionListener(new MouseAdapter()
        {
            @Override public void mouseMoved(MouseEvent e)
            {
                mouseX = e.getX();
                mouseY = e.getY();
                updateHover();
                repaint();
            }
        });
        addMouseListener(new MouseAdapter()
        {
            @Override public void mouseExited(MouseEvent e)
            {
                mouseX = -1;
                mouseY = -1;
                hoveredTooltip = null;
                repaint();
            }
        });
    }

    public void setModel(ChartModel model)
    {
        this.model         = model;
        this.noDataMessage = null;
        repaint();
    }

    public void setNoDataMessage(String msg)
    {
        this.model         = null;
        this.noDataMessage = msg;
        repaint();
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(BG);
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (noDataMessage != null)
            {
                drawCentered(g2, noDataMessage, AXIS_LABEL);
                return;
            }
            if (model == null
                || (model.series.isEmpty() && model.markers.isEmpty()))
            {
                drawCentered(g2, "No data", AXIS_LABEL);
                return;
            }

            int left   = MARGIN_LEFT;
            int right  = getWidth()  - MARGIN_RIGHT;
            int top    = MARGIN_TOP;
            int bottom = getHeight() - MARGIN_BOTTOM;
            if (right <= left || bottom <= top) return;

            double yMin = model.yMin, yMax = model.yMax;
            if (yMin == 0 && yMax == 0)
            {
                List<Double> all = new ArrayList<>();
                for (ChartModel.Series s : model.series)
                    for (double v : s.values) if (v > 0) all.add(v);
                for (ChartModel.Marker m : model.markers) all.add(m.value);
                double[] b = ChartScale.autoScaleBounds(
                    all.stream().mapToDouble(Double::doubleValue).toArray(), 0.08);
                yMin = b[0]; yMax = b[1];
            }
            paintYMin = yMin; paintYMax = yMax;

            drawGrid(g2, left, right, top, bottom, yMin, yMax);
            if (model.volumeTimestamps != null && model.volumeTimestamps.length > 0)
                drawVolume(g2, left, right, top, bottom);
            for (ChartModel.Series s : model.series)
                drawSeries(g2, s, left, right, top, bottom, yMin, yMax);
            drawConnections(g2, left, right, top, bottom, yMin, yMax);
            for (ChartModel.Marker m : model.markers)
                drawMarker(g2, m, left, right, top, bottom, yMin, yMax);

            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom)
            {
                g2.setColor(CROSSHAIR);
                float[] dash = {4f, 4f};
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 1f, dash, 0f));
                g2.drawLine(mouseX, top,  mouseX, bottom);
                g2.drawLine(left,   mouseY, right, mouseY);
                g2.setStroke(new BasicStroke(1f));
                if (hoveredTooltip != null) drawTooltip(g2, hoveredTooltip, mouseX, mouseY);
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    private void drawGrid(Graphics2D g2, int left, int right, int top, int bottom,
                          double yMin, double yMax)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i <= 4; i++)
        {
            double val = yMin + (yMax - yMin) * i / 4.0;
            int y = ChartScale.valueToPixelY(val, yMin, yMax, top, bottom);
            g2.setColor(GRID);
            g2.drawLine(left, y, right, y);
            g2.setColor(AXIS_LABEL);
            String lbl = PriceFormat.format((long) val);
            g2.drawString(lbl, 2, y + fm.getAscent() / 2);
        }

        long xRange = model.xMax - model.xMin;
        DateTimeFormatter timeFmt = xRange <= 86400 ? HOUR_FMT : DATE_FMT;
        for (int i = 0; i <= 4; i++)
        {
            long ts = model.xMin + xRange * i / 4;
            int  x  = ChartScale.timeToPixelX(ts, model.xMin, model.xMax, left, right);
            g2.setColor(GRID);
            g2.drawLine(x, top, x, bottom);
            g2.setColor(AXIS_LABEL);
            String lbl = timeFmt.format(Instant.ofEpochSecond(ts));
            int lw = fm.stringWidth(lbl);
            g2.drawString(lbl, Math.max(left, Math.min(right - lw, x - lw / 2)), bottom + 14);
        }
    }

    private void drawSeries(Graphics2D g2, ChartModel.Series s,
                             int left, int right, int top, int bottom,
                             double yMin, double yMax)
    {
        if (s.timestamps == null || s.timestamps.length < 2) return;
        g2.setColor(s.color);
        g2.setStroke(new BasicStroke(s.strokeWidth));
        int px = -1, py = -1;
        for (int i = 0; i < s.timestamps.length; i++)
        {
            if (s.values[i] == 0) { px = -1; continue; }
            int x = ChartScale.timeToPixelX(s.timestamps[i], model.xMin, model.xMax, left, right);
            int y = ChartScale.valueToPixelY(s.values[i], yMin, yMax, top, bottom);
            if (px >= 0) g2.drawLine(px, py, x, y);
            px = x; py = y;
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawMarker(Graphics2D g2, ChartModel.Marker m,
                             int left, int right, int top, int bottom,
                             double yMin, double yMax)
    {
        int x = ChartScale.timeToPixelX(m.timestamp, model.xMin, model.xMax, left, right);
        int y = ChartScale.valueToPixelY(m.value,     yMin, yMax, top, bottom);
        int h = MARKER_HALF;
        int[] xs, ys;
        if (m.isUp)
        {
            xs = new int[]{x,      x - h, x + h};
            ys = new int[]{y - h,  y + h, y + h};
        }
        else
        {
            xs = new int[]{x,      x - h, x + h};
            ys = new int[]{y + h,  y - h, y - h};
        }
        g2.setColor(m.color);
        g2.fillPolygon(xs, ys, 3);
        g2.setColor(m.color.darker());
        g2.drawPolygon(xs, ys, 3);
    }

    private void drawConnections(Graphics2D g2, int left, int right, int top, int bottom,
                                  double yMin, double yMax)
    {
        if (model.connections.isEmpty() || model.markers.isEmpty()) return;
        g2.setColor(new Color(255, 255, 255, 35));
        float[] dash = {3f, 3f};
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 1f, dash, 0f));
        for (ChartModel.Connection c : model.connections)
        {
            if (c.buyMarkerIndex >= model.markers.size()
                || c.sellMarkerIndex >= model.markers.size()) continue;
            ChartModel.Marker buy  = model.markers.get(c.buyMarkerIndex);
            ChartModel.Marker sell = model.markers.get(c.sellMarkerIndex);
            int x1 = ChartScale.timeToPixelX(buy.timestamp,  model.xMin, model.xMax, left, right);
            int y1 = ChartScale.valueToPixelY(buy.value,  yMin, yMax, top, bottom);
            int x2 = ChartScale.timeToPixelX(sell.timestamp, model.xMin, model.xMax, left, right);
            int y2 = ChartScale.valueToPixelY(sell.value, yMin, yMax, top, bottom);
            g2.drawLine(x1, y1, x2, y2);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawVolume(Graphics2D g2, int left, int right, int top, int bottom)
    {
        int volBottom = bottom;
        int volTop    = bottom - (int) ((bottom - top) * 0.15);
        double maxVol = 0;
        for (double v : model.highVolumes) if (v > maxVol) maxVol = v;
        for (double v : model.lowVolumes)  if (v > maxVol) maxVol = v;
        if (maxVol == 0) return;
        int n = model.volumeTimestamps.length;
        int barW = Math.max(1, (right - left) / n - 1);
        for (int i = 0; i < n; i++)
        {
            int x = ChartScale.timeToPixelX(model.volumeTimestamps[i], model.xMin, model.xMax, left, right);
            if (model.highVolumes[i] > 0)
            {
                int h = (int) (model.highVolumes[i] / maxVol * (volBottom - volTop));
                g2.setColor(new Color(0x5E, 0x7F, 0xFF, 40));
                g2.fillRect(x - barW / 2, volBottom - h, barW, h);
            }
            if (model.lowVolumes[i] > 0)
            {
                int h = (int) (model.lowVolumes[i] / maxVol * (volBottom - volTop));
                g2.setColor(new Color(0xFF, 0x9B, 0x44, 40));
                g2.fillRect(x - barW / 2, volBottom - h, barW / 2, h);
            }
        }
    }

    private void updateHover()
    {
        hoveredTooltip = null;
        if (model == null || mouseX < 0) return;
        int left   = MARGIN_LEFT;
        int right  = getWidth()  - MARGIN_RIGHT;
        int top    = MARGIN_TOP;
        int bottom = getHeight() - MARGIN_BOTTOM;
        for (ChartModel.Marker m : model.markers)
        {
            int x = ChartScale.timeToPixelX(m.timestamp, model.xMin, model.xMax, left, right);
            int y = ChartScale.valueToPixelY(m.value, paintYMin, paintYMax, top, bottom);
            if (Math.abs(mouseX - x) <= 9 && Math.abs(mouseY - y) <= 9)
            {
                hoveredTooltip = m.tooltip;
                return;
            }
        }
    }

    private void drawTooltip(Graphics2D g2, String text, int x, int y)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(text) + 10;
        int h = fm.getHeight() + 6;
        int tx = Math.min(x + 12, getWidth()  - w - 4);
        int ty = Math.max(y - h - 4, 2);
        g2.setColor(TOOLTIP_BG);
        g2.fillRoundRect(tx, ty, w, h, 5, 5);
        g2.setColor(Color.WHITE);
        g2.drawString(text, tx + 5, ty + fm.getAscent() + 3);
    }

    private void drawCentered(Graphics2D g2, String text, Color color)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text,
            (getWidth()  - fm.stringWidth(text)) / 2,
            (getHeight() + fm.getAscent()) / 2);
    }
}
```

- [ ] **Step 2: Run full suite — confirm compile success**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/ui/PriceChartComponent.java
git commit -m "feat: add PriceChartComponent Java2D renderer"
```

---

## Task 7: HistoryDataService

**Files:**
- Create: `src/main/java/com/exchangelens/service/HistoryDataService.java`

No unit test — async + I/O; validated manually in Task 11.

- [ ] **Step 1: Write HistoryDataService.java**

Create `src/main/java/com/exchangelens/service/HistoryDataService.java`:

```java
package com.exchangelens.service;

import com.exchangelens.api.WikiApiModels;
import com.exchangelens.api.WikiPriceClient;
import com.exchangelens.model.FlipRecord;
import com.exchangelens.tracker.FlipRepository;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fetches and caches Wiki /timeseries data and FlipRecord history for the
 * pop-out FlipHistoryWindow. All I/O runs off the EDT; callbacks are delivered
 * on the EDT via SwingUtilities.invokeLater.
 */
@Slf4j
@Singleton
public class HistoryDataService
{
    private final WikiPriceClient  wikiPriceClient;
    private final FlipRepository   flipRepository;
    private final ExecutorService  executor;
    private final ConcurrentHashMap<String, CachedTimeseries> timeseriesCache
        = new ConcurrentHashMap<>();

    // In-memory flip cache — cleared when a different account is requested
    private volatile List<FlipRecord> cachedFlips;
    private volatile String           cachedAccount;

    @Inject
    public HistoryDataService(WikiPriceClient wikiPriceClient, FlipRepository flipRepository)
    {
        this.wikiPriceClient = wikiPriceClient;
        this.flipRepository  = flipRepository;
        this.executor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "exchange-lens-history-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns cached timeseries if fresh; otherwise fetches on background thread.
     * Callback is always delivered on the EDT. data may be null on network failure.
     * <p>
     * Cache key is "{itemId}:{timestep}". TTL equals the timestep interval so that
     * 6h and 1D buttons (both use "5m") share a single cached fetch.
     */
    public void getTimeseries(int itemId, String timestep,
                              Consumer<WikiApiModels.TimeseriesResponse> callback)
    {
        String key    = itemId + ":" + timestep;
        CachedTimeseries cached = timeseriesCache.get(key);
        if (cached != null && !isExpired(cached, timestep))
        {
            SwingUtilities.invokeLater(() -> callback.accept(cached.data));
            return;
        }
        executor.submit(() ->
        {
            WikiApiModels.TimeseriesResponse data = null;
            try
            {
                data = wikiPriceClient.fetchTimeseries(itemId, timestep);
            }
            catch (Exception e)
            {
                log.warn("fetchTimeseries failed for item {} timestep {}", itemId, timestep, e);
            }
            if (data != null) timeseriesCache.put(key, new CachedTimeseries(data));
            final WikiApiModels.TimeseriesResponse result = data;
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /**
     * Loads all flips for the given account. Results are cached; if the same
     * account is requested again the cache is returned without disk I/O.
     * Callback is delivered on the EDT. Returns an empty list if accountName is null.
     */
    public void loadFlips(String accountName, Consumer<List<FlipRecord>> callback)
    {
        if (accountName != null && accountName.equals(cachedAccount) && cachedFlips != null)
        {
            final List<FlipRecord> result = cachedFlips;
            SwingUtilities.invokeLater(() -> callback.accept(result));
            return;
        }
        executor.submit(() ->
        {
            List<FlipRecord> flips = Collections.emptyList();
            if (accountName != null && !accountName.isEmpty())
            {
                try { flips = flipRepository.loadAll(accountName); }
                catch (Exception e) { log.warn("loadAll failed for {}", accountName, e); }
            }
            cachedFlips   = flips;
            cachedAccount = accountName;
            final List<FlipRecord> result = flips;
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /** Clears the flip cache so the next loadFlips call re-reads from disk. */
    public void invalidateFlips()
    {
        cachedFlips   = null;
        cachedAccount = null;
    }

    private boolean isExpired(CachedTimeseries c, String timestep)
    {
        long age = System.currentTimeMillis() / 1000 - c.fetchedAtEpochSec;
        return age > ttlSeconds(timestep);
    }

    private static long ttlSeconds(String timestep)
    {
        switch (timestep)
        {
            case "5m":  return  5 * 60;
            case "1h":  return 60 * 60;
            case "6h":  return  6 * 60 * 60;
            case "24h": return 24 * 60 * 60;
            default:    return  5 * 60;
        }
    }

    private static class CachedTimeseries
    {
        final WikiApiModels.TimeseriesResponse data;
        final long fetchedAtEpochSec;

        CachedTimeseries(WikiApiModels.TimeseriesResponse data)
        {
            this.data             = data;
            this.fetchedAtEpochSec = System.currentTimeMillis() / 1000;
        }
    }
}
```

- [ ] **Step 2: Run full suite — confirm compile success**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/service/HistoryDataService.java
git commit -m "feat: add HistoryDataService with timeseries + flip caching"
```

---

## Task 8: OverviewDashboardPanel

**Files:**
- Create: `src/main/java/com/exchangelens/ui/OverviewDashboardPanel.java`

Manual test in Task 11.

- [ ] **Step 1: Write OverviewDashboardPanel.java**

Create `src/main/java/com/exchangelens/ui/OverviewDashboardPanel.java`:

```java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.PriceFormat;
import com.exchangelens.tracker.FlipAnalytics;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Landing view of FlipHistoryWindow. Shows aggregate stat cards,
 * cumulative-profit chart, and sortable per-item summary table.
 * Row click fires onItemSelected so the window can navigate to ItemDetailPanel.
 */
public class OverviewDashboardPanel extends JPanel
{
    private static final Color BG    = ColorScheme.DARK_GRAY_COLOR;
    private static final Color GREEN = new Color(0x1EB980);
    private static final Color RED   = new Color(0xE04040);

    private final PriceChartComponent chart = new PriceChartComponent();
    private final DefaultTableModel   tableModel;
    private final JTable              table;

    private final JLabel totalProfitVal = new JLabel("—");
    private final JLabel flipsVal       = new JLabel("—");
    private final JLabel winRateVal     = new JLabel("—");
    private final JLabel taxVal         = new JLabel("—");
    private final JLabel bestFlipVal    = new JLabel("—");
    private final JLabel worstFlipVal   = new JLabel("—");

    private BiConsumer<Integer, String> onItemSelected;
    private List<FlipAnalytics.ItemSummary> summaries = Collections.emptyList();

    @Inject
    public OverviewDashboardPanel()
    {
        setLayout(new BorderLayout(0, 4));
        setBackground(BG);

        String[] cols = {"Item", "Flips", "Profit", "Avg ROI", "Avg Fill"};
        tableModel = new DefaultTableModel(cols, 0)
        {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = buildTable();

        add(buildStatCards(), BorderLayout.NORTH);
        add(buildChartSection(), BorderLayout.CENTER);
        add(buildTableSection(), BorderLayout.SOUTH);
    }

    /** Called by FlipHistoryWindow after flips are loaded. Must be called on EDT. */
    public void update(List<FlipRecord> flips)
    {
        long profit    = FlipAnalytics.totalProfit(flips);
        long tax       = FlipAnalytics.totalTax(flips);
        double winRate = FlipAnalytics.winRate(flips);
        FlipRecord best  = FlipAnalytics.bestFlip(flips);
        FlipRecord worst = FlipAnalytics.worstFlip(flips);

        totalProfitVal.setText(PriceFormat.format(profit));
        totalProfitVal.setForeground(profit >= 0 ? GREEN : RED);
        flipsVal.setText(String.valueOf(flips.stream()
            .filter(f -> f.getSellOffer() != null).count()));
        flipsVal.setForeground(Color.WHITE);
        winRateVal.setText(String.format("%.1f%%", winRate));
        winRateVal.setForeground(winRate >= 50 ? GREEN : RED);
        taxVal.setText(PriceFormat.format(tax));
        taxVal.setForeground(RED);
        bestFlipVal.setText(best  != null ? PriceFormat.format(best.getTotalNetProfit())  : "—");
        bestFlipVal.setForeground(GREEN);
        worstFlipVal.setText(worst != null ? PriceFormat.format(worst.getTotalNetProfit()) : "—");
        worstFlipVal.setForeground(RED);

        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        if (series.length >= 2)
        {
            ChartModel model = new ChartModel();
            model.xMin = series[0][0];
            model.xMax = series[series.length - 1][0];
            long[]   ts   = new long[series.length];
            double[] vals = new double[series.length];
            for (int i = 0; i < series.length; i++) { ts[i] = series[i][0]; vals[i] = series[i][1]; }
            ChartModel.Series s = new ChartModel.Series();
            s.label = "Cumulative Profit"; s.timestamps = ts; s.values = vals;
            s.color = GREEN; s.strokeWidth = 1.5f;
            model.series.add(s);
            chart.setModel(model);
        }
        else
        {
            chart.setNoDataMessage(flips.isEmpty() ? "No trades yet" : "Need 2+ completed flips for chart");
        }

        summaries = FlipAnalytics.perItemSummary(flips);
        tableModel.setRowCount(0);
        for (FlipAnalytics.ItemSummary s : summaries)
        {
            tableModel.addRow(new Object[]{
                s.itemName,
                s.flipCount,
                PriceFormat.format(s.totalProfit),
                String.format("%.1f%%", s.avgRoi * 100),
                PriceFormat.formatFillTime(s.avgFillSeconds / 60.0)
            });
        }
    }

    public void setOnItemSelected(BiConsumer<Integer, String> listener)
    {
        this.onItemSelected = listener;
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private JPanel buildStatCards()
    {
        JPanel grid = new JPanel(new GridLayout(2, 3, 4, 4));
        grid.setBackground(BG);
        grid.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        grid.add(statCard("Total Profit", totalProfitVal));
        grid.add(statCard("Flips",        flipsVal));
        grid.add(statCard("Win Rate",     winRateVal));
        grid.add(statCard("Tax Paid",     taxVal));
        grid.add(statCard("Best Flip",    bestFlipVal));
        grid.add(statCard("Worst Flip",   worstFlipVal));
        return grid;
    }

    private JPanel statCard(String title, JLabel valueLabel)
    {
        JPanel card = new JPanel(new BorderLayout(0, 2));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("SansSerif", Font.PLAIN, 9));
        titleLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        valueLabel.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 11f));
        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildChartSection()
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        chart.setPreferredSize(new Dimension(0, 160));
        wrapper.add(chart);
        return wrapper;
    }

    private JScrollPane buildTableSection()
    {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            ColorScheme.DARKER_GRAY_COLOR));
        scroll.setPreferredSize(new Dimension(0, 190));
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        return scroll;
    }

    private JTable buildTable()
    {
        JTable t = new JTable(tableModel);
        t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        t.setForeground(Color.WHITE);
        t.setGridColor(new Color(0x3A3A3A));
        t.setRowHeight(22);
        t.setFont(new Font("SansSerif", Font.PLAIN, 10));
        t.getTableHeader().setBackground(new Color(0x2A2A2A));
        t.getTableHeader().setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        t.setSelectionBackground(new Color(0x3A3A60));
        t.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        t.setRowSorter(new TableRowSorter<>(tableModel));
        t.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                int row = t.getSelectedRow();
                if (row < 0 || onItemSelected == null || summaries.isEmpty()) return;
                int modelRow = t.convertRowIndexToModel(row);
                if (modelRow < summaries.size())
                {
                    FlipAnalytics.ItemSummary s = summaries.get(modelRow);
                    onItemSelected.accept(s.itemId, s.itemName);
                }
            }
        });
        return t;
    }
}
```

- [ ] **Step 2: Run full suite — confirm compile success**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/ui/OverviewDashboardPanel.java
git commit -m "feat: add OverviewDashboardPanel with stat cards, profit chart, and item table"
```

---

## Task 9: ItemDetailPanel

**Files:**
- Create: `src/main/java/com/exchangelens/ui/ItemDetailPanel.java`

Manual test in Task 11.

- [ ] **Step 1: Write ItemDetailPanel.java**

Create `src/main/java/com/exchangelens/ui/ItemDetailPanel.java`:

```java
package com.exchangelens.ui;

import com.exchangelens.api.WikiApiModels;
import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.HistoryDataService;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-item drill-down: Wiki avgHigh/avgLow lines with trade markers overlaid.
 * Range buttons (6h, 1D, 1W, 1M, 1Y) share the same cached timeseries fetch
 * where possible (6h and 1D both use "5m" timestep).
 */
public class ItemDetailPanel extends JPanel
{
    private static final Color BG     = ColorScheme.DARK_GRAY_COLOR;
    private static final Color GREEN  = new Color(0x1EB980);
    private static final Color RED    = new Color(0xE04040);
    private static final Color BLUE   = new Color(0x5E7FFF);
    private static final Color ORANGE = new Color(0xFF9B44);

    /** {label, timestep, windowSeconds} */
    private static final Object[][] RANGES = {
        {"6h",  "5m",   6L  * 3600},
        {"1D",  "5m",  24L  * 3600},
        {"1W",  "1h",   7L  * 86400},
        {"1M",  "6h",  30L  * 86400},
        {"1Y",  "24h", 365L * 86400},
    };

    private final HistoryDataService  dataService;
    private final PriceChartComponent chart       = new PriceChartComponent();
    private final JLabel              nameLabel   = new JLabel("—");
    private final JPanel              flipList    = new JPanel();
    private final JButton[]           rangeBtns   = new JButton[RANGES.length];

    private int              currentItemId;
    private List<FlipRecord> currentFlips = Collections.emptyList();
    private int              selectedRange = 1; // default = 1D

    @Inject
    public ItemDetailPanel(HistoryDataService dataService)
    {
        this.dataService = dataService;
        setLayout(new BorderLayout());
        setBackground(BG);
        add(buildHeader(),     BorderLayout.NORTH);
        add(buildChartWrap(),  BorderLayout.CENTER);
        add(buildFlipScroll(), BorderLayout.SOUTH);
    }

    /** Called on EDT by FlipHistoryWindow. */
    public void loadItem(int itemId, String itemName, List<FlipRecord> allFlips)
    {
        currentItemId  = itemId;
        currentFlips   = allFlips.stream()
            .filter(f -> f.getItemId() == itemId && f.getSellOffer() != null)
            .collect(Collectors.toList());
        nameLabel.setText(itemName);
        rebuildFlipList();
        fetchAndRender(selectedRange);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void fetchAndRender(int rangeIdx)
    {
        selectedRange = rangeIdx;
        for (int i = 0; i < rangeBtns.length; i++)
            rangeBtns[i].setBackground(i == rangeIdx ? ColorScheme.BRAND_ORANGE : new Color(0x3C3C3C));

        String timestep   = (String) RANGES[rangeIdx][1];
        long   windowSecs = (long)   RANGES[rangeIdx][2];
        chart.setNoDataMessage("Loading...");

        dataService.getTimeseries(currentItemId, timestep, data ->
        {
            long now  = System.currentTimeMillis() / 1000;
            long xMin = now - windowSecs;
            long xMax = now;
            if (data == null || data.data == null || data.data.isEmpty())
            {
                chart.setModel(markersOnlyModel(xMin, xMax));
                return;
            }
            List<WikiApiModels.TimeseriesPoint> pts = data.data.stream()
                .filter(p -> p.timestamp >= xMin && p.timestamp <= xMax)
                .collect(Collectors.toList());
            chart.setModel(pts.isEmpty()
                ? markersOnlyModel(xMin, xMax)
                : buildModel(pts, xMin, xMax));
        });
    }

    private ChartModel buildModel(List<WikiApiModels.TimeseriesPoint> pts, long xMin, long xMax)
    {
        ChartModel model = new ChartModel();
        model.xMin = xMin;
        model.xMax = xMax;

        int n = pts.size();
        long[]   ts      = new long[n];
        double[] high    = new double[n];
        double[] low     = new double[n];
        double[] highVol = new double[n];
        double[] lowVol  = new double[n];

        for (int i = 0; i < n; i++)
        {
            WikiApiModels.TimeseriesPoint p = pts.get(i);
            ts[i]      = p.timestamp;
            high[i]    = p.avgHighPrice     != null ? p.avgHighPrice     : 0;
            low[i]     = p.avgLowPrice      != null ? p.avgLowPrice      : 0;
            highVol[i] = p.highPriceVolume  != null ? p.highPriceVolume  : 0;
            lowVol[i]  = p.lowPriceVolume   != null ? p.lowPriceVolume   : 0;
        }

        addSeries(model, "Avg High", ts, high, BLUE);
        addSeries(model, "Avg Low",  ts, low,  ORANGE);
        model.volumeTimestamps = ts;
        model.highVolumes      = highVol;
        model.lowVolumes       = lowVol;

        addMarkers(model);
        return model;
    }

    private ChartModel markersOnlyModel(long xMin, long xMax)
    {
        ChartModel model = new ChartModel();
        model.xMin = xMin;
        model.xMax = xMax;
        addMarkers(model);
        return model;
    }

    private void addSeries(ChartModel model, String label, long[] ts, double[] vals, Color color)
    {
        ChartModel.Series s = new ChartModel.Series();
        s.label = label; s.timestamps = ts; s.values = vals;
        s.color = color; s.strokeWidth = 1.5f;
        model.series.add(s);
    }

    private void addMarkers(ChartModel model)
    {
        List<Integer> buyIdx  = new ArrayList<>();
        List<Integer> sellIdx = new ArrayList<>();

        for (FlipRecord flip : currentFlips)
        {
            if (flip.getBuyOffer() != null)
            {
                long ts = flip.getBuyOffer().getCompletedAt();
                if (ts >= model.xMin && ts <= model.xMax)
                {
                    ChartModel.Marker m = new ChartModel.Marker();
                    m.timestamp = ts;
                    m.value     = flip.getBuyPrice();
                    m.color     = GREEN;
                    m.isUp      = true;
                    m.tooltip   = flip.getQuantity() + " × "
                        + PriceFormat.formatExact(flip.getBuyPrice()) + "  buy";
                    buyIdx.add(model.markers.size());
                    model.markers.add(m);
                }
            }
            if (flip.getSellOffer() != null)
            {
                long ts = flip.getSellOffer().getCompletedAt();
                if (ts >= model.xMin && ts <= model.xMax)
                {
                    ChartModel.Marker m = new ChartModel.Marker();
                    m.timestamp = ts;
                    m.value     = flip.getSellPrice();
                    m.color     = RED;
                    m.isUp      = false;
                    m.tooltip   = flip.getQuantity() + " × "
                        + PriceFormat.formatExact(flip.getSellPrice())
                        + "  +" + PriceFormat.format(flip.getTotalNetProfit());
                    sellIdx.add(model.markers.size());
                    model.markers.add(m);

                    if (!buyIdx.isEmpty())
                    {
                        ChartModel.Connection conn = new ChartModel.Connection();
                        conn.buyMarkerIndex  = buyIdx.get(buyIdx.size() - 1);
                        conn.sellMarkerIndex = sellIdx.get(sellIdx.size() - 1);
                        model.connections.add(conn);
                    }
                }
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        nameLabel.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(Color.WHITE);

        JPanel rangeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        rangeRow.setOpaque(false);
        for (int i = 0; i < RANGES.length; i++)
        {
            final int idx = i;
            JButton btn = new JButton((String) RANGES[i][0]);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 10));
            btn.setForeground(Color.WHITE);
            btn.setBackground(i == selectedRange ? ColorScheme.BRAND_ORANGE : new Color(0x3C3C3C));
            btn.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> fetchAndRender(idx));
            rangeBtns[i] = btn;
            rangeRow.add(btn);
        }

        header.add(nameLabel, BorderLayout.NORTH);
        header.add(rangeRow,  BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildChartWrap()
    {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG);
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        chart.setPreferredSize(new Dimension(0, 280));
        wrap.add(chart);
        return wrap;
    }

    private JScrollPane buildFlipScroll()
    {
        flipList.setLayout(new BoxLayout(flipList, BoxLayout.Y_AXIS));
        flipList.setBackground(BG);
        JScrollPane scroll = new JScrollPane(flipList);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            ColorScheme.DARKER_GRAY_COLOR));
        scroll.setPreferredSize(new Dimension(0, 150));
        scroll.getViewport().setBackground(BG);
        return scroll;
    }

    private void rebuildFlipList()
    {
        flipList.removeAll();
        if (currentFlips.isEmpty())
        {
            JLabel empty = new JLabel("No completed flips for this item");
            empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            flipList.add(empty);
        }
        else
        {
            for (FlipRecord flip : currentFlips)
                flipList.add(buildFlipRow(flip));
        }
        flipList.revalidate();
        flipList.repaint();
    }

    private JPanel buildFlipRow(FlipRecord flip)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel profit = new JLabel(PriceFormat.format(flip.getTotalNetProfit()) + " gp");
        profit.setForeground(flip.getTotalNetProfit() >= 0 ? GREEN : RED);
        profit.setFont(new Font("SansSerif", Font.BOLD, 10));

        JLabel detail = new JLabel(flip.getQuantity() + " × "
            + PriceFormat.formatExact(flip.getBuyPrice()) + " → "
            + PriceFormat.formatExact(flip.getSellPrice()));
        detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        detail.setFont(new Font("SansSerif", Font.PLAIN, 9));

        row.add(detail, BorderLayout.CENTER);
        row.add(profit, BorderLayout.EAST);
        return row;
    }
}
```

- [ ] **Step 2: Run full suite — confirm compile success**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/ui/ItemDetailPanel.java
git commit -m "feat: add ItemDetailPanel with Wiki overlay chart and trade markers"
```

---

## Task 10: FlipHistoryWindow

**Files:**
- Create: `src/main/java/com/exchangelens/ui/FlipHistoryWindow.java`

Manual test in Task 11.

- [ ] **Step 1: Write FlipHistoryWindow.java**

Create `src/main/java/com/exchangelens/ui/FlipHistoryWindow.java`:

```java
package com.exchangelens.ui;

import com.exchangelens.service.HistoryDataService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

/**
 * Resizable pop-out window for reviewing completed flips.
 * Uses CardLayout to switch between the overview dashboard and per-item drill-down.
 * Lazily initialized — do NOT inject directly; use Provider&lt;FlipHistoryWindow&gt;
 * so construction happens on the EDT on first open (see ExchangeLensPlugin).
 */
@Singleton
public class FlipHistoryWindow extends JFrame
{
    private static final String VIEW_OVERVIEW = "overview";
    private static final String VIEW_DETAIL   = "detail";

    private final CardLayout             cardLayout = new CardLayout();
    private final JPanel                 cardPanel  = new JPanel(cardLayout);
    private final OverviewDashboardPanel overviewPanel;
    private final ItemDetailPanel        itemDetailPanel;
    private final HistoryDataService     dataService;
    private final JLabel                 accountLabel = new JLabel();

    @Inject
    public FlipHistoryWindow(OverviewDashboardPanel overviewPanel,
                             ItemDetailPanel        itemDetailPanel,
                             HistoryDataService     dataService)
    {
        this.overviewPanel   = overviewPanel;
        this.itemDetailPanel = itemDetailPanel;
        this.dataService     = dataService;

        setTitle("Exchange Lens — Flip History");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(920, 620);
        setMinimumSize(new Dimension(700, 480));

        overviewPanel.setOnItemSelected(this::openItemDetail);

        cardPanel.add(overviewPanel,   VIEW_OVERVIEW);
        cardPanel.add(itemDetailPanel, VIEW_DETAIL);

        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(cardPanel,     BorderLayout.CENTER);
    }

    /**
     * Opens or focuses the window, then loads flips for the given account.
     * Must be called on the EDT.
     */
    public void open(String accountName)
    {
        accountLabel.setText(accountName != null ? accountName : "Unknown");
        dataService.invalidateFlips();
        overviewPanel.update(java.util.Collections.emptyList());
        cardLayout.show(cardPanel, VIEW_OVERVIEW);
        if (!isVisible()) setLocationRelativeTo(null);
        setVisible(true);
        toFront();
        requestFocus();

        dataService.loadFlips(accountName, overviewPanel::update);
    }

    /**
     * Switches to the item detail view for the given item.
     * Called from OverviewDashboardPanel row-click via setOnItemSelected.
     * Must be called on the EDT.
     */
    public void openItemDetail(int itemId, String itemName)
    {
        dataService.loadFlips(accountLabel.getText(), flips ->
        {
            itemDetailPanel.loadItem(itemId, itemName, flips);
            cardLayout.show(cardPanel, VIEW_DETAIL);
        });
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildTopBar()
    {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JButton overviewBtn = new JButton("Overview");
        overviewBtn.setBackground(new Color(0x3C3C3C));
        overviewBtn.setForeground(Color.WHITE);
        overviewBtn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        overviewBtn.setFocusPainted(false);
        overviewBtn.addActionListener(e -> cardLayout.show(cardPanel, VIEW_OVERVIEW));

        accountLabel.setFont(FontManager.getRunescapeSmallFont());
        accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(overviewBtn);
        left.add(accountLabel);

        bar.add(left, BorderLayout.WEST);
        return bar;
    }
}
```

- [ ] **Step 2: Run full suite — confirm compile success**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add src/main/java/com/exchangelens/ui/FlipHistoryWindow.java
git commit -m "feat: add FlipHistoryWindow JFrame shell with CardLayout"
```

---

## Task 11: Wire-up — ExchangeLensPanel button + ExchangeLensPlugin

**Files:**
- Modify: `src/main/java/com/exchangelens/ui/ExchangeLensPanel.java`
- Modify: `src/main/java/com/exchangelens/ExchangeLensPlugin.java`

- [ ] **Step 1: Add button + setter to ExchangeLensPanel**

Open `src/main/java/com/exchangelens/ui/ExchangeLensPanel.java`.

**1a.** Add the `openHistoryChart` field after the `onManualRefresh` field (around line 51):

```java
    private Runnable       openHistoryChart;
```

**1b.** Add a setter method alongside `setOnManualRefresh`:

```java
    public void setOnOpenHistoryChart(Runnable callback) { this.openHistoryChart = callback; }
```

**1c.** In `buildFilterRow()`, after `nav.add(sessionBtn)` / `nav.add(historyBtn)` / `nav.add(starBtn)` / `nav.add(gearBtn)`, add the chart button **before** `nav.add(sessionBtn)` (i.e., as the leftmost nav button):

Find the block:
```java
        nav.add(sessionBtn);
        nav.add(historyBtn);
        nav.add(starBtn);
        nav.add(gearBtn);
```

Replace with:
```java
        JButton chartBtn = iconButton("↗", "Flip history chart");
        chartBtn.addActionListener(e -> { if (openHistoryChart != null) openHistoryChart.run(); });
        nav.add(chartBtn);
        nav.add(sessionBtn);
        nav.add(historyBtn);
        nav.add(starBtn);
        nav.add(gearBtn);
```

(↗ U+2197 signals "open in new window".)

- [ ] **Step 2: Add Provider + lazy open + dispose to ExchangeLensPlugin**

Open `src/main/java/com/exchangelens/ExchangeLensPlugin.java`.

**2a.** Add imports at the top with the existing imports:

```java
import com.exchangelens.ui.FlipHistoryWindow;
import javax.inject.Provider;
```

**2b.** Add two new fields after the existing `@Inject` fields:

```java
    @Inject private Provider<FlipHistoryWindow> flipHistoryWindowProvider;
    private FlipHistoryWindow flipHistoryWindow;
    private String lastKnownAccount;
```

**2c.** In `startUp()`, change the single-line `setUpdateListener` from:
```java
        flipTrackerService.setUpdateListener(panel::updateSession);
```
to:
```java
        flipTrackerService.setUpdateListener((stats, flips) ->
        {
            if (!flips.isEmpty()) lastKnownAccount = flips.get(0).getAccountName();
            panel.updateSession(stats, flips);
        });
```

**2d.** In `startUp()`, after the line `flipTrackerService.startSession();`, add:
```java
        panel.setOnOpenHistoryChart(this::openFlipHistoryWindow);
```

**2e.** In `shutDown()`, after `clientToolbar.removeNavigation(navButton);`, add:
```java
        if (flipHistoryWindow != null)
        {
            flipHistoryWindow.dispose();
            flipHistoryWindow = null;
        }
```

**2f.** Add the private helper method at the end of the class, before the closing `}`:
```java
    private void openFlipHistoryWindow()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (flipHistoryWindow == null)
                flipHistoryWindow = flipHistoryWindowProvider.get();
            String name = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : lastKnownAccount;
            if (name != null)
                flipHistoryWindow.open(name);
        });
    }
```

- [ ] **Step 3: Run full suite — all tests still pass**

```
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test**

1. Launch the plugin via `.\gradlew.bat run` (or load in RuneLite).
2. Verify the ↗ button appears in the sidebar header nav row.
3. Click ↗ — the FlipHistoryWindow opens at ~920×620 px.
4. Overview panel shows stat cards (all "—" if no flips on file).
5. Log in with a character that has flip history on disk — stat cards and cumulative chart populate.
6. Click a row in the item table — the detail panel opens, range buttons appear.
7. Click "1D" — chart shows Wiki avgHigh / avgLow lines (or "No Wiki data" if offline).
8. Hover over a trade marker — tooltip appears with qty × price info.
9. Click "Overview" in the top bar — returns to overview.
10. Close the window — it hides (does not dispose).
11. Click ↗ again — reopens in the same state.
12. Disable the plugin (via RuneLite plugin hub) — no exception; window disposes cleanly.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/exchangelens/ui/ExchangeLensPanel.java
git add src/main/java/com/exchangelens/ExchangeLensPlugin.java
git commit -m "feat: wire up FlipHistoryWindow — sidebar button + lazy init + shutdown dispose"
```

---

## Spec Coverage Check

| Spec requirement | Task |
|---|---|
| Resizable pop-out JFrame, ~900×600 default | Task 10 — `setSize(920, 620)` |
| CardLayout overview + detail, switch with top bar | Task 10 |
| Overview: stat cards (profit, flips, win rate, tax, best/worst) | Task 8 |
| Overview: cumulative-profit line chart | Task 8 |
| Overview: sortable per-item table, row click → detail | Task 8 |
| Per-item: Wiki avgHigh + avgLow lines | Task 9 |
| Per-item: buy (▲) and sell (▼) markers | Task 9 |
| Per-item: faint connector between matched buy+sell | Task 9 |
| Per-item: hover tooltip on markers | Task 6 |
| Per-item: 5 range buttons (6h/1D/1W/1M/1Y) | Task 9 |
| 6h and 1D share single `5m` fetch | Task 7 — cache keyed by `itemId:timestep` |
| Range buttons use raw native timesteps | Task 9 — no aggregation |
| Wiki `/timeseries` endpoint, 4 timesteps | Task 1, 2 |
| HistoryDataService with TTL cache | Task 7 |
| FlipAnalytics pure functions | Task 3 |
| ChartScale unit-testable coordinate math | Task 4 |
| Custom Java2D, no new deps | All — Java2D only |
| Error: Wiki fail → "No Wiki data", markers still render | Task 9 — `markersOnlyModel` |
| Error: no flip history → "No trades yet" | Task 8, 9 |
| Error: incomplete flips skipped | Task 3 — `getSellOffer() != null` guard |
| Opened while logged out → falls back | Task 11 — `lastKnownAccount` fallback |
| Sidebar button opens window | Task 11 |
| Plugin shutDown disposes window | Task 11 |
| JUnit 4 pure tests for FlipAnalytics, timeseries parsing, ChartScale | Tasks 1, 3, 4 |
