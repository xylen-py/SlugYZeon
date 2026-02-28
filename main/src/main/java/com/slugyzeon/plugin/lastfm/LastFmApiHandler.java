package com.slugyzeon.plugin.lastfm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LastFmApiHandler {

    private static final Logger log = LoggerFactory.getLogger(LastFmApiHandler.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public LastFmApiHandler(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    public Map<String, String> parseUrlPath(String urlStr) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            String path = url.getPath();
            String[] segments = path.split("/");

            List<String> parts = new ArrayList<>();
            for (String s : segments) {
                if (!s.isEmpty())
                    parts.add(s);
            }

            if (parts.size() > 1 && parts.get(0).length() == 2 && parts.get(1).equals("music")) {
                parts.remove(0);
            }

            if (parts.isEmpty() || !parts.get(0).equals("music") || parts.size() < 2)
                return null;

            String artist = decodeHtml(parts.get(1).replace("+", " "));

            String trackTitle = "Unknown";
            boolean isTrack = false;

            if (parts.size() >= 4 && parts.get(2).equals("_")) {
                trackTitle = decodeHtml(parts.get(3).replace("+", " "));
                isTrack = true;
            } else if (parts.size() >= 3 && !parts.get(2).equals("_")) {
                trackTitle = decodeHtml(parts.get(2).replace("+", " "));
            }

            if (parts.size() >= 4)
                isTrack = true;

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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
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

    private static String decodeHtml(String text) {
        if (text == null)
            return null;
        return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
