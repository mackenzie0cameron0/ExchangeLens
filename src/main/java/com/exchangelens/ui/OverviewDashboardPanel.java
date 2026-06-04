package com.exchangelens.ui;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.PriceFormat;
import com.exchangelens.tracker.FlipAnalytics;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Landing view of FlipHistoryWindow. Shows aggregate stat cards,
 * cumulative-profit chart, and sortable per-item summary table.
 * Row click fires onItemSelected so the window can navigate to ItemDetailPanel.
 */
public class OverviewDashboardPanel extends JPanel
{
    private static final Color BG    = ColorScheme.DARK_GRAY_COLOR;
    private static final Color GREEN = new Color(0x1EB980);
    private static final Color RED   = new Color(0xE04040);

    private final PriceChartComponent chart = new PriceChartComponent();
    private final DefaultTableModel   tableModel;
    private final JTable              table;

    private final JLabel totalProfitVal = new JLabel("—");
    private final JLabel flipsVal       = new JLabel("—");
    private final JLabel winRateVal     = new JLabel("—");
    private final JLabel taxVal         = new JLabel("—");
    private final JLabel bestFlipVal    = new JLabel("—");
    private final JLabel worstFlipVal   = new JLabel("—");

    private BiConsumer<Integer, String> onItemSelected;
    private List<FlipAnalytics.ItemSummary> summaries = Collections.emptyList();

    @Inject
    public OverviewDashboardPanel()
    {
        setLayout(new BorderLayout(0, 4));
        setBackground(BG);

        String[] cols = {"Item", "Flips", "Profit", "Avg ROI", "Avg Fill"};
        tableModel = new DefaultTableModel(cols, 0)
        {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = buildTable();

        add(buildStatCards(), BorderLayout.NORTH);
        add(buildChartSection(), BorderLayout.CENTER);
        add(buildTableSection(), BorderLayout.SOUTH);
    }

    /** Called by FlipHistoryWindow after flips are loaded. Must be called on EDT. */
    public void update(List<FlipRecord> flips)
    {
        long profit    = FlipAnalytics.totalProfit(flips);
        long tax       = FlipAnalytics.totalTax(flips);
        double winRate = FlipAnalytics.winRate(flips);
        FlipRecord best  = FlipAnalytics.bestFlip(flips);
        FlipRecord worst = FlipAnalytics.worstFlip(flips);

        totalProfitVal.setText(PriceFormat.format(profit));
        totalProfitVal.setForeground(profit >= 0 ? GREEN : RED);
        flipsVal.setText(String.valueOf(flips.stream()
            .filter(f -> f.getSellOffer() != null).count()));
        flipsVal.setForeground(Color.WHITE);
        winRateVal.setText(String.format("%.1f%%", winRate));
        winRateVal.setForeground(winRate >= 50 ? GREEN : RED);
        taxVal.setText(PriceFormat.format(tax));
        taxVal.setForeground(RED);
        bestFlipVal.setText(best  != null ? PriceFormat.format(best.getTotalNetProfit())  : "—");
        bestFlipVal.setForeground(GREEN);
        worstFlipVal.setText(worst != null ? PriceFormat.format(worst.getTotalNetProfit()) : "—");
        worstFlipVal.setForeground(RED);

        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        if (series.length >= 2)
        {
            ChartModel model = new ChartModel();
            model.xMin = series[0][0];
            model.xMax = series[series.length - 1][0];
            long[]   ts   = new long[series.length];
            double[] vals = new double[series.length];
            for (int i = 0; i < series.length; i++) { ts[i] = series[i][0]; vals[i] = series[i][1]; }
            ChartModel.Series s = new ChartModel.Series();
            s.label = "Cumulative Profit"; s.timestamps = ts; s.values = vals;
            s.color = GREEN; s.strokeWidth = 1.5f;
            s.skipZeroValues = false;  // cumulative profit may be zero or negative
            model.series.add(s);
            chart.setModel(model);
        }
        else
        {
            chart.setNoDataMessage(flips.isEmpty() ? "No trades yet" : "Need 2+ completed flips for chart");
        }

        summaries = FlipAnalytics.perItemSummary(flips);
        tableModel.setRowCount(0);
        for (FlipAnalytics.ItemSummary s : summaries)
        {
            tableModel.addRow(new Object[]{
                s.itemName,
                s.flipCount,
                PriceFormat.format(s.totalProfit),
                String.format("%.1f%%", s.avgRoi * 100),
                PriceFormat.formatFillTime(s.avgFillSeconds / 60.0)
            });
        }
    }

    public void setOnItemSelected(BiConsumer<Integer, String> listener)
    {
        this.onItemSelected = listener;
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private JPanel buildStatCards()
    {
        JPanel grid = new JPanel(new GridLayout(2, 3, 4, 4));
        grid.setBackground(BG);
        grid.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        grid.add(statCard("Total Profit", totalProfitVal));
        grid.add(statCard("Flips",        flipsVal));
        grid.add(statCard("Win Rate",     winRateVal));
        grid.add(statCard("Tax Paid",     taxVal));
        grid.add(statCard("Best Flip",    bestFlipVal));
        grid.add(statCard("Worst Flip",   worstFlipVal));
        return grid;
    }

    private JPanel statCard(String title, JLabel valueLabel)
    {
        JPanel card = new JPanel(new BorderLayout(0, 2));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("SansSerif", Font.PLAIN, 9));
        titleLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        valueLabel.setFont(FontManager.getRunescapeFont().deriveFont(Font.BOLD, 11f));
        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildChartSection()
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        chart.setPreferredSize(new Dimension(0, 160));
        wrapper.add(chart);
        return wrapper;
    }

    private JScrollPane buildTableSection()
    {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            ColorScheme.DARKER_GRAY_COLOR));
        scroll.setPreferredSize(new Dimension(0, 190));
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        return scroll;
    }

    private JTable buildTable()
    {
        JTable t = new JTable(tableModel);
        t.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        t.setForeground(Color.WHITE);
        t.setGridColor(new Color(0x3A3A3A));
        t.setRowHeight(22);
        t.setFont(new Font("SansSerif", Font.PLAIN, 10));
        t.getTableHeader().setBackground(new Color(0x2A2A2A));
        t.getTableHeader().setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        t.setSelectionBackground(new Color(0x3A3A60));
        t.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        t.setRowSorter(new TableRowSorter<>(tableModel));
        t.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                int row = t.getSelectedRow();
                if (row < 0 || onItemSelected == null || summaries.isEmpty()) return;
                int modelRow = t.convertRowIndexToModel(row);
                if (modelRow < summaries.size())
                {
                    FlipAnalytics.ItemSummary s = summaries.get(modelRow);
                    onItemSelected.accept(s.itemId, s.itemName);
                }
            }
        });
        return t;
    }
}
