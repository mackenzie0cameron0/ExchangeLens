package com.exchangelens.ui;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PriceFormat;

/**
 * Pure mapping from a {@link FlipRecommendation} to the typed values shown in the
 * Recommendations table, plus static display formatters shared by the cell renderers
 * and tests. Holds raw typed values so the table can sort numerically.
 */
public final class RecommendationRow
{
    public final int       itemId;
    public final String    itemName;
    public final int       margin;       // netMargin, gp
    public final double    roiPercent;   // roi × 100
    public final RiskLevel risk;
    public final double    score;        // finalScore
    public final double    fillMinutes;  // estimatedFillMinutes

    private RecommendationRow(int itemId, String itemName, int margin, double roiPercent,
                             RiskLevel risk, double score, double fillMinutes)
    {
        this.itemId      = itemId;
        this.itemName    = itemName;
        this.margin      = margin;
        this.roiPercent  = roiPercent;
        this.risk        = risk;
        this.score       = score;
        this.fillMinutes = fillMinutes;
    }

    public static RecommendationRow from(FlipRecommendation r)
    {
        return new RecommendationRow(
            r.getItemId(), r.getItemName(), r.getNetMargin(), r.getRoi() * 100.0,
            r.getRiskLevel(), r.getFinalScore(), r.getEstimatedFillMinutes());
    }

    public static String riskLabel(RiskLevel risk)
    {
        if (risk == null) return "—";
        return risk == RiskLevel.MEDIUM ? "MED" : risk.name();
    }

    public static String marginText(int margin)      { return PriceFormat.format(margin); }
    public static String roiText(double roiPercent)  { return String.format("%.2f%%", roiPercent); }
    public static String scoreText(double score)     { return String.format("%.2f", score); }
    public static String fillText(double minutes)    { return PriceFormat.formatFillTime(minutes); }
}
