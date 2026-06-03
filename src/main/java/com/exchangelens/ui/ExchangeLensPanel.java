package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PriceFormat;
import com.exchangelens.service.StorageService;
import com.exchangelens.tracker.SessionStats;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ExchangeLensPanel extends PluginPanel
{
    // ── Palette ──────────────────────────────────────────────────────────────
    static final Color GOLD        = new Color(0xFFD700);
    static final Color GREEN       = new Color(0x1EB980);
    static final Color COLOR_LOW   = new Color(0x40C040);
    static final Color COLOR_MED   = new Color(0xE0A800);
    static final Color COLOR_HIGH  = new Color(0xE04040);
    static final Color BG_CARD     = ColorScheme.DARKER_GRAY_COLOR;
    static final Color BG_PANEL    = ColorScheme.DARK_GRAY_COLOR;
    static final Color TEXT_DIM    = ColorScheme.LIGHT_GRAY_COLOR;

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── State ─────────────────────────────────────────────────────────────────
    private final StorageService storage;
    private final SessionStatsPanel sessionStatsPanel;
    private final FlipHistoryPanel  flipHistoryPanel;
    private List<FlipRecommendation> allRecs = new ArrayList<>();
    private Set<Integer>   watchlist      = new HashSet<>();
    private Set<Integer>   blocklist      = new HashSet<>();
    private Set<RiskLevel> activeFilters  = new HashSet<>(Arrays.asList(RiskLevel.values()));
    private String         searchQuery    = "";
    private String         currentView    = "main";
    private Runnable       onManualRefresh;

    // ── Shared header components ──────────────────────────────────────────────
    private final JLabel statusLabel = new JLabel("Loading...");

    // ── Main view components ──────────────────────────────────────────────────
    private final JPanel     heroCard      = new JPanel(new BorderLayout(0, 2));
    private final JLabel     heroName      = new JLabel("Fetching market data...");
    private final JLabel     heroPrices    = new JLabel("");
    private final JLabel     heroStats     = new JLabel("");
    private final JPanel     cardContainer = new JPanel();
    private final JTextField searchBox     = new JTextField();

    // ── Filter buttons ────────────────────────────────────────────────────────
    private final Map<RiskLevel, JButton> filterBtns = new LinkedHashMap<>();

    // ── Inline session summary (main view) ────────────────────────────────────
    private final JLabel sessionProfitValue = new JLabel("—");
    private final JLabel sessionFlipsValue  = new JLabel("—");
    private final JLabel sessionHourlyValue = new JLabel("—");

    // ── Sub-panels ────────────────────────────────────────────────────────────
    private final JPanel watchlistContent  = new JPanel();
    private final JPanel blocklistContent  = new JPanel();

    // ── View container ────────────────────────────────────────────────────────
    private final CardLayout cardLayout    = new CardLayout();
    private final JPanel     viewContainer = new JPanel(cardLayout);

    @Inject
    public ExchangeLensPanel(StorageService storage,
                             SessionStatsPanel sessionStatsPanel,
                             FlipHistoryPanel flipHistoryPanel)
    {
        super(false);
        this.storage           = storage;
        this.sessionStatsPanel = sessionStatsPanel;
        this.flipHistoryPanel  = flipHistoryPanel;
        this.watchlist = storage.loadWatchlist();
        this.blocklist = storage.loadBlocklist();

        setLayout(new BorderLayout());
        setBackground(BG_PANEL);

        viewContainer.setBackground(BG_PANEL);
        viewContainer.add(buildMainView(),      "main");
        viewContainer.add(buildWatchlistView(), "watchlist");
        viewContainer.add(buildSettingsView(),  "settings");
        viewContainer.add(wrapView("$  Session",       sessionStatsPanel), "session");
        viewContainer.add(wrapView("≡  Flip History",  flipHistoryPanel),  "history");

        add(buildHeader(),    BorderLayout.NORTH);
        add(viewContainer,    BorderLayout.CENTER);
    }

    public SessionStatsPanel getSessionStatsPanel() { return sessionStatsPanel; }
    public FlipHistoryPanel  getFlipHistoryPanel()  { return flipHistoryPanel; }

    /**
     * Single EDT-safe sink for session updates, mirroring {@link #updateRecommendations}.
     * Drives the inline main-view summary plus the dedicated Session and History views.
     */
    public void updateSession(SessionStats stats, List<FlipRecord> flips)
    {
        SwingUtilities.invokeLater(() ->
        {
            sessionProfitValue.setText(PriceFormat.formatExact(stats.getTotalProfit()));
            sessionProfitValue.setForeground(stats.getTotalProfit() >= 0 ? GREEN : COLOR_HIGH);
            sessionFlipsValue.setText(String.valueOf(stats.getFlipsCompleted()));
            sessionHourlyValue.setText(PriceFormat.formatExact(stats.getHourlyProfit()));
            sessionStatsPanel.update(stats, flips);
            flipHistoryPanel.update(flips);
        });
    }

    public void setOnManualRefresh(Runnable callback) { this.onManualRefresh = callback; }

    public void updateRecommendations(List<FlipRecommendation> recs, Instant lastFetch, boolean stale)
    {
        SwingUtilities.invokeLater(() ->
        {
            this.allRecs = recs;
            String time = lastFetch != null ? TIME_FMT.format(lastFetch) : "--:--:--";
            statusLabel.setText((stale ? "⚠  " : "") + time + "  (" + recs.size() + " items)");
            statusLabel.setForeground(stale ? COLOR_MED : TEXT_DIM);
            rebuildHero();
            rebuildCards();
            rebuildWatchlist();
            rebuildBlocklist();
        });
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void showView(String name)
    {
        currentView = name;
        cardLayout.show(viewContainer, name);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Header  (always visible)
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(8, 10, 6, 10));

        // Title row
        JLabel title = new JLabel("Exchange Lens");
        title.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(GOLD);
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { showView("main"); }
        });

        JButton refreshBtn = iconButton("↻", "Refresh market data");
        refreshBtn.addActionListener(e -> { if (onManualRefresh != null) onManualRefresh.run(); });

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        titleRow.add(refreshBtn, BorderLayout.EAST);

        // Status
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(TEXT_DIM);

        // Filter + nav row
        JPanel filterRow = buildFilterRow();

        header.add(titleRow,   BorderLayout.NORTH);
        header.add(statusLabel, BorderLayout.CENTER);
        header.add(filterRow,  BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildFilterRow()
    {
        JPanel row = new JPanel(new BorderLayout(0, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 0, 0));

        // Risk toggles
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        filters.setOpaque(false);

        RiskLevel[] levels = {RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH};
        Color[]     colors = {COLOR_LOW,     COLOR_MED,        COLOR_HIGH};
        String[]    labels = {"Low",         "Med",            "High"};

        for (int i = 0; i < levels.length; i++)
        {
            RiskLevel level = levels[i];
            Color     on    = colors[i];
            JButton   btn   = riskToggle(labels[i], on);
            filterBtns.put(level, btn);
            btn.addActionListener(e -> onFilterToggle(level, btn, on));
            filters.add(btn);
        }

        // Nav icons
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        nav.setOpaque(false);

        JButton sessionBtn = iconButton("$", "Session stats");
        JButton historyBtn = iconButton("≡", "Flip history");
        JButton starBtn = iconButton("★", "Watchlist");
        JButton gearBtn = iconButton("⚙", "Settings");
        sessionBtn.addActionListener(e -> showView("session".equals(currentView)   ? "main" : "session"));
        historyBtn.addActionListener(e -> showView("history".equals(currentView)   ? "main" : "history"));
        starBtn.addActionListener(e -> showView("watchlist".equals(currentView) ? "main" : "watchlist"));
        gearBtn.addActionListener(e -> showView("settings".equals(currentView)  ? "main" : "settings"));
        nav.add(sessionBtn);
        nav.add(historyBtn);
        nav.add(starBtn);
        nav.add(gearBtn);

        row.add(filters, BorderLayout.WEST);
        row.add(nav,     BorderLayout.EAST);
        return row;
    }

    private void onFilterToggle(RiskLevel level, JButton btn, Color onColor)
    {
        if (activeFilters.contains(level))
        {
            activeFilters.remove(level);
            btn.setBackground(new Color(0x3C3C3C));
            btn.setForeground(TEXT_DIM);
        }
        else
        {
            activeFilters.add(level);
            btn.setBackground(onColor);
            btn.setForeground(Color.BLACK);
        }
        rebuildCards();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main view
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildMainView()
    {
        JPanel view = new JPanel(new BorderLayout());
        view.setBackground(BG_PANEL);

        // Fixed top: hero + stats + search
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(BG_PANEL);
        top.add(buildHeroCard());
        top.add(buildStatsSection());
        top.add(buildSearchBar());

        // Scrollable recommendation list
        cardContainer.setLayout(new BoxLayout(cardContainer, BoxLayout.Y_AXIS));
        cardContainer.setBackground(BG_PANEL);
        cardContainer.setBorder(new EmptyBorder(4, 6, 4, 6));

        JScrollPane scroll = new JScrollPane(cardContainer);
        scroll.setBorder(null);
        scroll.setBackground(BG_PANEL);
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        view.add(top,    BorderLayout.NORTH);
        view.add(scroll, BorderLayout.CENTER);
        return view;
    }

    // ── Hero card ─────────────────────────────────────────────────────────────

    private JPanel buildHeroCard()
    {
        heroCard.setBackground(BG_CARD);
        heroCard.setBorder(BorderFactory.createCompoundBorder(
            new EmptyBorder(8, 6, 4, 6),
            BorderFactory.createCompoundBorder(
                new MatteBorder(0, 3, 0, 0, GOLD),
                new EmptyBorder(8, 10, 8, 10))));

        JLabel topPickLabel = new JLabel("TOP PICK");
        topPickLabel.setFont(FontManager.getRunescapeSmallFont());
        topPickLabel.setForeground(GOLD);

        heroName.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 13f));
        heroName.setForeground(Color.WHITE);

        heroPrices.setFont(FontManager.getRunescapeSmallFont());
        heroPrices.setForeground(TEXT_DIM);

        heroStats.setFont(FontManager.getRunescapeSmallFont());
        heroStats.setForeground(GREEN);

        JPanel textBlock = new JPanel();
        textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
        textBlock.setOpaque(false);
        textBlock.add(topPickLabel);
        textBlock.add(Box.createVerticalStrut(3));
        textBlock.add(heroName);
        textBlock.add(heroPrices);
        textBlock.add(heroStats);

        heroCard.add(textBlock, BorderLayout.CENTER);
        return heroCard;
    }

    private void rebuildHero()
    {
        List<FlipRecommendation> filtered = filteredRecs();
        if (filtered.isEmpty())
        {
            heroName.setText("No recommendations");
            heroPrices.setText("");
            heroStats.setText("");
            return;
        }
        FlipRecommendation top = filtered.get(0);
        heroName.setText(top.getItemName() + (top.isMembers() ? " (m)" : ""));
        heroPrices.setText("Buy " + PriceFormat.formatExact(top.getBuyPrice())
            + " gp  →  Sell " + PriceFormat.formatExact(top.getSellPrice()) + " gp");
        heroStats.setText("+" + PriceFormat.format(top.getNetMargin())
            + " margin  ·  " + PriceFormat.formatRoi(top.getRoi()) + " ROI");
        heroCard.revalidate();
        heroCard.repaint();
    }

    // ── Session stats (Phase 3 placeholders) ──────────────────────────────────

    private JPanel buildStatsSection()
    {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(BG_PANEL);
        section.setBorder(new EmptyBorder(2, 6, 2, 6));

        section.add(sectionDivider("Session"));
        section.add(statRow("Profit",        sessionProfitValue));
        section.add(statRow("Flips made",    sessionFlipsValue));
        section.add(statRow("Hourly profit", sessionHourlyValue));
        return section;
    }

    private JPanel sectionDivider(String text)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 2, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        label.setForeground(TEXT_DIM);

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(0x3C3C3C));

        row.add(label, BorderLayout.WEST);
        row.add(sep,   BorderLayout.CENTER);
        return row;
    }

    private JPanel statRow(String key, String value)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel keyLbl = new JLabel(key);
        keyLbl.setFont(FontManager.getRunescapeSmallFont());
        keyLbl.setForeground(TEXT_DIM);

        JLabel valLbl = new JLabel(value);
        valLbl.setFont(FontManager.getRunescapeSmallFont());
        valLbl.setForeground(Color.WHITE);

        row.add(keyLbl, BorderLayout.WEST);
        row.add(valLbl, BorderLayout.EAST);
        return row;
    }

    /** Variant binding a caller-owned value label so it can be mutated live. */
    private JPanel statRow(String key, JLabel valLbl)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel keyLbl = new JLabel(key);
        keyLbl.setFont(FontManager.getRunescapeSmallFont());
        keyLbl.setForeground(TEXT_DIM);

        valLbl.setFont(FontManager.getRunescapeSmallFont());
        valLbl.setForeground(Color.WHITE);

        row.add(keyLbl, BorderLayout.WEST);
        row.add(valLbl, BorderLayout.EAST);
        return row;
    }

    private JPanel wrapView(String title, JComponent content)
    {
        JPanel view = new JPanel(new BorderLayout());
        view.setBackground(BG_PANEL);
        view.add(viewHeader(title), BorderLayout.NORTH);
        view.add(content, BorderLayout.CENTER);
        return view;
    }

    // ── Search bar ────────────────────────────────────────────────────────────

    private JPanel buildSearchBar()
    {
        searchBox.setFont(FontManager.getRunescapeSmallFont());
        searchBox.setBackground(BG_CARD);
        searchBox.setForeground(Color.WHITE);
        searchBox.setCaretColor(Color.WHITE);
        searchBox.putClientProperty("JTextField.placeholderText", "Search items...");
        searchBox.setBorder(new EmptyBorder(4, 6, 4, 6));
        searchBox.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
        {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { onSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { onSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_CARD);
        wrapper.setBorder(new EmptyBorder(6, 6, 4, 6));
        wrapper.add(searchBox, BorderLayout.CENTER);
        return wrapper;
    }

    private void onSearch()
    {
        searchQuery = searchBox.getText().trim().toLowerCase();
        rebuildCards();
    }

    // ── Recommendation cards ──────────────────────────────────────────────────

    private void rebuildCards()
    {
        List<FlipRecommendation> visible = filteredRecs().stream()
            .filter(r -> searchQuery.isEmpty() || r.getItemName().toLowerCase().contains(searchQuery))
            .collect(Collectors.toList());

        cardContainer.removeAll();
        if (visible.isEmpty())
        {
            JLabel empty = new JLabel(allRecs.isEmpty() ? "Fetching market data..." : "No results.");
            empty.setForeground(TEXT_DIM);
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setBorder(new EmptyBorder(12, 4, 0, 0));
            cardContainer.add(empty);
        }
        else
        {
            for (FlipRecommendation rec : visible)
            {
                RecommendationCard card = new RecommendationCard(this::watch, this::block);
                card.update(rec);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
                cardContainer.add(card);
                cardContainer.add(Box.createVerticalStrut(2));
            }
        }
        cardContainer.revalidate();
        cardContainer.repaint();
    }

    private List<FlipRecommendation> filteredRecs()
    {
        return allRecs.stream()
            .filter(r -> activeFilters.contains(r.getRiskLevel()))
            .collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Watchlist view
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildWatchlistView()
    {
        watchlistContent.setLayout(new BoxLayout(watchlistContent, BoxLayout.Y_AXIS));
        watchlistContent.setBackground(BG_PANEL);

        JScrollPane scroll = new JScrollPane(watchlistContent);
        scroll.setBorder(null);
        scroll.setBackground(BG_PANEL);
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel view = new JPanel(new BorderLayout());
        view.setBackground(BG_PANEL);
        view.add(viewHeader("★  Watchlist"), BorderLayout.NORTH);
        view.add(scroll, BorderLayout.CENTER);
        return view;
    }

    private void rebuildWatchlist()
    {
        List<FlipRecommendation> watched = allRecs.stream()
            .filter(r -> watchlist.contains(r.getItemId()))
            .collect(Collectors.toList());

        watchlistContent.removeAll();
        watchlistContent.setBorder(new EmptyBorder(4, 6, 4, 6));

        if (watched.isEmpty())
        {
            JLabel empty = new JLabel("No watched items yet.");
            empty.setForeground(TEXT_DIM);
            empty.setFont(FontManager.getRunescapeSmallFont());
            watchlistContent.add(empty);
        }
        else
        {
            for (FlipRecommendation rec : watched)
            {
                watchlistContent.add(buildWatchRow(rec));
                watchlistContent.add(Box.createVerticalStrut(2));
            }
        }
        watchlistContent.revalidate();
        watchlistContent.repaint();
    }

    private JPanel buildWatchRow(FlipRecommendation rec)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(BG_CARD);
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel info = new JLabel("<html><b>" + rec.getItemName() + "</b><br/>"
            + "<span style='color:#aaa'>"
            + PriceFormat.formatExact(rec.getBuyPrice()) + " → "
            + PriceFormat.formatExact(rec.getSellPrice()) + "  +"
            + PriceFormat.format(rec.getNetMargin()) + "  "
            + PriceFormat.formatRoi(rec.getRoi()) + " ROI"
            + "</span></html>");
        info.setForeground(Color.WHITE);
        info.setFont(FontManager.getRunescapeSmallFont());

        JButton remove = iconButton("★", "Remove from watchlist");
        remove.setForeground(GOLD);
        remove.addActionListener(e -> unwatch(rec.getItemId()));

        row.add(info,   BorderLayout.CENTER);
        row.add(remove, BorderLayout.EAST);
        return row;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Settings view
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildSettingsView()
    {
        blocklistContent.setLayout(new BoxLayout(blocklistContent, BoxLayout.Y_AXIS));
        blocklistContent.setBackground(BG_PANEL);

        JLabel runeNote = new JLabel("<html>Adjust margin, ROI, volume &amp; refresh<br/>"
            + "interval in the RuneLite settings panel.</html>");
        runeNote.setForeground(TEXT_DIM);
        runeNote.setFont(FontManager.getRunescapeSmallFont());
        runeNote.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel blockTitle = new JLabel("Blocked Items");
        blockTitle.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        blockTitle.setForeground(TEXT_DIM);
        blockTitle.setBorder(new EmptyBorder(4, 10, 4, 10));

        JScrollPane scroll = new JScrollPane(blocklistContent);
        scroll.setBorder(null);
        scroll.setBackground(BG_PANEL);
        scroll.getViewport().setBackground(BG_PANEL);

        JPanel view = new JPanel(new BorderLayout());
        view.setBackground(BG_PANEL);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_PANEL);
        content.add(runeNote);
        content.add(blockTitle);
        content.add(scroll);

        view.add(viewHeader("⚙  Settings"), BorderLayout.NORTH);
        view.add(content, BorderLayout.CENTER);
        return view;
    }

    private void rebuildBlocklist()
    {
        blocklistContent.removeAll();
        blocklistContent.setBorder(new EmptyBorder(0, 6, 4, 6));

        Map<Integer, String> blocked = new LinkedHashMap<>();
        for (FlipRecommendation r : allRecs)
            if (blocklist.contains(r.getItemId()))
                blocked.put(r.getItemId(), r.getItemName());

        if (blocked.isEmpty())
        {
            JLabel empty = new JLabel("No blocked items.");
            empty.setForeground(TEXT_DIM);
            empty.setFont(FontManager.getRunescapeSmallFont());
            blocklistContent.add(empty);
        }
        else
        {
            for (Map.Entry<Integer, String> entry : blocked.entrySet())
            {
                JPanel row = new JPanel(new BorderLayout(4, 0));
                row.setBackground(BG_CARD);
                row.setBorder(new EmptyBorder(4, 8, 4, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

                JLabel name = new JLabel(entry.getValue());
                name.setForeground(Color.WHITE);
                name.setFont(FontManager.getRunescapeSmallFont());

                JButton remove = iconButton("✕", "Unblock");
                remove.setForeground(COLOR_HIGH);
                remove.addActionListener(e -> unblock(entry.getKey()));

                row.add(name,   BorderLayout.CENTER);
                row.add(remove, BorderLayout.EAST);
                blocklistContent.add(row);
                blocklistContent.add(Box.createVerticalStrut(2));
            }
        }
        blocklistContent.revalidate();
        blocklistContent.repaint();
    }

    // ── Watchlist / blocklist mutations ───────────────────────────────────────

    private void watch(int itemId)
    {
        watchlist.add(itemId);
        storage.saveWatchlist(watchlist);
        rebuildWatchlist();
    }

    private void unwatch(int itemId)
    {
        watchlist.remove(itemId);
        storage.saveWatchlist(watchlist);
        rebuildWatchlist();
    }

    private void block(int itemId)
    {
        blocklist.add(itemId);
        storage.saveBlocklist(blocklist);
        allRecs = allRecs.stream().filter(r -> r.getItemId() != itemId).collect(Collectors.toList());
        rebuildCards();
        rebuildHero();
        rebuildBlocklist();
    }

    private void unblock(int itemId)
    {
        blocklist.remove(itemId);
        storage.saveBlocklist(blocklist);
        rebuildBlocklist();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel viewHeader(String label)
    {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBorder(new EmptyBorder(6, 10, 6, 10));

        JLabel title = new JLabel(label);
        title.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        title.setForeground(TEXT_DIM);

        JButton back = iconButton("←", "Back to recommendations");
        back.addActionListener(e -> showView("main"));

        bar.add(title, BorderLayout.WEST);
        bar.add(back,  BorderLayout.EAST);
        return bar;
    }

    static JButton iconButton(String text, String tooltip)
    {
        JButton btn = new JButton(text);
        btn.setFont(FontManager.getRunescapeSmallFont());
        btn.setForeground(TEXT_DIM);
        btn.setBackground(null);
        btn.setOpaque(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setToolTipText(tooltip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JButton riskToggle(String label, Color onColor)
    {
        JButton btn = new JButton(label);
        btn.setFont(FontManager.getRunescapeSmallFont());
        btn.setBackground(onColor);
        btn.setForeground(Color.BLACK);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(2, 7, 2, 7));
        return btn;
    }
}
