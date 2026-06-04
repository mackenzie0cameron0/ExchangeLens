package com.exchangelens.ui;

import com.exchangelens.api.WikiApiModels;
import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.HistoryDataService;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-item drill-down: Wiki avgHigh/avgLow lines with trade markers overlaid.
 * Range buttons (6h, 1D, 1W, 1M, 1Y) share the same cached timeseries fetch
 * where possible (6h and 1D both use "5m" timestep).
 */
public class ItemDetailPanel extends JPanel
{
    private static final Color BG     = ColorScheme.DARK_GRAY_COLOR;
    private static final Color GREEN  = new Color(0x1EB980);
    private static final Color RED    = new Color(0xE04040);
    private static final Color BLUE   = new Color(0x5E7FFF);
    private static final Color ORANGE = new Color(0xFF9B44);

    /** {label, timestep, windowSeconds} */
    private static final Object[][] RANGES = {
        {"6h",  "5m",   6L  * 3600},
        {"1D",  "5m",  24L  * 3600},
        {"1W",  "1h",   7L  * 86400},
        {"1M",  "6h",  30L  * 86400},
        {"1Y",  "24h", 365L * 86400},
    };

    private final HistoryDataService  dataService;
    private final PriceChartComponent chart       = new PriceChartComponent();
    private final JLabel              nameLabel   = new JLabel("—");
    private final JPanel              flipList    = new JPanel();
    private final JButton[]           rangeBtns   = new JButton[RANGES.length];

    private int              currentItemId;
    private List<FlipRecord> currentFlips = Collections.emptyList();
    private int              selectedRange = 1; // default = 1D

    @Inject
    public ItemDetailPanel(HistoryDataService dataService)
    {
        this.dataService = dataService;
        setLayout(new BorderLayout());
        setBackground(BG);
        add(buildHeader(),     BorderLayout.NORTH);
        add(buildChartWrap(),  BorderLayout.CENTER);
        add(buildFlipScroll(), BorderLayout.SOUTH);
    }

    /** Called on EDT by FlipHistoryWindow. */
    public void loadItem(int itemId, String itemName, List<FlipRecord> allFlips)
    {
        currentItemId  = itemId;
        currentFlips   = allFlips.stream()
            .filter(f -> f.getItemId() == itemId && f.getSellOffer() != null)
            .collect(Collectors.toList());
        nameLabel.setText(itemName);
        rebuildFlipList();
        fetchAndRender(selectedRange);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void fetchAndRender(int rangeIdx)
    {
        selectedRange = rangeIdx;
        for (int i = 0; i < rangeBtns.length; i++)
            rangeBtns[i].setBackground(i == rangeIdx ? ColorScheme.BRAND_ORANGE : new Color(0x3C3C3C));

        String timestep   = (String) RANGES[rangeIdx][1];
        long   windowSecs = (long)   RANGES[rangeIdx][2];
        chart.setNoDataMessage("Loading...");

        dataService.getTimeseries(currentItemId, timestep, data ->
        {
            long now  = System.currentTimeMillis() / 1000;
            long xMin = now - windowSecs;
            long xMax = now;
            if (data == null || data.data == null || data.data.isEmpty())
            {
                chart.setModel(markersOnlyModel(xMin, xMax));
                return;
            }
            List<WikiApiModels.TimeseriesPoint> pts = data.data.stream()
                .filter(p -> p.timestamp >= xMin && p.timestamp <= xMax)
                .collect(Collectors.toList());
            chart.setModel(pts.isEmpty()
                ? markersOnlyModel(xMin, xMax)
                : buildModel(pts, xMin, xMax));
        });
    }

    private ChartModel buildModel(List<WikiApiModels.TimeseriesPoint> pts, long xMin, long xMax)
    {
        ChartModel model = new ChartModel();
        model.xMin = xMin;
        model.xMax = xMax;

        int n = pts.size();
        long[]   ts      = new long[n];
        double[] high    = new double[n];
        double[] low     = new double[n];
        double[] highVol = new double[n];
        double[] lowVol  = new double[n];

        for (int i = 0; i < n; i++)
        {
            WikiApiModels.TimeseriesPoint p = pts.get(i);
            ts[i]      = p.timestamp;
            high[i]    = p.avgHighPrice     != null ? p.avgHighPrice     : 0;
            low[i]     = p.avgLowPrice      != null ? p.avgLowPrice      : 0;
            highVol[i] = p.highPriceVolume  != null ? p.highPriceVolume  : 0;
            lowVol[i]  = p.lowPriceVolume   != null ? p.lowPriceVolume   : 0;
        }

        addSeries(model, "Avg High", ts, high, BLUE);
        addSeries(model, "Avg Low",  ts, low,  ORANGE);
        model.volumeTimestamps = ts;
        model.highVolumes      = highVol;
        model.lowVolumes       = lowVol;

        addMarkers(model);
        return model;
    }

    private ChartModel markersOnlyModel(long xMin, long xMax)
    {
        ChartModel model = new ChartModel();
        model.xMin = xMin;
        model.xMax = xMax;
        addMarkers(model);
        return model;
    }

    private void addSeries(ChartModel model, String label, long[] ts, double[] vals, Color color)
    {
        ChartModel.Series s = new ChartModel.Series();
        s.label = label; s.timestamps = ts; s.values = vals;
        s.color = color; s.strokeWidth = 1.5f;
        model.series.add(s);
    }

    private void addMarkers(ChartModel model)
    {
        for (FlipRecord flip : currentFlips)
        {
            int thisBuyIdx = -1;

            if (flip.getBuyOffer() != null)
            {
                long ts = flip.getBuyOffer().getCompletedAt();
                if (ts >= model.xMin && ts <= model.xMax)
                {
                    ChartModel.Marker m = new ChartModel.Marker();
                    m.timestamp = ts;
                    m.value     = flip.getBuyPrice();
                    m.color     = GREEN;
                    m.isUp      = true;
                    m.tooltip   = flip.getQuantity() + " × "
                        + PriceFormat.formatExact(flip.getBuyPrice()) + "  buy";
                    thisBuyIdx = model.markers.size();
                    model.markers.add(m);
                }
            }

            if (flip.getSellOffer() != null)
            {
                long ts = flip.getSellOffer().getCompletedAt();
                if (ts >= model.xMin && ts <= model.xMax)
                {
                    ChartModel.Marker m = new ChartModel.Marker();
                    m.timestamp = ts;
                    m.value     = flip.getSellPrice();
                    m.color     = RED;
                    m.isUp      = false;
                    m.tooltip   = flip.getQuantity() + " × "
                        + PriceFormat.formatExact(flip.getSellPrice())
                        + "  " + signedProfit(flip.getTotalNetProfit());
                    int sellMarkerIdx = model.markers.size();
                    model.markers.add(m);

                    // Only connect when THIS flip's buy marker is also visible.
                    if (thisBuyIdx >= 0)
                    {
                        ChartModel.Connection conn = new ChartModel.Connection();
                        conn.buyMarkerIndex  = thisBuyIdx;
                        conn.sellMarkerIndex = sellMarkerIdx;
                        model.connections.add(conn);
                    }
                }
            }
        }
    }

    /** Formats profit with an explicit leading sign: "+1.2k" / "-500". */
    private static String signedProfit(long profit)
    {
        return (profit >= 0 ? "+" : "-") + PriceFormat.format(Math.abs(profit));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        nameLabel.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(Color.WHITE);

        JPanel rangeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        rangeRow.setOpaque(false);
        for (int i = 0; i < RANGES.length; i++)
        {
            final int idx = i;
            JButton btn = new JButton((String) RANGES[i][0]);
            btn.setFont(new Font("SansSerif", Font.PLAIN, 10));
            btn.setForeground(Color.WHITE);
            btn.setBackground(i == selectedRange ? ColorScheme.BRAND_ORANGE : new Color(0x3C3C3C));
            btn.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> fetchAndRender(idx));
            rangeBtns[i] = btn;
            rangeRow.add(btn);
        }

        header.add(nameLabel, BorderLayout.NORTH);
        header.add(rangeRow,  BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildChartWrap()
    {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG);
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        chart.setPreferredSize(new Dimension(0, 280));
        wrap.add(chart);
        return wrap;
    }

    private JScrollPane buildFlipScroll()
    {
        flipList.setLayout(new BoxLayout(flipList, BoxLayout.Y_AXIS));
        flipList.setBackground(BG);
        JScrollPane scroll = new JScrollPane(flipList);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            ColorScheme.DARKER_GRAY_COLOR));
        scroll.setPreferredSize(new Dimension(0, 150));
        scroll.getViewport().setBackground(BG);
        return scroll;
    }

    private void rebuildFlipList()
    {
        flipList.removeAll();
        if (currentFlips.isEmpty())
        {
            JLabel empty = new JLabel("No completed flips for this item");
            empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            flipList.add(empty);
        }
        else
        {
            for (FlipRecord flip : currentFlips)
                flipList.add(buildFlipRow(flip));
        }
        flipList.revalidate();
        flipList.repaint();
    }

    private JPanel buildFlipRow(FlipRecord flip)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel profit = new JLabel(PriceFormat.format(flip.getTotalNetProfit()) + " gp");
        profit.setForeground(flip.getTotalNetProfit() >= 0 ? GREEN : RED);
        profit.setFont(new Font("SansSerif", Font.BOLD, 10));

        JLabel detail = new JLabel(flip.getQuantity() + " × "
            + PriceFormat.formatExact(flip.getBuyPrice()) + " → "
            + PriceFormat.formatExact(flip.getSellPrice()));
        detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        detail.setFont(new Font("SansSerif", Font.PLAIN, 9));

        row.add(detail, BorderLayout.CENTER);
        row.add(profit, BorderLayout.EAST);
        return row;
    }
}
