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
import javax.inject.Inject;
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
        log.debug("Exchange Lens started");
    }

    @Override
    protected void shutDown()
    {
        marketDataService.stop();
        clientToolbar.removeNavigation(navButton);
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
}
