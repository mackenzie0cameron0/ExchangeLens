package com.exchangelens.tracker;

import com.exchangelens.model.FlipRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FlipAnalytics
{
    private FlipAnalytics() {}

    public static long totalProfit(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .mapToLong(FlipRecord::getTotalNetProfit)
            .sum();
    }

    public static long totalTax(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .mapToLong(FlipRecord::getTax)
            .sum();
    }

    public static double winRate(List<FlipRecord> flips)
    {
        List<FlipRecord> complete = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .collect(Collectors.toList());
        if (complete.isEmpty()) return 0.0;
        long profitable = complete.stream()
            .filter(f -> f.getTotalNetProfit() > 0)
            .count();
        return (double) profitable / complete.size() * 100.0;
    }

    public static FlipRecord bestFlip(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .max(Comparator.comparingLong(FlipRecord::getTotalNetProfit))
            .orElse(null);
    }

    public static FlipRecord worstFlip(List<FlipRecord> flips)
    {
        return flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .min(Comparator.comparingLong(FlipRecord::getTotalNetProfit))
            .orElse(null);
    }

    /**
     * Returns [timestamp (epoch s), cumulativeProfit] pairs, sorted ascending by timestamp.
     * Only includes completed flips (sellOffer != null).
     */
    public static long[][] buildCumulativeSeries(List<FlipRecord> flips)
    {
        List<FlipRecord> sorted = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .sorted(Comparator.comparingLong(FlipRecord::getCompletedAt))
            .collect(Collectors.toList());
        long[][] series = new long[sorted.size()][2];
        long running = 0;
        for (int i = 0; i < sorted.size(); i++)
        {
            running += sorted.get(i).getTotalNetProfit();
            series[i][0] = sorted.get(i).getCompletedAt();
            series[i][1] = running;
        }
        return series;
    }

    public static List<ItemSummary> perItemSummary(List<FlipRecord> flips)
    {
        Map<Integer, List<FlipRecord>> byItem = flips.stream()
            .filter(f -> f.getSellOffer() != null)
            .collect(Collectors.groupingBy(FlipRecord::getItemId));

        return byItem.entrySet().stream()
            .map(e ->
            {
                List<FlipRecord> g      = e.getValue();
                long   profit   = g.stream().mapToLong(FlipRecord::getTotalNetProfit).sum();
                double avgRoi   = g.stream().mapToDouble(FlipRecord::getRoi).average().orElse(0.0);
                double avgFill  = g.stream().mapToLong(FlipRecord::getTotalFlipSeconds).average().orElse(0.0);
                return new ItemSummary(e.getKey(), g.get(0).getItemName(),
                    g.size(), profit, avgRoi, avgFill);
            })
            .sorted(Comparator.comparingLong((ItemSummary s) -> s.totalProfit).reversed())
            .collect(Collectors.toList());
    }

    public static class ItemSummary
    {
        public final int    itemId;
        public final String itemName;
        public final int    flipCount;
        public final long   totalProfit;
        public final double avgRoi;
        public final double avgFillSeconds;

        public ItemSummary(int itemId, String itemName, int flipCount,
                           long totalProfit, double avgRoi, double avgFillSeconds)
        {
            this.itemId         = itemId;
            this.itemName       = itemName;
            this.flipCount      = flipCount;
            this.totalProfit    = totalProfit;
            this.avgRoi         = avgRoi;
            this.avgFillSeconds = avgFillSeconds;
        }
    }
}
