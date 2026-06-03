package com.exchangelens.tracker;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.MarketItem;
import com.exchangelens.model.MarketSnapshot;
import com.exchangelens.model.OfferSnapshot;
import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.StorageService;
import com.exchangelens.service.TaxService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Passively observes {@link GrandExchangeOfferChanged} events and assembles completed
 * {@link FlipRecord}s by matching buy and sell sides per GE slot. It snapshots Wiki API
 * market signals at offer time (the Phase 4 feature vector), persists finished flips via
 * {@link FlipRepository} on a background thread, and pushes live session stats to a
 * registered {@link SessionUpdateListener}.
 *
 * <p>This is a SEPARATE event subscriber from {@code SlotTracker}; they do not share state.
 *
 * <p>Threading: {@link #onGrandExchangeOfferChanged} runs on the client thread (where
 * {@code ItemManager} lookups are valid). Disk writes are offloaded to a single-thread io
 * executor. A 1-second Swing {@link Timer} drives the live duration clock on the EDT. The
 * per-slot maps are touched only on the client thread; {@code sessionFlips} is a
 * {@link CopyOnWriteArrayList} because the EDT timer reads it while the client thread appends.
 */
@Slf4j
@Singleton
public class FlipTrackerService
{
    /** Callback for pushing recomputed session stats to the UI. */
    @FunctionalInterface
    public interface SessionUpdateListener
    {
        void onSessionUpdate(SessionStats stats, List<FlipRecord> sessionFlips);
    }

    private final Client client;
    private final ItemManager itemManager;
    private final MarketDataService marketDataService;
    private final StorageService storageService;
    private final FlipRepository flipRepository;

    // slot -> in-progress buy offer (BUYING, not yet BOUGHT). ConcurrentHashMap because the
    // client-thread event handler and the lifecycle methods (start/endSession, which may run
    // on the EDT or client-callback thread) both mutate these maps.
    private final Map<Integer, OfferSnapshot> activeBuyOffers = new ConcurrentHashMap<>();
    // itemId -> FIFO of completed buys awaiting a matching sell. Keyed by ITEM, not slot:
    // a buy and its later sell are two separate GE offers, and collecting the bought item
    // empties its slot — so slot-keyed matching loses the buy before the sell happens. The
    // queue survives collection and is matched FIFO when a SOLD for the same item arrives.
    // Each Deque is mutated only on the client thread; the map is concurrent for lifecycle clears.
    private final Map<Integer, Deque<FlipRecord>> pendingByItem = new ConcurrentHashMap<>();
    // completed this session (cross-thread: client appends, EDT timer reads)
    private final List<FlipRecord> sessionFlips = new CopyOnWriteArrayList<>();

    private volatile Instant sessionStart;
    private ExecutorService ioExecutor;
    private Timer durationTimer;
    private volatile SessionUpdateListener updateListener;

    @Inject
    public FlipTrackerService(Client client,
                              ItemManager itemManager,
                              MarketDataService marketDataService,
                              StorageService storageService,
                              FlipRepository flipRepository)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.marketDataService = marketDataService;
        this.storageService = storageService;
        this.flipRepository = flipRepository;
    }

    public void setUpdateListener(SessionUpdateListener listener)
    {
        this.updateListener = listener;
    }

    // --- lifecycle ------------------------------------------------------------

    public void startSession()
    {
        // Guard against double-start (RuneLite can toggle plugins off/on).
        endSession();

        sessionStart = Instant.now();
        activeBuyOffers.clear();
        pendingByItem.clear();
        sessionFlips.clear();

        ioExecutor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "exchange-lens-io");
            t.setDaemon(true);
            return t;
        });

        // Swing Timer fires on the EDT; safe to update UI directly from the tick.
        durationTimer = new Timer(1000, e -> notifySessionUpdate());
        durationTimer.setRepeats(true);
        durationTimer.start();

        log.debug("FlipTracker session started at {}", sessionStart);
    }

    public void endSession()
    {
        if (durationTimer != null)
        {
            durationTimer.stop();
            durationTimer = null;
        }
        if (ioExecutor != null)
        {
            ioExecutor.shutdown();
            try
            {
                ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
            ioExecutor = null;
        }
        activeBuyOffers.clear();
        pendingByItem.clear();
        sessionFlips.clear();
        sessionStart = null;
    }

    public void shutDown()
    {
        endSession();
    }

    /**
     * Resets the session's stats boundary without tearing down the io executor or timer.
     * Safe to call from the EDT (the Reset button): it only moves {@code sessionStart} and
     * clears the {@link CopyOnWriteArrayList} of completed flips — it does NOT touch the
     * client-thread-only slot maps (so in-progress flips still complete into the new
     * session) and never blocks on executor shutdown.
     */
    public void resetSession()
    {
        sessionStart = Instant.now();
        sessionFlips.clear();
        notifySessionUpdate();
    }

    // --- event handling -------------------------------------------------------

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        GrandExchangeOffer offer = event.getOffer();
        int slot = event.getSlot();
        if (slot < 0 || slot >= 8) return;

        switch (offer.getState())
        {
            case BUYING:
                // Snapshot only on first sight of this slot (guards partial-fill repeats
                // and the login flood of current-state events).
                if (!activeBuyOffers.containsKey(slot))
                {
                    activeBuyOffers.put(slot, buildBuySnapshot(offer, slot));
                    log.debug("Buy offer opened slot={} item={}", slot, offer.getItemId());
                }
                break;

            case BOUGHT:
            {
                OfferSnapshot buySnap = finalizeBuySnapshot(offer, slot);
                FlipRecord pending = FlipRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .itemId(offer.getItemId())
                    .itemName(resolveItemName(offer.getItemId()))
                    .quantity(offer.getTotalQuantity())
                    .buyOffer(buySnap)
                    .startedAt(buySnap.getPostedAt())
                    .accountName(localPlayerName())
                    .suggestionSource(resolveSuggestionSource(offer.getItemId()))
                    .build();
                enqueuePendingBuy(offer.getItemId(), pending);
                activeBuyOffers.remove(slot);
                log.debug("Buy completed slot={} item={} queued for sale", slot, offer.getItemId());
                break;
            }

            case SELLING:
                // Attach the sell-time market snapshot to the oldest pending buy of this item
                // that doesn't have one yet. No pending buy → pre-existing inventory, ignore.
                if (pendingByItem.containsKey(offer.getItemId()))
                {
                    attachSell(offer.getItemId(), buildSellSnapshot(offer, slot));
                }
                break;

            case SOLD:
            {
                FlipRecord pending = pollPendingBuy(offer.getItemId());
                if (pending != null)
                {
                    FlipRecord completed = finalizeFlipRecord(pending, offer, slot);
                    sessionFlips.add(completed);
                    final FlipRecord toSave = completed;
                    if (ioExecutor != null)
                    {
                        ioExecutor.submit(() -> flipRepository.save(toSave));
                    }
                    notifySessionUpdate();
                    log.debug("Flip completed item={} netProfit={}",
                        completed.getItemId(), completed.getTotalNetProfit());
                }
                // SOLD with no pending buy (sold pre-existing inventory): ignore silently.
                break;
            }

            case CANCELLED_SELL:
                // The sell was cancelled — detach its snapshot so the buy can be re-sold later.
                detachSell(offer.getItemId());
                break;

            case CANCELLED_BUY:
                // If the buy partially filled before being cancelled, the bought portion can
                // still be sold — queue it at the ACTUAL filled quantity/spend so a later sell
                // matches. getSpent() already reflects only the filled portion.
                if (offer.getQuantitySold() > 0)
                {
                    OfferSnapshot buySnap = finalizeBuySnapshot(offer, slot).toBuilder()
                        .quantity(offer.getQuantitySold())
                        .build();
                    enqueuePendingBuy(offer.getItemId(), FlipRecord.builder()
                        .id(UUID.randomUUID().toString())
                        .itemId(offer.getItemId())
                        .itemName(resolveItemName(offer.getItemId()))
                        .quantity(offer.getQuantitySold())
                        .buyOffer(buySnap)
                        .startedAt(buySnap.getPostedAt())
                        .accountName(localPlayerName())
                        .suggestionSource(resolveSuggestionSource(offer.getItemId()))
                        .build());
                    log.debug("Partial buy cancelled slot={} item={} qty={} queued for sale",
                        slot, offer.getItemId(), offer.getQuantitySold());
                }
                activeBuyOffers.remove(slot);
                break;

            case EMPTY:
                // Only clear the transient per-slot buy state. Do NOT touch pendingByItem —
                // EMPTY fires when a completed buy is COLLECTED, which must not drop the flip.
                activeBuyOffers.remove(slot);
                break;

            default:
                break;
        }
    }

    // --- item-based buy/sell matching (FIFO per item) -------------------------

    private void enqueuePendingBuy(int itemId, FlipRecord flip)
    {
        pendingByItem.computeIfAbsent(itemId, k -> new ArrayDeque<>()).addLast(flip);
    }

    /** Attaches a sell snapshot to the oldest pending buy of this item that lacks one. */
    private void attachSell(int itemId, OfferSnapshot sell)
    {
        Deque<FlipRecord> q = pendingByItem.get(itemId);
        if (q == null) return;
        Deque<FlipRecord> rebuilt = new ArrayDeque<>(q.size());
        boolean attached = false;
        for (FlipRecord f : q)
        {
            if (!attached && f.getSellOffer() == null)
            {
                rebuilt.addLast(f.toBuilder().sellOffer(sell).build());
                attached = true;
            }
            else
            {
                rebuilt.addLast(f);
            }
        }
        pendingByItem.put(itemId, rebuilt);
    }

    /** Clears the sell snapshot from the oldest pending buy that has one (sell cancelled). */
    private void detachSell(int itemId)
    {
        Deque<FlipRecord> q = pendingByItem.get(itemId);
        if (q == null) return;
        Deque<FlipRecord> rebuilt = new ArrayDeque<>(q.size());
        boolean detached = false;
        for (FlipRecord f : q)
        {
            if (!detached && f.getSellOffer() != null)
            {
                rebuilt.addLast(f.toBuilder().sellOffer(null).build());
                detached = true;
            }
            else
            {
                rebuilt.addLast(f);
            }
        }
        pendingByItem.put(itemId, rebuilt);
    }

    /** Removes and returns the oldest pending buy for this item (FIFO), or null if none. */
    private FlipRecord pollPendingBuy(int itemId)
    {
        Deque<FlipRecord> q = pendingByItem.get(itemId);
        if (q == null || q.isEmpty()) return null;
        FlipRecord f = q.pollFirst();
        if (q.isEmpty()) pendingByItem.remove(itemId);
        return f;
    }

    // --- snapshot construction ------------------------------------------------

    private OfferSnapshot buildBuySnapshot(GrandExchangeOffer offer, int slot)
    {
        return baseSnapshot(offer, slot, true).completedAt(0).build();
    }

    private OfferSnapshot buildSellSnapshot(GrandExchangeOffer offer, int slot)
    {
        return baseSnapshot(offer, slot, false).completedAt(0).build();
    }

    private OfferSnapshot.OfferSnapshotBuilder baseSnapshot(GrandExchangeOffer offer, int slot, boolean isBuy)
    {
        long now = Instant.now().getEpochSecond();
        int itemId = offer.getItemId();
        return OfferSnapshot.builder()
            .slot(slot)
            .itemId(itemId)
            .itemName(resolveItemName(itemId))
            .isBuy(isBuy)
            .postedPrice(offer.getPrice())
            .quantity(offer.getTotalQuantity())
            .postedAt(now)
            .totalSpent(offer.getSpent())
            .marketSnapshot(captureMarketSnapshot(itemId));
    }

    private OfferSnapshot finalizeBuySnapshot(GrandExchangeOffer offer, int slot)
    {
        long now = Instant.now().getEpochSecond();
        OfferSnapshot active = activeBuyOffers.get(slot);
        if (active != null)
        {
            return active.toBuilder()
                .completedAt(now)
                .totalSpent(offer.getSpent())
                .build();
        }
        // Login-with-active-offers: BUYING was never observed for this slot. Synthesize a
        // buy snapshot with postedAt == completedAt so buyFillSeconds == 0 (flags the record
        // as not training-grade rather than dropping the flip).
        return baseSnapshot(offer, slot, true).completedAt(now).build();
    }

    private FlipRecord finalizeFlipRecord(FlipRecord rec, GrandExchangeOffer offer, int slot)
    {
        long now = Instant.now().getEpochSecond();
        OfferSnapshot sell = rec.getSellOffer();
        if (sell == null)
        {
            // Sell completed without an observed SELLING event.
            sell = buildSellSnapshot(offer, slot);
        }
        OfferSnapshot finalizedSell = sell.toBuilder()
            .completedAt(now)
            .totalSpent(offer.getSpent())
            .build();
        int qtySold = offer.getQuantitySold() > 0 ? offer.getQuantitySold() : rec.getQuantity();
        return computeOutcome(rec, finalizedSell, qtySold);
    }

    /**
     * Pure outcome math: given the buy side (already on {@code rec}) and the finalized sell
     * snapshot, compute per-item/total profit, tax, ROI, and fill durations. No RuneLite
     * dependencies — unit-testable in isolation. Tax is total (per-item GE tax × quantity).
     */
    public static FlipRecord computeOutcome(FlipRecord rec, OfferSnapshot sell, int qtySold)
    {
        OfferSnapshot buy = rec.getBuyOffer();
        int qty          = qtySold > 0 ? qtySold : rec.getQuantity();
        int buyPricePer  = perItem(buy.getTotalSpent(), buy.getQuantity(), buy.getPostedPrice());
        int sellPricePer = perItem(sell.getTotalSpent(), qty, sell.getPostedPrice());
        int  perItemTax  = TaxService.calculate(sellPricePer);
        long totalTax    = (long) perItemTax * qty;
        int  netPerItem  = sellPricePer - buyPricePer - perItemTax;
        long totalNet    = (long) netPerItem * qty;
        double roi       = buyPricePer > 0 ? (double) netPerItem / buyPricePer : 0.0;
        long buyFill     = Math.max(0, buy.getCompletedAt() - buy.getPostedAt());
        long sellFill    = Math.max(0, sell.getCompletedAt() - sell.getPostedAt());

        return rec.toBuilder()
            .sellOffer(sell)
            .quantity(qty)
            .buyPrice(buyPricePer)
            .sellPrice(sellPricePer)
            .tax(totalTax)
            .netProfitPerItem(netPerItem)
            .totalNetProfit(totalNet)
            .roi(roi)
            .buyFillSeconds(buyFill)
            .sellFillSeconds(sellFill)
            .totalFlipSeconds(buyFill + sellFill)
            .completedAt(sell.getCompletedAt())
            .build();
    }

    private static int perItem(int totalSpent, int qty, int fallbackPrice)
    {
        if (qty > 0 && totalSpent > 0) return totalSpent / qty;
        return fallbackPrice;
    }

    /**
     * Captures market signals at offer time. Derived scores come from a live
     * {@link FlipRecommendation} when one exists; otherwise the cheap derived fields are
     * recomputed from the raw {@link MarketItem} (the four ML scores are left null because
     * they cannot be reproduced without re-running the recommendation engine).
     */
    private MarketSnapshot captureMarketSnapshot(int itemId)
    {
        MarketItem item = marketDataService.getMarketItem(itemId);
        Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
        if (item == null && !recOpt.isPresent())
        {
            return null;
        }

        MarketSnapshot.MarketSnapshotBuilder b = MarketSnapshot.builder()
            .itemId(itemId)
            .capturedAt(Instant.now().getEpochSecond());

        if (item != null)
        {
            b.latestLow(item.getLatestLow()).latestHigh(item.getLatestHigh())
             .latestLowTime(item.getLatestLowTime()).latestHighTime(item.getLatestHighTime())
             .fiveMinAvgLow(item.getFiveMinLow()).fiveMinAvgHigh(item.getFiveMinHigh())
             .fiveMinLowVolume(item.getFiveMinLowVolume()).fiveMinHighVolume(item.getFiveMinHighVolume())
             .oneHourAvgLow(item.getOneHourLow()).oneHourAvgHigh(item.getOneHourHigh())
             .oneHourLowVolume(item.getOneHourLowVolume()).oneHourHighVolume(item.getOneHourHighVolume());
        }

        FlipRecommendation rec = recOpt.orElse(null);
        if (rec != null)
        {
            b.spread(rec.getSpread()).tax(rec.getTax()).netMargin(rec.getNetMargin()).roi(rec.getRoi())
             .liquidityScore(rec.getLiquidityScore()).velocityScore(rec.getVelocityScore())
             .stabilityScore(rec.getStabilityScore()).finalScore(rec.getFinalScore())
             .buyLimit(rec.getBuyLimit())
             .fiveMinVolume(rec.getFiveMinVolume()).oneHourVolume(rec.getOneHourVolume());
        }
        else if (item != null)
        {
            Integer low = item.getLatestLow();
            Integer high = item.getLatestHigh();
            if (low != null && high != null)
            {
                int spread = high - low;
                int tax = TaxService.calculate(high);
                int netMargin = spread - tax;
                b.spread(spread).tax(tax).netMargin(netMargin);
                if (low > 0)
                {
                    b.roi((double) netMargin / low);
                }
            }
            b.buyLimit(item.getLimit());
            b.fiveMinVolume(combine(item.getFiveMinLowVolume(), item.getFiveMinHighVolume()));
            b.oneHourVolume(combine(item.getOneHourLowVolume(), item.getOneHourHighVolume()));
            // liquidity/velocity/stability/final scores intentionally left null.
        }

        return b.build();
    }

    private static Integer combine(Integer a, Integer b)
    {
        if (a == null && b == null) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    // --- helpers --------------------------------------------------------------

    private String resolveItemName(int itemId)
    {
        try
        {
            return itemManager.getItemComposition(itemId).getName();
        }
        catch (Exception e)
        {
            log.debug("Could not resolve item name for {}", itemId);
            return null;
        }
    }

    private String resolveSuggestionSource(int itemId)
    {
        if (marketDataService.getRecommendationForItem(itemId).isPresent()) return "exchange-lens";
        if (storageService.loadWatchlist().contains(itemId)) return "exchange-lens";
        return "manual";
    }

    private String localPlayerName()
    {
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
        {
            return client.getLocalPlayer().getName();
        }
        return "unknown";
    }

    private void notifySessionUpdate()
    {
        SessionUpdateListener listener = updateListener;
        if (listener == null || sessionStart == null) return;
        List<FlipRecord> snapshot = new java.util.ArrayList<>(sessionFlips);
        SessionStats stats = SessionStatsCalculator.calculate(snapshot, sessionStart);
        SwingUtilities.invokeLater(() -> listener.onSessionUpdate(stats, snapshot));
    }
}
