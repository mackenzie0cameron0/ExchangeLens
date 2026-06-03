# Exchange Lens — Flip History Visualization Design Spec
Date: 2026-06-03

## Overview

A pop-out visualization window for reviewing completed flips. It provides two
equally-weighted views: an **overview dashboard** (aggregate performance across all
trades) and a **per-item drill-down** that overlays your actual buy/sell trades on the
item's OSRS Wiki price history. The headline capability is the per-item overlay — seeing
your trade markers sitting directly on the real market price curve, so you can visually
judge whether you bought low / sold high relative to the market.

All charts are rendered with custom Java2D (no new dependencies). Historical price data
comes from the OSRS Wiki `/timeseries` endpoint, which the plugin does not currently use.

## Decisions (locked during brainstorming)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Location | Resizable pop-out `JFrame`, opened from a sidebar button | The sidebar (~225px) is too narrow for comparison charts; a window gives room while staying in-client |
| Scope | Overview dashboard **and** per-item drill-down, equal priority | User wants both aggregate review and per-item market comparison |
| History source | OSRS Wiki `/timeseries` endpoint | The only source of historical per-item price/volume; supports the trade-vs-market overlay |
| History depth | Range selector (6h / 1D / 1W / 1M / 1Y) | Covers recent fills through long-term trends |
| Timestep handling | **Raw rendering, native timesteps only** (no downsampling/aggregation) | Simpler; the Wiki API only accepts `5m`/`1h`/`6h`/`24h` |
| Charting | Custom Java2D `JComponent` | No dependency baggage; full control of the trade overlay + RuneLite theming; the overlay is custom either way |

## Architecture & Components

A new pop-out window with its own data services, reusing existing plugin patterns
(Guice `@Inject`, daemon I/O executor, `SwingUtilities.invokeLater` for EDT handoff).

### New components

| Component | Package | Responsibility |
|-----------|---------|----------------|
| `FlipHistoryWindow` | `ui` | `JFrame` shell (resizable, ~900×600 default). `CardLayout` over the two views + a top bar to switch views and show the current account. |
| `OverviewDashboardPanel` | `ui` | Aggregate stat cards, cumulative-profit chart, sortable per-item summary table. Row click → item detail view. |
| `ItemDetailPanel` | `ui` | Hero overlay chart (Wiki lines + trade markers) + range buttons + that item's flip list. |
| `PriceChartComponent` | `ui` | Reusable Java2D renderer. Draws line series, point markers, optional bars from a `ChartModel`; auto-scales axes; crosshair + hover tooltip. No data fetching inside. |
| `ChartModel` | `ui` | Plain data holder describing what to draw (series, markers, bars, x-range). Built by the panels, consumed by `PriceChartComponent`. |
| `ChartScale` | `ui` | Pure static helpers for the coordinate math (time/value ↔ pixel, axis bounds auto-scaling). Extracted from `PriceChartComponent` so it is unit-testable without rendering. |
| `HistoryDataService` | `service` | Fetches/caches Wiki `/timeseries` off the client thread; loads `FlipRecord`s via `FlipRepository`. |
| `FlipAnalytics` | `tracker` | Pure functions for aggregates (cumulative profit, per-item rollups, win rate, best/worst). No RuneLite deps. Sits beside `SessionStatsCalculator`. |

### Modified components

| Component | Change |
|-----------|--------|
| `api/WikiPriceClient` | Add `fetchTimeseries(int itemId, String timestep)`. Reuses existing `fetchRaw` + User-Agent. |
| `api/WikiApiModels` | Add `TimeseriesResponse` and `TimeseriesPoint` models. |
| `ui/ExchangeLensPanel` | Add a button (next to the `≡` history nav) that opens `FlipHistoryWindow`. |
| `ExchangeLensPlugin` | Lazily construct/inject `FlipHistoryWindow` on first open; supply the current account name. Dispose the window in `shutDown`. |

### Composition

The window composes the two views; views compose `PriceChartComponent`; all data and
computation live behind `HistoryDataService` and `FlipAnalytics`. Each unit is
single-purpose and independently testable.

## Data Layer

### Wiki `/timeseries` hook

```
GET https://prices.runescape.wiki/api/v1/osrs/timeseries?timestep={5m|1h|6h|24h}&id={itemId}
→ { "data": [ { timestamp, avgHighPrice, avgLowPrice, highPriceVolume, lowPriceVolume }, ... ] }
```

Returns up to **365 data points** per call. Valid timesteps are **only** `5m`, `1h`,
`6h`, `24h` (verified against the OSRS Wiki Real-time Prices documentation). New models:

- `TimeseriesResponse { List<TimeseriesPoint> data; }`
- `TimeseriesPoint { long timestamp; Integer avgHighPrice; Integer avgLowPrice; Integer highPriceVolume; Integer lowPriceVolume; }` (boxed — fields may be null when no trades occurred in a bucket)

### Range buttons

All buttons render the raw native series for their timestep — no aggregation.

| Button | timestep | Window shown | Points |
|--------|----------|--------------|--------|
| 6h | `5m` | last 6h | ~72 |
| 1D | `5m` | last 24h | ~288 |
| 1W | `1h` | last 7d | ~168 |
| 1M | `6h` | last 30d | ~120 |
| 1Y | `24h` | last 365d | ~365 |

The **6h and 1D buttons share a single cached `5m` fetch** (a `5m` series covers ~30h);
they differ only in the x-axis window. So a fully-explored item makes at most **four**
`/timeseries` calls (one per native timestep).

### HistoryDataService

- `getTimeseries(itemId, timestep, callback)` — checks an in-memory cache keyed by
  `(itemId, timestep)`; on miss, fetches on a **daemon single-thread executor** (same
  idiom as `FlipTrackerService.ioExecutor`), then delivers to the EDT via
  `SwingUtilities.invokeLater`. Cache TTL = the timestep interval (`5m` data refetches
  after 5 min; `24h` is effectively static for a session). Cache is a concurrent map.
- `loadFlips(accountName)` — delegates to `FlipRepository.loadAll(...)`, held in memory
  while the window is open; the per-item view filters by `itemId`.

### Account resolution

The window loads flips for the currently logged-in account
(`client.getLocalPlayer().getName()`). If opened while logged out, it falls back to the
most recently written flips file. v1 handles one account at a time — no account picker.

## Views

### PriceChartComponent (reusable Java2D `JComponent`)

Renders purely from a `ChartModel`:
- **Line series** (timestamped values) → polylines (e.g. Wiki avgHigh, avgLow, cumulative profit)
- **Point markers** (timestamp, value, color, label) → trade dots
- **Bars** (optional) → volume
- Auto y-scaling, time x-axis labels, gp y-axis labels (via existing `PriceFormat`),
  gridlines, and a crosshair + tooltip on `mouseMoved`.

The coordinate math (time/value → pixel x/y, axis bounds auto-scaling) is extracted into
pure helper methods so it can be unit-tested independently of `paint()`.

### ItemDetailPanel (hero view)

- Header: item name + the 5 range buttons.
- Chart: Wiki **avgHigh** and **avgLow** lines (the market's sell/buy sides) with the
  user's trades overlaid — green ▲ at each buy `(buyOffer.completedAt, buyPrice)`, red ▼
  at each sell `(sellOffer.completedAt, sellPrice)`, and a faint connector linking a buy
  to its matching sell. Hovering a marker shows that flip's qty / price / profit / fill time.
- Below: the list of completed flips for that item, reusing the row style from
  `FlipHistoryPanel`.

### OverviewDashboardPanel (landing view)

- Top: stat cards — total profit, total flips, win rate (% profitable), total tax paid,
  best & worst flip.
- Middle: a **cumulative-profit-over-time** line (same `PriceChartComponent`, no Wiki overlay).
- Bottom: sortable per-item summary table — item, # flips, total profit, avg ROI, avg
  fill time. Row click → that item's detail view.

All aggregates are computed by `FlipAnalytics`; panels only render returned values.

## Error Handling

The window degrades gracefully and never throws to the EDT.

| Situation | Behavior |
|-----------|----------|
| Wiki `/timeseries` fails / times out | Chart shows "No Wiki data", logs a warning — trade markers still render (independent of Wiki data) |
| Item has trades but no Wiki data in range | Markers plot on a bare time axis |
| Wiki data present but trades outside window | Price line only; switching range brings trades into view |
| No flip history | "No trades yet" in both views |
| Opened while logged out | Falls back to most-recent flips file; if none, "No trade history found" |
| Malformed/partial JSON | Guarded `Gson` parse (same as existing `fetch()`) → treated as "No Wiki data" |
| Incomplete flips (sell side missing) | `FlipAnalytics` counts only completed buy+sell pairs, mirroring `FlipTrackerService.computeOutcome` |

All fetches run off the EDT; cache access is concurrent; UI updates go through `invokeLater`.

## Testing

Matches the existing JUnit 4 + pure-function style.

- **`FlipAnalytics`** — pure unit tests: cumulative profit ordering, win rate, per-item
  rollups, best/worst, skipping incomplete flips. Mirrors `SessionStatsCalculatorTest`.
- **`WikiApiModels` timeseries parsing** — Gson round-trip on a sample payload.
- **Timestep → window mapping** — pure, unit-tested.
- **`ChartScale` coordinate math** — test the pure helpers (time/value → pixel,
  auto-scale bounds); `PriceChartComponent.paint()` itself is verified manually.
- **Manual checklist** — open window → overview renders → click item → Wiki lines +
  markers → switch ranges → hover tooltips → logged-out fallback.

## Build Impact

None. Custom Java2D means no new dependencies; still Java 11 bytecode, existing Gradle
setup and test framework.

## Out of Scope (v1)

- Multi-account picker (one account at a time).
- Downsampling / custom non-native timesteps.
- Exporting charts as images.
- Zoom/pan beyond the fixed range buttons.
- Forecasting or predicted-price bands (that is Phase 4 Suggestion Engine territory).
- Bundled charting libraries.

## File Manifest

**New**
```
ui/FlipHistoryWindow.java
ui/OverviewDashboardPanel.java
ui/ItemDetailPanel.java
ui/PriceChartComponent.java
ui/ChartModel.java
ui/ChartScale.java
service/HistoryDataService.java
tracker/FlipAnalytics.java
test/FlipAnalyticsTest.java
test/TimeseriesParsingTest.java
test/ChartScaleTest.java          (coordinate-math helpers)
```

**Modified**
```
api/WikiPriceClient.java          (+ fetchTimeseries)
api/WikiApiModels.java            (+ TimeseriesResponse, TimeseriesPoint)
ui/ExchangeLensPanel.java         (+ open-window button)
ExchangeLensPlugin.java           (lazily construct/dispose window, supply account name)
```
