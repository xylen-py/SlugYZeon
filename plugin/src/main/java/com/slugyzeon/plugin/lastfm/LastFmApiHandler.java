package com.slugyzeon.plugin.lastfm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LastFmApiHandler {

    private static final Logger log = LoggerFactory.getLogger(LastFmApiHandler.class);
    private static final String API_BASE = "https://ws.audioscrobbler.com/2.0/";
    private static final String SEARCH_URL = "https://www.last.fm/search/tracks";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private static final Pattern HTML_TRACK_PATTERN = Pattern.compile(
            "data-youtube-url=\"([^\"]+)\"[\\s\\S]*?data-track-name=\"([^\"]+)\"[\\s\\S]*?data-track-url=\"([^\"]+)\"[\\s\\S]*?data-artist-name=\"([^\"]+)\""
    );

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int maxResults;

    public LastFmApiHandler(String apiKey, int maxResults) {
        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    public List<Map<String, String>> searchTracksApi(String query) throws IOException {
        String url = API_BASE + "?method=track.search&track=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8) +
                "&limit=" + maxResults +
                "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                "&format=json";

        JsonNode root = getJson(url);
        if (root == null) return Collections.emptyList();

        if (root.has("error")) {
            log.debug("Last.fm API error: {}", root.path("message").asText(""));
            return Collections.emptyList();
        }

        JsonNode tracks = root.path("results").path("trackmatches").path("track");
        if (!tracks.isArray()) return Collections.emptyList();

        List<Map<String, String>> results = new ArrayList<>();
        for (JsonNode t : tracks) {
            String name = t.path("name").asText(null);
            String artist = t.path("artist").asText(null);
            if (name == null || artist == null) continue;

            Map<String, String> trackInfo = new LinkedHashMap<>();
            trackInfo.put("title", name);
            trackInfo.put("author", artist);
            trackInfo.put("uri", t.path("url").asText(""));
            trackInfo.put("artworkUrl", extractImage(t.path("image")));
            results.add(trackInfo);
        }
        return results;
    }

    public List<Map<String, String>> searchTracksHtml(String query) throws IOException {
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return Collections.emptyList();
            String body = response.body().string();
            return parseHtmlSearch(body);
        }
    }

    public Map<String, String> parseUrlPath(String urlStr) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            String path = url.getPath();
            String[] segments = path.split("/");

            List<String> parts = new ArrayList<>();
            for (String s : segments) {
                if (!s.isEmpty()) parts.add(s);
            }

            if (parts.size() > 1 && parts.get(0).length() == 2 && parts.get(1).equals("music")) {
                parts.remove(0);
            }

            if (parts.isEmpty() || !parts.get(0).equals("music") || parts.size() < 2) return null;

            String artist = decodeHtml(parts.get(1).replace("+", " "));

            String trackTitle = "Unknown";
            boolean isTrack = false;

            if (parts.size() >= 4 && parts.get(2).equals("_")) {
                trackTitle = decodeHtml(parts.get(3).replace("+", " "));
                isTrack = true;
            } else if (parts.size() >= 3 && !parts.get(2).equals("_")) {
                trackTitle = decodeHtml(parts.get(2).replace("+", " "));
            }

            if (parts.size() >= 4) isTrack = true;

            Map<String, String> result = new LinkedHashMap<>();
            result.put("artist", artist);
            result.put("title", trackTitle);
            result.put("isTrack", String.valueOf(isTrack));
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse Last.fm URL: {}", e.getMessage());
            return null;
        }
    }

    public String fetchPageBody(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return response.body().string();
        }
    }

    public List<String> extractYouTubeUrls(String html) {
        Set<String> urls = new LinkedHashSet<>();

        Pattern playLinkPattern = Pattern.compile("header-new-playlink[^>]*href=\"([^\"]*youtube\\.com[^\"]+)\"");
        Matcher playMatcher = playLinkPattern.matcher(html);
        if (playMatcher.find()) {
            urls.add(decodeHtml(playMatcher.group(1)));
        }

        Pattern ytPattern = Pattern.compile("https?://(?:www\\.)?youtube\\.com/watch\\?v=[a-zA-Z0-9_-]+");
        Matcher ytMatcher = ytPattern.matcher(html);
        while (ytMatcher.find()) {
            urls.add(ytMatcher.group(0));
        }

        return new ArrayList<>(urls);
    }

    private List<Map<String, String>> parseHtmlSearch(String html) {
        List<Map<String, String>> results = new ArrayList<>();
        Matcher matcher = HTML_TRACK_PATTERN.matcher(html);

        int count = 0;
        while (matcher.find() && count < maxResults) {
            String youtubeUrl = decodeHtml(matcher.group(1));
            String title = decodeHtml(matcher.group(2));
            String trackUrl = decodeHtml(matcher.group(3));
            String artist = decodeHtml(matcher.group(4));

            if (!trackUrl.startsWith("http")) {
                trackUrl = "https://www.last.fm" + trackUrl;
            }

            Map<String, String> trackInfo = new LinkedHashMap<>();
            trackInfo.put("title", title);
            trackInfo.put("author", artist);
            trackInfo.put("uri", trackUrl);
            trackInfo.put("youtubeUrl", youtubeUrl);
            trackInfo.put("artworkUrl", null);
            results.add(trackInfo);
            count++;
        }
        return results;
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return objectMapper.readTree(response.body().string());
        }
    }

    private String extractImage(JsonNode imageArray) {
        if (imageArray == null || !imageArray.isArray() || imageArray.isEmpty()) return null;
        for (int i = imageArray.size() - 1; i >= 0; i--) {
            String url = imageArray.get(i).path("#text").asText(null);
            if (url != null && !url.isEmpty()) return url;
        }
        return null;
    }

    private static String decodeHtml(String text) {
        if (text == null) return null;
        return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
