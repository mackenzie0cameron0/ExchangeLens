// src/main/java/com/exchangelens/service/MarketDataService.java
package com.exchangelens.service;

import com.exchangelens.ExchangeLensConfig;
import com.exchangelens.api.WikiApiModels;
import com.exchangelens.api.WikiPriceClient;
import com.exchangelens.model.*;
import lombok.extern.slf4j.Slf4j;
import javax.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class MarketDataService
{
    private final WikiPriceClient client;
    private final StorageService storage;
    private final ExchangeLensConfig config;

    private ScheduledExecutorService scheduler;
    private Consumer<List<FlipRecommendation>> onUpdate;

    private Map<Integer, ItemMapping> mapping = new HashMap<>();
    private Map<Integer, WikiApiModels.LatestPriceData> latestData = new HashMap<>();
    private Map<Integer, WikiApiModels.AveragePriceData> fiveMinData = new HashMap<>();
    private Map<Integer, WikiApiModels.AveragePriceData> oneHourData = new HashMap<>();
    private Instant lastSuccessfulFetch;
    private int cycleCount = 0;

    @Inject
    public MarketDataService(WikiPriceClient client, StorageService storage, ExchangeLensConfig config)
    {
        this.client  = client;
        this.storage = storage;
        this.config  = config;
    }

    public void start(Consumer<List<FlipRecommendation>> onUpdate)
    {
        this.onUpdate = onUpdate;
        scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "exchange-lens-refresh"); t.setDaemon(true); return t; });

        loadMapping();
        scheduler.scheduleAtFixedRate(this::refresh, 0,
            config.refreshIntervalSeconds(), TimeUnit.SECONDS);
    }

    public void stop()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isStale()
    {
        if (lastSuccessfulFetch == null) return true;
        long thresholdMs = config.refreshIntervalSeconds() * 2 * 1000L;
        return Instant.now().toEpochMilli() - lastSuccessfulFetch.toEpochMilli() > thresholdMs;
    }

    public Instant getLastSuccessfulFetch()
    {
        return lastSuccessfulFetch;
    }

    private void refresh()
    {
        try
        {
            WikiApiModels.LatestResponse latestResp = client.fetchLatest();
            if (latestResp != null && latestResp.data != null)
            {
                latestData = new HashMap<>();
                for (Map.Entry<String, WikiApiModels.LatestPriceData> e : latestResp.data.entrySet())
                {
                    try { latestData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                    catch (NumberFormatException ignored) {}
                }
                lastSuccessfulFetch = Instant.now();
            }

            if (cycleCount % 3 == 0)
            {
                WikiApiModels.AverageResponse fiveResp = client.fetchFiveMinute();
                if (fiveResp != null && fiveResp.data != null)
                {
                    fiveMinData = new HashMap<>();
                    for (Map.Entry<String, WikiApiModels.AveragePriceData> e : fiveResp.data.entrySet())
                    {
                        try { fiveMinData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            if (cycleCount % 10 == 0)
            {
                WikiApiModels.AverageResponse hourResp = client.fetchOneHour();
                if (hourResp != null && hourResp.data != null)
                {
                    oneHourData = new HashMap<>();
                    for (Map.Entry<String, WikiApiModels.AveragePriceData> e : hourResp.data.entrySet())
                    {
                        try { oneHourData.put(Integer.parseInt(e.getKey()), e.getValue()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            cycleCount++;
            notifyUpdate();
        }
        catch (Exception e)
        {
            log.warn("Refresh cycle failed", e);
        }
    }

    private void loadMapping()
    {
        Map<Integer, ItemMapping> cached = storage.loadMappingCache();
        if (cached != null)
        {
            mapping = cached;
            log.debug("Loaded {} items from mapping cache", mapping.size());
            return;
        }
        List<WikiApiModels.MappingEntry> entries = client.fetchMapping();
        if (entries.isEmpty())
        {
            log.warn("Mapping fetch returned empty list");
            return;
        }
        mapping = new HashMap<>();
        for (WikiApiModels.MappingEntry e : entries)
        {
            mapping.put(e.id, ItemMapping.builder()
                .id(e.id).name(e.name).members(e.members).limit(e.limit)
                .lowAlch(e.lowalch).highAlch(e.highalch)
                .examine(e.examine)
                .build());
        }
        storage.saveMappingCache(mapping);
        log.debug("Fetched and cached {} items from /mapping", mapping.size());
    }

    private void notifyUpdate()
    {
        if (onUpdate == null || latestData.isEmpty()) return;
        List<MarketItem> items = mergeItems();
        Set<Integer> blocklist = storage.loadBlocklist();
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            items,
            config.minimumNetMargin(),
            config.minimumRoi() / 100.0,
            config.minimumHourlyVolume(),
            config.maximumCapitalPerFlip(),
            config.hideHighRiskItems(),
            config.includeMembersItems(),
            blocklist
        );
        onUpdate.accept(recs);
    }

    private List<MarketItem> mergeItems()
    {
        List<MarketItem> result = new ArrayList<>();
        for (Map.Entry<Integer, WikiApiModels.LatestPriceData> e : latestData.entrySet())
        {
            int id = e.getKey();
            ItemMapping meta = mapping.get(id);
            if (meta == null) continue;

            WikiApiModels.LatestPriceData latest    = e.getValue();
            WikiApiModels.AveragePriceData fiveMin  = fiveMinData.get(id);
            WikiApiModels.AveragePriceData oneHour  = oneHourData.get(id);

            MarketItem.MarketItemBuilder b = MarketItem.builder()
                .itemId(id).name(meta.getName()).members(meta.isMembers()).limit(meta.getLimit())
                .latestLow(latest.low).latestHigh(latest.high)
                .latestLowTime(latest.lowTime).latestHighTime(latest.highTime)
                .lastUpdated(lastSuccessfulFetch);

            if (fiveMin != null)
                b.fiveMinLow(fiveMin.avgLowPrice).fiveMinHigh(fiveMin.avgHighPrice)
                 .fiveMinLowVolume(fiveMin.lowPriceVolume).fiveMinHighVolume(fiveMin.highPriceVolume);

            if (oneHour != null)
                b.oneHourLow(oneHour.avgLowPrice).oneHourHigh(oneHour.avgHighPrice)
                 .oneHourLowVolume(oneHour.lowPriceVolume).oneHourHighVolume(oneHour.highPriceVolume);

            result.add(b.build());
        }
        return result;
    }
}
