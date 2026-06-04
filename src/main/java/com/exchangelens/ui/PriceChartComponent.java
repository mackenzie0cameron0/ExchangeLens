package com.exchangelens.ui;

import com.exchangelens.service.PriceFormat;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Java2D chart renderer. Accepts a {@link ChartModel}; panels set one
 * to trigger a repaint. No data fetching inside this class.
 */
public class PriceChartComponent extends JComponent
{
    private static final int MARGIN_LEFT   = 72;
    private static final int MARGIN_RIGHT  = 12;
    private static final int MARGIN_TOP    = 8;
    private static final int MARGIN_BOTTOM = 36;
    private static final int MARKER_HALF   = 6;  // half-size of marker triangle

    private static final Color BG         = new Color(0x1C1C1C);
    private static final Color GRID       = new Color(0x2A2A2A);
    private static final Color AXIS_LABEL = new Color(0x707070);
    private static final Color CROSSHAIR  = new Color(0x505050);
    private static final Color TOOLTIP_BG = new Color(0x2C2C2C);
    private static final Color VOL_HIGH   = new Color(0x5E, 0x7F, 0xFF, 40);
    private static final Color VOL_LOW    = new Color(0xFF, 0x9B, 0x44, 40);

    private static final DateTimeFormatter HOUR_FMT =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("MM/dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HOVER_DATETIME_FMT =
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private ChartModel model;
    private String     noDataMessage;
    private int        mouseX = -1, mouseY = -1;
    private String     hoveredTooltip;

    // Hover state for line-series readout: snapped crosshair x and highlight dots ({x, y, rgb}).
    private int                 hoverSnapX = -1;
    private final List<int[]>   hoverDots  = new ArrayList<>();

    // Cached y-bounds from last paint (needed for hover hit-testing)
    private double paintYMin, paintYMax;

    public PriceChartComponent()
    {
        setOpaque(true);
        addMouseMotionListener(new MouseAdapter()
        {
            @Override public void mouseMoved(MouseEvent e)
            {
                mouseX = e.getX();
                mouseY = e.getY();
                updateHover();
                repaint();
            }
        });
        addMouseListener(new MouseAdapter()
        {
            @Override public void mouseExited(MouseEvent e)
            {
                mouseX = -1;
                mouseY = -1;
                hoveredTooltip = null;
                hoverSnapX = -1;
                hoverDots.clear();
                repaint();
            }
        });
    }

    public void setModel(ChartModel model)
    {
        this.model         = model;
        this.noDataMessage = null;
        repaint();
    }

    public void setNoDataMessage(String msg)
    {
        this.model         = null;
        this.noDataMessage = msg;
        repaint();
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(BG);
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (noDataMessage != null)
            {
                drawCentered(g2, noDataMessage, AXIS_LABEL);
                return;
            }
            if (model == null
                || (model.series.isEmpty() && model.markers.isEmpty()))
            {
                drawCentered(g2, "No data", AXIS_LABEL);
                return;
            }

            int left   = MARGIN_LEFT;
            int right  = getWidth()  - MARGIN_RIGHT;
            int top    = MARGIN_TOP;
            int bottom = getHeight() - MARGIN_BOTTOM;
            if (right <= left || bottom <= top) return;

            double yMin = model.yMin, yMax = model.yMax;
            if (yMin == 0 && yMax == 0)
            {
                List<Double> all = new ArrayList<>();
                for (ChartModel.Series s : model.series)
                    for (double v : s.values) if (!s.skipZeroValues || v > 0) all.add(v);
                for (ChartModel.Marker m : model.markers) all.add(m.value);
                double[] b = ChartScale.autoScaleBounds(
                    all.stream().mapToDouble(Double::doubleValue).toArray(), 0.08);
                yMin = b[0]; yMax = b[1];
            }
            paintYMin = yMin; paintYMax = yMax;

            drawGrid(g2, left, right, top, bottom, yMin, yMax);
            if (model.volumeTimestamps != null && model.volumeTimestamps.length > 0)
                drawVolume(g2, left, right, top, bottom);
            for (ChartModel.Series s : model.series)
                drawSeries(g2, s, left, right, top, bottom, yMin, yMax);
            drawConnections(g2, left, right, top, bottom, yMin, yMax);
            for (ChartModel.Marker m : model.markers)
                drawMarker(g2, m, left, right, top, bottom, yMin, yMax);

            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom)
            {
                int vx = hoverSnapX >= 0 ? hoverSnapX : mouseX;
                g2.setColor(CROSSHAIR);
                float[] dash = {4f, 4f};
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 1f, dash, 0f));
                g2.drawLine(vx,   top,  vx,   bottom);
                g2.drawLine(left, mouseY, right, mouseY);
                g2.setStroke(new BasicStroke(1f));

                // Highlight the sampled point(s) the readout refers to.
                for (int[] d : hoverDots)
                {
                    g2.setColor(new Color(d[2]));
                    g2.fillOval(d[0] - 3, d[1] - 3, 6, 6);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(d[0] - 3, d[1] - 3, 6, 6);
                }

                if (hoveredTooltip != null) drawTooltip(g2, hoveredTooltip, mouseX, mouseY);
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    private void drawGrid(Graphics2D g2, int left, int right, int top, int bottom,
                          double yMin, double yMax)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        FontMetrics fm = g2.getFontMetrics();

        for (int i = 0; i <= 4; i++)
        {
            double val = yMin + (yMax - yMin) * i / 4.0;
            int y = ChartScale.valueToPixelY(val, yMin, yMax, top, bottom);
            g2.setColor(GRID);
            g2.drawLine(left, y, right, y);
            g2.setColor(AXIS_LABEL);
            String lbl = PriceFormat.format((long) val);
            g2.drawString(lbl, 2, y + fm.getAscent() / 2);
        }

        long xRange = model.xMax - model.xMin;
        DateTimeFormatter timeFmt = xRange <= 86400 ? HOUR_FMT : DATE_FMT;
        for (int i = 0; i <= 4; i++)
        {
            long ts = model.xMin + xRange * i / 4;
            int  x  = ChartScale.timeToPixelX(ts, model.xMin, model.xMax, left, right);
            g2.setColor(GRID);
            g2.drawLine(x, top, x, bottom);
            g2.setColor(AXIS_LABEL);
            String lbl = timeFmt.format(Instant.ofEpochSecond(ts));
            int lw = fm.stringWidth(lbl);
            g2.drawString(lbl, Math.max(left, Math.min(right - lw, x - lw / 2)), bottom + 14);
        }
    }

    private void drawSeries(Graphics2D g2, ChartModel.Series s,
                             int left, int right, int top, int bottom,
                             double yMin, double yMax)
    {
        if (s.timestamps == null || s.timestamps.length < 2) return;
        g2.setColor(s.color);
        g2.setStroke(new BasicStroke(s.strokeWidth));
        int px = -1, py = -1;
        for (int i = 0; i < s.timestamps.length; i++)
        {
            if (s.skipZeroValues && s.values[i] == 0) { px = -1; continue; }
            int x = ChartScale.timeToPixelX(s.timestamps[i], model.xMin, model.xMax, left, right);
            int y = ChartScale.valueToPixelY(s.values[i], yMin, yMax, top, bottom);
            if (px >= 0) g2.drawLine(px, py, x, y);
            px = x; py = y;
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawMarker(Graphics2D g2, ChartModel.Marker m,
                             int left, int right, int top, int bottom,
                             double yMin, double yMax)
    {
        int x = ChartScale.timeToPixelX(m.timestamp, model.xMin, model.xMax, left, right);
        int y = ChartScale.valueToPixelY(m.value,     yMin, yMax, top, bottom);
        int h = MARKER_HALF;
        int[] xs, ys;
        if (m.isUp)
        {
            xs = new int[]{x,      x - h, x + h};
            ys = new int[]{y - h,  y + h, y + h};
        }
        else
        {
            xs = new int[]{x,      x - h, x + h};
            ys = new int[]{y + h,  y - h, y - h};
        }
        g2.setColor(m.color);
        g2.fillPolygon(xs, ys, 3);
        g2.setColor(m.color.darker());
        g2.drawPolygon(xs, ys, 3);
    }

    private void drawConnections(Graphics2D g2, int left, int right, int top, int bottom,
                                  double yMin, double yMax)
    {
        if (model.connections.isEmpty() || model.markers.isEmpty()) return;
        g2.setColor(new Color(255, 255, 255, 35));
        float[] dash = {3f, 3f};
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 1f, dash, 0f));
        for (ChartModel.Connection c : model.connections)
        {
            if (c.buyMarkerIndex >= model.markers.size()
                || c.sellMarkerIndex >= model.markers.size()) continue;
            ChartModel.Marker buy  = model.markers.get(c.buyMarkerIndex);
            ChartModel.Marker sell = model.markers.get(c.sellMarkerIndex);
            int x1 = ChartScale.timeToPixelX(buy.timestamp,  model.xMin, model.xMax, left, right);
            int y1 = ChartScale.valueToPixelY(buy.value,  yMin, yMax, top, bottom);
            int x2 = ChartScale.timeToPixelX(sell.timestamp, model.xMin, model.xMax, left, right);
            int y2 = ChartScale.valueToPixelY(sell.value, yMin, yMax, top, bottom);
            g2.drawLine(x1, y1, x2, y2);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawVolume(Graphics2D g2, int left, int right, int top, int bottom)
    {
        int volBottom = bottom;
        int volTop    = bottom - (int) ((bottom - top) * 0.15);
        double maxVol = 0;
        for (double v : model.highVolumes) if (v > maxVol) maxVol = v;
        for (double v : model.lowVolumes)  if (v > maxVol) maxVol = v;
        if (maxVol == 0) return;
        int n = model.volumeTimestamps.length;
        int barW = Math.max(1, (right - left) / n - 1);
        for (int i = 0; i < n; i++)
        {
            int x = ChartScale.timeToPixelX(model.volumeTimestamps[i], model.xMin, model.xMax, left, right);
            if (model.highVolumes[i] > 0)
            {
                int h = (int) (model.highVolumes[i] / maxVol * (volBottom - volTop));
                g2.setColor(VOL_HIGH);
                g2.fillRect(x - barW / 2, volBottom - h, barW, h);
            }
            if (model.lowVolumes[i] > 0)
            {
                int h = (int) (model.lowVolumes[i] / maxVol * (volBottom - volTop));
                g2.setColor(VOL_LOW);
                g2.fillRect(x - barW / 2, volBottom - h, barW / 2, h);
            }
        }
    }

    private void updateHover()
    {
        hoveredTooltip = null;
        hoverSnapX = -1;
        hoverDots.clear();
        if (model == null || mouseX < 0) return;
        int left   = MARGIN_LEFT;
        int right  = getWidth()  - MARGIN_RIGHT;
        int top    = MARGIN_TOP;
        int bottom = getHeight() - MARGIN_BOTTOM;

        // Markers take priority — they carry richer per-trade tooltips.
        for (ChartModel.Marker m : model.markers)
        {
            int x = ChartScale.timeToPixelX(m.timestamp, model.xMin, model.xMax, left, right);
            int y = ChartScale.valueToPixelY(m.value, paintYMin, paintYMax, top, bottom);
            if (Math.abs(mouseX - x) <= 9 && Math.abs(mouseY - y) <= 9)
            {
                hoveredTooltip = m.tooltip;
                return;
            }
        }

        // Otherwise, read the nearest sample on each line series at the cursor's x.
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) return;
        if (model.series.isEmpty()) return;

        boolean       multi   = model.series.size() > 1;
        long          hoverTs = -1;
        int           bestDx  = Integer.MAX_VALUE;
        StringBuilder sb      = new StringBuilder();

        for (ChartModel.Series s : model.series)
        {
            if (s.timestamps == null || s.values == null || s.timestamps.length == 0) continue;

            int idx = -1, idxDx = Integer.MAX_VALUE;
            for (int i = 0; i < s.timestamps.length; i++)
            {
                if (s.skipZeroValues && s.values[i] == 0) continue;
                int x  = ChartScale.timeToPixelX(s.timestamps[i], model.xMin, model.xMax, left, right);
                int dx = Math.abs(mouseX - x);
                if (dx < idxDx) { idxDx = dx; idx = i; }
            }
            if (idx < 0) continue;

            int px = ChartScale.timeToPixelX(s.timestamps[idx], model.xMin, model.xMax, left, right);
            int py = ChartScale.valueToPixelY(s.values[idx], paintYMin, paintYMax, top, bottom);
            hoverDots.add(new int[]{px, py, (s.color != null ? s.color : Color.WHITE).getRGB()});

            if (idxDx < bestDx) { bestDx = idxDx; hoverSnapX = px; hoverTs = s.timestamps[idx]; }

            if (sb.length() > 0) sb.append('\n');
            String val = PriceFormat.formatExact((long) s.values[idx]);
            sb.append(multi && s.label != null ? s.label + ": " + val : val);
        }

        if (sb.length() == 0) return;
        hoveredTooltip = (hoverTs >= 0 ? formatTimestamp(hoverTs) + "\n" : "") + sb;
    }

    private String formatTimestamp(long ts)
    {
        long range = model.xMax - model.xMin;
        DateTimeFormatter fmt = range <= 86400 ? HOUR_FMT : HOVER_DATETIME_FMT;
        return fmt.format(Instant.ofEpochSecond(ts));
    }

    private void drawTooltip(Graphics2D g2, String text, int x, int y)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        String[] lines = text.split("\n");
        int textW = 0;
        for (String ln : lines) textW = Math.max(textW, fm.stringWidth(ln));
        int lineH = fm.getHeight();
        int w = textW + 10;
        int h = lineH * lines.length + 6;
        int tx = Math.min(x + 12, getWidth()  - w - 4);
        int ty = Math.max(y - h - 4, 2);
        g2.setColor(TOOLTIP_BG);
        g2.fillRoundRect(tx, ty, w, h, 5, 5);
        g2.setColor(Color.WHITE);
        int by = ty + fm.getAscent() + 3;
        for (String ln : lines)
        {
            g2.drawString(ln, tx + 5, by);
            by += lineH;
        }
    }

    private void drawCentered(Graphics2D g2, String text, Color color)
    {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text,
            (getWidth()  - fm.stringWidth(text)) / 2,
            (getHeight() + fm.getAscent()) / 2);
    }
}
