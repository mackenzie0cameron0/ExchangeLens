# Exchange Lens Phase 3 — FlipTracker Design Spec
Date: 2026-06-02

## Overview

FlipTracker records every GE trade you make along with a full snapshot of Wiki API market signals at the time the offer was placed. It serves two purposes simultaneously:

1. **Live session stats** — profit, ROI, hourly rate, and per-item breakdown displayed in the Exchange Lens sidebar
2. **Training dataset** — every completed flip becomes a labeled example for the Phase 4 suggestion engine. Features are the market signals at offer time; labels are actual fill time and margin achieved

The data is stored entirely locally — no server required. FlipTracker is passive: it observes `GrandExchangeOfferChanged` events and records what happens. It never directs or modifies gameplay.

---

## Architecture

FlipTracker adds one new service layer and two new UI panels:

```
Existing layers (unchanged)
  Network layer     WikiPriceClient
  Data layer        MarketDataService
  Logic layer       RecommendationEngine
  UI layer          ExchangeLensPanel (gains new tabs)

New (Phase 3)
  Event layer       FlipTrackerService      Subscribes to GE events, builds flip records
  Storage           FlipRepository          Persists flip history to disk as JSON
  UI                SessionStatsPanel       Live session profit/ROI display
  UI                FlipHistoryPanel        Per-flip list with outcomes
```

### New package structure additions

```
com.exchangelens/
├── tracker/
│   ├── FlipTrackerService.java     Core event handler — builds FlipRecord objects
│   ├── FlipRepository.java         Reads/writes flip history to JSON on disk
│   ├── SessionStats.java           Computed stats for current session
│   └── SessionStatsCalculator.java Pure function: List<FlipRecord> → SessionStats
├── model/
│   ├── FlipRecord.java             One completed flip (buy + sell pair)
│   ├── OfferSnapshot.java          Raw offer event + market signal snapshot
│   └── MarketSnapshot.java         Wiki API signals captured at offer time
├── ui/
│   ├── SessionStatsPanel.java      Sidebar tab: session profit/stats
│   └── FlipHistoryPanel.java       Sidebar tab: per-flip list
```

---

## Data Models

### MarketSnapshot
Captures the full state of Wiki API data at the moment an offer is placed. This is the feature vector for Phase 4 ML training.

```java
@Value @Builder
public class MarketSnapshot {
    int    itemId;
    long   capturedAt;          // epoch seconds

    // Latest prices
    int    latestLow;
    int    latestHigh;
    long   latestLowTime;
    long   latestHighTime;

    // 5-minute averages + volumes
    Integer fiveMinAvgLow;
    Integer fiveMinAvgHigh;
    Integer fiveMinLowVolume;
    Integer fiveMinHighVolume;

    // 1-hour averages + volumes
    Integer oneHourAvgLow;
    Integer oneHourAvgHigh;
    Integer oneHourLowVolume;
    Integer oneHourHighVolume;

    // Derived signals (pre-computed for training convenience)
    int    spread;              // latestHigh - latestLow
    int    tax;                 // TaxService.calculate(latestHigh)
    int    netMargin;           // spread - tax
    double roi;                 // netMargin / latestLow
    double liquidityScore;      // from RecommendationEngine
    double velocityScore;
    double stabilityScore;
    double finalScore;
    int    buyLimit;
    int    oneHourVolume;       // combined high+low
    int    fiveMinVolume;
}
```

### OfferSnapshot
Captures one side (buy or sell) of a GE transaction.

```java
@Value @Builder
public class OfferSnapshot {
    int    slot;
    int    itemId;
    String itemName;
    boolean isBuy;
    int    postedPrice;         // price the offer was posted at
    int    quantity;
    long   postedAt;            // epoch seconds when offer first appeared
    long   completedAt;         // epoch seconds when offer reached BOUGHT/SOLD state
    int    totalSpent;          // actual GP spent/received (from offer state)
    MarketSnapshot marketSnapshot; // signals at time of posting
}
```

### FlipRecord
A matched buy+sell pair — one complete flip. This is the primary training example.

```java
@Value @Builder
public class FlipRecord {
    String id;                  // UUID
    int    itemId;
    String itemName;
    int    quantity;

    OfferSnapshot buyOffer;
    OfferSnapshot sellOffer;    // null until sell completes

    // Outcomes (filled when sellOffer completes)
    int    buyPrice;            // actual price paid per item
    int    sellPrice;           // actual price received per item
    int    tax;
    int    netProfitPerItem;
    int    totalNetProfit;
    double roi;
    long   buyFillSeconds;      // time from buy posted to buy completed
    long   sellFillSeconds;     // time from sell posted to sell completed
    long   totalFlipSeconds;    // buyFillSeconds + sellFillSeconds

    // Metadata
    long   startedAt;
    long   completedAt;
    String accountName;

    // Was this trade suggested by an external tool (Copilot) or Exchange Lens?
    String suggestionSource;    // "copilot", "exchange-lens", "manual"
}
```

The `suggestionSource` field is critical — it lets you later filter training data to understand which signals Copilot was responding to, and compare outcomes across sources.

---

## FlipTrackerService

The core event handler. Listens to `GrandExchangeOfferChanged` and assembles `FlipRecord` objects as offers progress through their lifecycle.

### State machine per slot

Each GE slot (0–7) progresses through states:

```
EMPTY → BUY_ACTIVE → BUY_COMPLETE → SELL_ACTIVE → SELL_COMPLETE → EMPTY
```

`FlipTrackerService` maintains a `Map<Integer, OfferSnapshot> activeBuyOffers` and `Map<Integer, FlipRecord> pendingFlips`.

### Event handling logic

```java
@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
    GrandExchangeOffer offer = event.getOffer();
    int slot = event.getSlot();

    switch (offer.getState()) {
        case BUYING:
            // First time seeing this slot as a buy — create OfferSnapshot
            if (!activeBuyOffers.containsKey(slot)) {
                activeBuyOffers.put(slot, buildOfferSnapshot(offer, slot, true));
            }
            break;

        case BOUGHT:
            // Buy completed — snapshot final state, create pending FlipRecord
            OfferSnapshot buySnap = finalizeBuySnapshot(offer, slot);
            FlipRecord pending = FlipRecord.builder()
                .id(UUID.randomUUID().toString())
                .itemId(offer.getItemId())
                .buyOffer(buySnap)
                .startedAt(buySnap.getPostedAt())
                .accountName(client.getLocalPlayer().getName())
                .suggestionSource(resolveSuggestionSource(offer.getItemId()))
                .build();
            pendingFlips.put(slot, pending);
            activeBuyOffers.remove(slot);
            break;

        case SELLING:
            // Sell posted for an item we have a pending flip for
            if (pendingFlips.containsKey(slot)) {
                OfferSnapshot sellSnap = buildOfferSnapshot(offer, slot, false);
                pendingFlips.get(slot).toBuilder().sellOffer(sellSnap).build();
                // replace in map
            }
            break;

        case SOLD:
            // Flip complete — compute outcomes, persist
            if (pendingFlips.containsKey(slot)) {
                FlipRecord completed = finalizeFlipRecord(pendingFlips.get(slot), offer);
                flipRepository.save(completed);
                sessionFlips.add(completed);
                pendingFlips.remove(slot);
                notifyStatsUpdate();
            }
            break;

        case EMPTY:
            // Slot cleared — clean up any dangling state
            activeBuyOffers.remove(slot);
            pendingFlips.remove(slot);
            break;
    }
}
```

### Market snapshot capture

When `buildOfferSnapshot()` is called, it immediately requests the current `MarketItem` from `MarketDataService` for that item ID and builds a `MarketSnapshot`. This snapshot is immutable — it captures the market state at offer posting time, which is the feature vector for training.

```java
private MarketSnapshot captureMarketSnapshot(int itemId) {
    Optional<FlipRecommendation> rec = marketDataService.getRecommendationForItem(itemId);
    // If item is not in recommendations (e.g. filtered out), still capture raw signals
    MarketItem item = marketDataService.getMarketItem(itemId); // new method
    if (item == null) return null;
    return MarketSnapshot.builder()
        .itemId(itemId)
        .capturedAt(Instant.now().getEpochSecond())
        .latestLow(item.getLatestLow())
        // ... all fields
        .build();
}
```

### Suggestion source resolution

```java
private String resolveSuggestionSource(int itemId) {
    // Check if Copilot plugin is active and had a recent suggestion for this item
    // Simple heuristic: if item is in Exchange Lens watchlist → "exchange-lens"
    // If item was in last recommendation set → "exchange-lens"
    // Otherwise → "manual" (assume Copilot or player decision)
    if (storageService.loadWatchlist().contains(itemId)) return "exchange-lens";
    if (marketDataService.getRecommendationForItem(itemId).isPresent()) return "exchange-lens";
    return "manual";
}
```

---

## FlipRepository

Stores flip history as a JSON array on disk. Uses a single file per account, rotated monthly to prevent unbounded growth.

```
~/.runelite/exchangelens/
├── flips-AccountName-2026-06.json
├── flips-AccountName-2026-07.json
└── ...
```

```java
public class FlipRepository {
    private static final String DIR = System.getProperty("user.home")
        + "/.runelite/exchangelens/";

    public void save(FlipRecord flip) { /* append to current month file */ }
    public List<FlipRecord> loadAll(String accountName) { /* load all month files */ }
    public List<FlipRecord> loadSince(String accountName, Instant since) { /* filtered load */ }
    public List<FlipRecord> loadCurrentSession(String accountName, Instant sessionStart) { ... }
}
```

Gson serialization — same pattern as the existing `StorageService`. `FlipRecord` is `@Value @Builder` so Gson handles it cleanly.

**Note:** `FlipRepository` writes to disk on the background thread (from `FlipTrackerService`), not the EDT or client thread.

---

## SessionStatsCalculator

Pure function — no I/O, no side effects. Takes a list of `FlipRecord` and returns `SessionStats`.

```java
public class SessionStatsCalculator {
    public static SessionStats calculate(List<FlipRecord> flips, Instant sessionStart) {
        List<FlipRecord> session = flips.stream()
            .filter(f -> f.getStartedAt() >= sessionStart.getEpochSecond())
            .collect(toList());

        int totalProfit   = session.stream().mapToInt(FlipRecord::getTotalNetProfit).sum();
        int flipsCount    = session.size();
        double avgRoi     = session.stream().mapToDouble(FlipRecord::getRoi).average().orElse(0);
        long sessionSecs  = Instant.now().getEpochSecond() - sessionStart.getEpochSecond();
        int hourlyProfit  = sessionSecs > 0
            ? (int)(totalProfit / (sessionSecs / 3600.0)) : 0;

        return SessionStats.builder()
            .totalProfit(totalProfit)
            .flipsCompleted(flipsCount)
            .averageRoi(avgRoi)
            .sessionDurationSeconds(sessionSecs)
            .hourlyProfit(hourlyProfit)
            .build();
    }
}
```

---

## UI: SessionStatsPanel

New tab in `ExchangeLensPanel` — "Session" tab added alongside Recommendations/Watchlist/Settings.

**Displays:**
- Total profit (large, coloured green/red)
- Flips completed
- Average ROI %
- Session duration (hh:mm:ss, live updating)
- Hourly profit rate
- Reset session button

**Update cadence:** Refreshed every time a flip completes (callback from `FlipTrackerService`) and every 1 second for the live duration clock.

---

## UI: FlipHistoryPanel

Scrollable list of completed flips in the current session, newest first.

**Per-flip row shows:**
- Item name
- Qty × net profit per item = total profit (green/red)
- Buy fill time / Sell fill time
- ROI %
- Suggestion source badge ("EL" or "M" for manual)

---

## ExchangeLensPlugin Changes

```java
@Inject private FlipTrackerService flipTrackerService;

@Override
protected void startUp() {
    // existing...
    flipTrackerService.startSession();
    eventBus.register(flipTrackerService);
}

@Override
protected void shutDown() {
    // existing...
    eventBus.unregister(flipTrackerService);
    flipTrackerService.endSession();
}
```

---

## Export Format (Phase 4 training input)

`FlipRepository` provides an export method that writes all flips to a flat CSV for feeding into the Phase 4 model training pipeline:

```
flip_id, item_id, item_name, quantity, buy_price, sell_price, tax, net_profit,
roi, buy_fill_seconds, sell_fill_seconds, suggestion_source,
latest_low, latest_high, spread, net_margin, liquidity_score, velocity_score,
stability_score, final_score, five_min_volume, one_hour_volume,
five_min_avg_low, five_min_avg_high, one_hour_avg_low, one_hour_avg_high,
captured_at, completed_at
```

Each row is one `FlipRecord`. The market signal columns (everything from `latest_low` onwards) are the feature vector. `buy_fill_seconds`, `sell_fill_seconds`, and `net_profit` are the labels.

---

## Error Handling

- `GrandExchangeOfferChanged` with missing item mapping: log warning, still save flip with item ID only (name resolved later from mapping cache)
- `MarketDataService` returns no data for item at snapshot time: save flip with null `MarketSnapshot` — flip still recorded, just missing training features
- Disk write failure: log error, keep in-memory session data, retry on next flip
- Plugin restart mid-flip: `activeBuyOffers` and `pendingFlips` are rebuilt from current GE state on `startUp()` by reading the client's current offer state

---

## Out of Scope (Phase 3)

- Portfolio value tracking (Phase 4)
- Offer cancellation guidance (Phase 4)
- Cross-session aggregate stats (Phase 4)
- Model training pipeline (Phase 4)
- Any suggestion logic (Phase 4)
