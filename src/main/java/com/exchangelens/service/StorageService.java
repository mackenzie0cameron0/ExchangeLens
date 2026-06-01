// src/main/java/com/exchangelens/service/StorageService.java
package com.exchangelens.service;

import com.exchangelens.model.ItemMapping;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import javax.inject.Inject;
import java.lang.reflect.Type;
import java.util.*;

@Slf4j
public class StorageService
{
    private static final String GROUP = "exchangelens";
    private static final String KEY_WATCHLIST = "watchlist";
    private static final String KEY_BLOCKLIST = "blocklist";
    private static final String KEY_MAPPING = "mapping";
    private static final String KEY_MAPPING_TS = "mappingTimestamp";
    private static final long MAPPING_TTL_MS = 24 * 60 * 60 * 1000L;

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    @Inject
    public StorageService(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    public Set<Integer> loadWatchlist()
    {
        return loadIdSet(KEY_WATCHLIST);
    }

    public void saveWatchlist(Set<Integer> ids)
    {
        saveIdSet(KEY_WATCHLIST, ids);
    }

    public Set<Integer> loadBlocklist()
    {
        return loadIdSet(KEY_BLOCKLIST);
    }

    public void saveBlocklist(Set<Integer> ids)
    {
        saveIdSet(KEY_BLOCKLIST, ids);
    }

    /** Returns null if cache is absent or stale (older than 24h). */
    public Map<Integer, ItemMapping> loadMappingCache()
    {
        String tsStr = configManager.getConfiguration(GROUP, KEY_MAPPING_TS);
        if (tsStr == null) return null;
        long ts = Long.parseLong(tsStr);
        if (System.currentTimeMillis() - ts > MAPPING_TTL_MS) return null;

        String json = configManager.getConfiguration(GROUP, KEY_MAPPING);
        if (json == null) return null;
        try
        {
            Type type = new TypeToken<Map<Integer, ItemMapping>>(){}.getType();
            return gson.fromJson(json, type);
        }
        catch (Exception e)
        {
            log.warn("Failed to deserialize mapping cache", e);
            return null;
        }
    }

    public void saveMappingCache(Map<Integer, ItemMapping> mapping)
    {
        configManager.setConfiguration(GROUP, KEY_MAPPING, gson.toJson(mapping));
        configManager.setConfiguration(GROUP, KEY_MAPPING_TS, String.valueOf(System.currentTimeMillis()));
    }

    private Set<Integer> loadIdSet(String key)
    {
        String json = configManager.getConfiguration(GROUP, key);
        if (json == null) return new HashSet<>();
        try
        {
            int[] ids = gson.fromJson(json, int[].class);
            Set<Integer> result = new HashSet<>();
            for (int id : ids) result.add(id);
            return result;
        }
        catch (Exception e)
        {
            log.warn("Failed to load {} from config", key, e);
            return new HashSet<>();
        }
    }

    private void saveIdSet(String key, Set<Integer> ids)
    {
        configManager.setConfiguration(GROUP, key, gson.toJson(ids.toArray(new Integer[0])));
    }
}
