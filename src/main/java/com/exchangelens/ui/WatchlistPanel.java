// src/main/java/com/exchangelens/ui/WatchlistPanel.java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

public class WatchlistPanel extends JPanel
{
    private final JPanel listPanel = new JPanel();
    private final Consumer<Integer> onUnwatch;

    public WatchlistPanel(Consumer<Integer> onUnwatch)
    {
        this.onUnwatch = onUnwatch;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);
    }

    public void update(List<FlipRecommendation> watchedRecs)
    {
        SwingUtilities.invokeLater(() ->
        {
            listPanel.removeAll();
            if (watchedRecs.isEmpty())
            {
                JLabel empty = new JLabel("No items on watchlist.");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setFont(FontManager.getRunescapeSmallFont());
                empty.setBorder(new EmptyBorder(12, 12, 0, 0));
                listPanel.add(empty);
            }
            else
            {
                for (FlipRecommendation rec : watchedRecs)
                {
                    listPanel.add(buildRow(rec));
                    listPanel.add(Box.createVerticalStrut(2));
                }
            }
            listPanel.revalidate();
            listPanel.repaint();
        });
    }

    private JPanel buildRow(FlipRecommendation rec)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(5, 8, 5, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        JLabel info = new JLabel("<html><b>" + rec.getItemName() + "</b><br/>"
            + "Buy: " + PriceFormat.format(rec.getBuyPrice())
            + "  Sell: " + PriceFormat.format(rec.getSellPrice())
            + "  Margin: " + PriceFormat.format(rec.getNetMargin())
            + "  ROI: " + PriceFormat.formatRoi(rec.getRoi())
            + "  Fill: " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes())
            + "</html>");
        info.setForeground(Color.WHITE);
        info.setFont(FontManager.getRunescapeSmallFont());

        JButton unwatch = new JButton("Unwatch");
        unwatch.setFont(FontManager.getRunescapeSmallFont());
        unwatch.setFocusPainted(false);
        unwatch.addActionListener(e -> onUnwatch.accept(rec.getItemId()));

        row.add(info, BorderLayout.CENTER);
        row.add(unwatch, BorderLayout.EAST);
        return row;
    }
}
