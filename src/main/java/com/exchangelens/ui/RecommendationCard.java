package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class RecommendationCard extends JPanel
{
    private final JLabel nameLabel   = new JLabel();
    private final JLabel riskBadge   = new JLabel();
    private final JLabel pricesLabel = new JLabel();   // row 2: buy → sell  +margin
    private final JLabel metaLabel   = new JLabel();   // row 3: ROI  ·  fill time
    private final JButton watchBtn   = ExchangeLensPanel.iconButton("★", "Add to watchlist");
    private final JButton blockBtn   = ExchangeLensPanel.iconButton("✕", "Block item");

    public RecommendationCard(Consumer<Integer> onWatch, Consumer<Integer> onBlock)
    {
        setLayout(new BorderLayout(0, 2));
        setBackground(ExchangeLensPanel.BG_CARD);
        setBorder(new EmptyBorder(6, 8, 6, 8));

        // ── Row 1: name (left) + risk badge (right) ──────────────────────────
        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        nameLabel.setForeground(Color.WHITE);

        riskBadge.setFont(FontManager.getRunescapeSmallFont());
        riskBadge.setOpaque(true);
        riskBadge.setBorder(new EmptyBorder(1, 4, 1, 4));

        JPanel nameRow = new JPanel(new BorderLayout(4, 0));
        nameRow.setOpaque(false);
        nameRow.add(nameLabel, BorderLayout.WEST);
        nameRow.add(riskBadge, BorderLayout.EAST);

        // ── Row 2: buy → sell  +margin ───────────────────────────────────────
        pricesLabel.setFont(FontManager.getRunescapeSmallFont());
        pricesLabel.setForeground(ExchangeLensPanel.TEXT_DIM);

        // ── Row 3: ROI  ·  fill time  +  action buttons (right) ─────────────
        metaLabel.setFont(FontManager.getRunescapeSmallFont());
        metaLabel.setForeground(ExchangeLensPanel.TEXT_DIM);

        watchBtn.setForeground(ExchangeLensPanel.GOLD);
        blockBtn.setForeground(ExchangeLensPanel.COLOR_HIGH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        actions.add(watchBtn);
        actions.add(blockBtn);

        JPanel metaRow = new JPanel(new BorderLayout(4, 0));
        metaRow.setOpaque(false);
        metaRow.add(metaLabel, BorderLayout.WEST);
        metaRow.add(actions,   BorderLayout.EAST);

        // ── Stack rows ────────────────────────────────────────────────────────
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.add(pricesLabel);
        body.add(metaLabel);

        JPanel bottomBlock = new JPanel(new BorderLayout(4, 0));
        bottomBlock.setOpaque(false);
        bottomBlock.add(body,    BorderLayout.WEST);
        bottomBlock.add(actions, BorderLayout.EAST);

        add(nameRow,     BorderLayout.NORTH);
        add(bottomBlock, BorderLayout.CENTER);

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

        // Risk badge
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

        // Row 2: prices + margin
        pricesLabel.setText(
            PriceFormat.formatExact(rec.getBuyPrice())
            + " → " + PriceFormat.formatExact(rec.getSellPrice())
            + "  +" + PriceFormat.format(rec.getNetMargin()));

        // Row 3: ROI + fill time
        metaLabel.setText(
            PriceFormat.formatRoi(rec.getRoi()) + " ROI"
            + "  ·  " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes()));

        watchBtn.putClientProperty("itemId", rec.getItemId());
        blockBtn.putClientProperty("itemId", rec.getItemId());
        revalidate();
        repaint();
    }
}
