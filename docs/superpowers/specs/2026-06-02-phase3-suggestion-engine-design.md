# Exchange Lens Phase 4 — Suggestion Engine Design Spec
Date: 2026-06-02

## Overview

The Suggestion Engine is a locally-hosted prediction service that replaces the static +1 pricing strategy from Phase 2 with a model that predicts **fill time** and **margin stability** for each candidate flip. The plugin calls it over localhost HTTP, exactly mirroring Flipping Copilot's architecture — but the server runs on your own machine, the model is trained on your own data, and the algorithm is fully transparent.

**Two-stage rollout:**
- **Stage A (heuristic):** Hand-crafted rules derived from Wiki API signals. Runs immediately with no training data required. Produces reasonable suggestions from day one.
- **Stage B (regression):** A trained model replacing Stage A rules once sufficient FlipTracker data has been collected (~2-4 weeks of active flipping). Stage A and Stage B share the same API contract — swapping is a server-side change with zero plugin changes required.

---

## System Architecture

```
┌─────────────────────────────────────┐
│           Exchange Lens Plugin       │
│                                      │
│  SuggestionClient ──────────────────┼──► POST localhost:7891/suggestion
│  (new, Phase 4)                      │         │
│                                      │         ▼
│  PriceAdvisorStrategy ◄─────────────┼──── SuggestionResponse
│  (replaces PlusOneStrategy)          │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│       Suggestion Engine Server       │
│       (Python, runs locally)         │
│                                      │
│  POST /suggestion                    │
│  POST /prices                        │
│  GET  /health                        │
│  GET  /model/status                  │
│                                      │
│  ┌──────────┐    ┌─────────────────┐ │
│  │ Heuristic│    │ Regression Model│ │
│  │  Engine  │ OR │ (Stage B only)  │ │
│  └──────────┘    └─────────────────┘ │
│         │                │           │
│         └──────┬─────────┘           │
│          FeatureBuilder               │
│          (Wiki API + FlipTracker CSV) │
└─────────────────────────────────────┘
```

The server is a standalone Python process. The plugin communicates with it over localhost HTTP. If the server is not running, the plugin falls back to the Phase 2 +1 strategy silently.

---

## Server: Technology Stack

| Component | Choice | Reason |
|---|---|---|
| Language | Python 3.11+ | Best ML ecosystem, fast iteration |
| Web framework | FastAPI | Async, auto-docs, minimal boilerplate |
| ML library | scikit-learn | Simple regression, no GPU needed |
| Data | pandas | FlipTracker CSV loading and feature engineering |
| Serialization | JSON | Simple, no protobuf complexity |
| Packaging | Single `server.py` + `requirements.txt` | Runnable with one command |

Server runs as: `python server.py --port 7891`

---

## Server: API Contract

### POST /suggestion

Request — current market state for all candidate items:

```json
{
  "available_cash": 10000000,
  "candidates": [
    {
      "item_id": 4151,
      "item_name": "Abyssal whip",
      "latest_low": 1950000,
      "latest_high": 2050000,
      "spread": 100000,
      "net_margin": 59000,
      "roi": 0.030,
      "five_min_volume": 12,
      "one_hour_volume": 48,
      "liquidity_score": 0.72,
      "velocity_score": 0.85,
      "stability_score": 0.91,
      "final_score": 0.81,
      "buy_limit": 70,
      "five_min_avg_low": 1945000,
      "five_min_avg_high": 2048000,
      "one_hour_avg_low": 1940000,
      "one_hour_avg_high": 2055000,
      "latest_low_time": 1748890234,
      "latest_high_time": 1748890198
    }
  ]
}
```

Response:

```json
{
  "item_id": 4151,
  "item_name": "Abyssal whip",
  "action": "buy",
  "suggested_buy_price": 1950001,
  "suggested_sell_price": 2049999,
  "predicted_buy_fill_seconds": 142,
  "predicted_sell_fill_seconds": 310,
  "predicted_margin_stability": 0.87,
  "confidence": 0.74,
  "model_stage": "heuristic",
  "reasoning": "High stability, strong hourly volume relative to limit, spread holding stable for 3 cycles"
}
```

`model_stage` is either `"heuristic"` or `"regression"` — the plugin displays this in the UI so you always know what's driving the suggestion.

`reasoning` is a human-readable explanation — always populated in Stage A (rules are explainable by definition), populated in Stage B if feature importance is high enough to explain.

### POST /prices

Request for a single item's predicted price trajectory:

```json
{ "item_id": 4151, "timeframe_minutes": 60 }
```

Response:

```json
{
  "item_id": 4151,
  "current_low": 1950000,
  "current_high": 2050000,
  "predicted_low_range": [1920000, 1980000],
  "predicted_high_range": [2030000, 2070000],
  "trend": "stable",
  "confidence": 0.68
}
```

Stage A returns a confidence-weighted range based on recent price variance. Stage B returns regression-predicted intervals.

### GET /health

```json
{ "status": "ok", "model_stage": "heuristic", "uptime_seconds": 3821 }
```

### GET /model/status

```json
{
  "stage": "heuristic",
  "training_samples_available": 847,
  "training_samples_needed_for_regression": 500,
  "last_trained": null,
  "ready_for_regression": true
}
```

The plugin displays this in the Settings tab so you can see how close you are to Stage B.

---

## Stage A: Heuristic Engine

All rules are implemented as scored signals combined into a final recommendation. Same items Exchange Lens already scores — but the heuristic engine adds **fill time prediction** and **margin stability prediction** that the existing `RecommendationEngine` doesn't compute.

### Fill Time Prediction (Stage A)

```python
def predict_fill_seconds(item: CandidateItem) -> int:
    """
    Estimate how long a buy order will take to fill.
    Based on: how much volume trades per minute relative to our order size.
    """
    # Volume per minute at the 1h rate
    volume_per_minute = item.one_hour_volume / 60.0

    # How many of our units will fill per minute (we compete with others)
    # Conservative: assume we get 20% of volume (competition factor)
    our_fill_rate = volume_per_minute * 0.20

    affordable_qty = min(item.buy_limit, available_cash // item.latest_low)
    estimated_minutes = affordable_qty / max(our_fill_rate, 0.1)

    return int(estimated_minutes * 60)
```

The `0.20` competition factor is a tunable constant. Stage B replaces this with a learned coefficient per item class.

### Margin Stability Prediction (Stage A)

```python
def predict_margin_stability(item: CandidateItem) -> float:
    """
    Probability that the spread will still be profitable by fill time.
    High price deviation between latest and 5m avg → unstable.
    Large spread relative to price → stable (more buffer).
    """
    if item.five_min_avg_low is None:
        return 0.5  # unknown — default to medium

    high_dev = abs(item.latest_high - item.five_min_avg_high) / item.five_min_avg_high
    low_dev  = abs(item.latest_low  - item.five_min_avg_low)  / item.five_min_avg_low
    avg_dev  = (high_dev + low_dev) / 2.0

    # Spread buffer: how much can the price move before the flip loses money?
    spread_buffer = item.net_margin / item.latest_low

    # Stability = spread buffer advantage minus deviation penalty
    raw = spread_buffer - (avg_dev * 2.0)
    return max(0.0, min(1.0, raw / 0.05))  # normalise to [0, 1]
```

### Ranking (Stage A)

```python
def rank_candidates(candidates: list, available_cash: int) -> CandidateItem:
    scored = []
    for item in candidates:
        fill_secs    = predict_fill_seconds(item)
        stability    = predict_margin_stability(item)
        fill_score   = max(0.0, 1.0 - (fill_secs / 1800.0))  # 30min = 0
        combined     = (stability * 0.50) + (fill_score * 0.30) + (item.final_score * 0.20)
        scored.append((combined, item))

    scored.sort(reverse=True)
    return scored[0][1] if scored else None
```

Stage A ranking weights: stability 50%, fill speed 30%, Exchange Lens score 20%. These are tunable constants.

---

## Stage B: Regression Model

Triggered manually via `GET /model/train` once `/model/status` shows `ready_for_regression: true`.

### Feature Vector (per flip)

Loaded directly from the FlipTracker CSV export:

```python
FEATURES = [
    'spread', 'net_margin', 'roi',
    'liquidity_score', 'velocity_score', 'stability_score', 'final_score',
    'five_min_volume', 'one_hour_volume',
    'five_min_avg_low', 'five_min_avg_high',
    'one_hour_avg_low', 'one_hour_avg_high',
    'buy_limit',
    'hour_of_day',        # derived from captured_at
    'day_of_week',        # derived from captured_at
    'price_tier',         # log10 bucket of latest_low (1k, 10k, 100k, 1m+)
]
```

### Labels

Two separate models — one per prediction target:

| Model | Label | Type |
|---|---|---|
| FillTimeModel | `buy_fill_seconds` | Regression (GradientBoostingRegressor) |
| MarginStabilityModel | `net_profit > 0` | Binary classification (LogisticRegression) |

### Training pipeline

```python
# GET /model/train
def train():
    df = pd.read_csv(FLIP_TRACKER_CSV_PATH)
    df = engineer_features(df)          # hour_of_day, day_of_week, price_tier
    df = df.dropna(subset=FEATURES)     # drop rows with missing market snapshots

    X = df[FEATURES]
    y_fill   = df['buy_fill_seconds']
    y_margin = (df['net_profit'] > 0).astype(int)

    fill_model   = GradientBoostingRegressor(n_estimators=100).fit(X, y_fill)
    margin_model = LogisticRegression(max_iter=1000).fit(X, y_margin)

    joblib.dump(fill_model,   'models/fill_time.pkl')
    joblib.dump(margin_model, 'models/margin_stability.pkl')

    return {"samples_used": len(df), "fill_r2": fill_model.score(X, y_fill)}
```

Models are persisted as `.pkl` files and loaded into memory on server start. If no `.pkl` exists, server starts in Stage A automatically.

### Minimum data requirement

500 completed flips before Stage B is enabled. Below this threshold the model is likely to overfit. `/model/status` tracks progress toward this threshold.

---

## Plugin Integration: SuggestionClient

New class in the plugin. Replaces `PlusOneStrategy` as the `PriceAdvisorStrategy` implementation when the server is reachable.

```java
// service/SuggestionClient.java
public class SuggestionClient implements PriceAdvisorStrategy {
    private static final String BASE_URL = "http://localhost:7891";
    private static final int TIMEOUT_MS  = 2000;  // never block the game thread

    private final OkHttpClient httpClient;
    private final Gson gson;
    private SuggestionResponse lastSuggestion;
    private boolean serverReachable = false;

    public SuggestionResponse fetchSuggestion(List<FlipRecommendation> candidates, int availableCash) {
        // Called on background thread by MarketDataService
        // Builds request, POSTs to /suggestion, stores lastSuggestion
    }

    @Override
    public int suggestBuyPrice(FlipRecommendation rec) {
        if (!serverReachable || lastSuggestion == null
                || lastSuggestion.getItemId() != rec.getItemId()) {
            return rec.getBuyPrice() + 1;  // fallback to +1
        }
        return lastSuggestion.getSuggestedBuyPrice();
    }

    @Override
    public int suggestSellPrice(FlipRecommendation rec) {
        if (!serverReachable || lastSuggestion == null
                || lastSuggestion.getItemId() != rec.getItemId()) {
            return rec.getSellPrice() - 1;  // fallback to -1
        }
        return lastSuggestion.getSuggestedSellPrice();
    }

    public boolean isServerReachable() { return serverReachable; }
    public String getModelStage() { return lastSuggestion != null ? lastSuggestion.getModelStage() : "offline"; }
}
```

### Fallback behaviour

| Server state | Behaviour |
|---|---|
| Running, Stage A | Full suggestions with heuristic predictions |
| Running, Stage B | Full suggestions with model predictions |
| Not running | Silent fallback to Phase 2 +1 strategy |
| Timeout (>2s) | Silent fallback, log warning |
| Error response | Silent fallback, log warning |

The player never sees an error. The only visible indication is the model stage badge in the UI.

### Call cadence

`SuggestionClient.fetchSuggestion()` is called from `MarketDataService.notifyUpdate()` on the background refresh thread — same cadence as recommendations. The response is cached as `lastSuggestion` and consumed by `GePriceAdvisor` on demand.

---

## UI Changes (Phase 4 additions)

### Settings tab additions

```
[ Suggestion Engine ]
Status: ● Running  Stage: Heuristic
Training samples: 847 / 500 ✓ Ready for Stage B
[ Train Stage B Model ]

Server port: [7891    ]
[ Test Connection ]
```

### RecommendationCard additions

Each card gains a predicted fill time and margin stability confidence from the server response:

```
Abyssal whip (m)
Buy: 1,950,001 gp   Sell: 2,049,999 gp
Margin: 58,998 gp   ROI: 3.02%   Risk: LOW
Fill: ~2m buy / ~5m sell   Stability: 87%
[Watch] [Block]
```

`Stability: 87%` replaces the static stability score with the server's prediction when available.

---

## Server Setup & Distribution

The server ships as a folder alongside the plugin documentation:

```
ExchangeLens/
├── suggestion-server/
│   ├── server.py
│   ├── requirements.txt        (fastapi, uvicorn, scikit-learn, pandas, joblib)
│   ├── models/                 (empty — populated after first training)
│   └── README.md               (setup instructions)
```

**First-time setup:**
```bash
pip install -r requirements.txt
python server.py
```

The server auto-detects the FlipTracker CSV path at `~/.runelite/exchangelens/` and loads the most recent account's data.

---

## Error Handling

- Server not running: plugin falls back to +1 strategy, no UI error shown
- `/suggestion` returns 500: log warning, fallback
- Server response missing fields: fallback per field, never crash
- Training fails (not enough data, corrupt CSV): return error JSON with reason, don't overwrite existing model
- Model file corrupt: delete `.pkl`, revert to Stage A automatically
- Port conflict: configurable port in Exchange Lens settings

---

## Out of Scope (Phase 4)

- GPU-accelerated models (unnecessary at this data scale)
- Time-series forecasting (ARIMA/LSTM) — possible future upgrade
- Cross-account model training
- Cloud hosting or multi-user support
- Automated retraining schedule (manual `Train` button only)
- Quantity suggestion (separate feature — requires GE limit window tracking from FlipTracker)
