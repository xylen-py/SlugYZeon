package com.slugyzeon.plugin.amazonmusic;

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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AmazonMusicApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AmazonMusicApiHandler.class);

    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    // Credits: The BOT_UA value uses a test key provided by PerformanC/NodeLink.
    // Note: No code from NodeLink is used here, as this plugin relies entirely on the LavaSrc architecture.
    // This UA was a leftover test component that remained in production.
    private static final String BOT_UA = "Mozilla/5.0 (compatible; NodeLinkBot/0.1; +https://nodelink.js.org/)";
    private static final long CONFIG_TTL_MS = 120_000;
    private static final int MAX_RETRIES = 2;

    private static final String SEARCH_API = "https://na.mesk.skill.music.a2z.com/api/showSearch";
    private static final String CONFIG_URL = "https://music.amazon.com/config.json";

    private static final Pattern ISO8601_DURATION = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
    private static final Pattern JSON_LD_PATTERN = Pattern
            .compile("<script [^>]*type=\"application/ld\\+json\"[^>]*>([\\s\\S]*?)</script>");
    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"");
    private static final Pattern OG_TITLE_PATTERN = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"");
    private static final Pattern HEADER_PRIMARY_TEXT = Pattern
            .compile("<music-detail-header[^>]*primary-text=\"([^\"]+)\"");
    private static final Pattern HEADER_SECONDARY_TEXT = Pattern
            .compile("<music-detail-header[^>]*secondary-text(?:-1)?=\"([^\"]+)\"");
    private static final Pattern HEADER_IMAGE_SRC = Pattern.compile("<music-detail-header[^>]*image-src=\"([^\"]+)\"");
    private static final Pattern MUSIC_ROW_PATTERN = Pattern.compile(
            "<(?:music-image-row|music-text-row)[^>]*primary-text=\"([^\"]+)\"[^>]*primary-href=\"([^\"]+)\"(?:[^>]*secondary-text-1=\"([^\"]+)\")?[^>]*duration=\"([^\"]+)\"(?:[^>]*image-src=\"([^\"]+)\")?");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String countryCode;

    private final ReentrantLock configLock = new ReentrantLock();
    private volatile JsonNode cachedConfig;
    private volatile long cachedConfigTime;

    public AmazonMusicApiHandler(String countryCode) {
        this.countryCode = countryCode;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private JsonNode getAmazonConfig() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedConfig != null && (now - cachedConfigTime) < CONFIG_TTL_MS)
            return cachedConfig;

        configLock.lock();
        try {
            now = System.currentTimeMillis();
            if (cachedConfig != null && (now - cachedConfigTime) < CONFIG_TTL_MS)
                return cachedConfig;

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(CONFIG_URL))
                            .header("User-Agent", BROWSER_UA)
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .GET().build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || response.body() == null)
                        continue;

                    JsonNode cfg = objectMapper.readTree(response.body());
                    if (cfg == null || !cfg.has("csrf") || !cfg.get("csrf").has("token"))
                        continue;

                    cachedConfig = cfg;
                    cachedConfigTime = System.currentTimeMillis();
                    return cfg;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    if (attempt == MAX_RETRIES) {
                        log.error("Config fetch failed after {} retries: {}", MAX_RETRIES + 1, e.getMessage());
                        return null;
                    }
                    log.debug("Config fetch attempt {} failed, retrying...", attempt + 1);
                }
            }
            return null;
        } finally {
            configLock.unlock();
        }
    }

    public void invalidateConfig() {
        cachedConfig = null;
        cachedConfigTime = 0;
    }

    public List<Map<String, Object>> search(String query) throws IOException {
        JsonNode cfg = getAmazonConfig();
        if (cfg == null)
            throw new IOException("Failed to retrieve Amazon Music CSRF config");

        String accessToken = cfg.path("accessToken").asText("");
        JsonNode csrf = cfg.get("csrf");
        String deviceId = cfg.path("deviceId").asText("13580682033287541");
        String sessionId = cfg.path("sessionId").asText("142-4001091-4160417");

        long now = System.currentTimeMillis();
        String qEnc = URLEncoder.encode(query, StandardCharsets.UTF_8);

        Map<String, String> innerHeaders = new LinkedHashMap<>();
        innerHeaders.put("x-amzn-authentication",
                "{\"interface\":\"ClientAuthenticationInterface.v1_0.ClientTokenElement\",\"accessToken\":\""
                        + accessToken + "\"}");
        innerHeaders.put("x-amzn-device-model", "WEBPLAYER");
        innerHeaders.put("x-amzn-device-width", "1920");
        innerHeaders.put("x-amzn-device-height", "1080");
        innerHeaders.put("x-amzn-device-family", "WebPlayer");
        innerHeaders.put("x-amzn-device-id", deviceId);
        innerHeaders.put("x-amzn-user-agent", BROWSER_UA);
        innerHeaders.put("x-amzn-session-id", sessionId);
        innerHeaders.put("x-amzn-request-id", UUID.randomUUID().toString());
        innerHeaders.put("x-amzn-device-language", "en_US");
        innerHeaders.put("x-amzn-currency-of-preference", "USD");
        innerHeaders.put("x-amzn-os-version", "1.0");
        innerHeaders.put("x-amzn-application-version", "1.0.9172.0");
        innerHeaders.put("x-amzn-device-time-zone", "America/New_York");
        innerHeaders.put("x-amzn-timestamp", String.valueOf(now));
        innerHeaders.put("x-amzn-csrf", buildCsrfHeader(csrf));
        innerHeaders.put("x-amzn-music-domain", "music.amazon.com");
        innerHeaders.put("x-amzn-page-url",
                "https://music.amazon.com/search/" + qEnc + "?filter=IsLibrary%7Cfalse&sc=none");
        innerHeaders.put("x-amzn-feature-flags", "hd-supported,uhd-supported");

        Map<String, Object> searchPayload = new LinkedHashMap<>();
        searchPayload.put("filter", "{\"IsLibrary\":[\"false\"]}");
        searchPayload.put("keyword",
                "{\"interface\":\"Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation\",\"keyword\":\"\"}");
        searchPayload.put("suggestedKeyword", query);
        searchPayload.put("userHash", "{\"level\":\"LIBRARY_MEMBER\"}");
        searchPayload.put("headers", objectMapper.writeValueAsString(innerHeaders));

        String payloadStr = objectMapper.writeValueAsString(searchPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_API))
                .header("User-Agent", BROWSER_UA)
                .header("Content-Type", "text/plain;charset=UTF-8")
                .header("x-amzn-csrf", csrf.path("token").asText())
                .header("Origin", "https://music.amazon.com")
                .header("Referer", "https://music.amazon.com/")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payloadStr))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                invalidateConfig();
                log.debug("Search returned {}, invalidated config", response.statusCode());
                return Collections.emptyList();
            }

            if (response.statusCode() != 200 || response.body() == null) {
                log.error("Search API returned {}", response.statusCode());
                return Collections.emptyList();
            }

            return parseSearchResponse(objectMapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> parseSearchResponse(JsonNode data) {
        if (data == null)
            return Collections.emptyList();

        JsonNode widgets = data.path("methods").path(0).path("template").path("widgets");
        if (!widgets.isArray())
            return Collections.emptyList();

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode widget : widgets) {
            JsonNode items = widget.path("items");
            if (!items.isArray())
                continue;

            for (JsonNode item : items) {
                Map<String, Object> track = parseSearchItem(item);
                if (track != null)
                    tracks.add(track);
            }
        }
        return tracks;
    }

    private Map<String, Object> parseSearchItem(JsonNode item) {
        boolean isSong = "song".equals(item.path("label").asText(""));
        String iface = item.path("interface").asText("");
        boolean isSquare = iface.contains("SquareHorizontalItemElement");

        if (!isSong && !isSquare)
            return null;

        String deeplink = item.path("primaryLink").path("deeplink").asText(null);
        String identifier = extractIdentifier(deeplink);
        if (identifier == null)
            return null;
        if (!isSong && (deeplink == null || !deeplink.contains("trackAsin=")))
            return null;

        Map<String, Object> trackInfo = new LinkedHashMap<>();
        trackInfo.put("identifier", identifier);
        trackInfo.put("title", decodeHtml(getText(item.path("primaryText"), "Unknown Track")));
        trackInfo.put("author", decodeHtml(getText(item.path("secondaryText"), "Unknown Artist")));
        trackInfo.put("uri", "https://music.amazon.com/tracks/" + identifier);
        trackInfo.put("artworkUrl", upgradeArtwork(item.path("image").asText(null)));
        trackInfo.put("length", extractDuration(item));
        trackInfo.put("isrc", null);
        return trackInfo;
    }

    private long extractDuration(JsonNode item) {
        String durationText = getText(item.path("tertiaryText"), null);
        if (durationText != null && durationText.contains(":")) {
            long d = parseColonDuration(durationText);
            if (d > 0)
                return d;
        }

        String isoDur = item.path("duration").asText(null);
        if (isoDur != null) {
            long d = isoDur.contains(":") ? parseColonDuration(isoDur) : parseISO8601Duration(isoDur);
            if (d > 0)
                return d;
        }

        if (item.has("durationInSeconds")) {
            long d = item.path("durationInSeconds").asLong(0) * 1000L;
            if (d > 0)
                return d;
        }

        for (String field : new String[] { "secondaryText2", "tertiaryText1", "tertiaryText2", "quaternaryText" }) {
            String val = getText(item.path(field), null);
            if (val != null && val.contains(":")) {
                long d = parseColonDuration(val);
                if (d > 0)
                    return d;
            }
        }
        return 0L;
    }

    public Map<String, Object> fetchFromPage(String url, String targetId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", BOT_UA)
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;
            return parsePageContent(response.body(), url, targetId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public Map<String, Object> fetchFromOdesli(String url) throws IOException {
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        String apiUrl = "https://api.song.link/v1-alpha.1/links?url="
                + URLEncoder.encode(cleanUrl, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;

            JsonNode data = objectMapper.readTree(response.body());
            if (data == null || !data.has("entitiesByUniqueId"))
                return null;

            String entityId = data.path("entityUniqueId").asText(null);
            JsonNode entity = data.path("entitiesByUniqueId").path(entityId);

            if (entity.isMissingNode()) {
                Iterator<JsonNode> entities = data.path("entitiesByUniqueId").elements();
                if (entities.hasNext())
                    entity = entities.next();
                else
                    return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("_type", "track");
            result.put("title", entity.path("title").asText("Unknown Track"));
            result.put("author", entity.path("artistName").asText("Unknown Artist"));
            result.put("uri", url);
            result.put("artworkUrl", upgradeArtwork(entity.path("thumbnailUrl").asText(null)));
            result.put("identifier", entity.path("id").asText(""));
            result.put("isrc", entity.path("isrc").asText(null));
            result.put("length", 0L);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Odesli fallback failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parsePageContent(String body, String pageUrl, String targetId) {
        try {
            String headerArtist = extractPattern(HEADER_PRIMARY_TEXT, body);
            String headerSecondary = extractPattern(HEADER_SECONDARY_TEXT, body);
            String headerImage = extractPattern(HEADER_IMAGE_SRC, body);
            String ogImage = extractPattern(OG_IMAGE_PATTERN, body);
            String ogTitle = extractPattern(OG_TITLE_PATTERN, body);

            String artworkUrl = headerImage != null ? headerImage : ogImage;
            String collectionName = headerArtist != null ? decodeHtml(headerArtist)
                    : ogTitle != null ? decodeHtml(ogTitle) : "Unknown";
            String collectionArtist = headerSecondary != null ? decodeHtml(headerSecondary) : null;
            String collectionImage = artworkUrl;

            JsonNode collection = null;
            JsonNode trackData = null;

            Matcher jsonLdMatcher = JSON_LD_PATTERN.matcher(body);
            while (jsonLdMatcher.find()) {
                try {
                    String content = decodeHtml(jsonLdMatcher.group(1));
                    JsonNode parsed = objectMapper.readTree(content);
                    JsonNode node = parsed.isArray() && parsed.size() > 0 ? parsed.get(0) : parsed;

                    String type = node.path("@type").asText("");
                    switch (type) {
                        case "MusicAlbum":
                        case "MusicGroup":
                        case "Playlist":
                        case "MusicPlaylist":
                            collection = node;
                            break;
                        case "MusicRecording":
                            trackData = node;
                            break;
                    }
                } catch (Exception ignored) {
                }
            }

            List<Map<String, Object>> tracks = new ArrayList<>();

            if (collection != null) {
                String artistName = extractArtistFromJsonLd(collection);
                if (artistName != null)
                    collectionName = artistName;
                if (collectionArtist == null)
                    collectionArtist = artistName;

                if (collection.has("image"))
                    collectionImage = collection.get("image").asText(collectionImage);

                String albumName = collection.path("name").asText(null);

                JsonNode trackList = collection.path("track");
                if (trackList.isArray()) {
                    for (JsonNode t : trackList) {
                        Map<String, Object> trackInfo = parseJsonLdTrack(t, collectionName, collectionImage, albumName,
                                pageUrl);
                        if (trackInfo != null)
                            tracks.add(trackInfo);
                    }
                }
            }

            if (tracks.isEmpty()) {
                Matcher rowMatcher = MUSIC_ROW_PATTERN.matcher(body);
                while (rowMatcher.find()) {
                    Map<String, Object> trackInfo = parseHtmlRowTrack(rowMatcher, collectionName, collectionImage);
                    if (trackInfo != null)
                        tracks.add(trackInfo);
                }
            }

            if (!tracks.isEmpty()) {
                if (targetId != null) {
                    for (Map<String, Object> t : tracks) {
                        String id = (String) t.get("identifier");
                        String uri = (String) t.get("uri");
                        if (targetId.equals(id) || (uri != null && uri.contains(targetId))) {
                            t.put("_type", "track");
                            return t;
                        }
                    }
                }

                if (pageUrl.contains("/tracks/") && targetId == null) {
                    tracks.get(0).put("_type", "track");
                    return tracks.get(0);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("_type", "playlist");
                result.put("name", collectionName);
                result.put("author", collectionArtist);
                result.put("artworkUrl", collectionImage);
                result.put("tracks", tracks);
                return result;
            }

            if (trackData != null)
                return parseSingleTrackFromJsonLd(trackData, artworkUrl, pageUrl);

        } catch (Exception e) {
            log.debug("Page parse failed: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> parseJsonLdTrack(JsonNode t, String fallbackArtist, String fallbackImage,
            String albumName, String pageUrl) {
        String tUrl = t.path("url").asText(null);
        String id = tUrl != null ? tUrl.substring(tUrl.lastIndexOf('/') + 1) : t.path("@id").asText("unknown");
        if (id.contains("/"))
            id = id.substring(id.lastIndexOf('/') + 1);

        String artist = extractArtistFromJsonLd(t);
        if (artist == null)
            artist = fallbackArtist;

        Map<String, Object> trackInfo = new LinkedHashMap<>();
        trackInfo.put("identifier", id);
        trackInfo.put("title", t.path("name").asText("Unknown Track"));
        trackInfo.put("author", artist);
        trackInfo.put("albumName", albumName);
        trackInfo.put("length", parseISO8601Duration(t.path("duration").asText(null)));
        trackInfo.put("uri", tUrl != null ? tUrl : pageUrl);
        trackInfo.put("artworkUrl", upgradeArtwork(fallbackImage));
        trackInfo.put("isrc", t.path("isrcCode").asText(null));
        return trackInfo;
    }

    private Map<String, Object> parseHtmlRowTrack(Matcher rowMatcher, String fallbackArtist, String fallbackImage) {
        String tTitle = decodeHtml(rowMatcher.group(1));
        String tHref = rowMatcher.group(2);
        String tArtist = rowMatcher.group(3) != null ? decodeHtml(rowMatcher.group(3)) : fallbackArtist;
        String tDuration = rowMatcher.group(4);
        String tImage = rowMatcher.group(5) != null ? rowMatcher.group(5) : fallbackImage;
        String tId = extractIdentifier(tHref);
        if (tId == null)
            tId = "am-" + tTitle.hashCode();

        Map<String, Object> trackInfo = new LinkedHashMap<>();
        trackInfo.put("identifier", tId);
        trackInfo.put("title", tTitle);
        trackInfo.put("author", tArtist);
        trackInfo.put("length", tDuration != null && tDuration.contains(":") ? parseColonDuration(tDuration) : 0L);
        trackInfo.put("uri", "https://music.amazon.com/tracks/" + tId);
        trackInfo.put("artworkUrl", upgradeArtwork(tImage));
        trackInfo.put("isrc", null);
        return trackInfo;
    }

    private Map<String, Object> parseSingleTrackFromJsonLd(JsonNode trackData, String fallbackArtwork, String pageUrl) {
        String artist = extractArtistFromJsonLd(trackData);
        if (artist == null)
            artist = "Unknown Artist";

        String artwork = trackData.has("image") ? trackData.get("image").asText(fallbackArtwork) : fallbackArtwork;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_type", "track");
        result.put("title", trackData.path("name").asText("Unknown Track"));
        result.put("author", artist);
        result.put("uri", pageUrl);
        result.put("artworkUrl", upgradeArtwork(artwork));
        result.put("identifier", trackData.path("id").asText(pageUrl.substring(pageUrl.lastIndexOf('/') + 1)));
        result.put("length", parseISO8601Duration(trackData.path("duration").asText(null)));
        result.put("isrc", trackData.path("isrcCode").asText(null));
        return result;
    }

    private String extractArtistFromJsonLd(JsonNode node) {
        JsonNode byArtist = node.path("byArtist");
        if (byArtist.isArray() && byArtist.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode artist : byArtist) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(artist.path("name").asText(""));
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }
        if (byArtist.has("name")) {
            String name = byArtist.path("name").asText(null);
            if (name != null && !name.isEmpty())
                return name;
        }
        JsonNode author = node.path("author");
        if (author.has("name")) {
            String name = author.path("name").asText(null);
            if (name != null && !name.isEmpty())
                return name;
        }
        return null;
    }

    private String extractPattern(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String upgradeArtwork(String url) {
        if (url == null)
            return null;
        if (url.contains("._SL"))
            return url.replaceFirst("\\._SL\\d+_", "._SL500_");
        if (url.contains("._SS"))
            return url.replaceFirst("\\._SS\\d+_", "._SS500_");
        return url;
    }

    private String buildCsrfHeader(JsonNode csrf) {
        return "{\"interface\":\"CSRFInterface.v1_0.CSRFHeaderElement\"," +
                "\"token\":\"" + csrf.path("token").asText() + "\"," +
                "\"timestamp\":\"" + csrf.path("ts").asText() + "\"," +
                "\"rndNonce\":\"" + csrf.path("rnd").asText() + "\"}";
    }

    static String extractIdentifier(String deeplink) {
        if (deeplink == null)
            return null;

        int idx = deeplink.indexOf("trackAsin=");
        if (idx != -1) {
            int start = idx + "trackAsin=".length();
            int end = deeplink.length();
            int amp = deeplink.indexOf('&', start);
            if (amp != -1 && amp < end)
                end = amp;
            int hash = deeplink.indexOf('#', start);
            if (hash != -1 && hash < end)
                end = hash;
            String id = deeplink.substring(start, end);
            if (!id.isEmpty())
                return id;
        }

        int end = deeplink.length();
        int q = deeplink.indexOf('?');
        if (q != -1 && q < end)
            end = q;
        int h = deeplink.indexOf('#');
        if (h != -1 && h < end)
            end = h;

        int lastSlash = deeplink.lastIndexOf('/', end - 1);
        String id = deeplink.substring(lastSlash + 1, end);
        return id.isEmpty() ? null : id;
    }

    private static long parseISO8601Duration(String duration) {
        if (duration == null)
            return 0;
        Matcher m = ISO8601_DURATION.matcher(duration);
        if (!m.matches())
            return 0;
        int h = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int s = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return (h * 3600L + min * 60L + s) * 1000L;
    }

    private static long parseColonDuration(String s) {
        if (s == null)
            return 0;
        String[] parts = s.split(":");
        long sec = 0;
        for (String part : parts) {
            try {
                sec = sec * 60 + Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return sec * 1000;
    }

    private static String decodeHtml(String text) {
        if (text == null)
            return null;
        return text
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ");
    }

    private static String getText(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode())
            return fallback;
        if (node.isObject())
            return decodeHtml(node.path("text").asText(fallback));
        return decodeHtml(node.asText(fallback));
    }
}