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
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.eventbus.Subscribe;
import javax.inject.Inject;
import java.util.Optional;

@Slf4j
public class GePriceAdvisor
{
    private static final int SCRIPT_GE_OFFER_SETUP = 385;
    private static final String INJECTION_TAG = "EL_PRICE";

    private final Client client;
    private final MarketDataService marketDataService;
    private final ExchangeLensConfig config;
    private final PriceAdvisorStrategy strategy = new PlusOneStrategy();

    @Inject
    public GePriceAdvisor(Client client, MarketDataService marketDataService,
                           ExchangeLensConfig config)
    {
        this.client            = client;
        this.marketDataService = marketDataService;
        this.config            = config;
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() != SCRIPT_GE_OFFER_SETUP) return;
        if (!config.showPriceInjection()) return;
        injectPriceWidget();
    }

    private void injectPriceWidget()
    {
        Widget container = client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER);
        if (container == null || container.isHidden()) return;

        int itemId = getOfferedItemId();
        if (itemId <= 0) return;

        Optional<FlipRecommendation> recOpt = marketDataService.getRecommendationForItem(itemId);
        if (!recOpt.isPresent()) return;

        FlipRecommendation rec = recOpt.get();
        Boolean isBuy = isCurrentOfferBuy();
        if (isBuy == null) return;
        int suggestedPrice = isBuy ? strategy.suggestBuyPrice(rec) : strategy.suggestSellPrice(rec);

        // Guard against duplicate injection
        Widget[] children = container.getDynamicChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                if (child.getText() != null && child.getText().startsWith(INJECTION_TAG)) return;
            }
        }

        Widget priceHint = container.createChild(-1, WidgetType.TEXT);
        priceHint.setText(INJECTION_TAG + "Set to Exchange Lens price: "
                + PriceFormat.formatExact(suggestedPrice) + " gp");
        priceHint.setTextColor(0xFFD700);
        priceHint.setFontId(FontID.PLAIN_11);
        priceHint.setOriginalX(0);
        priceHint.setOriginalY(container.getHeight() - 20);
        priceHint.setOriginalWidth(container.getWidth());
        priceHint.setOriginalHeight(16);
        priceHint.setHasListener(true);

        final int price = suggestedPrice;
        priceHint.setOnOpListener((JavaScriptCallback) e ->
                client.runScript(SCRIPT_GE_OFFER_SETUP, price));

        priceHint.revalidate();
        log.debug("Injected EL price hint: {} gp for item {}", suggestedPrice, itemId);
    }

    private int getOfferedItemId()
    {
        Widget itemSprite = client.getWidget(162, 23);
        if (itemSprite == null) return -1;
        return itemSprite.getItemId();
    }

    private Boolean isCurrentOfferBuy()
    {
        Widget typeLabel = client.getWidget(162, 17);
        if (typeLabel == null) return null;
        return "Buy".equals(typeLabel.getText());
    }
}
