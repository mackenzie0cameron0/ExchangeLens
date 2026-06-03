package com.exchangelens;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.OfferSnapshot;
import com.exchangelens.tracker.FlipTrackerService;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exercises the pure money math in {@link FlipTrackerService#computeOutcome}, which has no
 * RuneLite dependencies and so is testable without a mock framework.
 */
public class FlipTrackerOutcomeTest
{
    private static OfferSnapshot buy(int totalSpent, int qty, int postedPrice, long postedAt, long completedAt)
    {
        return OfferSnapshot.builder()
            .isBuy(true).quantity(qty).postedPrice(postedPrice)
            .totalSpent(totalSpent).postedAt(postedAt).completedAt(completedAt)
            .build();
    }

    private static OfferSnapshot sell(int totalSpent, int qty, int postedPrice, long postedAt, long completedAt)
    {
        return OfferSnapshot.builder()
            .isBuy(false).quantity(qty).postedPrice(postedPrice)
            .totalSpent(totalSpent).postedAt(postedAt).completedAt(completedAt)
            .build();
    }

    private static FlipRecord recWithBuy(OfferSnapshot b, int quantity)
    {
        return FlipRecord.builder().id("t").itemId(4151).quantity(quantity).buyOffer(b).build();
    }

    @Test
    public void singleItemProfitTaxAndFillTimes()
    {
        OfferSnapshot b = buy(2_000_000, 1, 2_000_000, 100, 220);   // buyFill = 120
        OfferSnapshot s = sell(2_100_000, 1, 2_100_000, 300, 600);  // sellFill = 300
        FlipRecord out = FlipTrackerService.computeOutcome(recWithBuy(b, 1), s, 1);

        assertEquals(2_000_000, out.getBuyPrice());
        assertEquals(2_100_000, out.getSellPrice());
        assertEquals(42_000, out.getTax());                 // floor(2_100_000 * 0.02)
        assertEquals(58_000, out.getNetProfitPerItem());    // 2_100_000 - 2_000_000 - 42_000
        assertEquals(58_000, out.getTotalNetProfit());
        assertEquals(0.029, out.getRoi(), 0.0005);
        assertEquals(120, out.getBuyFillSeconds());
        assertEquals(300, out.getSellFillSeconds());
        assertEquals(420, out.getTotalFlipSeconds());
        assertEquals(600, out.getCompletedAt());
    }

    @Test
    public void multiQuantityScalesTotals()
    {
        // 100 @ buy 150 each = 15_000 spent; sell 200 each = 20_000 received
        OfferSnapshot b = buy(15_000, 100, 150, 0, 10);
        OfferSnapshot s = sell(20_000, 100, 200, 20, 60);
        FlipRecord out = FlipTrackerService.computeOutcome(recWithBuy(b, 100), s, 100);

        int perItemTax = (int) Math.floor(200 * 0.02);      // 4
        assertEquals(150, out.getBuyPrice());
        assertEquals(200, out.getSellPrice());
        assertEquals(perItemTax * 100, out.getTax());       // 400
        assertEquals(200 - 150 - perItemTax, out.getNetProfitPerItem()); // 46
        assertEquals(46 * 100, out.getTotalNetProfit());    // 4600
    }

    @Test
    public void lossFlipProducesNegativeProfit()
    {
        OfferSnapshot b = buy(1_000, 1, 1_000, 0, 5);
        OfferSnapshot s = sell(800, 1, 800, 10, 30);
        FlipRecord out = FlipTrackerService.computeOutcome(recWithBuy(b, 1), s, 1);
        assertTrue("expected negative profit, got " + out.getTotalNetProfit(),
            out.getTotalNetProfit() < 0);
    }

    @Test
    public void loginSyntheticBuyHasZeroFillNotNegative()
    {
        // postedAt == completedAt (login-with-active-offers synthesis)
        OfferSnapshot b = buy(5_000, 1, 5_000, 500, 500);
        OfferSnapshot s = sell(5_500, 1, 5_500, 600, 650);
        FlipRecord out = FlipTrackerService.computeOutcome(recWithBuy(b, 1), s, 1);
        assertEquals(0, out.getBuyFillSeconds());
        assertEquals(50, out.getSellFillSeconds());
    }
}
