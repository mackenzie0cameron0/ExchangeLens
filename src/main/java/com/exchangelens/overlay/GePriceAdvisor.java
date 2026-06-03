package com.exchangelens.overlay;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.PlusOneStrategy;
import com.exchangelens.service.PriceAdvisorStrategy;
import com.exchangelens.service.PriceFormat;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.eventbus.Subscribe;
import javax.inject.Inject;
import java.util.Optional;

/**
 * Injects a clickable "EL price: N" hint into the Grand Exchange price-entry chatbox.
 *
 * <p>Widget IDs confirmed via in-game dump on RuneLite 1.12.27:
 * <ul>
 *   <li>The offer setup panel is group {@code 465}, child {@code 26}; its "Buy offer"/
 *       "Sell offer" label tells us the side.</li>
 *   <li>The item being configured is read from varp {@code 1151} (CURRENT_GE_ITEM).</li>
 *   <li>The numeric price input lives in the chatbox (group {@code 162}); its prompt reads
 *       "Set a price for each item:". The hint is injected there — below the prompt, in the
 *       empty chatbox space — so it never overlaps the offer panel controls.</li>
 * </ul>
 */
@Slf4j
public class GePriceAdvisor
{
    /** GE offer setup panel: group 465, child 26 (gives us the buy/sell side). */
    private static final int GE_GROUP       = 465;
    private static final int GE_SETUP_PANEL = 26;

    /** Chatbox group that hosts the numeric price input + its prompt. */
    private static final int CHATBOX_GROUP = 162;

    /** Varp holding the item id of the offer currently being configured. */
    private static final int VARP_CURRENT_GE_ITEM = 1151;

    private static final String HINT_PREFIX         = "EL price:";
    private static final String PRICE_PROMPT_PREFIX = "Set a price";

    private final Client client;
    private final MarketDataService marketDataService;
    private final ExchangeLensConfig config;
    private final PriceAdvisorStrategy strategy = new PlusOneStrategy();

    /** Item id / side the current hint reflects, so we only rebuild when something changes. */
    private int     lastItemId = -1;
    private boolean lastWasBuy = false;

    @Inject
    public GePriceAdvisor(Client client, MarketDataService marketDataService,
                           ExchangeLensConfig config)
    {
        this.client            = client;
        this.marketDataService = marketDataService;
        this.config            = config;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!config.showPriceInjection())
        {
            lastItemId = -1;
            return;
        }

        // Offer setup panel must be open — it confirms GE setup state and gives the side.
        Widget panel = client.getWidget(GE_GROUP, GE_SETUP_PANEL);
        if (panel == null || panel.isHidden())
        {
            lastItemId = -1;
            return;
        }

        int itemId = client.getVarpValue(VARP_CURRENT_GE_ITEM);
        if (itemId <= 0)
        {
            lastItemId = -1;
            return;
        }

        // The clickable hint renders in the chatbox numeric-input area. If the price prompt
        // isn't showing (e.g. still searching for an item), there's nothing to attach to.
        Widget prompt = findChatboxPricePrompt();
        if (prompt == null)
        {
            lastItemId = -1;
            return;
        }
        Widget container = prompt.getParent();
        if (container == null) return;

        boolean isBuy   = isBuyOffer(panel);
        boolean present = findHint(container) != null;

        // Re-inject if the item changed, the side changed, or the chatbox rebuilt (wiping
        // our child, so `present` goes false — e.g. after the user types a digit).
        if (present && itemId == lastItemId && isBuy == lastWasBuy) return;

        if (injectHint(container, prompt, itemId, isBuy))
        {
            lastItemId = itemId;
            lastWasBuy = isBuy;
        }
    }

    private boolean injectHint(Widget container, Widget prompt, int itemId, boolean isBuy)
    {
        Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
        if (!recOpt.isPresent())
        {
            log.debug("EL: GE item {} not in current recommendations — no price hint", itemId);
            return false;
        }
        FlipRecommendation rec = recOpt.get();
        final int suggested = isBuy ? strategy.suggestBuyPrice(rec) : strategy.suggestSellPrice(rec);

        Widget hint = container.createChild(-1, WidgetType.TEXT);
        hint.setText(HINT_PREFIX + " " + PriceFormat.formatExact(suggested) + "  (click to apply)");
        hint.setTextColor(0xFFD700);
        hint.setFontId(FontID.PLAIN_12);
        hint.setTextShadowed(true);
        hint.setOriginalX(0);
        hint.setOriginalY(prompt.getOriginalY() + 34);   // below the prompt + cursor line
        hint.setOriginalWidth(container.getWidth());
        hint.setOriginalHeight(16);
        hint.setXTextAlignment(WidgetTextAlignment.CENTER);
        hint.setYTextAlignment(WidgetTextAlignment.CENTER);
        hint.setHasListener(true);
        hint.setAction(0, "Apply EL price");
        hint.setOnOpListener((JavaScriptCallback) e -> fillPrice(suggested));
        hint.revalidate();

        log.debug("EL: injected chatbox price hint {} for item {} (buy={})", suggested, itemId, isBuy);
        return true;
    }

    /**
     * Writes the suggested price into the GE numeric input. The chatbox numeric prompt is
     * already active during setup, so setting INPUT_TEXT populates the field; the game's own
     * redraw reflects it on the next tick.
     */
    private void fillPrice(int price)
    {
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(price));
        log.debug("EL: set GE input text to {}", price);
    }

    /** Top-level chatbox child whose text is the price prompt ("Set a price for each item:"). */
    private Widget findChatboxPricePrompt()
    {
        for (int c = 0; c < 120; c++)
        {
            Widget w = client.getWidget(CHATBOX_GROUP, c);
            if (w != null && w.getText() != null && w.getText().startsWith(PRICE_PROMPT_PREFIX))
            {
                return w;
            }
        }
        return null;
    }

    /** Our injected hint child within the chatbox container, or null if absent. */
    private Widget findHint(Widget container)
    {
        Widget[] children = container.getDynamicChildren();
        if (children == null) return null;
        for (Widget c : children)
        {
            if (c != null && !c.isHidden() && c.getText() != null && c.getText().startsWith(HINT_PREFIX))
            {
                return c;
            }
        }
        return null;
    }

    /** Determine offer side from the "Buy offer"/"Sell offer" label inside the setup panel. */
    private boolean isBuyOffer(Widget panel)
    {
        Widget[] children = panel.getDynamicChildren();
        if (children != null)
        {
            for (Widget c : children)
            {
                String t = c == null ? null : c.getText();
                if (t == null) continue;
                if (t.startsWith("Sell")) return false;
                if (t.startsWith("Buy"))  return true;
            }
        }
        return true;  // default to buy
    }
}
