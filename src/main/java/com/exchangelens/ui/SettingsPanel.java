// src/main/java/com/exchangelens/ui/SettingsPanel.java
package com.exchangelens.ui;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel
{
    private final JPanel blocklistPanel = new JPanel();

    public SettingsPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel note = new JLabel("<html>Use the RuneLite settings panel<br/>"
            + "(wrench icon) to adjust margin,<br/>"
            + "ROI, volume, and refresh interval.</html>");
        note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        note.setFont(FontManager.getRunescapeSmallFont());
        note.setBorder(new EmptyBorder(10, 10, 10, 10));
        add(note);

        JPanel blockSection = new JPanel(new BorderLayout());
        blockSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        blockSection.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
            "Blocked Items",
            TitledBorder.LEFT, TitledBorder.TOP,
            FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

        blocklistPanel.setLayout(new BoxLayout(blocklistPanel, BoxLayout.Y_AXIS));
        blocklistPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(blocklistPanel);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(200, 150));
        blockSection.add(scroll, BorderLayout.CENTER);
        add(blockSection);
    }

    public void updateBlocklist(Map<Integer, String> blockedItems, Consumer<Integer> onRemove)
    {
        SwingUtilities.invokeLater(() ->
        {
            blocklistPanel.removeAll();
            if (blockedItems.isEmpty())
            {
                JLabel empty = new JLabel("No blocked items.");
                empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                empty.setFont(FontManager.getRunescapeSmallFont());
                blocklistPanel.add(empty);
            }
            else
            {
                for (Map.Entry<Integer, String> entry : blockedItems.entrySet())
                {
                    JPanel row = new JPanel(new BorderLayout(4, 0));
                    row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                    row.setBorder(new EmptyBorder(2, 4, 2, 4));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

                    JLabel name = new JLabel(entry.getValue());
                    name.setForeground(Color.WHITE);
                    name.setFont(FontManager.getRunescapeSmallFont());

                    JButton remove = new JButton("X");
                    remove.setFont(FontManager.getRunescapeSmallFont());
                    remove.setFocusPainted(false);
                    remove.addActionListener(e -> onRemove.accept(entry.getKey()));

                    row.add(name, BorderLayout.CENTER);
                    row.add(remove, BorderLayout.EAST);
                    blocklistPanel.add(row);
                }
            }
            blocklistPanel.revalidate();
            blocklistPanel.repaint();
        });
    }
}
