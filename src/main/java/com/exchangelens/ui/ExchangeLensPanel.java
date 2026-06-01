// src/main/java/com/exchangelens/ui/ExchangeLensPanel.java
package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.service.StorageService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ExchangeLensPanel extends PluginPanel
{
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final StorageService storage;

    private final JLabel statusLabel   = new JLabel("Loading...");
    private final JTextField searchBox = new JTextField();
    private final JPanel cardContainer = new JPanel();
    private final WatchlistPanel watchlistPanel;
    private final SettingsPanel settingsPanel;

    private List<FlipRecommendation> allRecs  = new ArrayList<>();
    private Set<Integer> watchlist            = new HashSet<>();
    private Set<Integer> blocklist            = new HashSet<>();
    private Runnable onManualRefresh;

    @Inject
    public ExchangeLensPanel(StorageService storage)
    {
        super(false);
        this.storage = storage;
        this.watchlist = storage.loadWatchlist();
        this.blocklist = storage.loadBlocklist();

        watchlistPanel = new WatchlistPanel(this::unwatch);
        settingsPanel  = new SettingsPanel();

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
    }

    public void setOnManualRefresh(Runnable callback)
    {
        this.onManualRefresh = callback;
    }

    public void updateRecommendations(List<FlipRecommendation> recs, Instant lastFetch, boolean stale)
    {
        SwingUtilities.invokeLater(() ->
        {
            this.allRecs = recs;
            String timeStr = lastFetch != null ? TIME_FMT.format(lastFetch) : "--:--:--";
            statusLabel.setText((stale ? "⚠ Stale — " : "") + "Updated: " + timeStr
                + "  (" + recs.size() + " items)");
            statusLabel.setForeground(stale ? new Color(0xE0A800) : ColorScheme.LIGHT_GRAY_COLOR);
            rebuildCards();
            updateWatchlistPanel();
            updateBlocklistPanel();
        });
    }

    private void rebuildCards()
    {
        String query = searchBox.getText().trim().toLowerCase();
        List<FlipRecommendation> filtered = allRecs.stream()
            .filter(r -> query.isEmpty() || r.getItemName().toLowerCase().contains(query))
            .collect(Collectors.toList());

        cardContainer.removeAll();
        for (FlipRecommendation rec : filtered)
        {
            RecommendationCard card = new RecommendationCard(this::watch, this::block);
            card.update(rec);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            cardContainer.add(card);
            cardContainer.add(Box.createVerticalStrut(3));
        }
        if (filtered.isEmpty())
        {
            JLabel empty = new JLabel(allRecs.isEmpty() ? "Fetching market data..." : "No results.");
            empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setBorder(new EmptyBorder(12, 12, 0, 0));
            cardContainer.add(empty);
        }
        cardContainer.revalidate();
        cardContainer.repaint();
    }

    private void updateWatchlistPanel()
    {
        List<FlipRecommendation> watched = allRecs.stream()
            .filter(r -> watchlist.contains(r.getItemId()))
            .collect(Collectors.toList());
        watchlistPanel.update(watched);
    }

    private void updateBlocklistPanel()
    {
        Map<Integer, String> blockedNames = new LinkedHashMap<>();
        for (FlipRecommendation r : allRecs)
        {
            if (blocklist.contains(r.getItemId()))
                blockedNames.put(r.getItemId(), r.getItemName());
        }
        settingsPanel.updateBlocklist(blockedNames, this::unblock);
    }

    private void watch(int itemId)
    {
        watchlist.add(itemId);
        storage.saveWatchlist(watchlist);
        updateWatchlistPanel();
    }

    private void unwatch(int itemId)
    {
        watchlist.remove(itemId);
        storage.saveWatchlist(watchlist);
        updateWatchlistPanel();
    }

    private void block(int itemId)
    {
        blocklist.add(itemId);
        storage.saveBlocklist(blocklist);
        allRecs = allRecs.stream()
            .filter(r -> r.getItemId() != itemId)
            .collect(Collectors.toList());
        rebuildCards();
        updateBlocklistPanel();
    }

    private void unblock(int itemId)
    {
        blocklist.remove(itemId);
        storage.saveBlocklist(blocklist);
        updateBlocklistPanel();
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(8, 8, 6, 8));

        JLabel title = new JLabel("Exchange Lens");
        title.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(new Color(0xFFD700));

        JButton refreshBtn = new JButton("↻");
        refreshBtn.setFont(FontManager.getRunescapeSmallFont());
        refreshBtn.setFocusPainted(false);
        refreshBtn.setToolTipText("Manual refresh");
        refreshBtn.addActionListener(e -> { if (onManualRefresh != null) onManualRefresh.run(); });

        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        searchBox.setFont(FontManager.getRunescapeSmallFont());
        searchBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchBox.setForeground(Color.WHITE);
        searchBox.setCaretColor(Color.WHITE);
        searchBox.putClientProperty("JTextField.placeholderText", "Search items...");
        searchBox.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { rebuildCards(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { rebuildCards(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildCards(); }
        });

        JPanel topRow = new JPanel(new BorderLayout(4, 0));
        topRow.setOpaque(false);
        topRow.add(title, BorderLayout.WEST);
        topRow.add(refreshBtn, BorderLayout.EAST);

        header.add(topRow, BorderLayout.NORTH);
        header.add(statusLabel, BorderLayout.CENTER);
        header.add(searchBox, BorderLayout.SOUTH);
        return header;
    }

    private JTabbedPane buildTabs()
    {
        cardContainer.setLayout(new BoxLayout(cardContainer, BoxLayout.Y_AXIS));
        cardContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane recScroll = new JScrollPane(cardContainer);
        recScroll.setBorder(null);
        recScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        recScroll.getVerticalScrollBar().setUnitIncrement(16);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(FontManager.getRunescapeSmallFont());
        tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabs.addTab("Recommendations", recScroll);
        tabs.addTab("Watchlist", watchlistPanel);
        tabs.addTab("Settings", settingsPanel);
        return tabs;
    }
}
