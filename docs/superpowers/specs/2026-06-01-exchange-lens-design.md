# Exchange Lens — Design Spec
Date: 2026-06-01

## Overview

Exchange Lens is a RuneLite Plugin Hub plugin that functions as a manual decision-support tool for Grand Exchange flipping. It fetches real-time market data from the OSRS Wiki Prices API, scores items by flipping potential, and presents ranked recommendations in a sidebar panel. It does not automate any gameplay action.

**MVP scope (Phase 1):** Market scanner, recommendation engine, watchlist, blocklist, settings. Manual flip tracker is Phase 2.

---

## Architecture

Four strict layers. Data flows downward only — UI never calls the network directly.

```
Network layer   WikiPriceClient         HTTP requests on background ScheduledExecutorService
Data layer      MarketDataService        Merges datasets, owns refresh cycle, persists state
Logic layer     RecommendationEngine     Pure calculation, no I/O
UI layer        ExchangeLensPanel        Swing on EDT, repaints on data callback
```

### Package structure

```
com.exchangelens/
├── ExchangeLensPlugin.java
├── ExchangeLensConfig.java
├── api/
│   ├── WikiPriceClient.java
│   └── WikiApiModels.java
├── model/
│   ├── ItemMapping.java
│   ├── LatestPrice.java
│   ├── AveragePrice.java
│   ├── MarketItem.java
│   ├── FlipRecommendation.java
│   └── RiskLevel.java
├── service/
│   ├── MarketDataService.java
│   ├── RecommendationEngine.java
│   ├── TaxService.java
│   ├── RiskModel.java
│   └── StorageService.java
├── ui/
│   ├── ExchangeLensPanel.java
│   ├── RecommendationCard.java
│   ├── WatchlistPanel.java
│   └── SettingsPanel.java
└── util/
    ├── PriceFormat.java
    └── SafeMath.java
```

---

## Network Layer

### WikiPriceClient

- Runs on a `ScheduledExecutorService` (never the game/EDT thread)
- Sends `User-Agent: ExchangeLens/0.1 RuneLitePlugin contact:mackenzie0cameron0@gmail.com` on every request
- Never retries on failure — waits for the next scheduled cycle
- Always uses bulk endpoints, never loops individual item IDs

### Endpoints and refresh cadence

| Endpoint | Cadence | Purpose |
|---|---|---|
| `/mapping` | Once per session (cached 24h) | Item names, buy limits, members status |
| `/latest` | Every refresh cycle | Current high/low prices → margin calculations |
| `/5m` | Every 2–3 cycles | Short-term volume → liquidity, velocity |
| `/1h` | Every 10 cycles | Hourly volume → stability, risk |

### Refresh interval

- Default: **30 seconds**
- Minimum: **10 seconds**
- Maximum: 600 seconds
- Configurable by the user

### API etiquette

- No retry on error — log and wait for next cycle
- Show stale-data warning in UI if last successful fetch is > 2× the refresh interval
- Never request individual item IDs when bulk endpoint is available

---

## Data Models

### ItemMapping
```java
int id, String name, boolean members, int limit, int lowAlch, int highAlch
```

### LatestPrice
```java
int itemId, Integer high, Long highTime, Integer low, Long lowTime
```

### AveragePrice
```java
int itemId, Integer avgHighPrice, Integer highPriceVolume, Integer avgLowPrice, Integer lowPriceVolume
```

### MarketItem
Merged record combining all three sources. Holds latest, 5m, and 1h fields. `Instant lastUpdated`.

### FlipRecommendation
Computed record derived from MarketItem. All fields listed in the Scoring section below.

### RiskLevel
Enum: `LOW`, `MEDIUM`, `HIGH`

---

## Calculations

### GE Tax (TaxService — only place tax is calculated)
```java
int tax = (int) Math.floor(sellPrice * 0.02);
```

### Margin
```java
spread    = sellPrice - buyPrice
tax       = TaxService.calculate(sellPrice)
netMargin = spread - tax
```
Reject if `netMargin <= 0`.

### ROI
```java
roi = (double) netMargin / buyPrice
```
Reject if `buyPrice <= 0`.

### Profit per limit
```java
estimatedProfitPerLimit = netMargin * buyLimit
```

### Capital required
```java
capitalRequired  = buyPrice * buyLimit
affordableQty    = Math.min(buyLimit, availableCash / buyPrice)
affordableProfit = affordableQty * netMargin
```

### Liquidity score
```java
oneHourVolume        = oneHourHighVolume + oneHourLowVolume
fiveMinuteVolume     = fiveMinHighVolume + fiveMinLowVolume
hourlyActivity       = Math.min(1.0, oneHourVolume / Math.max(1, buyLimit))
expectedFiveMin      = Math.max(1.0, buyLimit / 12.0)
shortTermActivity    = Math.min(1.0, fiveMinuteVolume / expectedFiveMin)
liquidityScore       = (shortTermActivity * 0.6) + (hourlyActivity * 0.4)
```

### Velocity score
```java
estimatedFillMinutes = (buyLimit / (double) oneHourVolume) * 60.0
velocityScore        = Math.max(0.0, 1.0 - (estimatedFillMinutes / 120.0))
```
Score of 1.0 = fills in ≤0 min (instant). Score of 0 = fills in ≥120 min. Displayed in UI as estimated fill time.

### Stability score
```java
// Measures agreement between /latest and /5m average prices
// High divergence = possible manipulation or stale data
// If 5m data is absent (item not recently traded), default to 0.5 (medium)
if (avgHighPrice5m == null || avgLowPrice5m == null) return 0.5;
highDeviation  = Math.abs(latestHigh - avgHighPrice5m) / (double) avgHighPrice5m
lowDeviation   = Math.abs(latestLow  - avgLowPrice5m)  / (double) avgLowPrice5m
avgDeviation   = (highDeviation + lowDeviation) / 2.0
stabilityScore = Math.max(0.0, 1.0 - (avgDeviation / 0.10))  // 10% divergence = 0
```

### Margin normalised
```java
MARGIN_CAP     = 50_000   // 50k GP = score of 1.0
marginNorm     = Math.min(1.0, netMargin / (double) MARGIN_CAP)
```

### Final score
```java
// roi capped at 1.0 to prevent unbounded values dominating the score
roiCapped  = Math.min(1.0, roi)
finalScore = (roiCapped          * 0.30)
           + (liquidityScore     * 0.25)
           + (velocityScore      * 0.20)
           + (stabilityScore     * 0.15)
           + (marginNorm         * 0.10)
```

---

## Risk Model

Determined independently from the score. Used for colour coding and optional filter.

| Label | Condition |
|---|---|
| LOW | liquidityScore ≥ 0.8 AND stabilityScore ≥ 0.8 |
| HIGH | liquidityScore < 0.5 AND stabilityScore < 0.5 |
| MEDIUM | everything else |

Colour coding: LOW = green, MEDIUM = yellow, HIGH = red.

---

## Recommendation Filters (applied before scoring)

1. `netMargin > config.minimumNetMargin`
2. `roi >= config.minimumRoi`
3. `oneHourVolume >= config.minimumHourlyVolume`
4. `capitalRequired <= config.maximumCapitalPerFlip`
5. Item not in blocklist
6. If `hideHighRiskItems`: exclude HIGH risk
7. If `!includeMembersItems`: exclude members items

---

## UI

### Tab structure
```
[ Recommendations ] [ Watchlist ] [ Settings ]
```

### Recommendations tab
- Header: last-updated timestamp + manual refresh button
- Filter bar: text search (item name), cash filter toggle
- Sort selector: Score | ROI | Margin | Velocity | Volume
- Scrollable list of `RecommendationCard` rows

**RecommendationCard fields:**
- Item name + members icon
- Buy price / Sell price / Net margin (GP)
- ROI % + Risk label (colour-coded)
- Est. fill time (from velocity score)
- 1h volume + Liquidity label
- [Watch] button, [Block] button

### Watchlist tab
- Condensed card format
- Shows: name, buy/sell, margin, ROI, fill time, volume
- [Unwatch] button per item
- Persists between sessions

### Settings tab
- Cash stack input field
- Minimum net margin input
- Minimum ROI % input
- Minimum hourly volume input
- Refresh interval selector (10s minimum)
- Hide high-risk items toggle
- Include members items toggle
- Blocklist manager: scrollable list of blocked item names with [Remove] per entry

---

## Storage & Persistence

All state stored via RuneLite `ConfigManager`. No external files. Only `StorageService` reads/writes these keys.

| Key | Format | Contents |
|---|---|---|
| `exchangelens.watchlist` | JSON int array | Item IDs on watchlist |
| `exchangelens.blocklist` | JSON int array | Item IDs on blocklist |
| `exchangelens.mapping` | JSON object | Cached item mapping (refreshed if >24h old) |

---

## Error Handling

- API failure: log warning, retain last known data, show stale-data indicator if `now - lastSuccess > 2 * refreshInterval`
- Missing price data for an item: skip that item silently (incomplete records excluded from recommendations)
- Division by zero: guarded in `SafeMath` utility, returns 0.0
- ConfigManager read failure: default to empty watchlist/blocklist, log warning

---

## Out of Scope (Phase 1)

- Manual flip tracker and trade history
- Historical price charts (`/timeseries`)
- Volatility scoring
- GE slot planning
- CSV export
- Desktop notifications
- Profit dashboard
