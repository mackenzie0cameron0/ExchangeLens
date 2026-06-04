# Exchange Lens — Recommendations Tab Design Spec
Date: 2026-06-04

## Overview

A new **Recommendations** tab inside the existing Flip History pop-out window. It lists
the items Exchange Lens is currently recommending in a sortable table, and clicking a row
opens the same per-item drill-down chart used for trade history — now additionally
overlaying Exchange Lens's suggested **buy** and **sell** prices as horizontal reference
lines on the OSRS Wiki price curve. This lets the user browse current recommendations and
visually judge the suggested entry/exit against the live market, even for items they have
never flipped.

This builds directly on the Flip History Visualization feature
(`2026-06-03-flip-history-visualization-design.md`): it reuses `FlipHistoryWindow`,
`ItemDetailPanel`, `PriceChartComponent`, and `ChartModel`.

## Decisions (locked during brainstorming)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Where it lives | A third card in the existing `FlipHistoryWindow` CardLayout, reached from a top-bar `Recommendations` button | User asked for a browsable tab in the same window; reuses the existing chart drill-down |
| List presentation | Sortable `JTable` (Item, Margin, ROI, Risk, Score, Est. Fill) | Consistent with the Overview tab's table; compact and sortable |
| Numeric sorting | Table stores raw numbers behind a formatting renderer | Sorts correctly by value (the Overview table sorts formatted strings — not repeated here) |
| Chart for a recommended item | Reuse `ItemDetailPanel`, plus overlay suggested buy/sell | "Same format" as the existing drill-down, with rec-specific context added |
| Buy/sell overlay | Horizontal reference lines (green = buy, red = sell) on the Wiki curve | Shows recommended entry/exit against the real market; reusable chart primitive |
| Recommendation data source | `MarketDataService` volatile snapshot (`getLastRecommendations()`) | Already cached in-memory; no new fetching or threads |
| Refresh model | Snapshot on window open and each time the tab is shown | Simple; reflects the latest completed market fetch (YAGNI — no live subscription) |

## Architecture & Components

### New components

| Component | Package | Responsibility |
|-----------|---------|----------------|
| `RecommendationsPanel` | `ui` | Sortable `JTable` of current recommendations. Builds rows from `List<FlipRecommendation>`; numeric-correct sorting; risk-colored cell; row click fires `onItemSelected(itemId, itemName)`. No data fetching beyond reading the injected `MarketDataService` snapshot. |
| `RecommendationRow` | `ui` (small static helper, may live inside `RecommendationsPanel`) | Pure mapping from a `FlipRecommendation` to display cell values + raw sort keys. Unit-testable. |

### Modified components

| Component | Change |
|-----------|--------|
| `service/MarketDataService` | Add `public List<FlipRecommendation> getLastRecommendations()` returning the existing volatile `lastRecommendations` snapshot. |
| `ui/ChartModel` | Add `List<RefLine> refLines` and a `RefLine { double value; Color color; String label; }` inner class. |
| `ui/PriceChartComponent` | Draw each `RefLine` as a horizontal dashed line across the plot with a small right-edge label; include ref-line values in the auto-scale value set so the lines are always in view. |
| `ui/ItemDetailPanel` | `loadItem` gains a nullable `FlipRecommendation` parameter; when non-null, add a green buy ref line and red sell ref line to the chart model. |
| `ui/FlipHistoryWindow` | Add `RecommendationsPanel` as a third CardLayout card; add a `Recommendations` top-bar button; add `openRecommendationDetail(itemId, itemName)`; update the existing Overview→detail call to pass `null` for the new rec parameter. |

### Composition

`FlipHistoryWindow` composes three views (Overview, Recommendations, Item Detail) over a
`CardLayout`. `RecommendationsPanel` reads recommendations from `MarketDataService` and,
on row click, hands `(itemId, itemName)` back to the window. The window resolves the full
`FlipRecommendation` (via `MarketDataService.getRecommendationForItem`) plus the cached
flips for that item, then drives the shared `ItemDetailPanel`. The buy/sell overlay is a
generic `ChartModel`/`PriceChartComponent` primitive, not rec-specific.

## Data Flow

```
MarketDataService.lastRecommendations (volatile, refreshed each market fetch)
        │  getLastRecommendations()
        ▼
RecommendationsPanel.refresh()  → build table rows (EDT)
        │  row click → onItemSelected(itemId, itemName)
        ▼
FlipHistoryWindow.openRecommendationDetail(itemId, itemName)
        │  rec = MarketDataService.getRecommendationForItem(itemId)   (may be empty)
        │  flips = cached loadedFlips filtered later by ItemDetailPanel
        ▼
ItemDetailPanel.loadItem(itemId, itemName, flips, rec)
        │  builds ChartModel: Wiki avgHigh/avgLow lines + trade markers
        │  + RefLine(buyPrice, green) + RefLine(sellPrice, red)   when rec != null
        ▼
PriceChartComponent renders (Wiki history via HistoryDataService, unchanged)
```

- Recommendation reads happen on the EDT against the volatile snapshot — cheap, no I/O.
- Wiki `/timeseries` history is still fetched off-EDT by `HistoryDataService` (unchanged).
- Recommendations are a point-in-time snapshot taken when the window opens and when the
  Recommendations tab is shown; they reflect the last successful market fetch.

## Views

### RecommendationsPanel (table)

- Columns: **Item** (name), **Margin** (`netMargin` gp), **ROI** (`roi` %), **Risk**
  (LOW/MED/HIGH, colored: LOW `0x40C040`, MED `0xE0A800`, HIGH `0xE04040`), **Score**
  (`finalScore`), **Est. Fill** (`estimatedFillMinutes`, via `PriceFormat.formatFillTime`).
- Sortable by any column. Numeric columns sort by raw value (Integer/Double/Long backing
  values with a cell renderer for display), not by formatted string.
- Default sort: Score descending (matches the engine's own ranking).
- Row click → `onItemSelected.accept(itemId, itemName)`.
- Empty state (no recommendations available): a centered "No recommendations yet
  (waiting on market data)" message.

### ItemDetailPanel (reused, with overlay)

- Unchanged: item name header, 6h/1D/1W/1M/1Y range buttons, Wiki avgHigh/avgLow lines,
  the user's buy ▲ / sell ▼ markers + connectors, the per-item flip list below.
- New: when opened from the Recommendations tab, the chart also shows two horizontal
  reference lines — green at the suggested **buy** price, red at the suggested **sell**
  price — each labeled at the right edge (e.g. "buy 37,900" / "sell 38,911"). The chart's
  y-axis auto-scale includes these values so both lines are visible.
- When opened from the Overview tab, no reference lines are drawn (rec parameter null).

### FlipHistoryWindow (top bar)

- Top bar buttons: `Overview` and `Recommendations`, both always visible. Each shows its
  card. The item-detail chart is reached by clicking a row in either table; the user
  returns by clicking either top-bar button.

## Chart Overlay Primitive

`ChartModel`:
```
class RefLine { double value; Color color; String label; }
List<RefLine> refLines = new ArrayList<>();
```

`PriceChartComponent`:
- In the auto-scale value collection (the `yMin==0 && yMax==0` branch), also add every
  `refLines[i].value` so reference lines pull the y-range to include them.
- After drawing series and before/with markers, draw each ref line: a horizontal dashed
  line from `left` to `right` at `valueToPixelY(value)`, in `refLine.color`, with the
  label drawn just inside the right edge in the same color.

## Error Handling

| Situation | Behavior |
|-----------|----------|
| No recommendations cached (no fetch yet / logged out at startup) | Table empty-state message; no crash |
| Recommended item has no Wiki `/timeseries` data | Ref lines + any markers still draw on a bare time axis (existing graceful path) |
| `getRecommendationForItem` returns empty when opening detail | Chart opens with no ref lines (falls back to the plain item view) |
| Recommendation list changes while detail open | No live update; reopening the tab re-snapshots |

## Testing

Matches the existing JUnit 4 + pure-function style.

- **`RecommendationRow` mapping** — pure unit test: a sample `FlipRecommendation` maps to
  the expected display strings and raw sort keys (margin, ROI, score, fill).
- **Ref-line auto-scale inclusion** — if a small pure helper is extracted for the
  auto-scale value set, unit-test that ref-line values are included; otherwise covered by
  the existing `ChartScale` tests + manual verification.
- **Manual checklist** — open window → Recommendations tab lists current recs → sort by a
  column → click a row → chart shows Wiki lines + green buy / red sell ref lines → switch
  ranges → markers still show for flipped items → Overview→detail path shows no ref lines.

## Build Impact

None. Custom Java2D, no new dependencies; Java 11, existing Gradle and test framework.

## Out of Scope (v1)

- Live auto-refresh of the recommendations table while open (snapshot on open / tab show).
- A manual "refresh" button inside the window (the sidebar already refreshes market data).
- Recommendation filtering/search inside the window (the sidebar already filters by risk).
- Acting on a recommendation (placing offers) from this window.
- Fixing the Overview table's string-based sorting (separate, pre-existing; not in scope).

## File Manifest

**New**
```
ui/RecommendationsPanel.java
test/RecommendationRowTest.java
```

**Modified**
```
service/MarketDataService.java     (+ getLastRecommendations)
ui/ChartModel.java                 (+ RefLine, refLines list)
ui/PriceChartComponent.java        (+ draw ref lines, include in auto-scale)
ui/ItemDetailPanel.java            (+ nullable FlipRecommendation param → buy/sell ref lines)
ui/FlipHistoryWindow.java          (+ Recommendations card + top-bar button + openRecommendationDetail)
```
