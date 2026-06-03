package com.exchangelens.overlay;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.MarketItem;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.SlotTracker;
import com.exchangelens.service.TaxService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import javax.inject.Inject;
import java.util.Optional;

/**
 * Colors the price text in each Grand Exchange slot green (profitable) or red (loss),
 * based on the item's flip margin after GE tax.
 *
 * <p>Widget layout confirmed via in-game dump on RuneLite 1.12.27: the 8 GE slots are
 * children {@code 465,7}–{@code 465,14}; within each slot the dynamic children are
 * {@code dyn[16]} state ("Buy"/"Sell"/"Empty"), {@code dyn[18]} item sprite (itemId),
 * {@code dyn[19]} item name, {@code dyn[25]} price text ("5,200 coins"). The price text
 * is located by the verified index first, then by scanning for a "… coins" label, so a
 * future layout shift degrades gracefully instead of silently doing nothing.
 */
@Slf4j
public class GeSlotColorizer
{
    private static final int GE_SLOT_GROUP      = 465;
    private static final int GE_SLOT_CHILD_BASE = 7;   // slot 0 = child 7
    private static final int ITEM_SPRITE_CHILD  = 18;
    private static final int PRICE_TEXT_CHILD    = 25;  // verified; falls back to a text scan

    private static final int COLOR_PROFIT  = 0x00B300;
    private static final int COLOR_LOSS    = 0xCC0000;
    private static final int COLOR_DEFAULT = 0xFFFFFF;

    private final Client client;
    private final SlotTracker slotTracker;
    private final MarketDataService marketDataService;
    private final ExchangeLensConfig config;

    @Inject
    public GeSlotColorizer(Client client, SlotTracker slotTracker,
                           MarketDataService marketDataService, ExchangeLensConfig config)
    {
        this.client            = client;
        this.slotTracker       = slotTracker;
        this.marketDataService = marketDataService;
        this.config            = config;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!config.colorizeSlots()) return;

        // If group 465 isn't loaded, the GE interface is closed and getWidget returns null.
        // (Avoids depending on a specific WidgetInfo constant.) Per-slot hidden checks below
        // handle the offer-setup sub-screen.
        Widget firstSlot = client.getWidget(GE_SLOT_GROUP, GE_SLOT_CHILD_BASE);
        if (firstSlot == null) return;

        for (int slot = 0; slot < 8; slot++)
        {
            colorizeSlot(slot);
        }
    }

    private void colorizeSlot(int slot)
    {
        Widget slotContainer = client.getWidget(GE_SLOT_GROUP, GE_SLOT_CHILD_BASE + slot);
        if (slotContainer == null || slotContainer.isHidden()) return;

        Widget[] children = slotContainer.getDynamicChildren();
        if (children == null) return;

        // Item id: prefer the slot's own sprite widget, fall back to the tracker.
        int itemId = 0;
        if (ITEM_SPRITE_CHILD < children.length && children[ITEM_SPRITE_CHILD] != null)
        {
            itemId = children[ITEM_SPRITE_CHILD].getItemId();
        }
        if (itemId <= 0) itemId = slotTracker.getItemId(slot);
        if (itemId <= 0) return;  // empty slot

        Widget priceWidget = findPriceWidget(children);
        if (priceWidget == null) return;

        int color = computeColor(slot, itemId);
        priceWidget.setTextColor(color);
        log.debug("EL slot {}: item {} -> {}", slot, itemId,
            color == COLOR_PROFIT ? "green" : color == COLOR_LOSS ? "red" : "white");
    }

    /** Locate the "N coins" price label: verified index first, then a defensive scan. */
    private Widget findPriceWidget(Widget[] children)
    {
        if (PRICE_TEXT_CHILD < children.length && isPriceText(children[PRICE_TEXT_CHILD]))
        {
            return children[PRICE_TEXT_CHILD];
        }
        for (Widget w : children)
        {
            if (isPriceText(w)) return w;
        }
        return null;
    }

    private boolean isPriceText(Widget w)
    {
        return w != null && w.getText() != null && w.getText().endsWith("coins");
    }

    private int computeColor(int slot, int itemId)
    {
        int buyPrice;
        int sellPrice;

        Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
        if (recOpt.isPresent())
        {
            buyPrice  = recOpt.get().getBuyPrice();
            sellPrice = recOpt.get().getSellPrice();
        }
        else
        {
            // Not in the filtered recommendations — fall back to live market data so any
            // mapped item still gets colored.
            MarketItem mi = marketDataService.getMarketItem(itemId);
            if (mi == null || mi.getLatestLow() == null || mi.getLatestHigh() == null)
            {
                return COLOR_DEFAULT;
            }
            buyPrice  = mi.getLatestLow();
            sellPrice = mi.getLatestHigh();
        }

        // If we recorded the actual buy price for this slot (a buy offer), prefer it.
        int recordedBuy = slotTracker.getBuyPrice(slot);
        if (recordedBuy > 0) buyPrice = recordedBuy;

        int tax    = TaxService.calculate(sellPrice);
        int profit = sellPrice - tax - buyPrice;
        return profit > 0 ? COLOR_PROFIT : COLOR_LOSS;
    }
}
