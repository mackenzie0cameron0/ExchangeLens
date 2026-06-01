# Exchange Lens Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a RuneLite sidebar plugin that fetches OSRS Wiki real-time prices, scores items by flipping potential, and displays ranked recommendations with a watchlist and blocklist.

**Architecture:** Four-layer design: WikiPriceClient (network, background thread) → MarketDataService (orchestration, merge, schedule) → RecommendationEngine (pure calculation) → ExchangeLensPanel (Swing UI, EDT). Data flows strictly downward. StorageService owns all ConfigManager reads/writes for watchlist, blocklist, and mapping cache.

**Tech Stack:** Java 11, Gradle 8.10, RuneLite Plugin Hub API (latest.release), OkHttp3 (via RuneLite transitive), Gson (via RuneLite transitive), Lombok 1.18.30, JUnit 4.12, Swing.

---

## File Map

```
src/main/java/com/exchangelens/
├── ExchangeLensPlugin.java          MODIFY — wire services, sidebar button, start/stop lifecycle
├── ExchangeLensConfig.java          MODIFY — add all config items
├── api/
│   ├── WikiPriceClient.java         CREATE — HTTP calls, User-Agent header, bulk-only
│   └── WikiApiModels.java           CREATE — Gson-annotated response POJOs
├── model/
│   ├── ItemMapping.java             CREATE — item metadata (id, name, members, limit, alch)
│   ├── LatestPrice.java             CREATE — /latest high/low per item
│   ├── AveragePrice.java            CREATE — /5m and /1h averaged prices + volumes
│   ├── MarketItem.java              CREATE — merged record combining all three sources
│   ├── FlipRecommendation.java      CREATE — fully scored recommendation
│   └── RiskLevel.java               CREATE — LOW / MEDIUM / HIGH enum
├── service/
│   ├── TaxService.java              CREATE — GE tax calculation (only class that does this)
│   ├── SafeMath.java                CREATE — division helpers that never throw
│   ├── PriceFormat.java             CREATE — GP formatting for UI
│   ├── RiskModel.java               CREATE — assigns RiskLevel from liquidity + stability
│   ├── RecommendationEngine.java    CREATE — filter, score, rank MarketItems
│   ├── StorageService.java          CREATE — ConfigManager reads/writes (watchlist, blocklist, mapping)
│   └── MarketDataService.java       CREATE — scheduler, fetch cadence, merge, notify UI
└── ui/
    ├── ExchangeLensPanel.java        CREATE — PluginPanel with three-tab JTabbedPane
    ├── RecommendationCard.java       CREATE — single row card (name, prices, ROI, risk, buttons)
    ├── WatchlistPanel.java           CREATE — scrollable watchlist with Unwatch buttons
    └── SettingsPanel.java            CREATE — config inputs, blocklist manager

src/test/java/com/exchangelens/
├── ExchangeLensPluginTest.java      EXISTS — dev launcher (no changes needed)
├── TaxServiceTest.java              CREATE
├── SafeMathTest.java                CREATE
├── WikiApiModelsTest.java           CREATE
├── RiskModelTest.java               CREATE
└── RecommendationEngineTest.java    CREATE
```

---

## Task 1: Data Models

**Files:**
- Create: `src/main/java/com/exchangelens/model/RiskLevel.java`
- Create: `src/main/java/com/exchangelens/model/ItemMapping.java`
- Create: `src/main/java/com/exchangelens/model/LatestPrice.java`
- Create: `src/main/java/com/exchangelens/model/AveragePrice.java`
- Create: `src/main/java/com/exchangelens/model/MarketItem.java`
- Create: `src/main/java/com/exchangelens/model/FlipRecommendation.java`

- [ ] **Step 1: Create RiskLevel enum**

```java
// src/main/java/com/exchangelens/model/RiskLevel.java
package com.exchangelens.model;

public enum RiskLevel
{
    LOW, MEDIUM, HIGH
}
```

- [ ] **Step 2: Create ItemMapping**

```java
// src/main/java/com/exchangelens/model/ItemMapping.java
package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ItemMapping
{
    int id;
    String name;
    boolean members;
    int limit;
    int lowAlch;
    int highAlch;
    String examine;
}
```

- [ ] **Step 3: Create LatestPrice**

```java
// src/main/java/com/exchangelens/model/LatestPrice.java
package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LatestPrice
{
    int itemId;
    Integer high;
    Long highTime;
    Integer low;
    Long lowTime;
}
```

- [ ] **Step 4: Create AveragePrice**

```java
// src/main/java/com/exchangelens/model/AveragePrice.java
package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AveragePrice
{
    int itemId;
    Integer avgHighPrice;
    Integer highPriceVolume;
    Integer avgLowPrice;
    Integer lowPriceVolume;
}
```

- [ ] **Step 5: Create MarketItem**

```java
// src/main/java/com/exchangelens/model/MarketItem.java
package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
public class MarketItem
{
    int itemId;
    String name;
    boolean members;
    int limit;

    Integer latestLow;
    Integer latestHigh;
    Long latestLowTime;
    Long latestHighTime;

    Integer fiveMinLow;
    Integer fiveMinHigh;
    Integer fiveMinLowVolume;
    Integer fiveMinHighVolume;

    Integer oneHourLow;
    Integer oneHourHigh;
    Integer oneHourLowVolume;
    Integer oneHourHighVolume;

    Instant lastUpdated;
}
```

- [ ] **Step 6: Create FlipRecommendation**

```java
// src/main/java/com/exchangelens/model/FlipRecommendation.java
package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
public class FlipRecommendation
{
    int itemId;
    String itemName;
    boolean members;

    int buyPrice;
    int sellPrice;
    int spread;
    int tax;
    int netMargin;
    double roi;

    int buyLimit;
    long capitalRequired;
    long estimatedProfitPerLimit;
    int affordableQuantity;
    long affordableProfit;

    int fiveMinVolume;
    int oneHourVolume;
    double liquidityScore;
    double velocityScore;
    double stabilityScore;
    double finalScore;
    double estimatedFillMinutes;

    RiskLevel riskLevel;
    Instant lastUpdated;
}
```

- [ ] **Step 7: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/exchangelens/model/
git commit -m "feat: add data models for Exchange Lens"
```

---

## Task 2: Utility Classes

**Files:**
- Create: `src/main/java/com/exchangelens/service/SafeMath.java`
- Create: `src/main/java/com/exchangelens/service/TaxService.java`
- Create: `src/main/java/com/exchangelens/service/PriceFormat.java`
- Create: `src/test/java/com/exchangelens/SafeMathTest.java`
- Create: `src/test/java/com/exchangelens/TaxServiceTest.java`

- [ ] **Step 1: Write SafeMathTest**

```java
// src/test/java/com/exchangelens/SafeMathTest.java
package com.exchangelens;

import com.exchangelens.service.SafeMath;
import org.junit.Test;
import static org.junit.Assert.*;

public class SafeMathTest
{
    @Test
    public void divideReturnsCorrectResult()
    {
        assertEquals(0.5, SafeMath.divide(1, 2), 0.0001);
    }

    @Test
    public void divideByZeroReturnsZero()
    {
        assertEquals(0.0, SafeMath.divide(100, 0), 0.0001);
    }

    @Test
    public void clampBelowMin()
    {
        assertEquals(0.0, SafeMath.clamp(-1.0, 0.0, 1.0), 0.0001);
    }

    @Test
    public void clampAboveMax()
    {
        assertEquals(1.0, SafeMath.clamp(2.0, 0.0, 1.0), 0.0001);
    }

    @Test
    public void clampWithinRange()
    {
        assertEquals(0.5, SafeMath.clamp(0.5, 0.0, 1.0), 0.0001);
    }
}
```

- [ ] **Step 2: Write TaxServiceTest**

```java
// src/test/java/com/exchangelens/TaxServiceTest.java
package com.exchangelens;

import com.exchangelens.service.TaxService;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaxServiceTest
{
    @Test
    public void taxIsFloorOf2Percent()
    {
        // 1000 * 0.02 = 20.0 → 20
        assertEquals(20, TaxService.calculate(1000));
    }

    @Test
    public void taxTruncatesDecimal()
    {
        // 999 * 0.02 = 19.98 → 19
        assertEquals(19, TaxService.calculate(999));
    }

    @Test
    public void taxOnZeroIsZero()
    {
        assertEquals(0, TaxService.calculate(0));
    }

    @Test
    public void taxOnLargeValue()
    {
        // 10_000_000 * 0.02 = 200_000
        assertEquals(200_000, TaxService.calculate(10_000_000));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```
./gradlew test --tests "com.exchangelens.SafeMathTest" --tests "com.exchangelens.TaxServiceTest"
```

Expected: FAIL — `SafeMath` and `TaxService` not yet defined.

- [ ] **Step 4: Implement SafeMath**

```java
// src/main/java/com/exchangelens/service/SafeMath.java
package com.exchangelens.service;

public final class SafeMath
{
    private SafeMath() {}

    public static double divide(double numerator, double denominator)
    {
        if (denominator == 0) return 0.0;
        return numerator / denominator;
    }

    public static double clamp(double value, double min, double max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
```

- [ ] **Step 5: Implement TaxService**

```java
// src/main/java/com/exchangelens/service/TaxService.java
package com.exchangelens.service;

public final class TaxService
{
    private TaxService() {}

    public static int calculate(int sellPrice)
    {
        return (int) Math.floor(sellPrice * 0.02);
    }
}
```

- [ ] **Step 6: Implement PriceFormat**

```java
// src/main/java/com/exchangelens/service/PriceFormat.java
package com.exchangelens.service;

public final class PriceFormat
{
    private PriceFormat() {}

    public static String format(long gp)
    {
        if (gp >= 1_000_000_000) return String.format("%.1fb", gp / 1_000_000_000.0);
        if (gp >= 1_000_000) return String.format("%.1fm", gp / 1_000_000.0);
        if (gp >= 1_000) return String.format("%.1fk", gp / 1_000.0);
        return String.valueOf(gp);
    }

    public static String formatRoi(double roi)
    {
        return String.format("%.2f%%", roi * 100.0);
    }

    public static String formatFillTime(double minutes)
    {
        if (minutes < 1.0) return "<1 min";
        if (minutes >= 120.0) return ">2h";
        if (minutes >= 60.0) return String.format("%.0fh %.0fm", Math.floor(minutes / 60), minutes % 60);
        return String.format("%.0f min", minutes);
    }
}
```

- [ ] **Step 7: Run tests — expect pass**

```
./gradlew test --tests "com.exchangelens.SafeMathTest" --tests "com.exchangelens.TaxServiceTest"
```

Expected: `BUILD SUCCESSFUL`, both test classes green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/exchangelens/service/SafeMath.java \
        src/main/java/com/exchangelens/service/TaxService.java \
        src/main/java/com/exchangelens/service/PriceFormat.java \
        src/test/java/com/exchangelens/SafeMathTest.java \
        src/test/java/com/exchangelens/TaxServiceTest.java
git commit -m "feat: add TaxService, SafeMath, PriceFormat utilities"
```

---

## Task 3: API Response Models + Deserialization Test

**Files:**
- Create: `src/main/java/com/exchangelens/api/WikiApiModels.java`
- Create: `src/test/java/com/exchangelens/WikiApiModelsTest.java`

- [ ] **Step 1: Write WikiApiModelsTest**

```java
// src/test/java/com/exchangelens/WikiApiModelsTest.java
package com.exchangelens;

import com.exchangelens.api.WikiApiModels;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.Test;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class WikiApiModelsTest
{
    private final Gson gson = new Gson();

    @Test
    public void parsesLatestResponse()
    {
        String json = "{\"data\":{\"4151\":{\"high\":2500000,\"highTime\":1700000001,\"low\":2490000,\"lowTime\":1700000000}}}";
        WikiApiModels.LatestResponse response = gson.fromJson(json, WikiApiModels.LatestResponse.class);

        assertNotNull(response.data);
        WikiApiModels.LatestPriceData item = response.data.get("4151");
        assertNotNull(item);
        assertEquals(Integer.valueOf(2500000), item.high);
        assertEquals(Integer.valueOf(2490000), item.low);
        assertEquals(Long.valueOf(1700000001L), item.highTime);
    }

    @Test
    public void parsesAverageResponse()
    {
        String json = "{\"data\":{\"4151\":{\"avgHighPrice\":2498000,\"highPriceVolume\":120,\"avgLowPrice\":2488000,\"lowPriceVolume\":95}}}";
        WikiApiModels.AverageResponse response = gson.fromJson(json, WikiApiModels.AverageResponse.class);

        WikiApiModels.AveragePriceData item = response.data.get("4151");
        assertNotNull(item);
        assertEquals(Integer.valueOf(2498000), item.avgHighPrice);
        assertEquals(Integer.valueOf(120), item.highPriceVolume);
    }

    @Test
    public void parsesMappingArray()
    {
        String json = "[{\"id\":4151,\"name\":\"Abyssal whip\",\"members\":true,\"limit\":70,\"lowalch\":60000,\"highalch\":90000,\"examine\":\"A weapon.\"}]";
        Type listType = new TypeToken<List<WikiApiModels.MappingEntry>>(){}.getType();
        List<WikiApiModels.MappingEntry> entries = gson.fromJson(json, listType);

        assertEquals(1, entries.size());
        WikiApiModels.MappingEntry entry = entries.get(0);
        assertEquals(4151, entry.id);
        assertEquals("Abyssal whip", entry.name);
        assertTrue(entry.members);
        assertEquals(70, entry.limit);
        assertEquals(90000, entry.highalch);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "com.exchangelens.WikiApiModelsTest"
```

Expected: FAIL — `WikiApiModels` not yet defined.

- [ ] **Step 3: Implement WikiApiModels**

```java
// src/main/java/com/exchangelens/api/WikiApiModels.java
package com.exchangelens.api;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public final class WikiApiModels
{
    private WikiApiModels() {}

    public static class LatestResponse
    {
        public Map<String, LatestPriceData> data;
    }

    public static class LatestPriceData
    {
        public Integer high;
        public Long highTime;
        public Integer low;
        public Long lowTime;
    }

    public static class AverageResponse
    {
        public Map<String, AveragePriceData> data;
    }

    public static class AveragePriceData
    {
        public Integer avgHighPrice;
        public Integer highPriceVolume;
        public Integer avgLowPrice;
        public Integer lowPriceVolume;
    }

    public static class MappingEntry
    {
        public int id;
        public String name;
        public boolean members;
        public int limit;
        @SerializedName("lowalch")
        public int lowalch;
        @SerializedName("highalch")
        public int highalch;
        public String examine;
    }
}
```

- [ ] **Step 4: Run test — expect pass**

```
./gradlew test --tests "com.exchangelens.WikiApiModelsTest"
```

Expected: `BUILD SUCCESSFUL`, all three test methods green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/exchangelens/api/WikiApiModels.java \
        src/test/java/com/exchangelens/WikiApiModelsTest.java
git commit -m "feat: add Wiki API response models with Gson deserialization"
```

---

## Task 4: WikiPriceClient

**Files:**
- Create: `src/main/java/com/exchangelens/api/WikiPriceClient.java`

No unit tests — this class wraps HTTP I/O. Tested by running the plugin (Task 13).

- [ ] **Step 1: Implement WikiPriceClient**

```java
// src/main/java/com/exchangelens/api/WikiPriceClient.java
package com.exchangelens.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import javax.inject.Inject;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class WikiPriceClient
{
    private static final String BASE_URL = "https://prices.runescape.wiki/api/v1/osrs/";
    private static final String USER_AGENT = "ExchangeLens/0.1 RuneLitePlugin contact:mackenzie0cameron0@gmail.com";

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Inject
    public WikiPriceClient(OkHttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public WikiApiModels.LatestResponse fetchLatest()
    {
        return fetch(BASE_URL + "latest", WikiApiModels.LatestResponse.class);
    }

    public WikiApiModels.AverageResponse fetchFiveMinute()
    {
        return fetch(BASE_URL + "5m", WikiApiModels.AverageResponse.class);
    }

    public WikiApiModels.AverageResponse fetchOneHour()
    {
        return fetch(BASE_URL + "1h", WikiApiModels.AverageResponse.class);
    }

    public List<WikiApiModels.MappingEntry> fetchMapping()
    {
        String body = fetchRaw(BASE_URL + "mapping");
        if (body == null) return Collections.emptyList();
        Type listType = new TypeToken<List<WikiApiModels.MappingEntry>>(){}.getType();
        try
        {
            return gson.fromJson(body, listType);
        }
        catch (Exception e)
        {
            log.warn("Failed to parse mapping response", e);
            return Collections.emptyList();
        }
    }

    private <T> T fetch(String url, Class<T> type)
    {
        String body = fetchRaw(url);
        if (body == null) return null;
        try
        {
            return gson.fromJson(body, type);
        }
        catch (Exception e)
        {
            log.warn("Failed to parse response from {}", url, e);
            return null;
        }
    }

    private String fetchRaw(String url)
    {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build();
        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.warn("API request failed: {} {}", response.code(), url);
                return null;
            }
            return response.body().string();
        }
        catch (IOException e)
        {
            log.warn("API request IOException for {}", url, e);
            return null;
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/exchangelens/api/WikiPriceClient.java
git commit -m "feat: add WikiPriceClient with User-Agent header and bulk-only fetches"
```

---

## Task 5: RiskModel

**Files:**
- Create: `src/main/java/com/exchangelens/service/RiskModel.java`
- Create: `src/test/java/com/exchangelens/RiskModelTest.java`

- [ ] **Step 1: Write RiskModelTest**

```java
// src/test/java/com/exchangelens/RiskModelTest.java
package com.exchangelens;

import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.RiskModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class RiskModelTest
{
    @Test
    public void highLiquidityAndStabilityIsLow()
    {
        assertEquals(RiskLevel.LOW, RiskModel.assess(0.85, 0.85));
    }

    @Test
    public void liquidityAndStabilityBothBelowThresholdIsHigh()
    {
        assertEquals(RiskLevel.HIGH, RiskModel.assess(0.4, 0.4));
    }

    @Test
    public void mixedScoresIsMedium()
    {
        assertEquals(RiskLevel.MEDIUM, RiskModel.assess(0.9, 0.3));
    }

    @Test
    public void exactlyAtLowThresholdIsLow()
    {
        assertEquals(RiskLevel.LOW, RiskModel.assess(0.8, 0.8));
    }

    @Test
    public void exactlyAtHighThresholdIsMedium()
    {
        // 0.5 liquidity is not < 0.5, so not HIGH
        assertEquals(RiskLevel.MEDIUM, RiskModel.assess(0.5, 0.4));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "com.exchangelens.RiskModelTest"
```

Expected: FAIL — `RiskModel` not yet defined.

- [ ] **Step 3: Implement RiskModel**

```java
// src/main/java/com/exchangelens/service/RiskModel.java
package com.exchangelens.service;

import com.exchangelens.model.RiskLevel;

public final class RiskModel
{
    private RiskModel() {}

    public static RiskLevel assess(double liquidityScore, double stabilityScore)
    {
        if (liquidityScore >= 0.8 && stabilityScore >= 0.8) return RiskLevel.LOW;
        if (liquidityScore < 0.5 && stabilityScore < 0.5) return RiskLevel.HIGH;
        return RiskLevel.MEDIUM;
    }
}
```

- [ ] **Step 4: Run test — expect pass**

```
./gradlew test --tests "com.exchangelens.RiskModelTest"
```

Expected: `BUILD SUCCESSFUL`, all five methods green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/exchangelens/service/RiskModel.java \
        src/test/java/com/exchangelens/RiskModelTest.java
git commit -m "feat: add RiskModel"
```

---

## Task 6: RecommendationEngine

**Files:**
- Create: `src/main/java/com/exchangelens/service/RecommendationEngine.java`
- Create: `src/test/java/com/exchangelens/RecommendationEngineTest.java`

- [ ] **Step 1: Write RecommendationEngineTest**

```java
// src/test/java/com/exchangelens/RecommendationEngineTest.java
package com.exchangelens;

import com.exchangelens.model.*;
import com.exchangelens.service.RecommendationEngine;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class RecommendationEngineTest
{
    private static MarketItem buildItem(int id, String name, int buyPrice, int sellPrice,
                                        int buyLimit, int oneHourVol, int fiveMinVol,
                                        Integer avgHigh5m, Integer avgLow5m)
    {
        return MarketItem.builder()
            .itemId(id).name(name).members(false).limit(buyLimit)
            .latestLow(buyPrice).latestHigh(sellPrice)
            .latestLowTime(System.currentTimeMillis() / 1000L)
            .latestHighTime(System.currentTimeMillis() / 1000L)
            .fiveMinLow(avgLow5m).fiveMinHigh(avgHigh5m)
            .fiveMinLowVolume(fiveMinVol / 2).fiveMinHighVolume(fiveMinVol / 2)
            .oneHourLow(buyPrice).oneHourHigh(sellPrice)
            .oneHourLowVolume(oneHourVol / 2).oneHourHighVolume(oneHourVol / 2)
            .lastUpdated(Instant.now())
            .build();
    }

    @Test
    public void itemWithNegativeNetMarginIsExcluded()
    {
        // sell=100, buy=100 → spread=0, tax=2 → netMargin=-2
        MarketItem item = buildItem(1, "Bad Item", 100, 100, 100, 1000, 100, 100, 100);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertTrue(recs.isEmpty());
    }

    @Test
    public void itemWithPositiveMarginIsIncluded()
    {
        // buy=1000, sell=1200 → spread=200, tax=24 → netMargin=176
        MarketItem item = buildItem(2, "Good Item", 1000, 1200, 100, 5000, 500, 1200, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(1, recs.size());
        assertEquals(176, recs.get(0).getNetMargin());
    }

    @Test
    public void blockedItemIsExcluded()
    {
        MarketItem item = buildItem(3, "Blocked", 1000, 1200, 100, 5000, 500, 1200, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.singleton(3));
        assertTrue(recs.isEmpty());
    }

    @Test
    public void resultsSortedByFinalScoreDescending()
    {
        // High-volume, high-margin item should outrank low-volume item
        MarketItem highScore = buildItem(4, "High", 1000, 1200, 100, 50000, 5000, 1200, 1000);
        MarketItem lowScore  = buildItem(5, "Low",  1000, 1020, 100, 50, 5, 1020, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Arrays.asList(lowScore, highScore), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(2, recs.size());
        assertTrue(recs.get(0).getFinalScore() >= recs.get(1).getFinalScore());
        assertEquals(4, recs.get(0).getItemId());
    }

    @Test
    public void taxIsCalculatedCorrectly()
    {
        // sell=1000, tax=floor(1000*0.02)=20, buy=900, netMargin=80
        MarketItem item = buildItem(6, "Tax Test", 900, 1000, 100, 5000, 500, 1000, 900);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(1, recs.size());
        assertEquals(20, recs.get(0).getTax());
        assertEquals(80, recs.get(0).getNetMargin());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "com.exchangelens.RecommendationEngineTest"
```

Expected: FAIL — `RecommendationEngine` not yet defined.

- [ ] **Step 3: Implement RecommendationEngine**

```java
// src/main/java/com/exchangelens/service/RecommendationEngine.java
package com.exchangelens.service;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.MarketItem;
import com.exchangelens.model.RiskLevel;
import java.util.*;
import java.util.stream.Collectors;

public final class RecommendationEngine
{
    private static final int MARGIN_CAP = 50_000;

    private RecommendationEngine() {}

    public static List<FlipRecommendation> rank(
        List<MarketItem> items,
        int minimumNetMargin,
        double minimumRoi,
        int minimumHourlyVolume,
        int maximumCapital,
        boolean hideHighRisk,
        boolean includeMembersItems,
        Set<Integer> blocklist)
    {
        return items.stream()
            .filter(item -> !blocklist.contains(item.getItemId()))
            .filter(item -> includeMembersItems || !item.isMembers())
            .filter(item -> item.getLatestLow() != null && item.getLatestLow() > 0)
            .filter(item -> item.getLatestHigh() != null && item.getLatestHigh() > 0)
            .map(RecommendationEngine::score)
            .filter(Objects::nonNull)
            .filter(r -> r.getNetMargin() > minimumNetMargin)
            .filter(r -> r.getRoi() >= minimumRoi)
            .filter(r -> r.getOneHourVolume() >= minimumHourlyVolume)
            .filter(r -> !hideHighRisk || r.getRiskLevel() != RiskLevel.HIGH)
            .sorted(Comparator.comparingDouble(FlipRecommendation::getFinalScore).reversed())
            .collect(Collectors.toList());
    }

    private static FlipRecommendation score(MarketItem item)
    {
        int buyPrice  = item.getLatestLow();
        int sellPrice = item.getLatestHigh();
        int tax       = TaxService.calculate(sellPrice);
        int spread    = sellPrice - buyPrice;
        int netMargin = spread - tax;

        if (netMargin <= 0 || buyPrice <= 0) return null;

        double roi = SafeMath.divide(netMargin, buyPrice);

        int buyLimit          = item.getLimit();
        long capitalRequired  = (long) buyPrice * buyLimit;
        long profitPerLimit   = (long) netMargin * buyLimit;

        // Liquidity score
        int fiveMinVol  = nullToZero(item.getFiveMinLowVolume()) + nullToZero(item.getFiveMinHighVolume());
        int oneHourVol  = nullToZero(item.getOneHourLowVolume()) + nullToZero(item.getOneHourHighVolume());
        double hourlyActivity    = SafeMath.clamp(SafeMath.divide(oneHourVol, Math.max(1, buyLimit)), 0, 1);
        double expectedFiveMin   = Math.max(1.0, buyLimit / 12.0);
        double shortTermActivity = SafeMath.clamp(SafeMath.divide(fiveMinVol, expectedFiveMin), 0, 1);
        double liquidityScore    = (shortTermActivity * 0.6) + (hourlyActivity * 0.4);

        // Velocity score
        double estimatedFillMinutes = SafeMath.divide((double) buyLimit, Math.max(1, oneHourVol)) * 60.0;
        double velocityScore        = SafeMath.clamp(1.0 - (estimatedFillMinutes / 120.0), 0, 1);

        // Stability score
        double stabilityScore;
        Integer avgHigh5m = item.getFiveMinHigh();
        Integer avgLow5m  = item.getFiveMinLow();
        if (avgHigh5m == null || avgLow5m == null || avgHigh5m == 0 || avgLow5m == 0)
        {
            stabilityScore = 0.5;
        }
        else
        {
            double highDev  = Math.abs(sellPrice - avgHigh5m) / (double) avgHigh5m;
            double lowDev   = Math.abs(buyPrice  - avgLow5m)  / (double) avgLow5m;
            double avgDev   = (highDev + lowDev) / 2.0;
            stabilityScore  = SafeMath.clamp(1.0 - (avgDev / 0.10), 0, 1);
        }

        // Normalised margin
        double marginNorm = SafeMath.clamp(SafeMath.divide(netMargin, MARGIN_CAP), 0, 1);

        // Final score
        double roiCapped  = SafeMath.clamp(roi, 0, 1);
        double finalScore = (roiCapped      * 0.30)
                          + (liquidityScore * 0.25)
                          + (velocityScore  * 0.20)
                          + (stabilityScore * 0.15)
                          + (marginNorm     * 0.10);

        RiskLevel risk = RiskModel.assess(liquidityScore, stabilityScore);

        return FlipRecommendation.builder()
            .itemId(item.getItemId())
            .itemName(item.getName())
            .members(item.isMembers())
            .buyPrice(buyPrice)
            .sellPrice(sellPrice)
            .spread(spread)
            .tax(tax)
            .netMargin(netMargin)
            .roi(roi)
            .buyLimit(buyLimit)
            .capitalRequired(capitalRequired)
            .estimatedProfitPerLimit(profitPerLimit)
            .affordableQuantity(buyLimit)
            .affordableProfit(profitPerLimit)
            .fiveMinVolume(fiveMinVol)
            .oneHourVolume(oneHourVol)
            .liquidityScore(liquidityScore)
            .velocityScore(velocityScore)
            .stabilityScore(stabilityScore)
            .finalScore(finalScore)
            .estimatedFillMinutes(estimatedFillMinutes)
            .riskLevel(risk)
            .lastUpdated(item.getLastUpdated())
            .build();
    }

    private static int nullToZero(Integer value)
    {
        return value == null ? 0 : value;
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```
./gradlew test --tests "com.exchangelens.RecommendationEngineTest"
```

Expected: `BUILD SUCCESSFUL`, all five methods green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/exchangelens/service/RecommendationEngine.java \
        src/test/java/com/exchangelens/RecommendationEngineTest.java
git commit -m "feat: add RecommendationEngine with scoring and filtering"
```

---

## Task 7: StorageService

**Files:**
- Create: `src/main/java/com/exchangelens/service/StorageService.java`

- [ ] **Step 1: Implement StorageService**

```java
// src/main/java/com/exchangelens/service/StorageService.java
package com.exchangelens.service;

import com.exchangelens.model.ItemMapping;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import javax.inject.Inject;
import java.lang.reflect.Type;
import java.util.*;

@Slf4j
public class StorageService
{
    private static final String GROUP = "exchangelens";
    private static final String KEY_WATCHLIST = "watchlist";
    private static final String KEY_BLOCKLIST = "blocklist";
    private static final String KEY_MAPPING = "mapping";
    private static final String KEY_MAPPING_TS = "mappingTimestamp";
    private static final long MAPPING_TTL_MS = 24 * 60 * 60 * 1000L;

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    @Inject
    public StorageService(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    public Set<Integer> loadWatchlist()
    {
        return loadIdSet(KEY_WATCHLIST);
    }

    public void saveWatchlist(Set<Integer> ids)
    {
        saveIdSet(KEY_WATCHLIST, ids);
    }

    public Set<Integer> loadBlocklist()
    {
        return loadIdSet(KEY_BLOCKLIST);
    }

    public void saveBlocklist(Set<Integer> ids)
    {
        saveIdSet(KEY_BLOCKLIST, ids);
    }

    /** Returns null if cache is absent or stale (older than 24h). */
    public Map<Integer, ItemMapping> loadMappingCache()
    {
        String tsStr = configManager.getConfiguration(GROUP, KEY_MAPPING_TS);
        if (tsStr == null) return null;
        long ts = Long.parseLong(tsStr);
        if (System.currentTimeMillis() - ts > MAPPING_TTL_MS) return null;

        String json = configManager.getConfiguration(GROUP, KEY_MAPPING);
        if (json == null) return null;
        try
        {
            Type type = new TypeToken<Map<Integer, ItemMapping>>(){}.getType();
            return gson.fromJson(json, type);
        }
        catch (Exception e)
        {
            log.warn("Failed to deserialize mapping cache", e);
            return null;
        }
    }

    public void saveMappingCache(Map<Integer, ItemMapping> mapping)
    {
        configManager.setConfiguration(GROUP, KEY_MAPPING, gson.toJson(mapping));
        configManager.setConfiguration(GROUP, KEY_MAPPING_TS, String.valueOf(System.currentTimeMillis()));
    }

    private Set<Integer> loadIdSet(String key)
    {
        String json = configManager.getConfiguration(GROUP, key);
        if (json == null) return new HashSet<>();
        try
        {
            int[] ids = gson.fromJson(json, int[].class);
            Set<Integer> result = new HashSet<>();
            for (int id : ids) result.add(id);
            return result;
        }
        catch (Exception e)
        {
            log.warn("Failed to load {} from config", key, e);
            return new HashSet<>();
        }
    }

    private void saveIdSet(String key, Set<Integer> ids)
    {
        configManager.setConfiguration(GROUP, key, gson.toJson(ids.toArray(new Integer[0])));
    }
}
```

- [ ] **Step 2: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/exchangelens/service/StorageService.java
git commit -m "feat: add StorageService for watchlist, blocklist, and mapping cache"
```

---

## Task 8: MarketDataService

**Files:**
- Create: `src/main/java/com/exchangelens/service/MarketDataService.java`

- [ ] **Step 1: Implement MarketDataService**

```java
// src/main/java/com/exchangelens/service/MarketDataService.java
package com.exchangelens.service;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.api.WikiApiModels;
import com.exchangelens.api.WikiPriceClient;
import com.exchangelens.model.*;
import lombok.extern.slf4j.Slf4j;
import javax.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class MarketDataService
{
    private final WikiPriceClient client;
    private final StorageService storage;
    private final ExchangeLensConfig config;

    private ScheduledExecutorService scheduler;
    private Consumer<List<FlipRecommendation>> onUpdate;

    private Map<Integer, ItemMapping> mapping = new HashMap<>();
    private Map<Integer, WikiApiModels.LatestPriceData> latestData = new HashMap<>();
    private Map<Integer, WikiApiModels.AveragePriceData> fiveMinData = new HashMap<>();
    private Map<Integer, WikiApiModels.AveragePriceData> oneHourData = new HashMap<>();
    private Instant lastSuccessfulFetch;
    private int cycleCount = 0;

    @Inject
    public MarketDataService(WikiPriceClient client, StorageService storage, ExchangeLensConfig config)
    {
        this.client  = client;
        this.storage = storage;
        this.config  = config;
    }

    public void start(Consumer<List<FlipRecommendation>> onUpdate)
    {
        this.onUpdate = onUpdate;
        scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "exchange-lens-refresh"); t.setDaemon(true); return t; });

        loadMapping();
        scheduler.scheduleAtFixedRate(this::refresh, 0,
            config.refreshIntervalSeconds(), TimeUnit.SECONDS);
    }

    public void stop()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isStale()
    {
        if (lastSuccessfulFetch == null) return true;
        long thresholdMs = config.refreshIntervalSeconds() * 2 * 1000L;
        return Instant.now().toEpochMilli() - lastSuccessfulFetch.toEpochMilli() > thresholdMs;
    }

    public Instant getLastSuccessfulFetch()
    {
        return lastSuccessfulFetch;
    }

    private void refresh()
    {
        try
        {
            WikiApiModels.LatestResponse latestResp = client.fetchLatest();
            if (latestResp != null && latestResp.data != null)
            {
                latestData = new HashMap<>();
                for (Map.Entry<String, WikiApiModels.LatestPriceData> e : latestResp.data.entrySet())
                {
                    try { latestData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                    catch (NumberFormatException ignored) {}
                }
                lastSuccessfulFetch = Instant.now();
            }

            if (cycleCount % 3 == 0)
            {
                WikiApiModels.AverageResponse fiveResp = client.fetchFiveMinute();
                if (fiveResp != null && fiveResp.data != null)
                {
                    fiveMinData = new HashMap<>();
                    for (Map.Entry<String, WikiApiModels.AveragePriceData> e : fiveResp.data.entrySet())
                    {
                        try { fiveMinData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            if (cycleCount % 10 == 0)
            {
                WikiApiModels.AverageResponse hourResp = client.fetchOneHour();
                if (hourResp != null && hourResp.data != null)
                {
                    oneHourData = new HashMap<>();
                    for (Map.Entry<String, WikiApiModels.AveragePriceData> e : hourResp.data.entrySet())
                    {
                        try { oneHourData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            cycleCount++;
            notifyUpdate();
        }
        catch (Exception e)
        {
            log.warn("Refresh cycle failed", e);
        }
    }

    private void loadMapping()
    {
        Map<Integer, ItemMapping> cached = storage.loadMappingCache();
        if (cached != null)
        {
            mapping = cached;
            log.debug("Loaded {} items from mapping cache", mapping.size());
            return;
        }
        List<WikiApiModels.MappingEntry> entries = client.fetchMapping();
        if (entries.isEmpty())
        {
            log.warn("Mapping fetch returned empty list");
            return;
        }
        mapping = new HashMap<>();
        for (WikiApiModels.MappingEntry e : entries)
        {
            mapping.put(e.id, ItemMapping.builder()
                .id(e.id).name(e.name).members(e.members).limit(e.limit)
                .lowAlch(e.lowalch).highAlch(e.highalch)
                .examine(e.examine)
                .build());
        }
        storage.saveMappingCache(mapping);
        log.debug("Fetched and cached {} items from /mapping", mapping.size());
    }

    private void notifyUpdate()
    {
        if (onUpdate == null || latestData.isEmpty()) return;
        List<MarketItem> items = mergeItems();
        Set<Integer> blocklist = storage.loadBlocklist();
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            items,
            config.minimumNetMargin(),
            config.minimumRoi() / 100.0,
            config.minimumHourlyVolume(),
            config.maximumCapitalPerFlip(),
            config.hideHighRiskItems(),
            config.includeMembersItems(),
            blocklist
        );
        onUpdate.accept(recs);
    }

    private List<MarketItem> mergeItems()
    {
        List<MarketItem> result = new ArrayList<>();
        for (Map.Entry<Integer, WikiApiModels.LatestPriceData> e : latestData.entrySet())
        {
            int id = e.getKey();
            ItemMapping meta = mapping.get(id);
            if (meta == null) continue;

            WikiApiModels.LatestPriceData latest   = e.getValue();
            WikiApiModels.AveragePriceData fiveMin = fiveMinData.get(id);
            WikiApiModels.AveragePriceData oneHour = oneHourData.get(id);

            MarketItem.MarketItemBuilder b = MarketItem.builder()
                .itemId(id).name(meta.getName()).members(meta.isMembers()).limit(meta.getLimit())
                .latestLow(latest.low).latestHigh(latest.high)
                .latestLowTime(latest.lowTime).latestHighTime(latest.highTime)
                .lastUpdated(lastSuccessfulFetch);

            if (fiveMin != null)
                b.fiveMinLow(fiveMin.avgLowPrice).fiveMinHigh(fiveMin.avgHighPrice)
                 .fiveMinLowVolume(fiveMin.lowPriceVolume).fiveMinHighVolume(fiveMin.highPriceVolume);

            if (oneHour != null)
                b.oneHourLow(oneHour.avgLowPrice).oneHourHigh(oneHour.avgHighPrice)
                 .oneHourLowVolume(oneHour.lowPriceVolume).oneHourHighVolume(oneHour.highPriceVolume);

            result.add(b.build());
        }
        return result;
    }
}
```

- [ ] **Step 2: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/exchangelens/service/MarketDataService.java
git commit -m "feat: add MarketDataService with tiered refresh cadence"
```

---

## Task 9: ExchangeLensConfig Update

**Files:**
- Modify: `src/main/java/com/exchangelens/ExchangeLensConfig.java`

The existing stub has the right fields. One correction needed: `minimumRoi()` default is `0.25` (meaning 0.25%) but `RecommendationEngine` expects a fractional value — the service divides by 100 when calling the engine. Verify defaults are consistent.

- [ ] **Step 1: Verify existing ExchangeLensConfig compiles and has all required methods**

The file at `src/main/java/com/exchangelens/ExchangeLensConfig.java` should already have:
- `availableCash()` → default `10_000_000`
- `minimumNetMargin()` → default `100`
- `minimumRoi()` → default `0.25` (displayed as %, divided by 100 before use)
- `minimumHourlyVolume()` → default `100`
- `maximumCapitalPerFlip()` → default `10_000_000`
- `refreshIntervalSeconds()` → default `30`, `@Range(min = 10, max = 600)`
- `includeMembersItems()` → default `true`
- `hideHighRiskItems()` → default `false`
- `showStaleDataWarnings()` → default `true`

- [ ] **Step 2: Update `@Range` on refreshIntervalSeconds to min=10**

In `src/main/java/com/exchangelens/ExchangeLensConfig.java`, change:

```java
@Range(min = 30, max = 600)
@ConfigItem(
    keyName = "refreshIntervalSeconds",
    name = "Refresh Interval (seconds)",
    description = "How often to refresh market data (minimum 30 seconds)"
)
default int refreshIntervalSeconds()
{
    return 30;
}
```

to:

```java
@Range(min = 10, max = 600)
@ConfigItem(
    keyName = "refreshIntervalSeconds",
    name = "Refresh Interval (seconds)",
    description = "How often to refresh market data (minimum 10 seconds)"
)
default int refreshIntervalSeconds()
{
    return 30;
}
```

- [ ] **Step 3: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/exchangelens/ExchangeLensConfig.java
git commit -m "fix: lower refresh interval minimum to 10 seconds"
```

---

## Task 10: UI — RecommendationCard

**Files:**
- Create: `src/main/java/com/exchangelens/ui/RecommendationCard.java`

- [ ] **Step 1: Implement RecommendationCard**

```java
// src/main/java/com/exchangelens/ui/RecommendationCard.java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class RecommendationCard extends JPanel
{
    private static final Color COLOR_LOW    = new Color(0x40C040);
    private static final Color COLOR_MEDIUM = new Color(0xE0A800);
    private static final Color COLOR_HIGH   = new Color(0xE04040);
    private static final Color BG_CARD      = ColorScheme.DARKER_GRAY_COLOR;

    private final JLabel nameLabel    = new JLabel();
    private final JLabel priceLabel   = new JLabel();
    private final JLabel marginLabel  = new JLabel();
    private final JLabel roiLabel     = new JLabel();
    private final JLabel riskLabel    = new JLabel();
    private final JLabel fillLabel    = new JLabel();
    private final JLabel volumeLabel  = new JLabel();
    private final JButton watchBtn    = new JButton("Watch");
    private final JButton blockBtn    = new JButton("Block");

    public RecommendationCard(Consumer<Integer> onWatch, Consumer<Integer> onBlock)
    {
        setLayout(new BorderLayout(4, 2));
        setBackground(BG_CARD);
        setBorder(new EmptyBorder(6, 8, 6, 8));

        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(Color.WHITE);

        JPanel infoPanel = new JPanel(new GridLayout(3, 2, 2, 1));
        infoPanel.setOpaque(false);
        infoPanel.add(priceLabel);
        infoPanel.add(marginLabel);
        infoPanel.add(roiLabel);
        infoPanel.add(riskLabel);
        infoPanel.add(fillLabel);
        infoPanel.add(volumeLabel);

        for (JLabel lbl : new JLabel[]{priceLabel, marginLabel, roiLabel, riskLabel, fillLabel, volumeLabel})
        {
            lbl.setFont(FontManager.getRunescapeSmallFont());
            lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        }

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(watchBtn);
        buttonPanel.add(blockBtn);

        watchBtn.setFont(FontManager.getRunescapeSmallFont());
        blockBtn.setFont(FontManager.getRunescapeSmallFont());
        watchBtn.setFocusPainted(false);
        blockBtn.setFocusPainted(false);

        add(nameLabel, BorderLayout.NORTH);
        add(infoPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        watchBtn.addActionListener(e ->
        {
            if (watchBtn.getClientProperty("itemId") instanceof Integer)
                onWatch.accept((Integer) watchBtn.getClientProperty("itemId"));
        });
        blockBtn.addActionListener(e ->
        {
            if (blockBtn.getClientProperty("itemId") instanceof Integer)
                onBlock.accept((Integer) blockBtn.getClientProperty("itemId"));
        });
    }

    public void update(FlipRecommendation rec)
    {
        nameLabel.setText(rec.getItemName() + (rec.isMembers() ? " (m)" : ""));
        priceLabel.setText("Buy: " + PriceFormat.format(rec.getBuyPrice())
            + "  Sell: " + PriceFormat.format(rec.getSellPrice()));
        marginLabel.setText("Margin: " + PriceFormat.format(rec.getNetMargin()));
        roiLabel.setText("ROI: " + PriceFormat.formatRoi(rec.getRoi()));
        fillLabel.setText("Fill: " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes()));
        volumeLabel.setText("Vol/h: " + rec.getOneHourVolume());

        String riskText = rec.getRiskLevel().name();
        Color riskColor = rec.getRiskLevel() == RiskLevel.LOW ? COLOR_LOW
            : rec.getRiskLevel() == RiskLevel.HIGH ? COLOR_HIGH : COLOR_MEDIUM;
        riskLabel.setText("Risk: " + riskText);
        riskLabel.setForeground(riskColor);

        watchBtn.putClientProperty("itemId", rec.getItemId());
        blockBtn.putClientProperty("itemId", rec.getItemId());
        revalidate();
        repaint();
    }
}
```

- [ ] **Step 2: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/exchangelens/ui/RecommendationCard.java
git commit -m "feat: add RecommendationCard UI component"
```

---

## Task 11: UI — WatchlistPanel and SettingsPanel

**Files:**
- Create: `src/main/java/com/exchangelens/ui/WatchlistPanel.java`
- Create: `src/main/java/com/exchangelens/ui/SettingsPanel.java`

- [ ] **Step 1: Implement WatchlistPanel**

```java
// src/main/java/com/exchangelens/ui/WatchlistPanel.java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class WatchlistPanel extends JPanel
{
    private final JPanel listPanel = new JPanel();
    private final Consumer<Integer> onUnwatch;

    public WatchlistPanel(Consumer<Integer> onUnwatch)
    {
        this.onUnwatch = onUnwatch;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);
    }

    public void update(List<FlipRecommendation> watchedRecs)
    {
        SwingUtilities.invokeLater(() ->
        {
            listPanel.removeAll();
            if (watchedRecs.isEmpty())
            {
                JLabel empty = new JLabel("No items on watchlist.");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setFont(FontManager.getRunescapeSmallFont());
                empty.setBorder(new EmptyBorder(12, 12, 0, 0));
                listPanel.add(empty);
            }
            else
            {
                for (FlipRecommendation rec : watchedRecs)
                {
                    listPanel.add(buildRow(rec));
                    listPanel.add(Box.createVerticalStrut(2));
                }
            }
            listPanel.revalidate();
            listPanel.repaint();
        });
    }

    private JPanel buildRow(FlipRecommendation rec)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(5, 8, 5, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        JLabel info = new JLabel("<html><b>" + rec.getItemName() + "</b><br/>"
            + "Buy: " + PriceFormat.format(rec.getBuyPrice())
            + "  Sell: " + PriceFormat.format(rec.getSellPrice())
            + "  Margin: " + PriceFormat.format(rec.getNetMargin())
            + "  ROI: " + PriceFormat.formatRoi(rec.getRoi())
            + "  Fill: " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes())
            + "</html>");
        info.setForeground(Color.WHITE);
        info.setFont(FontManager.getRunescapeSmallFont());

        JButton unwatch = new JButton("Unwatch");
        unwatch.setFont(FontManager.getRunescapeSmallFont());
        unwatch.setFocusPainted(false);
        unwatch.addActionListener(e -> onUnwatch.accept(rec.getItemId()));

        row.add(info, BorderLayout.CENTER);
        row.add(unwatch, BorderLayout.EAST);
        return row;
    }
}
```

- [ ] **Step 2: Implement SettingsPanel**

```java
// src/main/java/com/exchangelens/ui/SettingsPanel.java
package com.exchangelens.ui;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.*;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel
{
    private final JPanel blocklistPanel = new JPanel();

    public SettingsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel note = new JLabel("<html>Use the RuneLite settings panel<br/>"
            + "(wrench icon) to adjust margin,<br/>"
            + "ROI, volume, and refresh interval.</html>");
        note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        note.setFont(FontManager.getRunescapeSmallFont());
        note.setBorder(new EmptyBorder(10, 10, 10, 10));
        add(note);

        JPanel blockSection = new JPanel(new BorderLayout());
        blockSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        blockSection.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
            "Blocked Items",
            TitledBorder.LEFT, TitledBorder.TOP,
            FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

        blocklistPanel.setLayout(new BoxLayout(blocklistPanel, BoxLayout.Y_AXIS));
        blocklistPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(blocklistPanel);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(200, 150));
        blockSection.add(scroll, BorderLayout.CENTER);
        add(blockSection);
    }

    public void updateBlocklist(Map<Integer, String> blockedItems, Consumer<Integer> onRemove)
    {
        SwingUtilities.invokeLater(() ->
        {
            blocklistPanel.removeAll();
            if (blockedItems.isEmpty())
            {
                JLabel empty = new JLabel("No blocked items.");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setFont(FontManager.getRunescapeSmallFont());
                blocklistPanel.add(empty);
            }
            else
            {
                for (Map.Entry<Integer, String> entry : blockedItems.entrySet())
                {
                    JPanel row = new JPanel(new BorderLayout(4, 0));
                    row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                    row.setBorder(new EmptyBorder(2, 4, 2, 4));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

                    JLabel name = new JLabel(entry.getValue());
                    name.setForeground(Color.WHITE);
                    name.setFont(FontManager.getRunescapeSmallFont());

                    JButton remove = new JButton("X");
                    remove.setFont(FontManager.getRunescapeSmallFont());
                    remove.setFocusPainted(false);
                    remove.addActionListener(e -> onRemove.accept(entry.getKey()));

                    row.add(name, BorderLayout.CENTER);
                    row.add(remove, BorderLayout.EAST);
                    blocklistPanel.add(row);
                }
            }
            blocklistPanel.revalidate();
            blocklistPanel.repaint();
        });
    }
}
```

- [ ] **Step 3: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/exchangelens/ui/WatchlistPanel.java \
        src/main/java/com/exchangelens/ui/SettingsPanel.java
git commit -m "feat: add WatchlistPanel and SettingsPanel UI components"
```

---

## Task 12: ExchangeLensPanel (Main Tabbed Panel)

**Files:**
- Create: `src/main/java/com/exchangelens/ui/ExchangeLensPanel.java`

- [ ] **Step 1: Implement ExchangeLensPanel**

```java
// src/main/java/com/exchangelens/ui/ExchangeLensPanel.java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.StorageService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ExchangeLensPanel extends PluginPanel
{
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final StorageService storage;

    private final JLabel statusLabel   = new JLabel("Loading...");
    private final JTextField searchBox = new JTextField();
    private final JPanel cardContainer = new JPanel();
    private final WatchlistPanel watchlistPanel;
    private final SettingsPanel settingsPanel;

    private List<FlipRecommendation> allRecs  = new ArrayList<>();
    private Set<Integer> watchlist            = new HashSet<>();
    private Set<Integer> blocklist            = new HashSet<>();
    private Runnable onManualRefresh;

    @Inject
    public ExchangeLensPanel(StorageService storage)
    {
        super(false);
        this.storage = storage;
        this.watchlist = storage.loadWatchlist();
        this.blocklist = storage.loadBlocklist();

        watchlistPanel = new WatchlistPanel(this::unwatch);
        settingsPanel  = new SettingsPanel();

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
    }

    public void setOnManualRefresh(Runnable callback)
    {
        this.onManualRefresh = callback;
    }

    public void updateRecommendations(List<FlipRecommendation> recs, Instant lastFetch, boolean stale)
    {
        SwingUtilities.invokeLater(() ->
        {
            this.allRecs = recs;
            String timeStr = lastFetch != null ? TIME_FMT.format(lastFetch) : "--:--:--";
            statusLabel.setText((stale ? "⚠ Stale — " : "") + "Updated: " + timeStr
                + "  (" + recs.size() + " items)");
            statusLabel.setForeground(stale ? new Color(0xE0A800) : ColorScheme.LIGHT_GRAY_COLOR);
            rebuildCards();
            updateWatchlistPanel();
            updateBlocklistPanel();
        });
    }

    private void rebuildCards()
    {
        String query = searchBox.getText().trim().toLowerCase();
        List<FlipRecommendation> filtered = allRecs.stream()
            .filter(r -> query.isEmpty() || r.getItemName().toLowerCase().contains(query))
            .collect(Collectors.toList());

        cardContainer.removeAll();
        for (FlipRecommendation rec : filtered)
        {
            RecommendationCard card = new RecommendationCard(this::watch, this::block);
            card.update(rec);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            cardContainer.add(card);
            cardContainer.add(Box.createVerticalStrut(3));
        }
        if (filtered.isEmpty())
        {
            JLabel empty = new JLabel(allRecs.isEmpty() ? "Fetching market data..." : "No results.");
            empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setBorder(new EmptyBorder(12, 12, 0, 0));
            cardContainer.add(empty);
        }
        cardContainer.revalidate();
        cardContainer.repaint();
    }

    private void updateWatchlistPanel()
    {
        List<FlipRecommendation> watched = allRecs.stream()
            .filter(r -> watchlist.contains(r.getItemId()))
            .collect(Collectors.toList());
        watchlistPanel.update(watched);
    }

    private void updateBlocklistPanel()
    {
        Map<Integer, String> blockedNames = new LinkedHashMap<>();
        for (FlipRecommendation r : allRecs)
        {
            if (blocklist.contains(r.getItemId()))
                blockedNames.put(r.getItemId(), r.getItemName());
        }
        settingsPanel.updateBlocklist(blockedNames, this::unblock);
    }

    private void watch(int itemId)
    {
        watchlist.add(itemId);
        storage.saveWatchlist(watchlist);
        updateWatchlistPanel();
    }

    private void unwatch(int itemId)
    {
        watchlist.remove(itemId);
        storage.saveWatchlist(watchlist);
        updateWatchlistPanel();
    }

    private void block(int itemId)
    {
        blocklist.add(itemId);
        storage.saveBlocklist(blocklist);
        allRecs = allRecs.stream()
            .filter(r -> r.getItemId() != itemId)
            .collect(Collectors.toList());
        rebuildCards();
        updateBlocklistPanel();
    }

    private void unblock(int itemId)
    {
        blocklist.remove(itemId);
        storage.saveBlocklist(blocklist);
        updateBlocklistPanel();
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(8, 8, 6, 8));

        JLabel title = new JLabel("Exchange Lens");
        title.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(new Color(0xFFD700));

        JButton refreshBtn = new JButton("↻");
        refreshBtn.setFont(FontManager.getRunescapeSmallFont());
        refreshBtn.setFocusPainted(false);
        refreshBtn.setToolTipText("Manual refresh");
        refreshBtn.addActionListener(e -> { if (onManualRefresh != null) onManualRefresh.run(); });

        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        searchBox.setFont(FontManager.getRunescapeSmallFont());
        searchBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchBox.setForeground(Color.WHITE);
        searchBox.setCaretColor(Color.WHITE);
        searchBox.putClientProperty("JTextField.placeholderText", "Search items...");
        searchBox.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { rebuildCards(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { rebuildCards(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildCards(); }
        });

        JPanel topRow = new JPanel(new BorderLayout(4, 0));
        topRow.setOpaque(false);
        topRow.add(title, BorderLayout.WEST);
        topRow.add(refreshBtn, BorderLayout.EAST);

        header.add(topRow, BorderLayout.NORTH);
        header.add(statusLabel, BorderLayout.CENTER);
        header.add(searchBox, BorderLayout.SOUTH);
        return header;
    }

    private JTabbedPane buildTabs()
    {
        cardContainer.setLayout(new BoxLayout(cardContainer, BoxLayout.Y_AXIS));
        cardContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane recScroll = new JScrollPane(cardContainer);
        recScroll.setBorder(null);
        recScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        recScroll.getVerticalScrollBar().setUnitIncrement(16);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(FontManager.getRunescapeSmallFont());
        tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabs.addTab("Recommendations", recScroll);
        tabs.addTab("Watchlist", watchlistPanel);
        tabs.addTab("Settings", settingsPanel);
        return tabs;
    }
}
```

- [ ] **Step 2: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/exchangelens/ui/ExchangeLensPanel.java
git commit -m "feat: add ExchangeLensPanel with tabbed Recommendations/Watchlist/Settings"
```

---

## Task 13: ExchangeLensPlugin — Wire Everything Together

**Files:**
- Modify: `src/main/java/com/exchangelens/ExchangeLensPlugin.java`

- [ ] **Step 1: Add plugin icon to resources**

Create `src/main/resources/com/exchangelens/` directory (already created). The plugin generates a fallback icon at runtime if no `icon.png` is present — no separate file required for MVP.

- [ ] **Step 2: Replace ExchangeLensPlugin.java**

```java
// src/main/java/com/exchangelens/ExchangeLensPlugin.java
package com.exchangelens;

import com.exchangelens.api.WikiPriceClient;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.StorageService;
import com.exchangelens.ui.ExchangeLensPanel;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
    name = "Exchange Lens",
    description = "Grand Exchange flipping assistant with margin analysis and flip tracking",
    tags = {"grand exchange", "ge", "flip", "market", "price"}
)
public class ExchangeLensPlugin extends Plugin
{
    @Inject private ClientToolbar clientToolbar;
    @Inject private ExchangeLensConfig config;
    @Inject private ExchangeLensPanel panel;
    @Inject private MarketDataService marketDataService;
    @Inject private StorageService storageService;

    private NavigationButton navButton;

    @Override
    protected void startUp()
    {
        log.debug("Exchange Lens starting");

        panel.setOnManualRefresh(() ->
        {
            // Trigger immediate re-fetch by restarting the service
            marketDataService.stop();
            marketDataService.start(recs ->
                SwingUtilities.invokeLater(() ->
                    panel.updateRecommendations(recs, marketDataService.getLastSuccessfulFetch(),
                        marketDataService.isStale())));
        });

        marketDataService.start(recs ->
            SwingUtilities.invokeLater(() ->
                panel.updateRecommendations(recs, marketDataService.getLastSuccessfulFetch(),
                    marketDataService.isStale())));

        navButton = NavigationButton.builder()
            .tooltip("Exchange Lens")
            .icon(buildIcon())
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);
        log.debug("Exchange Lens started");
    }

    @Override
    protected void shutDown()
    {
        marketDataService.stop();
        clientToolbar.removeNavigation(navButton);
        navButton = null;
        log.debug("Exchange Lens stopped");
    }

    @Provides
    ExchangeLensConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ExchangeLensConfig.class);
    }

    private BufferedImage buildIcon()
    {
        try
        {
            return net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "icon.png");
        }
        catch (Exception e)
        {
            // Generate a fallback gold coin icon
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xFFD700));
            g.fillOval(1, 1, 14, 14);
            g.setColor(new Color(0xB8860B));
            g.drawOval(1, 1, 14, 14);
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 9));
            g.drawString("EL", 2, 11);
            g.dispose();
            return img;
        }
    }
}
```

- [ ] **Step 3: Compile to verify**

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/exchangelens/ExchangeLensPlugin.java
git commit -m "feat: wire ExchangeLensPlugin startup, nav button, and market data service"
```

---

## Task 14: Run All Tests + Smoke Test

- [ ] **Step 1: Run the full test suite**

```
./gradlew test
```

Expected: `BUILD SUCCESSFUL` — TaxServiceTest, SafeMathTest, WikiApiModelsTest, RiskModelTest, RecommendationEngineTest all pass.

- [ ] **Step 2: Launch RuneLite with the plugin**

```
./gradlew run
```

Expected: RuneLite opens in developer mode.

- [ ] **Step 3: Verify plugin loads**

In RuneLite: open the Plugin Hub (puzzle icon) → search "Exchange Lens" — it should appear enabled. Alternatively, look for the gold "EL" icon in the left sidebar navigation.

- [ ] **Step 4: Verify recommendations load**

Click the Exchange Lens sidebar icon. After one refresh cycle (≤30 seconds), the Recommendations tab should populate with cards showing item names, buy/sell prices, margin, ROI, fill time, risk label, and Watch/Block buttons.

- [ ] **Step 5: Verify Watch/Block**

Click "Watch" on any card → switch to Watchlist tab → item should appear. Click "Block" on a card → item should disappear from Recommendations. Go to Settings tab → blocked item should appear with a Remove button.

- [ ] **Step 6: Verify persistence**

Close RuneLite via `Ctrl+C`. Re-run `./gradlew run`. Watchlist and blocklist should still contain the items added in the previous step.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat: Exchange Lens Phase 1 complete — market scanner, watchlist, blocklist"
```
