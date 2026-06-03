package com.exchangelens.tracker;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.MarketSnapshot;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Persists {@link FlipRecord}s to disk as one JSON array per account per month,
 * rotated monthly to keep files bounded. Files live under
 * {@code ~/.runelite/exchangelens/flips-{accountName}-{yyyy-MM}.json}.
 *
 * <p>This is a passive I/O object: it does no threading of its own. Callers
 * ({@code FlipTrackerService}) invoke {@link #save} from a background executor so
 * disk writes never touch the client thread or EDT.
 *
 * <p>Uses plain {@code new Gson()} (matching {@code StorageService}); all
 * {@code FlipRecord} timestamps are {@code long} epoch seconds, so no java.time
 * adapter is required.
 */
@Slf4j
public class FlipRepository
{
    /**
     * CSV column order. MUST stay exactly in sync with the Phase 4 training pipeline,
     * which depends on positional columns. Everything from {@code latest_low} onward is
     * the feature vector (captured at buy time); absent fields are written as the literal
     * token {@code null} so pandas {@code read_csv}/{@code dropna} treats them as NaN.
     */
    static final String CSV_HEADER =
        "flip_id,item_id,item_name,quantity,buy_price,sell_price,tax,net_profit,roi,"
        + "buy_fill_seconds,sell_fill_seconds,suggestion_source,"
        + "latest_low,latest_high,spread,net_margin,liquidity_score,velocity_score,"
        + "stability_score,final_score,five_min_volume,one_hour_volume,"
        + "five_min_avg_low,five_min_avg_high,one_hour_avg_low,one_hour_avg_high,"
        + "captured_at,completed_at";

    private final File baseDir;
    private final Gson gson = new Gson();

    @Inject
    public FlipRepository()
    {
        this(defaultDir());
    }

    /** Test/override hook: point the repository at an arbitrary directory. */
    public FlipRepository(File baseDir)
    {
        this.baseDir = baseDir;
    }

    private static File defaultDir()
    {
        return new File(System.getProperty("user.home")
            + File.separator + ".runelite" + File.separator + "exchangelens");
    }

    /**
     * Appends a flip to the current month's file. Synchronized because it is a
     * load-append-write cycle; the single-threaded io executor already serializes
     * calls, but this guards against any future concurrent caller.
     */
    public synchronized void save(FlipRecord flip)
    {
        baseDir.mkdirs();
        File file = fileFor(flip.getAccountName(), YearMonth.now().toString());
        List<FlipRecord> existing = loadFromFile(file);
        existing.add(flip);
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
        {
            gson.toJson(existing, writer);
        }
        catch (IOException e)
        {
            log.warn("Failed to save flip record to {}", file, e);
        }
    }

    /** All flips for an account across every monthly file, oldest file first. */
    public List<FlipRecord> loadAll(String accountName)
    {
        List<FlipRecord> all = new ArrayList<>();
        String prefix = "flips-" + accountName + "-";
        File[] files = baseDir.listFiles(
            (dir, name) -> name.startsWith(prefix) && name.endsWith(".json"));
        if (files == null) return all;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) all.addAll(loadFromFile(f));
        return all;
    }

    /** Flips whose buy side started at or after {@code since}. */
    public List<FlipRecord> loadSince(String accountName, Instant since)
    {
        long s = since.getEpochSecond();
        return loadAll(accountName).stream()
            .filter(f -> f.getStartedAt() >= s)
            .collect(Collectors.toList());
    }

    /** Flips belonging to the current session (started at or after sessionStart). */
    public List<FlipRecord> loadCurrentSession(String accountName, Instant sessionStart)
    {
        return loadSince(accountName, sessionStart);
    }

    /**
     * Writes every flip for an account to a flat CSV at
     * {@code flips-{accountName}-export.csv}. Returns the file, or null on failure.
     */
    public File exportCsv(String accountName)
    {
        baseDir.mkdirs();
        File out = new File(baseDir, "flips-" + accountName + "-export.csv");
        List<FlipRecord> flips = loadAll(accountName);
        try (Writer writer = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8))
        {
            writer.write(CSV_HEADER);
            writer.write("\n");
            for (FlipRecord f : flips)
            {
                writer.write(toCsvRow(f));
                writer.write("\n");
            }
        }
        catch (IOException e)
        {
            log.warn("Failed to export CSV to {}", out, e);
            return null;
        }
        return out;
    }

    // --- internals ------------------------------------------------------------

    private File fileFor(String accountName, String month)
    {
        return new File(baseDir, "flips-" + accountName + "-" + month + ".json");
    }

    private List<FlipRecord> loadFromFile(File file)
    {
        if (!file.exists()) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<List<FlipRecord>>(){}.getType();
            List<FlipRecord> list = gson.fromJson(reader, type);
            return list != null ? list : new ArrayList<>();
        }
        catch (Exception e)
        {
            log.warn("Failed to read flip records from {}", file, e);
            return new ArrayList<>();
        }
    }

    String toCsvRow(FlipRecord f)
    {
        MarketSnapshot ms = f.getBuyOffer() != null ? f.getBuyOffer().getMarketSnapshot() : null;
        List<String> cols = new ArrayList<>();
        cols.add(csvStr(f.getId()));
        cols.add(String.valueOf(f.getItemId()));
        cols.add(csvStr(f.getItemName()));
        cols.add(String.valueOf(f.getQuantity()));
        cols.add(String.valueOf(f.getBuyPrice()));
        cols.add(String.valueOf(f.getSellPrice()));
        cols.add(String.valueOf(f.getTax()));
        cols.add(String.valueOf(f.getTotalNetProfit()));
        cols.add(String.valueOf(f.getRoi()));
        cols.add(String.valueOf(f.getBuyFillSeconds()));
        cols.add(String.valueOf(f.getSellFillSeconds()));
        cols.add(csvStr(f.getSuggestionSource()));
        // Feature vector (captured at buy time) — literal "null" token when absent.
        cols.add(num(ms == null ? null : ms.getLatestLow()));
        cols.add(num(ms == null ? null : ms.getLatestHigh()));
        cols.add(num(ms == null ? null : ms.getSpread()));
        cols.add(num(ms == null ? null : ms.getNetMargin()));
        cols.add(num(ms == null ? null : ms.getLiquidityScore()));
        cols.add(num(ms == null ? null : ms.getVelocityScore()));
        cols.add(num(ms == null ? null : ms.getStabilityScore()));
        cols.add(num(ms == null ? null : ms.getFinalScore()));
        cols.add(num(ms == null ? null : ms.getFiveMinVolume()));
        cols.add(num(ms == null ? null : ms.getOneHourVolume()));
        cols.add(num(ms == null ? null : ms.getFiveMinAvgLow()));
        cols.add(num(ms == null ? null : ms.getFiveMinAvgHigh()));
        cols.add(num(ms == null ? null : ms.getOneHourAvgLow()));
        cols.add(num(ms == null ? null : ms.getOneHourAvgHigh()));
        cols.add(ms == null ? "null" : String.valueOf(ms.getCapturedAt()));
        cols.add(String.valueOf(f.getCompletedAt()));
        return String.join(",", cols);
    }

    private static String num(Number n)
    {
        return n == null ? "null" : String.valueOf(n);
    }

    private static String csvStr(String s)
    {
        if (s == null) return "null";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
        {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
