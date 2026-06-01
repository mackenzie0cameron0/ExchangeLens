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
    private static final Color COLOR_LOW    = new Color(0x40C040);
    private static final Color COLOR_MEDIUM = new Color(0xE0A800);
    private static final Color COLOR_HIGH   = new Color(0xE04040);
    private static final Color BG_CARD      = ColorScheme.DARKER_GRAY_COLOR;

    private final JLabel nameLabel    = new JLabel();
    private final JLabel priceLabel   = new JLabel();
    private final JLabel marginLabel  = new JLabel();
    private final JLabel roiLabel     = new JLabel();
    private final JLabel riskLabel    = new JLabel();
    private final JLabel fillLabel    = new JLabel();
    private final JLabel volumeLabel  = new JLabel();
    private final JButton watchBtn    = new JButton("Watch");
    private final JButton blockBtn    = new JButton("Block");

    public RecommendationCard(Consumer<Integer> onWatch, Consumer<Integer> onBlock)
    {
        setLayout(new BorderLayout(4, 2));
        setBackground(BG_CARD);
        setBorder(new EmptyBorder(6, 8, 6, 8));

        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(Color.WHITE);

        JPanel infoPanel = new JPanel(new GridLayout(3, 2, 2, 1));
        infoPanel.setOpaque(false);
        infoPanel.add(priceLabel);
        infoPanel.add(marginLabel);
        infoPanel.add(roiLabel);
        infoPanel.add(riskLabel);
        infoPanel.add(fillLabel);
        infoPanel.add(volumeLabel);

        for (JLabel lbl : new JLabel[]{priceLabel, marginLabel, roiLabel, riskLabel, fillLabel, volumeLabel})
        {
            lbl.setFont(FontManager.getRunescapeSmallFont());
            lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        }

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(watchBtn);
        buttonPanel.add(blockBtn);

        watchBtn.setFont(FontManager.getRunescapeSmallFont());
        blockBtn.setFont(FontManager.getRunescapeSmallFont());
        watchBtn.setFocusPainted(false);
        blockBtn.setFocusPainted(false);

        add(nameLabel, BorderLayout.NORTH);
        add(infoPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        watchBtn.addActionListener(e ->
        {
            if (watchBtn.getClientProperty("itemId") instanceof Integer)
                onWatch.accept((Integer) watchBtn.getClientProperty("itemId"));
        });
        blockBtn.addActionListener(e ->
        {
            if (blockBtn.getClientProperty("itemId") instanceof Integer)
                onBlock.accept((Integer) blockBtn.getClientProperty("itemId"));
        });
    }

    public void update(FlipRecommendation rec)
    {
        nameLabel.setText(rec.getItemName() + (rec.isMembers() ? " (m)" : ""));
        priceLabel.setText("Buy: " + PriceFormat.format(rec.getBuyPrice())
            + "  Sell: " + PriceFormat.format(rec.getSellPrice()));
        marginLabel.setText("Margin: " + PriceFormat.format(rec.getNetMargin()));
        roiLabel.setText("ROI: " + PriceFormat.formatRoi(rec.getRoi()));
        fillLabel.setText("Fill: " + PriceFormat.formatFillTime(rec.getEstimatedFillMinutes()));
        volumeLabel.setText("Vol/h: " + rec.getOneHourVolume());

        String riskText = rec.getRiskLevel().name();
        Color riskColor = rec.getRiskLevel() == RiskLevel.LOW ? COLOR_LOW
            : rec.getRiskLevel() == RiskLevel.HIGH ? COLOR_HIGH : COLOR_MEDIUM;
        riskLabel.setText("Risk: " + riskText);
        riskLabel.setForeground(riskColor);

        watchBtn.putClientProperty("itemId", rec.getItemId());
        blockBtn.putClientProperty("itemId", rec.getItemId());
        revalidate();
        repaint();
    }
}
