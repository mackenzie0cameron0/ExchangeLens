# Phase 3 Suggestion Engine — Implementation Handoff
Date: 2026-06-02

## What You Are Building

A locally-hosted Python prediction server and a Java plugin client that together replace the static +1 pricing strategy from Phase 1 with a scored, explainable suggestion system. The server runs on your machine, the plugin calls it over localhost HTTP, and if the server is offline the plugin silently falls back to the +1 strategy with zero user impact.

Two deliverables:
1. **`suggestion-server/`** — standalone Python FastAPI server
2. **Plugin additions** — `SuggestionClient.java`, `SuggestionResponse.java`, UI status display

Read the full design spec at:
`docs/superpowers/specs/2026-06-02-phase3-suggestion-engine-design.md`

---

## Prerequisites

**Phase 1 and Phase 2 must be implemented first.**

Phase 3 depends on:
- `PriceAdvisorStrategy` interface (Phase 1) — `SuggestionClient` implements this
- `PlusOneStrategy` (Phase 1) — used as fallback when server is offline
- `FlipRepository.exportCsv()` (Phase 2) — provides the training dataset
- `MarketDataService.getRecommendationForItem()` (Phase 1) — used to build request payload
- `lastRecommendations` list in `MarketDataService` (Phase 1) — the candidate list sent to the server

---

## Project Location & Build

Plugin (Java):
```
F:\ClaudeProject\ExchangeLens\
```

Server (Python):
```
F:\ClaudeProject\ExchangeLens\suggestion-server\
```

Java build commands:
```bash
export JAVA_HOME="C:/Users/BWAmackenzie/.jdks/temurin-11.0.31"
./gradlew compileJava
./gradlew test
./gradlew run
```

Python server commands:
```bash
cd F:\ClaudeProject\ExchangeLens\suggestion-server
pip install -r requirements.txt
python server.py                    # starts on port 7891
python server.py --port 8000        # custom port
```

---

## Part 1: Python Server

### Directory structure

```
suggestion-server/
├── server.py               Entry point — FastAPI app, all routes
├── heuristic.py            Stage A scoring logic
├── regression.py           Stage B model training and prediction
├── features.py             Feature engineering shared by both stages
├── models/                 .pkl files written here after training (gitignored)
├── requirements.txt
└── README.md
```

### requirements.txt

```
fastapi==0.111.0
uvicorn==0.29.0
scikit-learn==1.4.2
pandas==2.2.2
joblib==1.4.0
```

### server.py skeleton

```python
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Optional
import uvicorn, argparse, joblib, os
from heuristic import HeuristicEngine
from regression import RegressionEngine

app = FastAPI(title="Exchange Lens Suggestion Server")
heuristic = HeuristicEngine()
regression = RegressionEngine()

# ── Request / Response models ──────────────────────────────────────────────

class CandidateItem(BaseModel):
    item_id: int
    item_name: str
    latest_low: int
    latest_high: int
    spread: int
    net_margin: int
    roi: float
    five_min_volume: int
    one_hour_volume: int
    liquidity_score: float
    velocity_score: float
    stability_score: float
    final_score: float
    buy_limit: int
    five_min_avg_low: Optional[int] = None
    five_min_avg_high: Optional[int] = None
    one_hour_avg_low: Optional[int] = None
    one_hour_avg_high: Optional[int] = None
    latest_low_time: Optional[int] = None
    latest_high_time: Optional[int] = None

class SuggestionRequest(BaseModel):
    available_cash: int
    candidates: List[CandidateItem]

class SuggestionResponse(BaseModel):
    item_id: int
    item_name: str
    action: str                         # "buy"
    suggested_buy_price: int
    suggested_sell_price: int
    predicted_buy_fill_seconds: int
    predicted_sell_fill_seconds: int
    predicted_margin_stability: float
    confidence: float
    model_stage: str                    # "heuristic" or "regression"
    reasoning: str

# ── Routes ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    stage = "regression" if regression.is_loaded() else "heuristic"
    return {"status": "ok", "model_stage": stage}

@app.get("/model/status")
def model_status():
    samples = regression.count_training_samples()
    return {
        "stage": "regression" if regression.is_loaded() else "heuristic",
        "training_samples_available": samples,
        "training_samples_needed_for_regression": 500,
        "ready_for_regression": samples >= 500,
        "last_trained": regression.last_trained_timestamp()
    }

@app.get("/model/train")
def train_model():
    result = regression.train()
    return result

@app.post("/suggestion", response_model=SuggestionResponse)
def get_suggestion(req: SuggestionRequest):
    if regression.is_loaded():
        return regression.suggest(req.candidates, req.available_cash)
    return heuristic.suggest(req.candidates, req.available_cash)

@app.post("/prices")
def get_prices(item_id: int, timeframe_minutes: int = 60):
    # Phase 3 stretch goal — return price range prediction for single item
    # Stub for now: return current prices with no prediction
    return {"item_id": item_id, "trend": "unknown", "confidence": 0.0}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7891)
    args = parser.parse_args()
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="info")
```

### heuristic.py

```python
import math
from typing import List

FILL_COMPETITION_FACTOR = 0.20   # assume we get 20% of hourly volume
STABILITY_DIVERGENCE_CAP = 0.10  # 10% price divergence = 0 stability

class HeuristicEngine:

    def predict_buy_fill_seconds(self, item, available_cash: int) -> int:
        volume_per_minute = max(item.one_hour_volume / 60.0, 0.1)
        our_fill_rate     = volume_per_minute * FILL_COMPETITION_FACTOR
        affordable_qty    = min(item.buy_limit, available_cash // max(item.latest_low, 1))
        minutes           = affordable_qty / our_fill_rate
        return int(minutes * 60)

    def predict_sell_fill_seconds(self, item) -> int:
        # Sell fills ~1.5x slower than buy on average (more competition on sell side)
        buy_fill = self.predict_buy_fill_seconds(item, item.latest_low * item.buy_limit)
        return int(buy_fill * 1.5)

    def predict_margin_stability(self, item) -> float:
        if item.five_min_avg_low is None or item.five_min_avg_high is None:
            return 0.5
        high_dev  = abs(item.latest_high - item.five_min_avg_high) / max(item.five_min_avg_high, 1)
        low_dev   = abs(item.latest_low  - item.five_min_avg_low)  / max(item.five_min_avg_low, 1)
        avg_dev   = (high_dev + low_dev) / 2.0
        buffer    = item.net_margin / max(item.latest_low, 1)
        raw       = buffer - (avg_dev * 2.0)
        return max(0.0, min(1.0, raw / 0.05))

    def score(self, item, available_cash: int) -> float:
        fill_secs  = self.predict_buy_fill_seconds(item, available_cash)
        stability  = self.predict_margin_stability(item)
        fill_score = max(0.0, 1.0 - (fill_secs / 1800.0))  # 30 min = score 0
        return (stability * 0.50) + (fill_score * 0.30) + (item.final_score * 0.20)

    def suggest(self, candidates: List, available_cash: int):
        if not candidates:
            return None
        scored = [(self.score(c, available_cash), c) for c in candidates]
        scored.sort(key=lambda x: x[0], reverse=True)
        best_score, best = scored[0]

        fill_secs  = self.predict_buy_fill_seconds(best, available_cash)
        sell_secs  = self.predict_sell_fill_seconds(best)
        stability  = self.predict_margin_stability(best)

        return {
            "item_id": best.item_id,
            "item_name": best.item_name,
            "action": "buy",
            "suggested_buy_price":  best.latest_low + 1,
            "suggested_sell_price": best.latest_high - 1,
            "predicted_buy_fill_seconds":  fill_secs,
            "predicted_sell_fill_seconds": sell_secs,
            "predicted_margin_stability":  round(stability, 3),
            "confidence": round(best_score, 3),
            "model_stage": "heuristic",
            "reasoning": self._build_reasoning(best, stability, fill_secs)
        }

    def _build_reasoning(self, item, stability: float, fill_secs: int) -> str:
        parts = []
        if stability >= 0.8:  parts.append("stable spread")
        elif stability < 0.4: parts.append("volatile spread")
        if fill_secs < 300:   parts.append("fast fill expected")
        elif fill_secs > 900: parts.append("slow fill expected")
        if item.one_hour_volume > item.buy_limit * 2: parts.append("high volume")
        return ", ".join(parts) if parts else "within normal parameters"
```

### regression.py

```python
import pandas as pd
import joblib
import os
from datetime import datetime
from pathlib import Path
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler

MODELS_DIR    = Path(__file__).parent / "models"
FLIP_CSV_PATH = Path.home() / ".runelite" / "exchangelens"
MIN_SAMPLES   = 500

FEATURES = [
    'spread', 'net_margin', 'roi',
    'liquidity_score', 'velocity_score', 'stability_score', 'final_score',
    'five_min_volume', 'one_hour_volume',
    'five_min_avg_low', 'five_min_avg_high',
    'one_hour_avg_low', 'one_hour_avg_high',
    'buy_limit', 'hour_of_day', 'day_of_week', 'price_tier'
]

class RegressionEngine:
    def __init__(self):
        self.fill_model   = None
        self.margin_model = None
        self.scaler       = None
        self._last_trained = None
        self._try_load()

    def is_loaded(self) -> bool:
        return self.fill_model is not None

    def last_trained_timestamp(self):
        return self._last_trained.isoformat() if self._last_trained else None

    def count_training_samples(self) -> int:
        try:
            dfs = [pd.read_csv(f) for f in FLIP_CSV_PATH.glob("flips-*.csv")]
            return sum(len(df) for df in dfs) if dfs else 0
        except Exception:
            return 0

    def train(self) -> dict:
        try:
            dfs = [pd.read_csv(f) for f in FLIP_CSV_PATH.glob("flips-*.csv")]
            if not dfs:
                return {"error": "No CSV files found in ~/.runelite/exchangelens/"}
            df = pd.concat(dfs, ignore_index=True)
            df = self._engineer_features(df)
            df = df.dropna(subset=FEATURES + ['buy_fill_seconds', 'net_profit'])
            if len(df) < MIN_SAMPLES:
                return {"error": f"Need {MIN_SAMPLES} samples, have {len(df)}"}

            X = df[FEATURES]
            y_fill   = df['buy_fill_seconds']
            y_margin = (df['net_profit'] > 0).astype(int)

            self.scaler       = StandardScaler().fit(X)
            X_scaled          = self.scaler.transform(X)
            self.fill_model   = GradientBoostingRegressor(n_estimators=100, random_state=42).fit(X_scaled, y_fill)
            self.margin_model = LogisticRegression(max_iter=1000).fit(X_scaled, y_margin)

            MODELS_DIR.mkdir(exist_ok=True)
            joblib.dump(self.fill_model,   MODELS_DIR / "fill_time.pkl")
            joblib.dump(self.margin_model, MODELS_DIR / "margin_stability.pkl")
            joblib.dump(self.scaler,       MODELS_DIR / "scaler.pkl")
            self._last_trained = datetime.now()

            return {
                "samples_used": len(df),
                "fill_r2": round(self.fill_model.score(X_scaled, y_fill), 3)
            }
        except Exception as e:
            return {"error": str(e)}

    def suggest(self, candidates: list, available_cash: int) -> dict:
        # Build feature rows for all candidates, predict, return best
        # Implementation: convert each CandidateItem to a feature row,
        # run through scaler + models, score = margin_prob * 0.6 + fill_score * 0.4
        ...  # full implementation follows spec

    def _engineer_features(self, df: pd.DataFrame) -> pd.DataFrame:
        df['hour_of_day']  = pd.to_datetime(df['captured_at'], unit='s').dt.hour
        df['day_of_week']  = pd.to_datetime(df['captured_at'], unit='s').dt.dayofweek
        df['price_tier']   = df['latest_low'].apply(
            lambda x: int(len(str(int(x))) / 1) if x > 0 else 0)
        return df

    def _try_load(self):
        try:
            if (MODELS_DIR / "fill_time.pkl").exists():
                self.fill_model   = joblib.load(MODELS_DIR / "fill_time.pkl")
                self.margin_model = joblib.load(MODELS_DIR / "margin_stability.pkl")
                self.scaler       = joblib.load(MODELS_DIR / "scaler.pkl")
                self._last_trained = datetime.fromtimestamp(
                    os.path.getmtime(MODELS_DIR / "fill_time.pkl"))
        except Exception:
            self.fill_model = None  # corrupt models → fall back to heuristic
```

---

## Part 2: Java Plugin — SuggestionClient

### New classes

```
service/
├── SuggestionClient.java       Implements PriceAdvisorStrategy, calls server
└── SuggestionResponse.java     Gson-deserialized server response
```

### SuggestionResponse.java

```java
package com.exchangelens.service;

public class SuggestionResponse {
    public int    itemId;
    public String itemName;
    public String action;
    public int    suggestedBuyPrice;
    public int    suggestedSellPrice;
    public int    predictedBuyFillSeconds;
    public int    predictedSellFillSeconds;
    public double predictedMarginStability;
    public double confidence;
    public String modelStage;
    public String reasoning;
}
```

Use plain fields (not `@Value @Builder`) — Gson requires a no-arg constructor or public fields for deserialization. Follow the `WikiApiModels` pattern.

**Important:** Gson maps `snake_case` JSON to `camelCase` Java using `@SerializedName` or by using `GsonBuilder().setFieldNamingPolicy(LOWER_CASE_WITH_UNDERSCORES)`. The server returns `snake_case`. Either annotate each field with `@SerializedName("suggested_buy_price")` or create the Gson instance with:
```java
private final Gson gson = new GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .create();
```

### SuggestionClient.java

```java
@Slf4j
public class SuggestionClient implements PriceAdvisorStrategy {
    private static final String BASE_URL    = "http://127.0.0.1:";
    private static final int    TIMEOUT_MS  = 2000;

    private final OkHttpClient  httpClient;
    private final Gson          gson;
    private final ExchangeLensConfig config;

    private volatile SuggestionResponse lastSuggestion = null;
    private volatile boolean            serverReachable = false;

    @Inject
    public SuggestionClient(OkHttpClient httpClient, ExchangeLensConfig config) {
        this.httpClient = httpClient;
        this.config     = config;
        this.gson       = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();
    }

    /** Called from background thread (MarketDataService refresh cycle). */
    public void fetchSuggestion(List<FlipRecommendation> candidates, int availableCash) {
        try {
            String url  = BASE_URL + config.suggestionServerPort() + "/suggestion";
            String body = gson.toJson(buildRequest(candidates, availableCash));
            Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

            // Honour timeout — never block refresh thread
            OkHttpClient timed = httpClient.newBuilder()
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();

            try (Response resp = timed.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    lastSuggestion  = gson.fromJson(resp.body().string(), SuggestionResponse.class);
                    serverReachable = true;
                }
            }
        } catch (Exception e) {
            // Server offline or timeout — silent fallback
            serverReachable = false;
            log.debug("Suggestion server unreachable: {}", e.getMessage());
        }
    }

    @Override
    public int suggestBuyPrice(FlipRecommendation rec) {
        if (serverReachable && lastSuggestion != null
                && lastSuggestion.itemId == rec.getItemId()) {
            return lastSuggestion.suggestedBuyPrice;
        }
        return rec.getBuyPrice() + 1;   // +1 fallback
    }

    @Override
    public int suggestSellPrice(FlipRecommendation rec) {
        if (serverReachable && lastSuggestion != null
                && lastSuggestion.itemId == rec.getItemId()) {
            return lastSuggestion.suggestedSellPrice;
        }
        return rec.getSellPrice() - 1;  // -1 fallback
    }

    public boolean isServerReachable() { return serverReachable; }
    public SuggestionResponse getLastSuggestion() { return lastSuggestion; }

    private Map<String, Object> buildRequest(List<FlipRecommendation> candidates, int cash) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FlipRecommendation r : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id",          r.getItemId());
            item.put("item_name",        r.getItemName());
            item.put("latest_low",       r.getBuyPrice());
            item.put("latest_high",      r.getSellPrice());
            item.put("spread",           r.getSpread());
            item.put("net_margin",       r.getNetMargin());
            item.put("roi",              r.getRoi());
            item.put("five_min_volume",  r.getFiveMinVolume());
            item.put("one_hour_volume",  r.getOneHourVolume());
            item.put("liquidity_score",  r.getLiquidityScore());
            item.put("velocity_score",   r.getVelocityScore());
            item.put("stability_score",  r.getStabilityScore());
            item.put("final_score",      r.getFinalScore());
            item.put("buy_limit",        r.getBuyLimit());
            items.add(item);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("available_cash", cash);
        req.put("candidates",     items);
        return req;
    }
}
```

### Wiring into MarketDataService

`SuggestionClient.fetchSuggestion()` is called at the end of `notifyUpdate()` on the background refresh thread:

```java
@Inject private SuggestionClient suggestionClient;

private void notifyUpdate() {
    // ... existing code ...
    onUpdate.accept(recs);
    // Call suggestion server with latest recommendations
    suggestionClient.fetchSuggestion(recs, config.availableCash());
}
```

### New config items

```java
@Range(min = 1024, max = 65535)
@ConfigItem(keyName = "suggestionServerPort", name = "Suggestion Server Port",
    description = "Port the local suggestion server is running on")
default int suggestionServerPort() { return 7891; }

@ConfigItem(keyName = "showModelStage", name = "Show Model Stage",
    description = "Display whether suggestions come from heuristic or regression model")
default boolean showModelStage() { return true; }
```

---

## UI: Settings Tab Addition

Add a "Suggestion Engine" section to `SettingsPanel`:

```
[ Suggestion Engine ]────────────────
Status: ● Online   Stage: Heuristic
Training samples: 847 / 500  ✓ Ready
[ Train Regression Model ]
Port: [7891]
```

Poll `/health` every 30 seconds from a background thread to update the status indicator. Display green dot for online, grey for offline. The "Train" button calls `GET /model/train` asynchronously and shows a spinner while training.

---

## Tests to Write

`SuggestionClient` requires RuneLite's `OkHttpClient` — mock it for unit tests:

```java
@Test
public void fallsBackToPlusOneWhenServerUnreachable() {
    SuggestionClient client = new SuggestionClient(brokenHttpClient(), mockConfig());
    client.fetchSuggestion(Collections.singletonList(buildRec(1000, 1200)), 1_000_000);
    // Server unreachable → fallback
    assertEquals(1001, client.suggestBuyPrice(buildRec(1000, 1200)));
    assertEquals(1199, client.suggestSellPrice(buildRec(1000, 1200)));
}

@Test
public void usesSuggestionWhenServerReturnsMatchingItem() {
    // Mock HTTP client that returns a valid SuggestionResponse JSON
    SuggestionClient client = new SuggestionClient(mockHttpClient(validResponse()), mockConfig());
    client.fetchSuggestion(Collections.singletonList(buildRec(4151, 1000, 1200)), 1_000_000);
    assertEquals(1050, client.suggestBuyPrice(buildRec(4151, 1000, 1200))); // from mock response
}
```

For the Python server, test the heuristic engine directly:

```python
# test_heuristic.py
def test_high_volume_item_fills_faster():
    engine = HeuristicEngine()
    fast = make_item(one_hour_volume=10000, buy_limit=100, latest_low=1000)
    slow = make_item(one_hour_volume=50,    buy_limit=100, latest_low=1000)
    assert engine.predict_buy_fill_seconds(fast, 1_000_000) \
         < engine.predict_buy_fill_seconds(slow, 1_000_000)

def test_stable_spread_gives_high_stability():
    engine = HeuristicEngine()
    item = make_item(latest_low=1000, latest_high=1200,
                     five_min_avg_low=1000, five_min_avg_high=1200,
                     net_margin=176, buy_limit=100)
    assert engine.predict_margin_stability(item) > 0.7

def test_no_fivemin_data_returns_default_stability():
    engine = HeuristicEngine()
    item = make_item(five_min_avg_low=None, five_min_avg_high=None)
    assert engine.predict_margin_stability(item) == 0.5
```

---

## Known Gotchas

1. **OkHttpClient is a singleton in RuneLite.** Don't create a new one — inject the existing one and use `.newBuilder()` to add a timeout without modifying the shared instance.

2. **`fetchSuggestion()` is called every refresh cycle.** If the server is slow (>2s) the timeout will trigger and `serverReachable` will be set to false. The next cycle will try again — no manual reconnection needed.

3. **Server port conflicts.** If port 7891 is in use, the server will fail to start. Make the port configurable (it already is via config) and document this in the README.

4. **`models/` directory is empty initially.** The server starts in Stage A (heuristic) automatically when no `.pkl` files exist. Do not commit `.pkl` files to git — add `suggestion-server/models/` to `.gitignore`.

5. **CSV path scanning.** `regression.py` scans for `flips-*.csv` files. The Phase 2 handoff uses `flips-AccountName-2026-06.json` format — JSON, not CSV. `FlipRepository.exportCsv()` writes separate CSV files. Ensure the CSV export filename pattern is `flips-{accountName}-{yyyy-MM}.csv` (distinct from the JSON files).

6. **Pydantic v2 breaking changes.** If `pip install fastapi` pulls Pydantic v2, `BaseModel` usage changes slightly. Pin `pydantic==1.10.x` in requirements.txt if compatibility issues arise, or use Pydantic v2's `model_config` approach.

7. **CORS.** The server only accepts requests from `127.0.0.1` — no CORS headers needed since it's a Java client, not a browser.

---

## README for suggestion-server/

```markdown
# Exchange Lens Suggestion Server

Local prediction server for Exchange Lens flip suggestions.

## Setup

```bash
pip install -r requirements.txt
```

## Running

```bash
python server.py           # default port 7891
python server.py --port 8000
```

## Training the regression model

The heuristic engine works immediately with no data.
Once you have 500+ completed flips recorded by FlipTracker:

1. Open Exchange Lens settings in RuneLite
2. Under "Suggestion Engine", click "Train Regression Model"

Or via HTTP:
```bash
curl http://localhost:7891/model/train
```

## Model files

Trained models are stored in `models/` and loaded automatically on server start.
If model files are deleted or corrupt, the server reverts to the heuristic engine.
```

---

## Definition of Done

**Python server:**
- [ ] `python server.py` starts without errors
- [ ] `GET /health` returns `{"status": "ok", "model_stage": "heuristic"}`
- [ ] `POST /suggestion` with a valid payload returns a complete `SuggestionResponse`
- [ ] `GET /model/status` returns correct sample count from FlipTracker CSVs
- [ ] `GET /model/train` (with ≥500 samples) produces `.pkl` files in `models/`
- [ ] After training, `GET /health` returns `"model_stage": "regression"`
- [ ] Server restarts with regression model loaded from `.pkl` files
- [ ] Corrupt `.pkl` files fall back to heuristic without crashing
- [ ] Python unit tests pass: `python -m pytest test_heuristic.py`

**Java plugin:**
- [ ] `./gradlew test` — all tests pass including new SuggestionClient tests
- [ ] `./gradlew run` — Settings tab shows "Status: Offline" when server not running
- [ ] Start server → Settings tab shows "Status: Online  Stage: Heuristic"
- [ ] GE price injection uses server-suggested price when server is online
- [ ] GE price injection falls back to +1 when server is offline — no errors in log
- [ ] Train regression model → Settings tab shows "Stage: Regression"
- [ ] Port config change takes effect on next refresh cycle
