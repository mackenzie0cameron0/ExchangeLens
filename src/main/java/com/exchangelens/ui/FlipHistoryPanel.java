package com.exchangelens.ui;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.service.PriceFormat;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

/**
 * Scrollable list of completed flips in the current session, newest first. Each row shows
 * the item name, a suggestion-source badge ("EL"/"M"), the qty × per-item = total profit
 * (coloured by sign), buy/sell fill times, and ROI. Mirrors {@link ExchangeLensPanel}'s
 * watchlist-row style. Updated on the EDT via {@link #update}.
 */
@Singleton
public class FlipHistoryPanel extends JPanel
{
    private final JPanel listContent = new JPanel();
    private int lastRenderedCount = -1;
    private String lastRenderedTopId = null;

    @Inject
    public FlipHistoryPanel()
    {
        setLayout(new BorderLayout());
        setBackground(ExchangeLensPanel.BG_PANEL);

        listContent.setLayout(new BoxLayout(listContent, BoxLayout.Y_AXIS));
        listContent.setBackground(ExchangeLensPanel.BG_PANEL);
        listContent.setBorder(new EmptyBorder(4, 6, 4, 6));

        JScrollPane scroll = new JScrollPane(listContent);
        scroll.setBorder(null);
        scroll.setBackground(ExchangeLensPanel.BG_PANEL);
        scroll.getViewport().setBackground(ExchangeLensPanel.BG_PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(scroll, BorderLayout.CENTER);
    }

    /** Called on the EDT. Renders {@code flips} newest-first. */
    public void update(List<FlipRecord> flips)
    {
        // The 1-second duration clock re-pushes the same list; only rebuild when the set of
        // completed flips actually changed. Key on both the count AND the newest flip's id so a
        // reset-then-refill that returns to a previously-seen size still forces a rebuild.
        int count = flips == null ? 0 : flips.size();
        String topId = count == 0 ? null : flips.get(count - 1).getId();
        if (count == lastRenderedCount && java.util.Objects.equals(topId, lastRenderedTopId)) return;
        lastRenderedCount = count;
        lastRenderedTopId = topId;

        listContent.removeAll();

        if (flips == null || flips.isEmpty())
        {
            JLabel empty = new JLabel("No completed flips this session.");
            empty.setForeground(ExchangeLensPanel.TEXT_DIM);
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setBorder(new EmptyBorder(12, 4, 0, 0));
            listContent.add(empty);
        }
        else
        {
            for (int i = flips.size() - 1; i >= 0; i--)
            {
                listContent.add(buildFlipRow(flips.get(i)));
                listContent.add(Box.createVerticalStrut(2));
            }
        }

        listContent.revalidate();
        listContent.repaint();
    }

    private JPanel buildFlipRow(FlipRecord f)
    {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        row.setBackground(ExchangeLensPanel.BG_CARD);
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        String name = f.getItemName() != null ? f.getItemName() : "Item " + f.getItemId();
        JLabel nameLbl = new JLabel(name);
        nameLbl.setForeground(Color.WHITE);
        nameLbl.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(nameLbl, BorderLayout.WEST);
        top.add(sourceBadge(f.getSuggestionSource()), BorderLayout.EAST);

        boolean profit = f.getTotalNetProfit() >= 0;
        String color = profit ? "#1EB980" : "#E04040";
        String sign  = profit ? "+" : "";
        String stats = "<html><span style='color:#aaaaaa'>"
            + f.getQuantity() + " × " + PriceFormat.format(f.getNetProfitPerItem()) + " = </span>"
            + "<span style='color:" + color + "'>" + sign + PriceFormat.formatExact(f.getTotalNetProfit()) + "</span>"
            + "<br/><span style='color:#888888'>buy " + PriceFormat.formatFillTime(f.getBuyFillSeconds() / 60.0)
            + " · sell " + PriceFormat.formatFillTime(f.getSellFillSeconds() / 60.0)
            + " · " + PriceFormat.formatRoi(f.getRoi()) + " ROI</span></html>";
        JLabel statsLbl = new JLabel(stats);
        statsLbl.setFont(FontManager.getRunescapeSmallFont());

        row.add(top, BorderLayout.NORTH);
        row.add(statsLbl, BorderLayout.CENTER);
        return row;
    }

    private JLabel sourceBadge(String source)
    {
        boolean el = "exchange-lens".equals(source);
        JLabel badge = new JLabel(el ? "EL" : "M");
        badge.setOpaque(true);
        badge.setBackground(el ? ExchangeLensPanel.GREEN : new Color(0x3C3C3C));
        badge.setForeground(el ? Color.BLACK : ExchangeLensPanel.TEXT_DIM);
        badge.setFont(FontManager.getRunescapeSmallFont());
        badge.setBorder(new EmptyBorder(1, 4, 1, 4));
        return badge;
    }
}
