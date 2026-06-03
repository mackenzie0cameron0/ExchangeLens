package com.exchangelens.service;

import com.exchangelens.api.WikiApiModels;
import com.exchangelens.api.WikiPriceClient;
import com.exchangelens.model.FlipRecord;
import com.exchangelens.tracker.FlipRepository;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fetches and caches Wiki /timeseries data and FlipRecord history for the
 * pop-out FlipHistoryWindow. All I/O runs off the EDT; callbacks are delivered
 * on the EDT via SwingUtilities.invokeLater.
 */
@Slf4j
@Singleton
public class HistoryDataService
{
    private final WikiPriceClient  wikiPriceClient;
    private final FlipRepository   flipRepository;
    private final ExecutorService  executor;
    private final ConcurrentHashMap<String, CachedTimeseries> timeseriesCache
        = new ConcurrentHashMap<>();

    // In-memory flip cache — cleared when a different account is requested
    private volatile List<FlipRecord> cachedFlips;
    private volatile String           cachedAccount;

    @Inject
    public HistoryDataService(WikiPriceClient wikiPriceClient, FlipRepository flipRepository)
    {
        this.wikiPriceClient = wikiPriceClient;
        this.flipRepository  = flipRepository;
        this.executor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "exchange-lens-history-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns cached timeseries if fresh; otherwise fetches on background thread.
     * Callback is always delivered on the EDT. data may be null on network failure.
     * <p>
     * Cache key is "{itemId}:{timestep}". TTL equals the timestep interval so that
     * 6h and 1D buttons (both use "5m") share a single cached fetch.
     */
    public void getTimeseries(int itemId, String timestep,
                              Consumer<WikiApiModels.TimeseriesResponse> callback)
    {
        String key    = itemId + ":" + timestep;
        CachedTimeseries cached = timeseriesCache.get(key);
        if (cached != null && !isExpired(cached, timestep))
        {
            SwingUtilities.invokeLater(() -> callback.accept(cached.data));
            return;
        }
        executor.submit(() ->
        {
            WikiApiModels.TimeseriesResponse data = null;
            try
            {
                data = wikiPriceClient.fetchTimeseries(itemId, timestep);
            }
            catch (Exception e)
            {
                log.warn("fetchTimeseries failed for item {} timestep {}", itemId, timestep, e);
            }
            if (data != null) timeseriesCache.put(key, new CachedTimeseries(data));
            final WikiApiModels.TimeseriesResponse result = data;
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /**
     * Loads all flips for the given account. Results are cached; if the same
     * account is requested again the cache is returned without disk I/O.
     * Callback is delivered on the EDT. Returns an empty list if accountName is null.
     */
    public void loadFlips(String accountName, Consumer<List<FlipRecord>> callback)
    {
        if (accountName != null && accountName.equals(cachedAccount) && cachedFlips != null)
        {
            final List<FlipRecord> result = cachedFlips;
            SwingUtilities.invokeLater(() -> callback.accept(result));
            return;
        }
        executor.submit(() ->
        {
            List<FlipRecord> flips = Collections.emptyList();
            if (accountName != null && !accountName.isEmpty())
            {
                try { flips = flipRepository.loadAll(accountName); }
                catch (Exception e) { log.warn("loadAll failed for {}", accountName, e); }
            }
            cachedFlips   = flips;
            cachedAccount = accountName;
            final List<FlipRecord> result = flips;
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /** Clears the flip cache so the next loadFlips call re-reads from disk. */
    public void invalidateFlips()
    {
        cachedFlips   = null;
        cachedAccount = null;
    }

    private boolean isExpired(CachedTimeseries c, String timestep)
    {
        long age = System.currentTimeMillis() / 1000 - c.fetchedAtEpochSec;
        return age > ttlSeconds(timestep);
    }

    private static long ttlSeconds(String timestep)
    {
        switch (timestep)
        {
            case "5m":  return  5 * 60;
            case "1h":  return 60 * 60;
            case "6h":  return  6 * 60 * 60;
            case "24h": return 24 * 60 * 60;
            default:    return  5 * 60;
        }
    }

    private static class CachedTimeseries
    {
        final WikiApiModels.TimeseriesResponse data;
        final long fetchedAtEpochSec;

        CachedTimeseries(WikiApiModels.TimeseriesResponse data)
        {
            this.data             = data;
            this.fetchedAtEpochSec = System.currentTimeMillis() / 1000;
        }
    }
}
