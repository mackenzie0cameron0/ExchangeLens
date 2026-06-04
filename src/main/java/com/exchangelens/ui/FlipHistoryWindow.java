package com.exchangelens.ui;

import com.exchangelens.service.HistoryDataService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

/**
 * Resizable pop-out window for reviewing completed flips.
 * Uses CardLayout to switch between the overview dashboard and per-item drill-down.
 * Lazily initialized — do NOT inject directly; use Provider&lt;FlipHistoryWindow&gt;
 * so construction happens on the EDT on first open (see ExchangeLensPlugin).
 */
@Singleton
public class FlipHistoryWindow extends JFrame
{
    private static final String VIEW_OVERVIEW = "overview";
    private static final String VIEW_DETAIL   = "detail";

    private final CardLayout             cardLayout = new CardLayout();
    private final JPanel                 cardPanel  = new JPanel(cardLayout);
    private final OverviewDashboardPanel overviewPanel;
    private final ItemDetailPanel        itemDetailPanel;
    private final HistoryDataService     dataService;
    private final JLabel                 accountLabel = new JLabel();
    private java.util.List<com.exchangelens.model.FlipRecord> loadedFlips = java.util.Collections.emptyList();

    @Inject
    public FlipHistoryWindow(OverviewDashboardPanel overviewPanel,
                             ItemDetailPanel        itemDetailPanel,
                             HistoryDataService     dataService)
    {
        this.overviewPanel   = overviewPanel;
        this.itemDetailPanel = itemDetailPanel;
        this.dataService     = dataService;

        setTitle("Exchange Lens — Flip History");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(920, 620);
        setMinimumSize(new Dimension(700, 480));

        overviewPanel.setOnItemSelected(this::openItemDetail);

        cardPanel.add(overviewPanel,   VIEW_OVERVIEW);
        cardPanel.add(itemDetailPanel, VIEW_DETAIL);

        setLayout(new BorderLayout());
        add(buildTopBar(), BorderLayout.NORTH);
        add(cardPanel,     BorderLayout.CENTER);
    }

    /**
     * Opens or focuses the window, then loads flips for the given account.
     * Must be called on the EDT.
     */
    public void open(String accountName)
    {
        accountLabel.setText(accountName != null ? accountName : "Unknown");
        dataService.invalidateFlips();
        overviewPanel.update(java.util.Collections.emptyList());
        cardLayout.show(cardPanel, VIEW_OVERVIEW);
        if (!isVisible()) setLocationRelativeTo(null);
        setVisible(true);
        toFront();
        requestFocus();

        dataService.loadFlips(accountName, flips ->
        {
            loadedFlips = flips;
            overviewPanel.update(flips);
        });
    }

    /**
     * Switches to the item detail view for the given item.
     * Called from OverviewDashboardPanel row-click via setOnItemSelected.
     * Must be called on the EDT.
     */
    public void openItemDetail(int itemId, String itemName)
    {
        itemDetailPanel.loadItem(itemId, itemName, loadedFlips);
        cardLayout.show(cardPanel, VIEW_DETAIL);
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildTopBar()
    {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JButton overviewBtn = new JButton("Overview");
        overviewBtn.setBackground(new Color(0x3C3C3C));
        overviewBtn.setForeground(Color.WHITE);
        overviewBtn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        overviewBtn.setFocusPainted(false);
        overviewBtn.addActionListener(e -> cardLayout.show(cardPanel, VIEW_OVERVIEW));

        accountLabel.setFont(FontManager.getRunescapeSmallFont());
        accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(overviewBtn);
        left.add(accountLabel);

        bar.add(left, BorderLayout.WEST);
        return bar;
    }
}
