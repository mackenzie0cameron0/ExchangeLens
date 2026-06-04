package com.exchangelens;

import com.exchangelens.service.MarketDataService;
import com.exchangelens.service.StorageService;
import com.exchangelens.ui.ExchangeLensPanel;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import com.exchangelens.overlay.GePriceAdvisor;
import com.exchangelens.overlay.GeSlotColorizer;
import com.exchangelens.overlay.GeSellTooltipEnhancer;
import com.exchangelens.service.SlotTracker;
import com.exchangelens.tracker.FlipTrackerService;
import net.runelite.api.Client;
import net.runelite.client.eventbus.EventBus;
import com.exchangelens.ui.FlipHistoryWindow;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
    name = "Exchange Lens",
    description = "Grand Exchange flipping assistant with margin analysis and flip tracking",
    tags = {"grand exchange", "ge", "flip", "market", "price"}
)
public class ExchangeLensPlugin extends Plugin
{
    @Inject private ClientToolbar clientToolbar;
    @Inject private ExchangeLensConfig config;
    @Inject private ExchangeLensPanel panel;
    @Inject private MarketDataService marketDataService;
    @Inject private StorageService storageService;
    @Inject private Client client;
    @Inject private EventBus eventBus;
    @Inject private SlotTracker slotTracker;
    @Inject private FlipTrackerService flipTrackerService;
    @Inject private GePriceAdvisor gePriceAdvisor;
    @Inject private GeSlotColorizer geSlotColorizer;
    @Inject private GeSellTooltipEnhancer geSellTooltipEnhancer;
    @Inject private Provider<FlipHistoryWindow> flipHistoryWindowProvider;
    private FlipHistoryWindow flipHistoryWindow;
    private volatile String lastKnownAccount;

    private NavigationButton navButton;

    @Override
    protected void startUp()
    {
        log.debug("Exchange Lens starting");

        panel.setOnManualRefresh(() ->
        {
            marketDataService.stop();
            marketDataService.start(recs ->
                SwingUtilities.invokeLater(() ->
                    panel.updateRecommendations(recs, marketDataService.getLastSuccessfulFetch(),
                        marketDataService.isStale())));
        });

        marketDataService.start(recs ->
            SwingUtilities.invokeLater(() ->
                panel.updateRecommendations(recs, marketDataService.getLastSuccessfulFetch(),
                    marketDataService.isStale())));

        navButton = NavigationButton.builder()
            .tooltip("Exchange Lens")
            .icon(buildIcon())
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);
        eventBus.register(slotTracker);
        eventBus.register(gePriceAdvisor);
        eventBus.register(geSlotColorizer);
        eventBus.register(geSellTooltipEnhancer);

        // FlipTracker: push session updates into the panel; reset clears session stats.
        flipTrackerService.setUpdateListener((stats, flips) ->
        {
            if (!flips.isEmpty()) lastKnownAccount = flips.get(0).getAccountName();
            panel.updateSession(stats, flips);
        });
        panel.getSessionStatsPanel().setOnReset(flipTrackerService::resetSession);
        flipTrackerService.startSession();
        panel.setOnOpenHistoryChart(this::openFlipHistoryWindow);
        eventBus.register(flipTrackerService);

        log.debug("Exchange Lens started");
    }

    @Override
    protected void shutDown()
    {
        eventBus.unregister(geSellTooltipEnhancer);
        eventBus.unregister(geSlotColorizer);
        eventBus.unregister(gePriceAdvisor);
        eventBus.unregister(slotTracker);
        // Unregister + end the flip session before stopping market data so any final
        // queued save flushes cleanly.
        eventBus.unregister(flipTrackerService);
        flipTrackerService.endSession();
        marketDataService.stop();
        clientToolbar.removeNavigation(navButton);
        if (flipHistoryWindow != null)
        {
            final FlipHistoryWindow w = flipHistoryWindow;
            flipHistoryWindow = null;
            SwingUtilities.invokeLater(w::dispose);
        }
        navButton = null;
        log.debug("Exchange Lens stopped");
    }

    @Provides
    ExchangeLensConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ExchangeLensConfig.class);
    }

    private BufferedImage buildIcon()
    {
        try
        {
            return net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "icon.png");
        }
        catch (Exception e)
        {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xFFD700));
            g.fillOval(1, 1, 14, 14);
            g.setColor(new Color(0xB8860B));
            g.drawOval(1, 1, 14, 14);
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 9));
            g.drawString("EL", 2, 11);
            g.dispose();
            return img;
        }
    }

    private void openFlipHistoryWindow()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (flipHistoryWindow == null)
                flipHistoryWindow = flipHistoryWindowProvider.get();
            String name = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : lastKnownAccount;
            if (name != null)
                flipHistoryWindow.open(name);
        });
    }
}
