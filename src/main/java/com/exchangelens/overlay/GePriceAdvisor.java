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
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
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
    private static final int SCRIPT_GE_OFFER_SETUP  = ScriptID.GE_OFFERS_SETUP_BUILD; // 779
    private static final String INJECTION_PRICE = "EL price:";
    private static final String INJECTION_QTY   = "EL qty:";

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
        // Temporary: log all script IDs when GE container is visible, to verify script IDs
        Widget geCheck = client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER);
        if (geCheck != null && !geCheck.isHidden())
        {
            log.debug("GE open — script fired: {}", event.getScriptId());
        }

        if (event.getScriptId() != SCRIPT_GE_OFFER_SETUP) return;
        if (!config.showPriceInjection()) return;
        injectOfferWidgets();
    }

    private void injectOfferWidgets()
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
                String t = child.getText();
                if (t != null && (t.startsWith(INJECTION_PRICE) || t.startsWith(INJECTION_QTY))) return;
            }
        }

        int baseY = container.getHeight() - 34;

        // Quantity hint (only on buy offers — qty is fixed for sell)
        if (isBuy && rec.getBuyLimit() > 0)
        {
            final int qty = rec.getBuyLimit();
            Widget qtyHint = container.createChild(-1, WidgetType.TEXT);
            qtyHint.setText(INJECTION_QTY + " " + PriceFormat.formatExact(qty) + "  (buy limit)");
            qtyHint.setTextColor(0xFFD700);
            qtyHint.setFontId(FontID.PLAIN_11);
            qtyHint.setOriginalX(0);
            qtyHint.setOriginalY(baseY);
            qtyHint.setOriginalWidth(container.getWidth());
            qtyHint.setOriginalHeight(14);
            qtyHint.setHasListener(true);
            qtyHint.setOnOpListener((JavaScriptCallback) e -> fillQuantity(qty));
            qtyHint.revalidate();
        }

        // Price hint
        final int price = suggestedPrice;
        Widget priceHint = container.createChild(-1, WidgetType.TEXT);
        priceHint.setText(INJECTION_PRICE + " " + PriceFormat.formatExact(suggestedPrice) + " gp");
        priceHint.setTextColor(0xFFD700);
        priceHint.setFontId(FontID.PLAIN_11);
        priceHint.setOriginalX(0);
        priceHint.setOriginalY(baseY + 16);
        priceHint.setOriginalWidth(container.getWidth());
        priceHint.setOriginalHeight(14);
        priceHint.setHasListener(true);
        priceHint.setOnOpListener((JavaScriptCallback) e -> fillPrice(price));
        priceHint.revalidate();

        log.debug("Injected EL hints: qty={} price={} for item {}", rec.getBuyLimit(), suggestedPrice, itemId);
    }

    private int getOfferedItemId()
    {
        Widget itemSprite = client.getWidget(162, 23);
        if (itemSprite == null) return -1;
        return itemSprite.getItemId();
    }

    private void fillPrice(int price)
    {
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(price));
        Widget input = client.getWidget(162, 33);
        if (input != null) input.setText(String.valueOf(price));
        client.runScript(ScriptID.GE_OFFERS_SETUP_BUILD);
    }

    private void fillQuantity(int qty)
    {
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(qty));
        Widget input = client.getWidget(162, 24);
        if (input != null) input.setText(String.valueOf(qty));
        client.runScript(ScriptID.GE_OFFERS_SETUP_BUILD);
    }

    private Boolean isCurrentOfferBuy()
    {
        Widget typeLabel = client.getWidget(162, 17);
        if (typeLabel == null) return null;
        return "Buy".equals(typeLabel.getText());
    }
}
