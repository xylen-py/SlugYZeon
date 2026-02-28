package com.slugyzeon.plugin.lastfm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LastFmApiHandler {

    private static final Logger log = LoggerFactory.getLogger(LastFmApiHandler.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    private static final String API_BASE = "https://ws.audioscrobbler.com/2.0/";

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

    public JsonNode searchTracks(String query, int limit) throws IOException {
        String url = API_BASE + "?method=track.search&track=" +
                encode(query) + "&limit=" + limit + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode searchAlbums(String query, int limit) throws IOException {
        String url = API_BASE + "?method=album.search&album=" +
                encode(query) + "&limit=" + limit + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode searchArtists(String query, int limit) throws IOException {
        String url = API_BASE + "?method=artist.search&artist=" +
                encode(query) + "&limit=" + limit + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode getTrackInfo(String artist, String track) throws IOException {
        String url = API_BASE + "?method=track.getInfo&artist=" +
                encode(artist) + "&track=" + encode(track) + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode getAlbumInfo(String artist, String album) throws IOException {
        String url = API_BASE + "?method=album.getInfo&artist=" +
                encode(artist) + "&album=" + encode(album) + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode getArtistTopTracks(String artist, int limit) throws IOException {
        String url = API_BASE + "?method=artist.getTopTracks&artist=" +
                encode(artist) + "&limit=" + limit + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode getSimilarTracks(String artist, String track, int limit) throws IOException {
        String url = API_BASE + "?method=track.getSimilar&artist=" +
                encode(artist) + "&track=" + encode(track) + "&limit=" + limit +
                "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
    }

    public JsonNode getTagTopTracks(String tag, int limit) throws IOException {
        String url = API_BASE + "?method=tag.getTopTracks&tag=" +
                encode(tag) + "&limit=" + limit + "&api_key=" + apiKey + "&format=json";
        return fetchJson(url);
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

            String artist = decodeComponent(parts.get(1));

            Map<String, String> result = new LinkedHashMap<>();
            result.put("artist", artist);

            if (parts.size() >= 4 && parts.get(2).equals("_")) {

                result.put("title", decodeComponent(parts.get(3)));
                result.put("type", "track");
            } else if (parts.size() >= 3 && !parts.get(2).equals("+albums") && !parts.get(2).equals("+tracks")) {

                result.put("title", decodeComponent(parts.get(2)));
                result.put("type", "album");
            } else if (parts.size() >= 3 && parts.get(2).equals("+tracks")) {

                result.put("title", artist + " Top Tracks");
                result.put("type", "artist_tracks");
            } else {

                result.put("title", artist);
                result.put("type", "artist");
            }

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

    public List<Map<String, String>> parseHtmlSearchResults(String html) {
        List<Map<String, String>> results = new ArrayList<>();
        Pattern regex = Pattern.compile(
                "data-youtube-url=\"([^\"]*)\"[\\s\\S]*?data-track-name=\"([^\"]*)\"[\\s\\S]*?data-track-url=\"([^\"]*)\"[\\s\\S]*?data-artist-name=\"([^\"]*)\"");
        Matcher matcher = regex.matcher(html);

        while (matcher.find()) {
            Map<String, String> track = new LinkedHashMap<>();
            track.put("youtubeUrl", decodeHtml(matcher.group(1)));
            track.put("title", decodeHtml(matcher.group(2)));
            String trackUrl = decodeHtml(matcher.group(3));
            if (!trackUrl.startsWith("http")) {
                trackUrl = "https://www.last.fm" + trackUrl;
            }
            track.put("url", trackUrl);
            track.put("artist", decodeHtml(matcher.group(4)));
            results.add(track);
        }

        return results;
    }

    private JsonNode fetchJson(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decodeComponent(String text) {
        if (text == null)
            return null;
        try {
            return java.net.URLDecoder.decode(text.replace("+", " "), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text.replace("+", " ");
        }
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