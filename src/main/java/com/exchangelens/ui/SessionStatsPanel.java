package com.exchangelens.ui;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.PriceFormat;
import com.exchangelens.tracker.SessionStats;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.List;

/**
 * Live session stats view: total profit, flips completed, average ROI, session duration
 * (driven by {@code FlipTrackerService}'s 1-second timer), hourly profit rate, and a
 * Reset Session button. Style follows {@link ExchangeLensPanel} (shared palette + RuneScape
 * fonts). All updates arrive on the EDT via {@link #update}.
 */
@Singleton
public class SessionStatsPanel extends JPanel
{
    private final JLabel profitLabel   = new JLabel("0 gp");
    private final JLabel flipsLabel     = new JLabel("0");
    private final JLabel roiLabel       = new JLabel("0.00%");
    private final JLabel durationLabel  = new JLabel("00:00:00");
    private final JLabel hourlyLabel    = new JLabel("0 gp");
    private final JButton resetButton   = new JButton("Reset session");

    private Runnable onReset;

    @Inject
    public SessionStatsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ExchangeLensPanel.BG_PANEL);
        setBorder(new EmptyBorder(8, 10, 8, 10));

        // Hero profit number
        JLabel profitCaption = new JLabel("SESSION PROFIT");
        profitCaption.setFont(FontManager.getRunescapeSmallFont());
        profitCaption.setForeground(ExchangeLensPanel.TEXT_DIM);
        profitCaption.setAlignmentX(LEFT_ALIGNMENT);

        profitLabel.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 15f));
        profitLabel.setForeground(ExchangeLensPanel.GREEN);
        profitLabel.setAlignmentX(LEFT_ALIGNMENT);

        add(profitCaption);
        add(Box.createVerticalStrut(2));
        add(profitLabel);
        add(Box.createVerticalStrut(6));
        add(divider("Stats"));
        add(statRow("Flips completed", flipsLabel));
        add(statRow("Average ROI",     roiLabel));
        add(statRow("Duration",        durationLabel));
        add(statRow("Profit / hour",   hourlyLabel));
        add(Box.createVerticalStrut(10));

        resetButton.setFont(FontManager.getRunescapeSmallFont());
        resetButton.setBackground(ExchangeLensPanel.BG_CARD);
        resetButton.setForeground(ExchangeLensPanel.TEXT_DIM);
        resetButton.setFocusPainted(false);
        resetButton.setMargin(new Insets(4, 10, 4, 10));
        resetButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        resetButton.setAlignmentX(LEFT_ALIGNMENT);
        resetButton.addActionListener(e -> { if (onReset != null) onReset.run(); });
        add(resetButton);
    }

    public void setOnReset(Runnable onReset)
    {
        this.onReset = onReset;
    }

    /** Called on the EDT (FlipTrackerService marshals via SwingUtilities.invokeLater). */
    public void update(SessionStats stats, List<FlipRecord> recentFlips)
    {
        profitLabel.setText(PriceFormat.formatExact(stats.getTotalProfit()));
        profitLabel.setForeground(stats.getTotalProfit() >= 0
            ? ExchangeLensPanel.GREEN : ExchangeLensPanel.COLOR_HIGH);

        flipsLabel.setText(String.valueOf(stats.getFlipsCompleted()));
        roiLabel.setText(PriceFormat.formatRoi(stats.getAverageRoi()));
        durationLabel.setText(formatDuration(stats.getSessionDurationSeconds()));

        hourlyLabel.setText(PriceFormat.formatExact(stats.getHourlyProfit()));
        hourlyLabel.setForeground(stats.getHourlyProfit() >= 0
            ? ExchangeLensPanel.GREEN : ExchangeLensPanel.COLOR_HIGH);

        revalidate();
        repaint();
    }

    public static String formatDuration(long seconds)
    {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private JPanel divider(String text)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 2, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        label.setForeground(ExchangeLensPanel.TEXT_DIM);

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(0x3C3C3C));

        row.add(label, BorderLayout.WEST);
        row.add(sep, BorderLayout.CENTER);
        return row;
    }

    private JPanel statRow(String key, JLabel valueLabel)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel keyLbl = new JLabel(key);
        keyLbl.setFont(FontManager.getRunescapeSmallFont());
        keyLbl.setForeground(ExchangeLensPanel.TEXT_DIM);

        valueLabel.setFont(FontManager.getRunescapeSmallFont());
        valueLabel.setForeground(Color.WHITE);

        row.add(keyLbl, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);
        return row;
    }
}
