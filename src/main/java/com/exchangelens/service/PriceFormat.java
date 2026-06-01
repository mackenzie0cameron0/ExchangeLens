package com.exchangelens.service;

public final class PriceFormat
{
    private PriceFormat() {}

    public static String format(long gp)
    {
        if (gp >= 1_000_000_000) return String.format("%.1fb", gp / 1_000_000_000.0);
        if (gp >= 1_000_000) return String.format("%.1fm", gp / 1_000_000.0);
        if (gp >= 1_000) return String.format("%.1fk", gp / 1_000.0);
        return String.valueOf(gp);
    }

    public static String formatExact(long gp)
    {
        return String.format("%,d gp", gp);
    }

    public static String formatRoi(double roi)
    {
        return String.format("%.2f%%", roi * 100.0);
    }

    public static String formatFillTime(double minutes)
    {
        if (minutes < 1.0) return "<1 min";
        if (minutes >= 120.0) return ">2h";
        if (minutes >= 60.0) return String.format("%.0fh %.0fm", Math.floor(minutes / 60), minutes % 60);
        return String.format("%.0f min", minutes);
    }
}
