package com.exchangelens.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import javax.inject.Inject;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

@Slf4j
public class WikiPriceClient
{
    private static final String BASE_URL = "https://prices.runescape.wiki/api/v1/osrs/";
    private static final String USER_AGENT = "ExchangeLens/0.1 RuneLitePlugin contact:mackenzie0cameron0@gmail.com";

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Inject
    public WikiPriceClient(OkHttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public WikiApiModels.LatestResponse fetchLatest()
    {
        return fetch(BASE_URL + "latest", WikiApiModels.LatestResponse.class);
    }

    public WikiApiModels.AverageResponse fetchFiveMinute()
    {
        return fetch(BASE_URL + "5m", WikiApiModels.AverageResponse.class);
    }

    public WikiApiModels.AverageResponse fetchOneHour()
    {
        return fetch(BASE_URL + "1h", WikiApiModels.AverageResponse.class);
    }

    public List<WikiApiModels.MappingEntry> fetchMapping()
    {
        String body = fetchRaw(BASE_URL + "mapping");
        if (body == null) return Collections.emptyList();
        Type listType = new TypeToken<List<WikiApiModels.MappingEntry>>(){}.getType();
        try
        {
            return gson.fromJson(body, listType);
        }
        catch (Exception e)
        {
            log.warn("Failed to parse mapping response", e);
            return Collections.emptyList();
        }
    }

    private <T> T fetch(String url, Class<T> type)
    {
        String body = fetchRaw(url);
        if (body == null) return null;
        try
        {
            return gson.fromJson(body, type);
        }
        catch (Exception e)
        {
            log.warn("Failed to parse response from {}", url, e);
            return null;
        }
    }

    private String fetchRaw(String url)
    {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build();
        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.warn("API request failed: {} {}", response.code(), url);
                return null;
            }
            return response.body().string();
        }
        catch (IOException e)
        {
            log.warn("API request IOException for {}", url, e);
            return null;
        }
    }
}
