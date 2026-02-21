package com.slugyzeon.plugin.amazonmusic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AmazonMusicApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AmazonMusicApiHandler.class);
    private static final String SEARCH_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36";
    private static final String BOT_USER_AGENT = "Mozilla/5.0 (compatible; NodeLinkBot/0.1; +https://nodelink.js.org/)";
    private static final long CONFIG_TTL_MS = 60_000;

    private static final Pattern ISO8601_DURATION = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
    private static final Pattern JSON_LD_PATTERN = Pattern.compile("<script [^>]*type=\"application/ld\\+json\"[^>]*>([\\s\\S]*?)</script>");
    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"");
    private static final Pattern HEADER_PRIMARY_TEXT = Pattern.compile("<music-detail-header[^>]*primary-text=\"([^\"]+)\"");
    private static final Pattern HEADER_IMAGE_SRC = Pattern.compile("<music-detail-header[^>]*image-src=\"([^\"]+)\"");
    private static final Pattern MUSIC_ROW_PATTERN = Pattern.compile(
            "<(?:music-image-row|music-text-row)[^>]*primary-text=\"([^\"]+)\"[^>]*primary-href=\"([^\"]+)\"(?:[^>]*secondary-text-1=\"([^\"]+)\")?[^>]*duration=\"([^\"]+)\"(?:[^>]*image-src=\"([^\"]+)\")?"
    );
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]+)</title>");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SlugYZeonConfig.AmazonMusicConfig config;

    private volatile JsonNode cachedConfig;
    private volatile long cachedConfigTime;

    public AmazonMusicApiHandler(SlugYZeonConfig.AmazonMusicConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private synchronized JsonNode getAmazonConfig() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedConfig != null && (now - cachedConfigTime) < CONFIG_TTL_MS) {
            return cachedConfig;
        }

        Request request = new Request.Builder()
                .url("https://music.amazon.com/config.json")
                .header("User-Agent", SEARCH_USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonNode cfg = objectMapper.readTree(response.body().string());
            if (cfg == null || !cfg.has("csrf") || !cfg.get("csrf").has("token")) return null;
            cachedConfig = cfg;
            cachedConfigTime = System.currentTimeMillis();
            return cfg;
        }
    }

    public List<Map<String, Object>> search(String query) throws IOException {
        JsonNode cfg = getAmazonConfig();
        if (cfg == null) throw new IOException("Failed to retrieve Amazon Music CSRF config");

        String accessToken = cfg.has("accessToken") ? cfg.get("accessToken").asText("") : "";
        JsonNode csrf = cfg.get("csrf");
        String deviceId = cfg.has("deviceId") ? cfg.get("deviceId").asText("13580682033287541") : "13580682033287541";
        String sessionId = cfg.has("sessionId") ? cfg.get("sessionId").asText("142-4001091-4160417") : "142-4001091-4160417";

        long now = System.currentTimeMillis();
        String qEnc = URLEncoder.encode(query, StandardCharsets.UTF_8);


        Map<String, String> innerHeaders = new LinkedHashMap<>();
        innerHeaders.put("x-amzn-authentication", "{\"interface\":\"ClientAuthenticationInterface.v1_0.ClientTokenElement\",\"accessToken\":\"" + accessToken + "\"}");
        innerHeaders.put("x-amzn-device-model", "WEBPLAYER");
        innerHeaders.put("x-amzn-device-width", "1920");
        innerHeaders.put("x-amzn-device-height", "1080");
        innerHeaders.put("x-amzn-device-family", "WebPlayer");
        innerHeaders.put("x-amzn-device-id", deviceId);
        innerHeaders.put("x-amzn-user-agent", SEARCH_USER_AGENT);
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
        innerHeaders.put("x-amzn-page-url", "https://music.amazon.com/search/" + qEnc + "?filter=IsLibrary%7Cfalse&sc=none");
        innerHeaders.put("x-amzn-feature-flags", "hd-supported,uhd-supported");

        Map<String, Object> searchPayload = new LinkedHashMap<>();
        searchPayload.put("filter", "{\"IsLibrary\":[\"false\"]}");
        searchPayload.put("keyword", "{\"interface\":\"Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation\",\"keyword\":\"\"}");
        searchPayload.put("suggestedKeyword", query);
        searchPayload.put("userHash", "{\"level\":\"LIBRARY_MEMBER\"}");
        searchPayload.put("headers", objectMapper.writeValueAsString(innerHeaders));

        String payloadStr = objectMapper.writeValueAsString(searchPayload);

        Request request = new Request.Builder()
                .url("https://na.mesk.skill.music.a2z.com/api/showSearch")
                .post(RequestBody.create(payloadStr, MediaType.parse("text/plain;charset=UTF-8")))
                .header("User-Agent", SEARCH_USER_AGENT)
                .header("Content-Type", "text/plain;charset=UTF-8")
                .header("x-amzn-csrf", csrf.get("token").asText())
                .header("Origin", "https://music.amazon.com")
                .header("Referer", "https://music.amazon.com/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("[AmazonMusic] Search API returned {}", response.code());
                return Collections.emptyList();
            }

            JsonNode data = objectMapper.readTree(response.body().string());
            if (data == null) return Collections.emptyList();

            JsonNode widgets = data.path("methods").path(0).path("template").path("widgets");
            if (!widgets.isArray()) return Collections.emptyList();

            List<Map<String, Object>> tracks = new ArrayList<>();
            for (JsonNode widget : widgets) {
                JsonNode items = widget.path("items");
                if (!items.isArray()) continue;

                for (JsonNode item : items) {
                    boolean isSong = "song".equals(item.path("label").asText(""));
                    String iface = item.path("interface").asText("");
                    boolean isSquare = iface.contains("SquareHorizontalItemElement");

                    if (!isSong && !isSquare) continue;

                    String deeplink = item.path("primaryLink").path("deeplink").asText(null);
                    String identifier = extractIdentifier(deeplink);
                    if (identifier == null) continue;
                    if (!isSong && (deeplink == null || !deeplink.contains("trackAsin="))) continue;

                    Map<String, Object> trackInfo = new LinkedHashMap<>();
                    trackInfo.put("identifier", identifier);
                    trackInfo.put("title", decodeAmp(getText(item.path("primaryText"), "Unknown Track")));
                    trackInfo.put("author", decodeAmp(getText(item.path("secondaryText"), "Unknown Artist")));
                    trackInfo.put("uri", "https://music.amazon.com/tracks/" + identifier);
                    trackInfo.put("artworkUrl", item.path("image").asText(null));
                    trackInfo.put("length", 0L);
                    tracks.add(trackInfo);
                }
            }
            return tracks;
        }
    }

    public Map<String, Object> fetchFromPage(String url, String targetId) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BOT_USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            return parsePageContent(body, url, targetId);
        }
    }

    public Map<String, Object> fetchFromOdesli(String url) throws IOException {
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        String apiUrl = "https://api.song.link/v1-alpha.1/links?url=" + URLEncoder.encode(cleanUrl, StandardCharsets.UTF_8);

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("User-Agent", SEARCH_USER_AGENT)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;

            JsonNode data = objectMapper.readTree(response.body().string());
            if (data == null || !data.has("entitiesByUniqueId")) return null;

            String entityId = data.path("entityUniqueId").asText(null);
            JsonNode entity = data.path("entitiesByUniqueId").path(entityId);

            if (entity.isMissingNode()) {
                Iterator<JsonNode> entities = data.path("entitiesByUniqueId").elements();
                if (entities.hasNext()) entity = entities.next();
                else return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", entity.path("title").asText("Unknown Track"));
            result.put("author", entity.path("artistName").asText("Unknown Artist"));
            result.put("uri", url);
            result.put("artworkUrl", entity.path("thumbnailUrl").asText(null));
            result.put("identifier", entity.path("id").asText(""));
            result.put("isrc", entity.path("isrc").asText(null));
            result.put("length", 0L);
            return result;
        } catch (Exception e) {
            log.debug("[AmazonMusic] Odesli fallback failed: {}", e.getMessage());
            return null;
        }
    }



    private Map<String, Object> parsePageContent(String body, String pageUrl, String targetId) {
        try {
            Matcher headerArtistMatcher = HEADER_PRIMARY_TEXT.matcher(body);
            String headerArtist = headerArtistMatcher.find() ? headerArtistMatcher.group(1).replace("&amp;", "&") : null;

            Matcher headerImageMatcher = HEADER_IMAGE_SRC.matcher(body);
            String headerImage = headerImageMatcher.find() ? headerImageMatcher.group(1) : null;

            Matcher ogImageMatcher = OG_IMAGE_PATTERN.matcher(body);
            String artworkUrl = headerImage != null ? headerImage : (ogImageMatcher.find() ? ogImageMatcher.group(1) : null);


            List<Map<String, Object>> tracks = new ArrayList<>();
            String collectionName = headerArtist != null ? headerArtist : "Unknown Artist";
            String collectionImage = artworkUrl;

            Matcher jsonLdMatcher = JSON_LD_PATTERN.matcher(body);
            JsonNode collection = null;
            JsonNode trackData = null;

            while (jsonLdMatcher.find()) {
                try {
                    String content = jsonLdMatcher.group(1).replace("&quot;", "\"").replace("&amp;", "&");
                    JsonNode parsed = objectMapper.readTree(content);
                    JsonNode data = parsed.isArray() && parsed.size() > 0 ? parsed.get(0) : parsed;

                    String type = data.path("@type").asText("");
                    if (type.equals("MusicAlbum") || type.equals("MusicGroup") || type.equals("Playlist")) {
                        collection = data;
                    } else if (type.equals("MusicRecording")) {
                        trackData = data;
                    }
                } catch (Exception ignored) {}
            }

            if (collection != null) {
                String artistName = collection.path("byArtist").path("name").asText(null);
                if (artistName == null) {
                    JsonNode byArtist = collection.path("byArtist");
                    if (byArtist.isArray() && byArtist.size() > 0) {
                        artistName = byArtist.get(0).path("name").asText(null);
                    }
                }
                if (artistName == null) artistName = collection.path("author").path("name").asText(null);
                if (artistName != null) collectionName = artistName;

                if (collection.has("image")) collectionImage = collection.get("image").asText(collectionImage);

                JsonNode trackList = collection.path("track");
                if (trackList.isArray()) {
                    for (JsonNode t : trackList) {
                        String tUrl = t.path("url").asText(null);
                        String id = tUrl != null ? tUrl.substring(tUrl.lastIndexOf('/') + 1) : t.path("@id").asText("unknown");
                        if (id.contains("/")) id = id.substring(id.lastIndexOf('/') + 1);

                        Map<String, Object> trackInfo = new LinkedHashMap<>();
                        trackInfo.put("identifier", id);
                        trackInfo.put("title", t.path("name").asText("Unknown Track"));
                        trackInfo.put("author", t.path("byArtist").path("name").asText(t.path("author").path("name").asText(collectionName)));
                        trackInfo.put("length", parseISO8601Duration(t.path("duration").asText(null)));
                        trackInfo.put("uri", tUrl != null ? tUrl : pageUrl);
                        trackInfo.put("artworkUrl", collectionImage);
                        trackInfo.put("isrc", t.path("isrcCode").asText(null));
                        tracks.add(trackInfo);
                    }
                }
            }


            if (tracks.isEmpty()) {
                Matcher rowMatcher = MUSIC_ROW_PATTERN.matcher(body);
                while (rowMatcher.find()) {
                    String tTitle = rowMatcher.group(1).replace("&amp;", "&");
                    String tHref = rowMatcher.group(2);
                    String tArtist = rowMatcher.group(3) != null ? rowMatcher.group(3).replace("&amp;", "&") : collectionName;
                    String tDuration = rowMatcher.group(4);
                    String tImage = rowMatcher.group(5) != null ? rowMatcher.group(5) : collectionImage;
                    String tId = extractIdentifier(tHref);
                    if (tId == null) tId = "am-" + tTitle.hashCode();

                    Map<String, Object> trackInfo = new LinkedHashMap<>();
                    trackInfo.put("identifier", tId);
                    trackInfo.put("title", tTitle);
                    trackInfo.put("author", tArtist);
                    trackInfo.put("length", tDuration != null && tDuration.contains(":") ? parseColonDuration(tDuration) : 0L);
                    trackInfo.put("uri", "https://music.amazon.com/tracks/" + tId);
                    trackInfo.put("artworkUrl", tImage);
                    trackInfo.put("isrc", null);
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
                result.put("tracks", tracks);
                return result;
            }


            if (trackData != null) {
                String artist = trackData.path("byArtist").path("name").asText(
                        trackData.path("author").path("name").asText("Unknown Artist"));

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("_type", "track");
                result.put("title", trackData.path("name").asText("Unknown Track"));
                result.put("author", artist);
                result.put("uri", pageUrl);
                result.put("artworkUrl", trackData.has("image") ? trackData.get("image").asText(artworkUrl) : artworkUrl);
                result.put("identifier", trackData.path("id").asText(pageUrl.substring(pageUrl.lastIndexOf('/') + 1)));
                result.put("length", parseISO8601Duration(trackData.path("duration").asText(null)));
                result.put("isrc", trackData.path("isrcCode").asText(null));
                return result;
            }
        } catch (Exception e) {
            log.debug("[AmazonMusic] Page parse failed: {}", e.getMessage());
        }
        return null;
    }

    private String buildCsrfHeader(JsonNode csrf) {
        return "{\"interface\":\"CSRFInterface.v1_0.CSRFHeaderElement\"," +
                "\"token\":\"" + csrf.path("token").asText() + "\"," +
                "\"timestamp\":\"" + csrf.path("ts").asText() + "\"," +
                "\"rndNonce\":\"" + csrf.path("rnd").asText() + "\"}";
    }

    static String extractIdentifier(String deeplink) {
        if (deeplink == null) return null;


        int idx = deeplink.indexOf("trackAsin=");
        if (idx != -1) {
            int start = idx + "trackAsin=".length();
            int end = deeplink.length();
            int amp = deeplink.indexOf('&', start);
            if (amp != -1 && amp < end) end = amp;
            int hash = deeplink.indexOf('#', start);
            if (hash != -1 && hash < end) end = hash;
            String id = deeplink.substring(start, end);
            if (!id.isEmpty()) return id;
        }


        int end = deeplink.length();
        int q = deeplink.indexOf('?');
        if (q != -1 && q < end) end = q;
        int h = deeplink.indexOf('#');
        if (h != -1 && h < end) end = h;

        int lastSlash = deeplink.lastIndexOf('/', end - 1);
        String id = deeplink.substring(lastSlash + 1, end);
        return id.isEmpty() ? null : id;
    }

    private static long parseISO8601Duration(String duration) {
        if (duration == null) return 0;
        Matcher m = ISO8601_DURATION.matcher(duration);
        if (!m.matches()) return 0;
        int h = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int s = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return (h * 3600L + min * 60L + s) * 1000L;
    }

    private static long parseColonDuration(String s) {
        if (s == null) return 0;
        String[] parts = s.split(":");
        long sec = 0;
        for (String part : parts) {
            try { sec = sec * 60 + Integer.parseInt(part.trim()); } catch (NumberFormatException e) { return 0; }
        }
        return sec * 1000;
    }

    private static String decodeAmp(String v) {
        return v != null ? v.replace("&amp;", "&") : v;
    }

    private static String getText(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode()) return fallback;
        if (node.isObject()) return decodeAmp(node.path("text").asText(fallback));
        return decodeAmp(node.asText(fallback));
    }
}
