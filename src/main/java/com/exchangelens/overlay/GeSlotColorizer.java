package com.exchangelens.overlay;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.SlotTracker;
import com.exchangelens.service.TaxService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import javax.inject.Inject;
import java.util.Optional;

@Slf4j
public class GeSlotColorizer
{
    private static final int SCRIPT_GE_SLOT_REDRAW = 4730; // observed in-game; 149 was wrong
    private static final int GE_SLOT_GROUP = 465;
    private static final int GE_SLOT_CHILD_BASE = 7;
    private static final int PRICE_TEXT_CHILD = 15;

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
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() != SCRIPT_GE_SLOT_REDRAW) return;
        if (!config.colorizeSlots()) return;
        colorizeAllSlots();
    }

    private void colorizeAllSlots()
    {
        for (int slot = 0; slot < 8; slot++)
        {
            colorizeSlot(slot);
        }
    }

    private void colorizeSlot(int slot)
    {
        if (slotTracker.isBuySlot(slot)) return;

        int itemId = slotTracker.getItemId(slot);
        if (itemId <= 0) return;

        Widget slotContainer = client.getWidget(GE_SLOT_GROUP, GE_SLOT_CHILD_BASE + slot);
        if (slotContainer == null || slotContainer.isHidden()) return;

        Widget[] children = slotContainer.getDynamicChildren();
        if (children == null || PRICE_TEXT_CHILD >= children.length) return;

        Widget priceWidget = children[PRICE_TEXT_CHILD];
        if (priceWidget == null) return;

        int color = computeColor(slot, itemId);
        priceWidget.setTextColor(color);
    }

    private int computeColor(int slot, int itemId)
    {
        Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
        if (!recOpt.isPresent()) return COLOR_DEFAULT;

        FlipRecommendation rec = recOpt.get();
        int sellPrice = rec.getSellPrice();
        int buyPrice  = slotTracker.getBuyPrice(slot) > 0
                ? slotTracker.getBuyPrice(slot)
                : rec.getBuyPrice();

        int tax    = TaxService.calculate(sellPrice);
        int profit = sellPrice - tax - buyPrice;

        return profit > 0 ? COLOR_PROFIT : COLOR_LOSS;
    }
}
