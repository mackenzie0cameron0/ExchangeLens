package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class RecommendationCard extends JPanel
{
    private final JLabel nameLabel  = new JLabel();
    private final JLabel riskBadge  = new JLabel();
    private final JLabel statsLabel = new JLabel();
    private final JButton watchBtn  = ExchangeLensPanel.iconButton("★", "Add to watchlist");
    private final JButton blockBtn  = ExchangeLensPanel.iconButton("✕", "Block item");

    public RecommendationCard(Consumer<Integer> onWatch, Consumer<Integer> onBlock)
    {
        setLayout(new BorderLayout(4, 2));
        setBackground(ExchangeLensPanel.BG_CARD);
        setBorder(new EmptyBorder(6, 8, 6, 8));

        // Name + risk badge (top row)
        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        nameLabel.setForeground(Color.WHITE);

        riskBadge.setFont(FontManager.getRunescapeSmallFont());
        riskBadge.setOpaque(true);
        riskBadge.setBorder(new EmptyBorder(1, 4, 1, 4));

        JPanel nameRow = new JPanel(new BorderLayout(4, 0));
        nameRow.setOpaque(false);
        nameRow.add(nameLabel, BorderLayout.WEST);
        nameRow.add(riskBadge, BorderLayout.EAST);

        // Stats row (bottom)
        statsLabel.setFont(FontManager.getRunescapeSmallFont());
        statsLabel.setForeground(ExchangeLensPanel.TEXT_DIM);

        // Action buttons (far right, icon-style)
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        watchBtn.setForeground(ExchangeLensPanel.GOLD);
        blockBtn.setForeground(ExchangeLensPanel.COLOR_HIGH);
        actions.add(watchBtn);
        actions.add(blockBtn);

        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.setOpaque(false);
        bottomRow.add(statsLabel, BorderLayout.WEST);
        bottomRow.add(actions,   BorderLayout.EAST);

        add(nameRow,   BorderLayout.NORTH);
        add(bottomRow, BorderLayout.CENTER);

        watchBtn.addActionListener(e ->
        {
            Object id = watchBtn.getClientProperty("itemId");
            if (id instanceof Integer) onWatch.accept((Integer) id);
        });
        blockBtn.addActionListener(e ->
        {
            Object id = blockBtn.getClientProperty("itemId");
            if (id instanceof Integer) onBlock.accept((Integer) id);
        });
    }

    public void update(FlipRecommendation rec)
    {
        nameLabel.setText(rec.getItemName() + (rec.isMembers() ? " (m)" : ""));

        RiskLevel risk = rec.getRiskLevel();
        String riskText;
        Color  riskColor;
        Color  riskFg = Color.BLACK;
        if (risk == RiskLevel.LOW)
        {
            riskText  = "LOW";
            riskColor = ExchangeLensPanel.COLOR_LOW;
        }
        else if (risk == RiskLevel.HIGH)
        {
            riskText  = "HIGH";
            riskColor = ExchangeLensPanel.COLOR_HIGH;
            riskFg    = Color.WHITE;
        }
        else
        {
            riskText  = "MED";
            riskColor = ExchangeLensPanel.COLOR_MED;
        }
        riskBadge.setText(riskText);
        riskBadge.setBackground(riskColor);
        riskBadge.setForeground(riskFg);

        statsLabel.setText(
            PriceFormat.formatExact(rec.getBuyPrice()) + " → "
            + PriceFormat.formatExact(rec.getSellPrice())
            + "  +" + PriceFormat.format(rec.getNetMargin())
            + "  " + PriceFormat.formatRoi(rec.getRoi()) + " ROI"
            + "  " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes()));

        watchBtn.putClientProperty("itemId", rec.getItemId());
        blockBtn.putClientProperty("itemId", rec.getItemId());
        revalidate();
        repaint();
    }
}
