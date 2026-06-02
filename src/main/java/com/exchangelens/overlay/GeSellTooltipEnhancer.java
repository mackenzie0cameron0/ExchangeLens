package com.exchangelens.overlay;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.PriceFormat;
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
public class GeSellTooltipEnhancer
{
    private static final int SCRIPT_GE_TOOLTIP = 526;
    private static final int TOOLTIP_GROUP     = 193;
    private static final int TOOLTIP_CHILD     = 0;

    private final Client client;
    private final SlotTracker slotTracker;
    private final MarketDataService marketDataService;
    private final ExchangeLensConfig config;

    @Inject
    public GeSellTooltipEnhancer(Client client, SlotTracker slotTracker,
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
        if (event.getScriptId() != SCRIPT_GE_TOOLTIP) return;
        if (!config.showTooltipProfit()) return;
        enhanceTooltip();
    }

    private void enhanceTooltip()
    {
        Widget tooltip = client.getWidget(TOOLTIP_GROUP, TOOLTIP_CHILD);
        if (tooltip == null || tooltip.isHidden()) return;

        String existing = tooltip.getText();
        if (existing == null || existing.contains("Profit:")) return;

        for (int slot = 0; slot < 8; slot++)
        {
            if (slotTracker.isBuySlot(slot)) continue;
            int itemId = slotTracker.getItemId(slot);
            if (itemId <= 0) continue;

            Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
            if (!recOpt.isPresent()) continue;

            FlipRecommendation rec = recOpt.get();
            int sellPrice = rec.getSellPrice();
            int buyPrice  = slotTracker.getBuyPrice(slot) > 0
                    ? slotTracker.getBuyPrice(slot)
                    : rec.getBuyPrice();

            int tax           = TaxService.calculate(sellPrice);
            int profitPerItem = sellPrice - tax - buyPrice;

            tooltip.setText(existing + "<br>Profit: " + PriceFormat.formatExact(profitPerItem) + " gp");
            tooltip.setOriginalHeight(tooltip.getOriginalHeight() + 14);
            tooltip.revalidate();
            return;
        }
    }
}
