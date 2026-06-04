# Volume Bar Readability — Design Spec

**Date:** 2026-06-04
**Status:** Approved (design)
**Scope:** Small enhancement to the existing detail-chart volume bars in `PriceChartComponent`.

## Goal

Make the trade-volume bars on the per-item price chart easier to read, and let the
user inspect exact volume figures by hovering — the same way the price line series
already supports a hover readout.

This is a follow-on to the Recommendations tab work (branch
`feature/recommendations-tab`) and must land before that branch merges.

## Background / Current State

`PriceChartComponent` already draws volume bars at the bottom of the chart:

- Colors `VOL_HIGH = Color(0x5E,0x7F,0xFF,40)` (blue) and `VOL_LOW = Color(0xFF,0x9B,0x44,40)`
  (orange) — both at **alpha 40/255 (~16% opacity)** over the dark background `BG (0x1C1C1C)`.
  The low opacity is why they are hard to read.
- `drawVolume(...)` renders the bars in the bottom **15%** band of the plot
  (`volTop = bottom - 0.15*(bottom-top)`, `volBottom = bottom`), scaled to the max
  of `highVolumes`/`lowVolumes`. Data comes from the Wiki timeseries
  (`highPriceVolume` / `lowPriceVolume`) via `ItemDetailPanel.buildModel`.
- The line-series hover already works: `updateHover()` finds the nearest sample at
  the cursor's x and sets `hoveredTooltip` (timestamp + value) plus a snapped
  crosshair (`hoverSnapX`) and highlight dots; `drawTooltip()` renders the box.
  **Volume bars currently have no hover readout.**

## Requirements

### R1 — Boost volume bar contrast ~30%
Increase the alpha of `VOL_HIGH` and `VOL_LOW` from **40 → 52** (40 × 1.3), leaving
hue/RGB unchanged. Blue still = high, orange still = low (consistent with the
Avg High / Avg Low line colors). Literal +30%; if still too faint when viewed
in-game, the value can be raised further in a follow-up — not a redesign.

### R2 — Volume hover tooltip (band-scoped)
When the cursor is inside the bottom volume band **and** the model has volume data,
show a tooltip for the nearest volume bucket:

```
MM/dd HH:mm
High vol: 1,234,567
Low vol:    987,654
```

- Reuse the existing `drawTooltip` and the `hoveredTooltip` / `hoverSnapX` mechanism;
  the crosshair snaps to the hovered bucket's x, mirroring the line readout.
- **Trigger scope (decision: A):** the volume tooltip appears **only** when the
  cursor's y is within the volume band. Hovering the price area above the band still
  shows the existing price-line readout, unchanged.
- **Priority:** marker hover keeps top priority (richest per-trade tooltip); volume
  band hover is checked next; the line-series readout is the fallback.
- Timestamp uses the existing `formatTimestamp` helper (HH:mm for ≤1d ranges,
  MM/dd HH:mm otherwise). Volume numbers use `PriceFormat.formatExact` (exact, with
  thousands separators) to match the precision of the line readout.
- Show the nearest bucket's actual high/low values even if one is zero.

## Design

All changes are confined to `PriceChartComponent.java`.

1. **Constants:** change the two `VOL_*` alpha literals 40 → 52 (R1). Extract the
   band fraction into a shared constant `VOLUME_BAND_FRACTION = 0.15` so `drawVolume`
   and the new hit-test agree on the band geometry (replacing the inline `0.15`).

2. **`updateHover()` volume branch (R2):** after the marker-priority loop and before
   the line-series readout, add:
   - Guard: `model.volumeTimestamps != null && length > 0` **and** at least one
     volume value `> 0` (mirrors `drawVolume`'s `maxVol == 0` early return — no
     volume tooltip when there are no visible bars).
   - Compute the band: `volTop = bottom - (int)((bottom-top)*VOLUME_BAND_FRACTION)`,
     `volBottom = bottom`.
   - If `mouseY` is within `[volTop, volBottom]` and `mouseX` within `[left, right]`:
     find the nearest bucket index by `|mouseX - timeToPixelX(volumeTimestamps[i])|`,
     set `hoverSnapX` to that bucket's x, build the tooltip string (timestamp +
     High vol / Low vol lines), and `return` (volume wins inside its band).
   - No highlight dots for volume (bars aren't points) — crosshair snap + tooltip only.

The existing paint path already renders `hoveredTooltip` and the snapped crosshair
when the cursor is in the plot, so no change to `paintComponent` is required beyond
what the constant extraction touches.

## Out of Scope

- The recommendation's own `fiveMinVolume` / `oneHourVolume` / liquidity metrics
  (not requested here).
- A volume column in the Recommendations table.
- Highlighting/brightening the specific hovered bar.
- Any change to how volume data is fetched or to `markersOnlyModel` (which has no
  volume data and therefore no volume tooltip — acceptable).

## Verification

- `gradlew compileJava` and full `gradlew test` pass (coordinate math already covered
  by `ChartScaleTest`; no new unit tests — consistent with the existing rendering code,
  which is verified manually).
- Manual in-game check: hovering the bottom volume band shows the timestamp + High/Low
  vol readout with a snapped crosshair; hovering the price area still shows the price
  readout; bars are visibly more readable.
