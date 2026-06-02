# Exchange Lens Phase 2 — In-Game UI Enhancements Design Spec
Date: 2026-06-02

## Overview

Phase 2 adds three in-game UI enhancements that bridge the Exchange Lens sidebar recommendations directly into the Grand Exchange interface. The plugin moves from a passive display tool to an active trading assistant — surfacing the right price at the right moment without the player needing to alt-tab or context-switch.

**Phase 2 scope:** GE price injection, GE slot colorization, sell tooltip profit display. No trade tracking, no profit stats, no portfolio — those are Phase 3.

---

## Architecture

Phase 2 introduces two new layers to Exchange Lens:

```
Existing layers (unchanged)
  Network layer     WikiPriceClient         HTTP requests on background thread
  Data layer        MarketDataService        Merges datasets, owns refresh cycle
  Logic layer       RecommendationEngine     Pure calculation, no I/O
  UI layer          ExchangeLensPanel        Sidebar panel

New layers (Phase 2)
  Game event layer  GePriceAdvisor          Subscribes to GE widget events, injects UI
                    GeSlotColorizer          Colors GE slot price text by profitability
                    GeSellTooltipEnhancer    Injects profit line into sell tooltips
```

All three new classes are injected into `ExchangeLensPlugin` and registered/deregistered in `startUp()`/`shutDown()`.

### New package: `overlay/`

```
com.exchangelens/
├── overlay/
│   ├── GePriceAdvisor.java        Injects suggested price into GE offer setup screen
│   ├── GeSlotColorizer.java       Colors active GE slot price text green/red
│   └── GeSellTooltipEnhancer.java Injects profit line into GE sell tooltips
```

---

## Feature 1: GE Price Injection

### Behaviour

When the player opens a **Buy offer** or **Sell offer** setup screen for an item that Exchange Lens has a current recommendation for, a clickable line appears at the bottom of the GE interface:

```
Set to Exchange Lens price: 60,009 gp
```

Clicking it calls `setWidgetText` on the price input widget and fires the confirm script, auto-filling the price field exactly as if the player had typed it.

### Price Strategy

**Buy price:** `latestLow + 1`
One coin above the current insta-sell price — undercuts competing buyers, maximises fill speed.

**Sell price:** `latestHigh - 1`
One coin below the current insta-buy price — undercuts competing sellers.

This strategy is the initial implementation and will be replaced by the suggestion engine output in Phase 4. The price calculation is isolated in `PriceAdvisorStrategy` (a single interface with one method: `int suggestBuyPrice(FlipRecommendation)` / `int suggestSellPrice(FlipRecommendation)`) so swapping the strategy in Phase 4 requires changing one class only.

### Implementation Notes

- Subscribe to `ScriptPostFired` — script ID `385` fires when the GE offer setup screen opens
- Use `client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER)` to locate the injection point
- Create a new `Widget` child with `setType(WidgetType.TEXT)`, `setOnOpListener(...)`, `setHasListener(true)`
- If no recommendation exists for the current item, inject nothing — no empty widget
- Recommendation lookup: `MarketDataService.getRecommendationForItem(int itemId)` — new method returning `Optional<FlipRecommendation>`
- Only inject on the **client thread** (`clientThread.invokeLater`)

### Config

```java
@ConfigItem(keyName = "showPriceInjection", name = "Show Price Suggestions", 
    description = "Show Exchange Lens suggested price in GE offer screen")
default boolean showPriceInjection() { return true; }
```

---

## Feature 2: GE Slot Colorization

### Behaviour

Active GE slots show their price text colored based on profitability:

| State | Color |
|---|---|
| Sell offer, profit > 0 | Green `#00B300` |
| Sell offer, profit < 0 | Red `#CC0000` |
| Buy offer (in progress) | Default (white) |
| No recommendation for item | Default (white) |

Profit is calculated as: `(sellPrice - buyPrice - TaxService.calculate(sellPrice))` where `buyPrice` is taken from the recorded offer price of the matching active buy slot. If no matching buy slot exists, falls back to `latestLow` from the current recommendation.

### Implementation Notes

- Subscribe to `GrandExchangeOfferChanged` to track active offer prices per slot (0–7)
- Subscribe to `ScriptPostFired` script ID `149` (GE slot redraw) to recolor after each repaint
- Store `int[8] slotBuyPrices` — updated when a buy offer is placed, cleared when slot empties
- Use `widget.setTextColor(int color)` on the price text widget within each slot container
- Color only sell slots — buy slots remain default

### Config

```java
@ConfigItem(keyName = "colorizeSlots", name = "Colorize GE Slot Prices",
    description = "Color GE slot price text green/red based on profitability")
default boolean colorizeSlots() { return true; }

@Alpha
@ConfigItem(keyName = "profitableColor", name = "Profitable Color", description = "")
default Color profitableColor() { return new Color(0x00B300); }

@Alpha  
@ConfigItem(keyName = "unprofitableColor", name = "Unprofitable Color", description = "")
default Color unprofitableColor() { return new Color(0xCC0000); }
```

---

## Feature 3: Sell Tooltip Profit Enhancement

### Behaviour

When hovering over an active **sell offer** in the GE interface, the existing tooltip is extended with a profit line:

```
Selling: Dragon bones x 100
Price: 2,450 gp
Profit: 18,200 gp     ← injected by Exchange Lens
```

If the item has no recorded buy price and no recommendation, the profit line is omitted.

### Profit Calculation

```java
int sellPrice    = offer.getPrice();
int qty          = offer.getTotalQuantity();
int buyPrice     = slotBuyPrices[slot];   // from GrandExchangeOfferChanged
int tax          = TaxService.calculate(sellPrice);
int profitPerItem = sellPrice - tax - buyPrice;
int totalProfit   = profitPerItem * qty;
```

### Implementation Notes

- Subscribe to `ScriptPostFired` script ID `526` (GE tooltip script)
- Locate tooltip widget via `client.getWidget(193, 0)` (standard tooltip container)
- Append profit line using `widget.setText(existing + "<br>Profit: " + PriceFormat.formatExact(totalProfit) + " gp")`
- Resize tooltip height by ~14px to accommodate extra line
- Guard against double-injection: check if text already contains "Profit:" before appending

### Config

```java
@ConfigItem(keyName = "showTooltipProfit", name = "Show Profit in Tooltip",
    description = "Show estimated profit in GE sell offer tooltip")
default boolean showTooltipProfit() { return true; }
```

---

## Shared State: SlotTracker

All three features need to know what's in each GE slot. Rather than duplicating this in each class, a new `SlotTracker` service owns this state:

```java
// service/SlotTracker.java
public class SlotTracker {
    private final int[] buyPrices   = new int[8];   // price paid per slot
    private final int[] itemIds     = new int[8];   // item ID per slot
    private final boolean[] isBuy   = new boolean[8]; // buy vs sell

    public void onOfferChanged(GrandExchangeOfferChanged event) { ... }
    public int getBuyPrice(int slot) { ... }
    public int getItemId(int slot) { ... }
    public boolean isBuySlot(int slot) { ... }
}
```

`SlotTracker` is `@Singleton`, injected into `GePriceAdvisor`, `GeSlotColorizer`, and `GeSellTooltipEnhancer`. `ExchangeLensPlugin` subscribes it to `GrandExchangeOfferChanged` via the event bus.

---

## MarketDataService Addition

New method to support price injection lookup:

```java
public Optional<FlipRecommendation> getRecommendationForItem(int itemId) {
    return lastRecommendations.stream()
        .filter(r -> r.getItemId() == itemId)
        .findFirst();
}
```

`lastRecommendations` is a new field — `List<FlipRecommendation>` updated each time `notifyUpdate()` fires.

---

## PriceAdvisorStrategy Interface

Isolates the +1 strategy so Phase 4 can swap it out:

```java
// service/PriceAdvisorStrategy.java
public interface PriceAdvisorStrategy {
    int suggestBuyPrice(FlipRecommendation rec);
    int suggestSellPrice(FlipRecommendation rec);
}

// service/PlusOneStrategy.java  (Phase 2 implementation)
public class PlusOneStrategy implements PriceAdvisorStrategy {
    public int suggestBuyPrice(FlipRecommendation rec)  { return rec.getBuyPrice() + 1; }
    public int suggestSellPrice(FlipRecommendation rec) { return rec.getSellPrice() - 1; }
}
```

---

## ExchangeLensPlugin Changes

```java
@Inject private GePriceAdvisor        gePriceAdvisor;
@Inject private GeSlotColorizer       geSlotColorizer;
@Inject private GeSellTooltipEnhancer geSellTooltipEnhancer;
@Inject private SlotTracker           slotTracker;

@Override
protected void startUp() {
    // existing startup...
    overlayManager.add(gePriceAdvisor);
    eventBus.register(slotTracker);
    eventBus.register(geSlotColorizer);
    eventBus.register(geSellTooltipEnhancer);
}

@Override
protected void shutDown() {
    // existing shutdown...
    overlayManager.remove(gePriceAdvisor);
    eventBus.unregister(slotTracker);
    eventBus.unregister(geSlotColorizer);
    eventBus.unregister(geSellTooltipEnhancer);
}
```

---

## Error Handling

- Widget lookup failures (null widget): log debug, skip injection silently — never crash on widget absence
- No recommendation for item: inject nothing, color nothing, add no tooltip
- `GrandExchangeOfferChanged` with EMPTY state: clear slot in `SlotTracker`
- All widget manipulation on client thread only — never from background or EDT

---

## Out of Scope (Phase 2)

- Profit/session stats panel (Phase 3)
- Portfolio tracking (Phase 3)
- Quantity suggestion injection (Phase 4 — requires knowing your cash stack relative to limit)
- Price prediction (Phase 4 — requires trained model)
- GE NPC / banker highlighting (Phase 4)
