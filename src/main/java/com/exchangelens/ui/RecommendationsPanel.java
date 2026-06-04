package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.MarketDataService;
import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Recommendations tab of FlipHistoryWindow. Shows the current Exchange Lens
 * recommendations in a sortable table; a row click fires onRecSelected with the
 * chosen {@link FlipRecommendation}. Reads a point-in-time snapshot from
 * {@link MarketDataService}; call {@link #refresh()} on the EDT to repopulate.
 */
public class RecommendationsPanel extends JPanel
{
    private static final Color LOW   = new Color(0x40C040);
    private static final Color MED   = new Color(0xE0A800);
    private static final Color HIGH  = new Color(0xE04040);

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private final MarketDataService marketDataService;
    private final CardLayout        cards = new CardLayout();
    private final DefaultTableModel tableModel;
    private final JTable            table;

    private List<FlipRecommendation> currentRecs = Collections.emptyList();
    private Consumer<FlipRecommendation> onRecSelected;

    @Inject
    public RecommendationsPanel(MarketDataService marketDataService)
    {
        this.marketDataService = marketDataService;
        setLayout(cards);
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        String[] cols = {"Item", "Margin", "ROI", "Risk", "Score", "Est. Fill"};
        tableModel = new DefaultTableModel(cols, 0)
        {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c)
            {
                switch (c)
                {
                    case 1:  return Integer.class;    // margin (raw gp)
                    case 2:  return Double.class;     // roi %
                    case 3:  return RiskLevel.class;  // risk (enum: LOW<MEDIUM<HIGH)
                    case 4:  return Double.class;     // score
                    case 5:  return Double.class;     // fill minutes
                    default: return String.class;     // item name
                }
            }
        };
        table = buildTable();

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel empty = new JLabel("No recommendations yet (waiting on market data)",
            SwingConstants.CENTER);
        empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        JPanel emptyWrap = new JPanel(new BorderLayout());
        emptyWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        emptyWrap.add(empty, BorderLayout.CENTER);

        add(scroll,    CARD_TABLE);
        add(emptyWrap, CARD_EMPTY);
        cards.show(this, CARD_EMPTY);
    }

    public void setOnRecSelected(Consumer<FlipRecommendation> listener)
    {
        this.onRecSelected = listener;
    }

    /** Re-reads the current recommendation snapshot and rebuilds the table. EDT only. */
    public void refresh()
    {
        currentRecs = new ArrayList<>(marketDataService.getLastRecommendations());
        tableModel.setRowCount(0);
        for (FlipRecommendation rec : currentRecs)
        {
            RecommendationRow row = RecommendationRow.from(rec);
            tableModel.addRow(new Object[]{
                row.itemName,
                row.margin,        // Integer
                row.roiPercent,    // Double
                row.risk,          // RiskLevel
                row.score,         // Double
                row.fillMinutes    // Double
            });
        }
        cards.show(this, currentRecs.isEmpty() ? CARD_EMPTY : CARD_TABLE);
    }

    // ── Table ───────────────────────────────────────────────────────────────────

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

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        t.setRowSorter(sorter);
        sorter.setSortKeys(Collections.singletonList(
            new RowSorter.SortKey(4, SortOrder.DESCENDING)));   // default: Score desc

        t.getColumnModel().getColumn(1).setCellRenderer(
            rightRenderer(v -> RecommendationRow.marginText((Integer) v)));
        t.getColumnModel().getColumn(2).setCellRenderer(
            rightRenderer(v -> RecommendationRow.roiText((Double) v)));
        t.getColumnModel().getColumn(4).setCellRenderer(
            rightRenderer(v -> RecommendationRow.scoreText((Double) v)));
        t.getColumnModel().getColumn(5).setCellRenderer(
            rightRenderer(v -> RecommendationRow.fillText((Double) v)));
        t.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer()
        {
            { setHorizontalAlignment(SwingConstants.CENTER); }
            @Override public Component getTableCellRendererComponent(JTable tbl, Object value,
                boolean sel, boolean foc, int row, int col)
            {
                Component c = super.getTableCellRendererComponent(tbl, value, sel, foc, row, col);
                RiskLevel rl = value instanceof RiskLevel ? (RiskLevel) value : null;
                setText(RecommendationRow.riskLabel(rl));
                setForeground(riskColor(rl));
                return c;
            }
        });

        t.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                int viewRow = t.getSelectedRow();
                Consumer<FlipRecommendation> cb = onRecSelected;
                if (viewRow < 0 || cb == null) return;
                int modelRow = t.convertRowIndexToModel(viewRow);
                if (modelRow >= 0 && modelRow < currentRecs.size())
                    cb.accept(currentRecs.get(modelRow));
            }
        });
        return t;
    }

    private static Color riskColor(RiskLevel risk)
    {
        if (risk == RiskLevel.LOW)    return LOW;
        if (risk == RiskLevel.MEDIUM) return MED;
        if (risk == RiskLevel.HIGH)   return HIGH;
        return Color.WHITE;
    }

    private static DefaultTableCellRenderer rightRenderer(Function<Object, String> fmt)
    {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer()
        {
            @Override protected void setValue(Object value)
            {
                setText(value == null ? "" : fmt.apply(value));
            }
        };
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        return r;
    }
}
