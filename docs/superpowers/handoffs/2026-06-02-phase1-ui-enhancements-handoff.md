# Phase 1 UI Enhancements — Implementation Handoff
Date: 2026-06-02

## What You Are Building

Three in-game Grand Exchange UI enhancements on top of the existing Exchange Lens RuneLite plugin:

1. **GE Price Injection** — when a player opens a buy/sell offer screen for an item Exchange Lens has a recommendation for, inject a clickable "Set to Exchange Lens price: X gp" line that auto-fills the price field
2. **GE Slot Colorization** — color the price text on active GE slots green (profitable) or red (unprofitable)
3. **Sell Tooltip Profit** — append "Profit: X gp" to the hover tooltip on active sell offers

Read the full design spec at:
`docs/superpowers/specs/2026-06-02-exchange-lens-phase2-ui-enhancements.md`

This handoff covers everything you need to know about the existing codebase that the spec does not.

---

## Project Location

```
F:\ClaudeProject\ExchangeLens\
```

## Build & Run Commands

All commands require `JAVA_HOME` to be set first:

```bash
export JAVA_HOME="C:/Users/BWAmackenzie/.jdks/temurin-11.0.31"

./gradlew compileJava       # verify it builds
./gradlew test              # run all tests (22 tests, must all pass)
./gradlew run               # launch RuneLite with the plugin loaded
./gradlew shadowJar         # build distributable JAR
```

Git is configured. Commit frequently. Branch is `main`.

---

## Existing Package Structure

```
src/main/java/com/exchangelens/
├── ExchangeLensPlugin.java       Main plugin — startUp/shutDown wiring
├── ExchangeLensConfig.java       @ConfigGroup("exchangelens") — all user settings
├── api/
│   ├── WikiPriceClient.java      HTTP fetcher — /latest, /5m, /1h, /mapping
│   └── WikiApiModels.java        Gson-deserialized API response models
├── model/
│   ├── MarketItem.java           Merged data from all three API endpoints
│   ├── FlipRecommendation.java   Scored recommendation — output of engine
│   ├── RiskLevel.java            Enum: LOW / MEDIUM / HIGH
│   ├── ItemMapping.java          Item metadata (name, limit, members)
│   ├── LatestPrice.java          (unused directly — merged into MarketItem)
│   └── AveragePrice.java         (unused directly — merged into MarketItem)
├── service/
│   ├── MarketDataService.java    Owns refresh cycle, merges data, calls engine
│   ├── RecommendationEngine.java Pure static rank() — no I/O
│   ├── StorageService.java       ONLY class that touches ConfigManager
│   ├── TaxService.java           floor(sell * 0.02) — only place tax is computed
│   ├── RiskModel.java            liquidity+stability → RiskLevel
│   ├── SafeMath.java             divide() / clamp() — guards div/0
│   └── PriceFormat.java          format() abbreviated, formatExact() with commas
└── ui/
    ├── ExchangeLensPanel.java    PluginPanel — tabbed sidebar
    ├── RecommendationCard.java   One card per recommendation
    ├── WatchlistPanel.java       Watchlist tab content
    └── SettingsPanel.java        Settings tab content
```

---

## Key Existing Classes You Will Modify

### ExchangeLensPlugin.java
The central wiring point. You will add `@Inject` fields for the three new classes and register/deregister them in `startUp()`/`shutDown()`.

Currently injects: `ClientToolbar`, `ExchangeLensConfig`, `ExchangeLensPanel`, `MarketDataService`, `StorageService`.

**Important:** The plugin does NOT currently inject `Client`, `ClientThread`, `OverlayManager`, or `EventBus`. You will need to add these injections. They are all standard RuneLite Guice bindings — just `@Inject` them.

```java
@Inject private Client client;
@Inject private ClientThread clientThread;
@Inject private EventBus eventBus;
```

For overlay-based approaches you also need:
```java
@Inject private OverlayManager overlayManager;
```

### MarketDataService.java
Owns the background refresh cycle. Calls `RecommendationEngine.rank()` and fires a consumer with the result.

**You need to add two methods:**

```java
// Cache the last recommendations so overlay classes can look up items
private volatile List<FlipRecommendation> lastRecommendations = new ArrayList<>();

public Optional<FlipRecommendation> getRecommendationForItem(int itemId) {
    return lastRecommendations.stream()
        .filter(r -> r.getItemId() == itemId)
        .findFirst();
}

public MarketItem getMarketItem(int itemId) {
    // Return the raw MarketItem for an item ID — needed for Phase 3 snapshots
    // Iterate latestData + mapping to build it on demand, or cache mergeItems() result
}
```

Update `notifyUpdate()` to populate `lastRecommendations` before firing the consumer:
```java
private void notifyUpdate() {
    ...
    List<FlipRecommendation> recs = RecommendationEngine.rank(...);
    this.lastRecommendations = recs;   // ← add this line
    onUpdate.accept(recs);
}
```

### ExchangeLensConfig.java
Add three new config items (see spec). Follow the exact pattern of existing items — `@ConfigItem` annotation, interface method, default value.

---

## New Classes to Create

Per the design spec, create these in the `overlay/` package:

```
overlay/
├── GePriceAdvisor.java
├── GeSlotColorizer.java
└── GeSellTooltipEnhancer.java
```

And these in `service/`:
```
service/
├── SlotTracker.java
├── PriceAdvisorStrategy.java    (interface)
└── PlusOneStrategy.java         (Stage A implementation)
```

---

## RuneLite APIs You Will Need

### Subscribing to events
All event subscription is via `@Subscribe` annotation + `eventBus.register(this)` / `eventBus.unregister(this)`.

```java
@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
    GrandExchangeOffer offer = event.getOffer();
    int slot = event.getSlot();
    // offer.getState() → GrandExchangeOfferState enum
    // offer.getItemId(), offer.getPrice(), offer.getQuantitySold()
}

@Subscribe
public void onScriptPostFired(ScriptPostFired event) {
    if (event.getScriptId() == 385) {
        // GE offer setup screen opened
    }
    if (event.getScriptId() == 149) {
        // GE slot container redrawn
    }
    if (event.getScriptId() == 526) {
        // GE tooltip script fired
    }
}
```

### Accessing widgets
```java
// GE offer container (buy/sell setup screen)
Widget offerContainer = client.getWidget(30, 0); // ComponentID constants vary by version
// Better: use WidgetInfo enum or search by script

// Tooltip widget
Widget tooltip = client.getWidget(193, 0);

// GE slot container (one per slot, 0–7)
Widget slotContainer = client.getWidget(465, 7 + slotIndex);
```

**Widget manipulation must always happen on the client thread:**
```java
clientThread.invokeLater(() -> {
    Widget w = client.getWidget(...);
    if (w == null || w.isHidden()) return;
    w.setTextColor(color);
    // or w.setText("new text")
});
```

### Creating a clickable widget child
```java
Widget parent = client.getWidget(...);
Widget child = parent.createChild(-1, WidgetType.TEXT);
child.setText("Set to Exchange Lens price: 60,009 gp");
child.setTextColor(0xFFD700);  // gold
child.setFontId(FontID.PLAIN_11);
child.setOriginalX(0);
child.setOriginalY(parent.getHeight() - 20);
child.setOriginalWidth(parent.getWidth());
child.setOriginalHeight(16);
child.setHasListener(true);
child.setOnOpListener((JavaScriptCallback) e -> fillPrice(suggestedPrice));
child.revalidate();
```

### Auto-filling the price field
```java
private void fillPrice(int price) {
    clientThread.invokeLater(() -> {
        client.runScript(ScriptID.GE_OFFER_PRICE, price);
        // or find the chatbox input widget and set its text
    });
}
```

### Coloring widget text
```java
widget.setTextColor(0x00B300);  // green
widget.setTextColor(0xCC0000);  // red
widget.setTextColor(0xFFFFFF);  // white (default)
```

---

## GrandExchangeOfferState Values

```java
GrandExchangeOfferState.EMPTY       // slot is empty
GrandExchangeOfferState.BUYING      // buy offer in progress
GrandExchangeOfferState.BOUGHT      // buy offer complete
GrandExchangeOfferState.SELLING     // sell offer in progress
GrandExchangeOfferState.SOLD        // sell offer complete
GrandExchangeOfferState.CANCELLED_BUY
GrandExchangeOfferState.CANCELLED_SELL
```

---

## Patterns to Follow

### Guice injection
Every new class that needs RuneLite services uses `@Inject` constructor injection — never `new`. Guice wires everything automatically as long as the class is `@Inject`-annotated and registered in `startUp()`.

### Thread safety
- **Wiki API calls** → background `ScheduledExecutorService` ("exchange-lens-refresh" thread)
- **Event handlers** (`@Subscribe`) → client thread
- **Widget manipulation** → client thread only (use `clientThread.invokeLater()` if unsure)
- **Swing/UI updates** → EDT only (use `SwingUtilities.invokeLater()`)
- **Never** touch widgets from the background thread or EDT

### Null safety on widgets
Widgets can be null at any time (interface not open, wrong game state). Always null-check:
```java
Widget w = client.getWidget(...);
if (w == null || w.isHidden()) return;
```

### Lombok models
All `model/` classes use `@Value @Builder` (immutable). New model classes should follow the same pattern.

### Config pattern
```java
@ConfigItem(
    keyName = "camelCaseKey",
    name = "Human Readable Name",
    description = "Shown in RuneLite config panel"
)
default boolean myOption() { return true; }
```

---

## What the Tests Cover

22 passing tests across 5 classes:
- `SafeMathTest` — divide, clamp
- `TaxServiceTest` — tax calculation
- `WikiApiModelsTest` — Gson deserialization
- `RiskModelTest` — risk level logic
- `RecommendationEngineTest` — full scoring pipeline

**None of the new Phase 1 classes need unit tests** — they are pure RuneLite widget manipulation which cannot be tested without the game client. However, `SlotTracker` and `PlusOneStrategy` are pure logic and should have tests.

`PlusOneStrategy` test:
```java
@Test
public void buyPriceIsPlusOne() {
    FlipRecommendation rec = buildRec(1000, 1200); // buy=1000, sell=1200
    assertEquals(1001, new PlusOneStrategy().suggestBuyPrice(rec));
    assertEquals(1199, new PlusOneStrategy().suggestSellPrice(rec));
}
```

`SlotTracker` test:
```java
@Test
public void slotTrackerRecordsBuyPrice() {
    SlotTracker tracker = new SlotTracker();
    // simulate BUYING event on slot 0 for item 4151 at price 1950000
    tracker.onOfferChanged(buildEvent(0, 4151, 1950000, GrandExchangeOfferState.BUYING));
    assertEquals(1950000, tracker.getBuyPrice(0));
    assertEquals(4151, tracker.getItemId(0));
    assertTrue(tracker.isBuySlot(0));
}

@Test
public void slotTrackerClearsOnEmpty() {
    SlotTracker tracker = new SlotTracker();
    tracker.onOfferChanged(buildEvent(0, 4151, 1950000, GrandExchangeOfferState.BUYING));
    tracker.onOfferChanged(buildEvent(0, 0, 0, GrandExchangeOfferState.EMPTY));
    assertEquals(0, tracker.getBuyPrice(0));
}
```

---

## Known Gotchas

1. **Widget IDs change between RuneLite versions.** If a widget lookup returns null, the component ID may have shifted. Check the RuneLite `ComponentID` class and the `WidgetInfo` enum for up-to-date IDs.

2. **ScriptPostFired script IDs.** IDs 385, 149, and 526 are documented in community resources but should be verified against a current RuneLite client during development. If injection isn't firing, add a debug log to `onScriptPostFired` that prints every script ID when the GE is open.

3. **Widget children persist across redraws.** If you inject a widget child and the parent redraws (e.g. player changes quantity), your child may disappear or duplicate. Always check for an existing injected child before creating a new one — use a `clientProperty` tag or text-prefix check.

4. **`GrandExchangeOfferChanged` fires for ALL accounts** if the player has multiple accounts. The `SlotTracker` is per-session so this is fine, but be aware it fires frequently.

5. **Tooltip widget 193,0 is shared.** Other plugins may also modify it. Append to existing text rather than replacing it, and guard against double-injection by checking for "Profit:" in the current text.

6. **`client.getWidget()` returns stale data.** Widget state is only valid on the client thread during the current game tick. Never cache widget references — always re-fetch.

---

## Commit Convention

Follow the existing commit message style:
```
feat: add GE price injection overlay
feat: add GE slot profit colorization  
feat: add sell tooltip profit display
fix: guard widget null on GE close
test: add SlotTracker unit tests
```

---

## Definition of Done

- [ ] `./gradlew test` — all 22 existing tests pass + new SlotTracker and PlusOneStrategy tests
- [ ] `./gradlew compileJava` — no warnings beyond existing deprecation notices
- [ ] `./gradlew run` — RuneLite launches, plugin loads without errors in log
- [ ] Opening a GE buy offer for a recommended item shows the Exchange Lens price injection
- [ ] Clicking the injected price fills the price field
- [ ] Active sell slots show green/red price text
- [ ] Hovering a sell offer shows "Profit: X gp" in the tooltip
- [ ] Disabling each feature in config correctly suppresses it
- [ ] No NullPointerExceptions when GE is closed or item has no recommendation
