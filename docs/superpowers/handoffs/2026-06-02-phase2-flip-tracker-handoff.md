# Phase 2 FlipTracker — Implementation Handoff
Date: 2026-06-02

## What You Are Building

A trade recording system that passively observes `GrandExchangeOfferChanged` events, matches buy and sell sides into completed `FlipRecord` objects, snapshots Wiki API market signals at offer time, persists records to disk as monthly JSON files, and displays a live session stats panel in the Exchange Lens sidebar.

Read the full design spec at:
`docs/superpowers/specs/2026-06-02-phase2-flip-tracker-design.md`

---

## Prerequisites

**Phase 1 must be implemented first.** Phase 2 depends on:
- `SlotTracker` — tracks active GE slot state (buy prices, item IDs). Phase 2's `FlipTrackerService` also subscribes to `GrandExchangeOfferChanged` independently — do NOT merge them. They are separate subscribers with separate responsibilities.
- `MarketDataService.getRecommendationForItem(int itemId)` — added in Phase 1
- `MarketDataService.getMarketItem(int itemId)` — added in Phase 1

---

## Project Location & Build

```
F:\ClaudeProject\ExchangeLens\
```

```bash
export JAVA_HOME="C:/Users/BWAmackenzie/.jdks/temurin-11.0.31"

./gradlew compileJava
./gradlew test              # must pass all existing tests + new Phase 2 tests
./gradlew run
```

---

## Existing Code to Understand Before Starting

### StorageService.java
The pattern for all persistence in this project. Uses RuneLite `ConfigManager` for small data (watchlist, blocklist). **Do NOT use ConfigManager for FlipTracker data** — flip history will grow too large for ConfigManager. Use direct file I/O to `~/.runelite/exchangelens/` instead.

Gson serialization pattern used throughout:
```java
private final Gson gson = new Gson();

// Serialize
String json = gson.toJson(myObject);

// Deserialize with generics
Type type = new TypeToken<List<FlipRecord>>(){}.getType();
List<FlipRecord> records = gson.fromJson(json, type);
```

### MarketDataService.java — `notifyUpdate()` method
This is where `lastRecommendations` gets populated (added in Phase 1). When building a `MarketSnapshot`, call `marketDataService.getMarketItem(itemId)` here to get raw price data regardless of whether the item passed the recommendation filters.

### FlipRecommendation.java fields
All scoring signals are already present on `FlipRecommendation` — `liquidityScore`, `velocityScore`, `stabilityScore`, `finalScore`, `oneHourVolume`, `fiveMinVolume`, `estimatedFillMinutes`. When building a `MarketSnapshot`, these can be pulled directly from a `FlipRecommendation` if one exists for the item. If not (item didn't pass filters), fall back to raw `MarketItem` fields.

### ExchangeLensPanel.java — tab structure
Currently has three tabs: Recommendations, Watchlist, Settings. You will add a fourth "Session" tab. Follow the exact pattern of the existing tabs:
```java
tabs.addTab("Session", sessionStatsPanel);   // add after Watchlist
```

---

## New Package Structure

```
src/main/java/com/exchangelens/
├── tracker/
│   ├── FlipTrackerService.java
│   ├── FlipRepository.java
│   ├── SessionStats.java
│   └── SessionStatsCalculator.java
├── model/
│   ├── FlipRecord.java             (new)
│   ├── OfferSnapshot.java          (new)
│   └── MarketSnapshot.java         (new)
└── ui/
    ├── SessionStatsPanel.java      (new)
    └── FlipHistoryPanel.java       (new)
```

---

## Data Models

All new model classes use `@Value @Builder` (Lombok) — same pattern as all existing models. This makes them immutable and Gson-serializable.

### MarketSnapshot
Captures Wiki API state at offer posting time. This is the ML feature vector for Phase 3. All fields should be `Integer`/`Double` (boxed) where they may be absent (e.g. `fiveMinAvgLow` is null if the item hasn't traded in the 5m window). Use `int`/`double` only for fields guaranteed to be present.

### OfferSnapshot
One side of a flip. `completedAt` is 0 until the offer reaches BOUGHT/SOLD state. `totalSpent` comes from `offer.getSpent()` (total GP transacted, not price × qty — accounts for partial fills).

### FlipRecord
The matched pair. `sellOffer` is null until the sell side completes. `suggestionSource` values: `"exchange-lens"`, `"manual"`. Do not hard-code `"copilot"` — Exchange Lens cannot reliably detect Copilot's suggestions.

**Important Lombok note:** `@Value` makes all fields `final` and generates no setters. For mutable builder pattern with partial construction (buy recorded first, sell recorded later), use `@Builder(toBuilder = true)` which enables:
```java
FlipRecord updated = existing.toBuilder().sellOffer(sellSnap).completedAt(...).build();
```

---

## FlipTrackerService — Event Handling

### State per slot
Maintain two maps:
```java
private final Map<Integer, OfferSnapshot> activeBuyOffers  = new HashMap<>();  // slot → buy snapshot
private final Map<Integer, FlipRecord>    pendingFlips      = new HashMap<>();  // slot → in-progress flip
private final List<FlipRecord>            sessionFlips      = new ArrayList<>(); // completed this session
private Instant sessionStart;
```

### GrandExchangeOfferState handling
```java
@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
    GrandExchangeOffer offer = event.getOffer();
    int slot = event.getSlot();

    switch (offer.getState()) {
        case BUYING:
            // Only create snapshot on first sight of this slot
            if (!activeBuyOffers.containsKey(slot)) {
                activeBuyOffers.put(slot, buildBuySnapshot(offer, slot));
            }
            break;

        case BOUGHT:
            OfferSnapshot buySnap = finalizeBuySnapshot(offer, slot);
            if (buySnap != null) {
                FlipRecord pending = FlipRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .itemId(offer.getItemId())
                    .itemName(resolveItemName(offer.getItemId()))
                    .quantity(offer.getTotalQuantity())
                    .buyOffer(buySnap)
                    .startedAt(buySnap.getPostedAt())
                    .accountName(client.getLocalPlayer() != null
                        ? client.getLocalPlayer().getName() : "unknown")
                    .suggestionSource(resolveSuggestionSource(offer.getItemId()))
                    .build();
                pendingFlips.put(slot, pending);
            }
            activeBuyOffers.remove(slot);
            break;

        case SELLING:
            if (pendingFlips.containsKey(slot) && !pendingFlipHasSellOffer(slot)) {
                OfferSnapshot sellSnap = buildSellSnapshot(offer, slot);
                FlipRecord updated = pendingFlips.get(slot).toBuilder()
                    .sellOffer(sellSnap).build();
                pendingFlips.put(slot, updated);
            }
            break;

        case SOLD:
            if (pendingFlips.containsKey(slot)) {
                FlipRecord completed = finalizeFlipRecord(pendingFlips.get(slot), offer);
                flipRepository.save(completed);
                sessionFlips.add(completed);
                pendingFlips.remove(slot);
                notifySessionUpdate();
            }
            break;

        case CANCELLED_BUY:
        case CANCELLED_SELL:
        case EMPTY:
            activeBuyOffers.remove(slot);
            pendingFlips.remove(slot);
            break;
    }
}
```

### resolveItemName
Use `itemManager.getItemComposition(itemId).getName()` — RuneLite's `ItemManager` is injectable. Always call from the client thread.

### resolveSuggestionSource
```java
private String resolveSuggestionSource(int itemId) {
    if (marketDataService.getRecommendationForItem(itemId).isPresent()) return "exchange-lens";
    if (storageService.loadWatchlist().contains(itemId)) return "exchange-lens";
    return "manual";
}
```

### notifySessionUpdate
Fire on the client thread, update the UI on the EDT:
```java
private void notifySessionUpdate() {
    SessionStats stats = SessionStatsCalculator.calculate(sessionFlips, sessionStart);
    SwingUtilities.invokeLater(() -> sessionStatsPanel.update(stats, sessionFlips));
}
```

---

## FlipRepository — File I/O

Write flip records to `~/.runelite/exchangelens/flips-{accountName}-{yyyy-MM}.json`.

```java
private static final String BASE_DIR = System.getProperty("user.home")
    + File.separator + ".runelite" + File.separator + "exchangelens";

public void save(FlipRecord flip) {
    // File I/O must happen on a background thread — never client thread or EDT
    File dir = new File(BASE_DIR);
    dir.mkdirs();
    String month = YearMonth.now().toString(); // "2026-06"
    File file = new File(dir, "flips-" + flip.getAccountName() + "-" + month + ".json");

    // Load existing, append, save
    List<FlipRecord> existing = loadFromFile(file);
    existing.add(flip);
    try (FileWriter writer = new FileWriter(file)) {
        gson.toJson(existing, writer);
    } catch (IOException e) {
        log.warn("Failed to save flip record", e);
    }
}
```

**Thread safety note:** `FlipTrackerService` calls `flipRepository.save()` from the client thread (inside `@Subscribe`). Disk writes on the client thread will cause game stutters. Use a single-threaded executor:
```java
private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
    r -> { Thread t = new Thread(r, "exchange-lens-io"); t.setDaemon(true); return t; });

// In onGrandExchangeOfferChanged SOLD case:
ioExecutor.submit(() -> flipRepository.save(completed));
```

Shut down the executor in `endSession()` / `FlipTrackerService.shutDown()`.

---

## SessionStatsCalculator

Pure static function — no I/O, no RuneLite dependencies. Fully unit testable.

```java
public final class SessionStatsCalculator {
    private SessionStatsCalculator() {}

    public static SessionStats calculate(List<FlipRecord> flips, Instant sessionStart) {
        long sessionSecs = Instant.now().getEpochSecond() - sessionStart.getEpochSecond();
        int  totalProfit = flips.stream().mapToInt(FlipRecord::getTotalNetProfit).sum();
        int  count       = flips.size();
        double avgRoi    = flips.stream().mapToDouble(FlipRecord::getRoi).average().orElse(0.0);
        int  hourly      = sessionSecs > 0
            ? (int)(totalProfit / (sessionSecs / 3600.0)) : 0;

        return SessionStats.builder()
            .totalProfit(totalProfit)
            .flipsCompleted(count)
            .averageRoi(avgRoi)
            .sessionDurationSeconds(sessionSecs)
            .hourlyProfit(hourly)
            .build();
    }
}
```

---

## SessionStatsPanel UI

New `JPanel` added as a tab in `ExchangeLensPanel`. Follow the same style as `WatchlistPanel` — `BoxLayout Y_AXIS`, `ColorScheme.DARK_GRAY_COLOR` background, `FontManager.getRunescapeSmallFont()` for all labels.

```java
public class SessionStatsPanel extends JPanel {
    private final JLabel profitLabel    = new JLabel("0 gp");
    private final JLabel flipsLabel     = new JLabel("0 flips");
    private final JLabel roiLabel       = new JLabel("0.00%");
    private final JLabel durationLabel  = new JLabel("00:00:00");
    private final JLabel hourlyLabel    = new JLabel("0 gp/hr");

    public void update(SessionStats stats, List<FlipRecord> recentFlips) {
        // Always called via SwingUtilities.invokeLater from FlipTrackerService
        profitLabel.setText(PriceFormat.formatExact(stats.getTotalProfit()) + " gp");
        profitLabel.setForeground(stats.getTotalProfit() >= 0
            ? new Color(0x00B300) : new Color(0xCC0000));
        // ... update other labels
        rebuildFlipList(recentFlips);
    }
}
```

The live duration clock (hh:mm:ss) requires a `Timer` that fires every second and calls `update()`. Create it in `FlipTrackerService.startSession()` and cancel it in `endSession()`.

---

## ExchangeLensPlugin Changes

```java
@Inject private FlipTrackerService flipTrackerService;
@Inject private ItemManager itemManager;  // needed by FlipTrackerService

@Override
protected void startUp() {
    // ... existing startup
    flipTrackerService.startSession();
    eventBus.register(flipTrackerService);
}

@Override
protected void shutDown() {
    eventBus.unregister(flipTrackerService);
    flipTrackerService.endSession();
    // ... existing shutdown
}
```

Pass `SessionStatsPanel` reference into `ExchangeLensPanel` the same way `WatchlistPanel` is passed — as a constructor parameter or via a setter called in `startUp()`.

---

## CSV Export

`FlipRepository.exportCsv(String accountName)` writes a flat CSV to the same directory. Column order must exactly match the spec — Phase 3 training pipeline depends on it:

```
flip_id,item_id,item_name,quantity,buy_price,sell_price,tax,net_profit,roi,
buy_fill_seconds,sell_fill_seconds,suggestion_source,
latest_low,latest_high,spread,net_margin,liquidity_score,velocity_score,
stability_score,final_score,five_min_volume,one_hour_volume,
five_min_avg_low,five_min_avg_high,one_hour_avg_low,one_hour_avg_high,
captured_at,completed_at
```

Write `null` for absent market snapshot fields (not empty string). The Python training pipeline uses `df.dropna()` to exclude rows with missing features.

---

## Tests to Write

`SessionStatsCalculator` is pure logic — write full unit tests:

```java
@Test
public void emptyFlipListReturnsZeroStats() {
    SessionStats stats = SessionStatsCalculator.calculate(
        Collections.emptyList(), Instant.now().minusSeconds(300));
    assertEquals(0, stats.getTotalProfit());
    assertEquals(0, stats.getFlipsCompleted());
}

@Test
public void totalProfitSumsAllFlips() {
    List<FlipRecord> flips = Arrays.asList(
        buildFlipRecord(5000), buildFlipRecord(3000), buildFlipRecord(-1000));
    SessionStats stats = SessionStatsCalculator.calculate(flips, Instant.now().minusSeconds(3600));
    assertEquals(7000, stats.getTotalProfit());
    assertEquals(3, stats.getFlipsCompleted());
}

@Test
public void hourlyProfitScalesWithSessionDuration() {
    // 10000 gp in 30 minutes = 20000 gp/hr
    List<FlipRecord> flips = Collections.singletonList(buildFlipRecord(10000));
    Instant start = Instant.now().minusSeconds(1800);
    SessionStats stats = SessionStatsCalculator.calculate(flips, start);
    // Allow ±500 for timing variance
    assertTrue(Math.abs(stats.getHourlyProfit() - 20000) < 500);
}
```

---

## Known Gotchas

1. **`GrandExchangeOfferChanged` fires on login** with the current state of all slots. On plugin startup, if the player is already logged in with active offers, you'll receive BUYING/SELLING events immediately. Handle this gracefully — don't create duplicate snapshots.

2. **Partial fills.** An offer in BUYING state may fire `GrandExchangeOfferChanged` multiple times before reaching BOUGHT. Only create the `OfferSnapshot` on the first BUYING event (the `containsKey` guard in the event handler handles this).

3. **`offer.getSpent()`** returns total GP actually transacted, not `price × qty`. For partially filled orders that get cancelled, `getSpent()` gives you the real amount. Use it for `totalSpent` in `OfferSnapshot`.

4. **Sell without matching buy.** Player may sell an item they bought before installing the plugin. In this case `pendingFlips` won't have an entry for the slot. Skip the SELLING/SOLD handling silently — don't try to create a partial FlipRecord.

5. **`client.getLocalPlayer()` returns null** before full login. Guard all calls:
   ```java
   String name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "unknown";
   ```

6. **Gson and `Instant`.** Gson does not serialize `java.time.Instant` by default. Either store timestamps as `long` (epoch seconds) in all model classes, or register a custom Gson adapter. The existing codebase stores timestamps as `long` — follow that pattern.

7. **File path separators.** Use `File.separator` not hardcoded `/` or `\` for cross-platform safety.

---

## Definition of Done

- [ ] `./gradlew test` — all existing tests pass + SessionStatsCalculator tests pass
- [ ] `./gradlew run` — plugin loads, Session tab visible in sidebar
- [ ] Making a buy offer in-game triggers a log entry (add debug log in BUYING handler)
- [ ] Completing a flip (buy + sell) increments session flip count and profit
- [ ] Session stats update in real time after each completed flip
- [ ] `~/.runelite/exchangelens/flips-AccountName-2026-06.json` exists and contains valid JSON after first flip
- [ ] Selling an item with no matching buy offer (pre-existing inventory) does not crash
- [ ] Plugin restart clears in-memory session but JSON file persists
- [ ] `FlipRepository.exportCsv()` produces a valid CSV with correct column headers
