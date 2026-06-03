package com.exchangelens;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.MarketSnapshot;
import com.exchangelens.model.OfferSnapshot;
import com.exchangelens.tracker.FlipRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.*;

public class FlipRepositoryTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FlipRepository repo;

    @Before
    public void setUp()
    {
        repo = new FlipRepository(tmp.getRoot());
    }

    private static FlipRecord.FlipRecordBuilder baseFlip(String id)
    {
        return FlipRecord.builder()
            .id(id)
            .itemId(4151)
            .itemName("Abyssal whip")
            .quantity(1)
            .accountName("Tester")
            .suggestionSource("exchange-lens")
            .buyPrice(2_000_000)
            .sellPrice(2_100_000)
            .tax(42_000)
            .totalNetProfit(58_000)
            .roi(0.029)
            .buyFillSeconds(120)
            .sellFillSeconds(300)
            .startedAt(1_000_000L)
            .completedAt(1_000_500L);
    }

    private static MarketSnapshot fullSnapshot()
    {
        return MarketSnapshot.builder()
            .itemId(4151)
            .capturedAt(999_999L)
            .latestLow(2_000_000).latestHigh(2_100_000)
            .spread(100_000).netMargin(58_000)
            .liquidityScore(0.8).velocityScore(0.6).stabilityScore(0.9).finalScore(0.75)
            .fiveMinVolume(50).oneHourVolume(600)
            .fiveMinAvgLow(1_999_000).fiveMinAvgHigh(2_101_000)
            .oneHourAvgLow(1_998_000).oneHourAvgHigh(2_102_000)
            .build();
    }

    @Test
    public void saveAndLoadRoundTrip()
    {
        FlipRecord flip = baseFlip("abc")
            .buyOffer(OfferSnapshot.builder().itemId(4151).marketSnapshot(fullSnapshot()).build())
            .build();
        repo.save(flip);

        List<FlipRecord> loaded = repo.loadAll("Tester");
        assertEquals(1, loaded.size());
        FlipRecord r = loaded.get(0);
        assertEquals("abc", r.getId());
        assertEquals(58_000, r.getTotalNetProfit());
        assertEquals("Abyssal whip", r.getItemName());
        assertNotNull(r.getBuyOffer());
        assertNotNull(r.getBuyOffer().getMarketSnapshot());
        assertEquals(Integer.valueOf(2_000_000), r.getBuyOffer().getMarketSnapshot().getLatestLow());
    }

    @Test
    public void saveAppendsToSameMonthFile()
    {
        repo.save(baseFlip("one").build());
        repo.save(baseFlip("two").build());
        assertEquals(2, repo.loadAll("Tester").size());
    }

    @Test
    public void loadAllIsolatesByAccount()
    {
        repo.save(baseFlip("mine").accountName("Tester").build());
        repo.save(baseFlip("theirs").accountName("Other").build());
        assertEquals(1, repo.loadAll("Tester").size());
        assertEquals(1, repo.loadAll("Other").size());
    }

    @Test
    public void loadSinceFiltersByStartedAt()
    {
        repo.save(baseFlip("old").startedAt(100L).build());
        repo.save(baseFlip("new").startedAt(5_000L).build());
        List<FlipRecord> recent = repo.loadSince("Tester", Instant.ofEpochSecond(1_000L));
        assertEquals(1, recent.size());
        assertEquals("new", recent.get(0).getId());
    }

    @Test
    public void csvHeaderMatchesSpecExactly() throws Exception
    {
        repo.save(baseFlip("x")
            .buyOffer(OfferSnapshot.builder().itemId(4151).marketSnapshot(fullSnapshot()).build())
            .build());
        File csv = repo.exportCsv("Tester");
        assertNotNull(csv);
        List<String> lines = Files.readAllLines(csv.toPath());
        assertEquals(
            "flip_id,item_id,item_name,quantity,buy_price,sell_price,tax,net_profit,roi,"
            + "buy_fill_seconds,sell_fill_seconds,suggestion_source,"
            + "latest_low,latest_high,spread,net_margin,liquidity_score,velocity_score,"
            + "stability_score,final_score,five_min_volume,one_hour_volume,"
            + "five_min_avg_low,five_min_avg_high,one_hour_avg_low,one_hour_avg_high,"
            + "captured_at,completed_at",
            lines.get(0));
        // header + 1 data row
        assertEquals(2, lines.size());
        // 28 columns
        assertEquals(28, lines.get(0).split(",", -1).length);
        assertEquals(28, lines.get(1).split(",", -1).length);
    }

    @Test
    public void csvPopulatedSnapshotMapsToCorrectColumns() throws Exception
    {
        // Guards the Phase 4 training contract: feature columns must land in fixed positions.
        repo.save(baseFlip("mapped")
            .buyOffer(OfferSnapshot.builder().itemId(4151).marketSnapshot(fullSnapshot()).build())
            .build());
        File csv = repo.exportCsv("Tester");
        String[] c = Files.readAllLines(csv.toPath()).get(1).split(",", -1);

        assertEquals("mapped", c[0]);          // flip_id
        assertEquals("4151", c[1]);            // item_id
        assertEquals("Abyssal whip", c[2]);    // item_name
        assertEquals("2000000", c[4]);         // buy_price
        assertEquals("2100000", c[5]);         // sell_price
        assertEquals("58000", c[7]);           // net_profit
        assertEquals("exchange-lens", c[11]);  // suggestion_source
        assertEquals("2000000", c[12]);        // latest_low
        assertEquals("2100000", c[13]);        // latest_high
        assertEquals("100000", c[14]);         // spread
        assertEquals("58000", c[15]);          // net_margin
        assertEquals("0.8", c[16]);            // liquidity_score
        assertEquals("0.75", c[19]);           // final_score
        assertEquals("50", c[20]);             // five_min_volume
        assertEquals("600", c[21]);            // one_hour_volume
        assertEquals("999999", c[26]);         // captured_at
        assertEquals("1000500", c[27]);        // completed_at
    }

    @Test
    public void loadCurrentSessionDelegatesToStartedAtFilter()
    {
        repo.save(baseFlip("before").startedAt(100L).build());
        repo.save(baseFlip("after").startedAt(9_000L).build());
        List<FlipRecord> session = repo.loadCurrentSession("Tester", Instant.ofEpochSecond(5_000L));
        assertEquals(1, session.size());
        assertEquals("after", session.get(0).getId());
    }

    @Test
    public void csvWritesNullTokenForAbsentMarketSnapshot() throws Exception
    {
        // No buyOffer at all → every feature column must be the literal "null".
        repo.save(baseFlip("nofeatures").build());
        File csv = repo.exportCsv("Tester");
        List<String> lines = Files.readAllLines(csv.toPath());
        String[] cols = lines.get(1).split(",", -1);
        // latest_low is column index 12 (0-based) — first feature-vector column.
        assertEquals("null", cols[12]);     // latest_low
        assertEquals("null", cols[26]);     // captured_at
        // non-feature columns are still real values
        assertEquals("nofeatures", cols[0]); // flip_id
        assertEquals("58000", cols[7]);      // net_profit
    }
}
