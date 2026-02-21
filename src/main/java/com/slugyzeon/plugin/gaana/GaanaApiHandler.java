package com.slugyzeon.plugin.gaana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class GaanaApiHandler {

    private static final Logger log = LoggerFactory.getLogger(GaanaApiHandler.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public GaanaApiHandler(SlugYZeonConfig.GaanaConfig config) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.objectMapper = new ObjectMapper();
        String url = config.getApiUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public JsonNode searchSongs(String query, int limit) throws IOException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return getJson("/api/search/songs?q=" + encoded + "&limit=" + limit);
    }

    public JsonNode getSong(String seokey) throws IOException {
        return getJson("/api/songs/" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
    }

    public JsonNode getAlbum(String seokey) throws IOException {
        return getJson("/api/albums/" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
    }

    public JsonNode getPlaylist(String seokey) throws IOException {
        return getJson("/api/playlists/" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
    }

    public JsonNode getArtist(String seokey) throws IOException {
        return getJson("/api/artists/" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
    }

    public JsonNode getStream(String trackId, String quality) throws IOException {
        String encodedId = URLEncoder.encode(trackId, StandardCharsets.UTF_8);
        String encodedQuality = URLEncoder.encode(quality, StandardCharsets.UTF_8);
        return getJson("/api/stream/" + encodedId + "?quality=" + encodedQuality);
    }

    private JsonNode getJson(String path) throws IOException {
        String finalPath = path;
        if (baseUrl.endsWith("/api") && finalPath.startsWith("/api")) {
            finalPath = finalPath.substring(4);
        }

        String url = baseUrl + finalPath;
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://gaana.com/")
                .header("Origin", "https://gaana.com")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("[Gaana] Request failed: {} {}", response.code(), url);
                return null;
            }

            String bodyStr = response.body().string();
            JsonNode json = objectMapper.readTree(bodyStr);

            if (json.has("success")) {
                if (!json.get("success").asBoolean(false))
                    return null;
                return json.has("data") ? json.get("data") : json;
            }
            return json;
        } catch (Exception e) {
            log.debug("[Gaana] Error fetching {}: {}", url, e.getMessage());
            return null;
        }
    }
}
