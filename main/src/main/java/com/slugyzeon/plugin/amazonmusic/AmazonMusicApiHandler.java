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
    private static final Pattern ISO8601_DURATION = Pattern.compile("PT(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+(?:\\\\.[0-9]+)?)S)?");

    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private static final long CONFIG_TTL_MS = 120_000;
    private static final int MAX_RETRIES = 2;


    private static final String CONFIG_URL = "https://music.amazon.com/config.json";

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

    private String getBaseApi() {
        if (countryCode == null) return "https://na.mesk.skill.music.a2z.com/api/";
        switch (countryCode.toUpperCase()) {
            case "GB": case "DE": case "FR": case "IT": case "ES":
                return "https://eu.mesk.skill.music.a2z.com/api/";
            case "JP": case "AU": case "IN": case "NZ":
                return "https://fe.mesk.skill.music.a2z.com/api/";
            case "US": case "CA": case "MX": case "BR":
            default:
                return "https://na.mesk.skill.music.a2z.com/api/";
        }
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
                .uri(URI.create(getBaseApi() + "showSearch"))
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

    public Map<String, Object> fetchEntity(String url, String id, String type) throws IOException {
        JsonNode cfg = getAmazonConfig();
        if (cfg == null)
            throw new IOException("Failed to retrieve Amazon Music CSRF config");

        String accessToken = cfg.path("accessToken").asText("");
        JsonNode csrf = cfg.get("csrf");
        String deviceId = cfg.path("deviceId").asText("13580682033287541");
        String sessionId = cfg.path("sessionId").asText("142-4001091-4160417");

        long now = System.currentTimeMillis();

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
        innerHeaders.put("x-amzn-page-url", url);
        innerHeaders.put("x-amzn-feature-flags", "hd-supported,uhd-supported");

        String apiUrl;
        switch (type) {
            case "track":
                apiUrl = getBaseApi() + "cosmicTrack/displayCatalogTrack";
                break;
            case "album":
                apiUrl = getBaseApi() + "showCatalogAlbum";
                break;
            case "artist":
                apiUrl = getBaseApi() + "showCatalogTracks";
                break;
            case "playlist":
            default:
                apiUrl = getBaseApi() + "showCatalogPlaylist";
                break;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("userHash", "{\"level\":\"LIBRARY_MEMBER\"}");
        payload.put("headers", objectMapper.writeValueAsString(innerHeaders));

        String payloadStr = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
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
                return null;
            }

            if (response.statusCode() != 200 || response.body() == null) {
                return null;
            }

            return parseEntity(objectMapper.readTree(response.body()), id, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Map<String, Object> parseEntity(JsonNode data, String id, String type) {
        if (data == null || !data.has("methods") || data.get("methods").isEmpty())
            return null;

        JsonNode firstMethod = data.get("methods").get(0);
        JsonNode template = firstMethod.path("template");
        if (template.isMissingNode())
            return null;

        String collectionName = getText(template.path("headerText"), "Unknown Collection");
        String collectionArtist = getText(template.path("headerPrimaryText"), null);
        String collectionImage = upgradeArtwork(template.path("headerImage").asText(null));
        
        String globalIsrc = null;
        JsonNode seoScripts = template.path("templateData").path("seoHead").path("script");
        if (seoScripts.isArray()) {
            for (JsonNode script : seoScripts) {
                String innerHtml = script.path("innerHTML").asText("");
                if (!innerHtml.isEmpty() && innerHtml.contains("\"isrcCode\"")) {
                    try {
                        JsonNode ldNode = objectMapper.readTree(innerHtml);
                        if (ldNode.isArray()) ldNode = ldNode.get(0);
                        if (ldNode.has("isrcCode")) {
                            globalIsrc = ldNode.get("isrcCode").asText(null);
                            break;
                        } else if (ldNode.has("track") && ldNode.get("track").isArray()) {
                            for (JsonNode t : ldNode.get("track")) {
                                if (t.has("isrcCode") && t.path("url").asText("").contains(id)) {
                                    globalIsrc = t.get("isrcCode").asText(null);
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        JsonNode widgets = template.path("widgets");
        
        JsonNode targetWidget = null;
        if (widgets.isArray()) {
            for (JsonNode w : widgets) {
                String header = w.path("header").asText("").toLowerCase();
                if (header.contains("tracklist") || header.contains("popular") || "track".equals(type) || targetWidget == null) {
                    if (w.has("items")) {
                        targetWidget = w;
                        break;
                    }
                }
            }
        }

        if (targetWidget != null && targetWidget.has("items")) {
            for (JsonNode item : targetWidget.get("items")) {
                String trackId = null;
                String deeplink = item.path("primaryLink").path("deeplink").asText("");
                if (deeplink.contains("trackAsin=")) {
                    trackId = deeplink.split("trackAsin=")[1].split("&")[0];
                } else if (deeplink.contains("/tracks/")) {
                    trackId = deeplink.split("/tracks/")[1].split("\\?")[0];
                } else if (item.has("iconButton")) {
                    String storageKey = item.path("iconButton").path("observer").path("storageKey").asText("");
                    if (storageKey.contains(":")) {
                        trackId = storageKey.split(":")[1];
                    }
                }
                
                if (trackId == null && "track".equals(type)) {
                    trackId = id;
                }
                if (trackId == null) continue;

                Map<String, Object> trackInfo = new LinkedHashMap<>();
                trackInfo.put("identifier", trackId);
                trackInfo.put("title", decodeHtml(getText(item.path("primaryText"), "Unknown Track")));
                
                String artist = getText(item.path("secondaryText2"), null);
                if (artist == null) artist = getText(item.path("secondaryText1"), collectionArtist);
                if (artist == null) artist = "Unknown Artist";
                
                trackInfo.put("author", decodeHtml(artist));
                trackInfo.put("uri", "https://music.amazon.com/tracks/" + trackId);
                trackInfo.put("artworkUrl", upgradeArtwork(item.path("image").asText(collectionImage)));
                
                String duration = getText(item.path("secondaryText3"), null);
                trackInfo.put("length", duration != null ? parseColonDuration(duration) : 0L);
                
                String itemIsrc = globalIsrc;
                if (itemIsrc == null && item.has("trackIsrc")) {
                     itemIsrc = item.get("trackIsrc").asText(null);
                }
                trackInfo.put("isrc", itemIsrc);

                if ("track".equals(type)) {
                    if (trackId.equals(id)) {
                        trackInfo.put("_type", "track");
                        return trackInfo;
                    }
                } else {
                    tracks.add(trackInfo);
                }
            }
        }
        
        if ("track".equals(type) && !tracks.isEmpty()) {
            Map<String, Object> t = tracks.get(0);
            t.put("_type", "track");
            return t;
        }

        if ("track".equals(type)) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_type", "playlist");
        result.put("name", collectionName);
        result.put("author", collectionArtist);
        result.put("artworkUrl", collectionImage);
        result.put("tracks", tracks);
        return result;
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