package com.slugyzeon.plugin.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramApiHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramApiHandler.class);

    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String AUDIO_API_URL = "https://www.instagram.com/api/v1/clips/music/";
    private static final String JAZOEST = "2957";

    private static final Pattern CSRF_PATTERN = Pattern.compile("\"csrf_token\":\"(.*?)\"");
    private static final Pattern APP_ID_PATTERN = Pattern.compile("\"appId\":\"(.*?)\"");
    private static final Pattern LSD_PATTERN = Pattern.compile("\"LSD\",\\[],\\{\"token\":\"(.*?)\"\\},");
    private static final Pattern LSD_PATTERN_ALT = Pattern.compile("name=\"lsd\" value=\"(.*?)\"");
    private static final Pattern DOC_ID_PATTERN = Pattern.compile("\"PostPage\",\\[],\"(\\d+)\",");
    private static final Pattern BASE_URL_PATTERN = Pattern.compile("<BaseURL>(.*?)</BaseURL>");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock initLock = new ReentrantLock();

    private volatile String csrfToken;
    private volatile String igAppId;
    private volatile String fbLsd;
    private volatile String docIdPost = "10015901848480474";
    private volatile boolean initialized = false;

    public InstagramApiHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean initialize() {
        if (initialized)
            return true;

        initLock.lock();
        try {
            if (initialized)
                return true;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.instagram.com/"))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.error("Failed to fetch Instagram homepage (Status: {})", response.statusCode());
                return false;
            }

            String body = response.body();

            this.csrfToken = extractPattern(CSRF_PATTERN, body);
            this.igAppId = extractPattern(APP_ID_PATTERN, body);
            this.fbLsd = extractPattern(LSD_PATTERN, body);
            if (this.fbLsd == null)
                this.fbLsd = extractPattern(LSD_PATTERN_ALT, body);

            String docId = extractPattern(DOC_ID_PATTERN, body);
            if (docId != null)
                this.docIdPost = docId;

            if (csrfToken == null || igAppId == null || fbLsd == null) {
                log.error("Missing Instagram parameters (CSRF: {}, AppID: {}, LSD: {})",
                        csrfToken != null, igAppId != null, fbLsd != null);
                return false;
            }

            initialized = true;
            log.info("Instagram API parameters initialized");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("Instagram initialization failed: {}", e.getMessage());
            return false;
        } finally {
            initLock.unlock();
        }
    }

    public boolean reinitialize() {
        initLock.lock();
        try {
            initialized = false;
            return initialize();
        } finally {
            initLock.unlock();
        }
    }

    public Map<String, Object> fetchFromGraphQL(String shortcode, String pathSegment) throws IOException {
        if (!initialized && !initialize())
            return null;

        Map<String, Object> result = doFetchFromGraphQL(shortcode, pathSegment);
        if (result == null && reinitialize()) {
            result = doFetchFromGraphQL(shortcode, pathSegment);
        }
        return result;
    }

    private Map<String, Object> doFetchFromGraphQL(String shortcode, String pathSegment) throws IOException {
        String variables = "{\"shortcode\":\"" + shortcode + "\",\"fetch_comment_count\":\"null\"," +
                "\"fetch_related_profile_media_count\":\"null\",\"parent_comment_count\":\"null\"," +
                "\"child_comment_count\":\"null\",\"fetch_like_count\":\"null\"," +
                "\"fetch_tagged_user_count\":\"null\",\"fetch_preview_comment_count\":\"null\"," +
                "\"has_threaded_comments\":\"false\",\"hoisted_comment_id\":\"null\",\"hoisted_reply_id\":\"null\"}";

        String formBody = buildFormBody(
                "av", "0",
                "__user", "0",
                "__a", "1",
                "__req", "3",
                "dpr", "1",
                "__ccg", "UNKNOWN",
                "lsd", fbLsd,
                "jazoest", JAZOEST,
                "doc_id", docIdPost,
                "variables", variables,
                "fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery",
                "fb_api_caller_class", "RelayModern");

        String referer = "https://www.instagram.com/" + (pathSegment != null ? pathSegment : "p") + "/" + shortcode
                + "/";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPHQL_URL))
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                .header("X-CSRFToken", csrfToken)
                .header("X-IG-App-ID", igAppId)
                .header("X-FB-LSD", fbLsd)
                .header("X-ASBD-ID", "129477")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", BROWSER_UA)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", referer)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.debug("GraphQL request failed with status {}", response.statusCode());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (json == null || !json.has("data"))
                return null;

            JsonNode media = json.path("data").path("xdt_shortcode_media");
            if (media.isNull() || media.isMissingNode())
                return null;

            JsonNode videoNode = findVideoNode(media);
            if (videoNode == null)
                return null;

            String videoUrl = videoNode.path("video_url").asText(null);
            if (videoUrl == null)
                return null;

            String title = extractCaption(media);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("videoUrl", videoUrl);
            result.put("author", media.path("owner").path("username").asText("Unknown"));
            result.put("length", (long) (videoNode.path("video_duration").asDouble(0) * 1000));
            result.put("thumbnail", videoNode.path("display_url").asText(media.path("display_url").asText("")));
            result.put("title", title);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public Map<String, Object> fetchFromAudioAPI(String audioId) throws IOException {
        if (!initialized && !initialize())
            return null;

        Map<String, Object> result = doFetchFromAudioAPI(audioId);
        if (result == null && reinitialize()) {
            result = doFetchFromAudioAPI(audioId);
        }
        return result;
    }

    private Map<String, Object> doFetchFromAudioAPI(String audioId) throws IOException {
        String formBody = buildFormBody(
                "audio_cluster_id", audioId,
                "lsd", fbLsd,
                "jazoest", JAZOEST,
                "__user", "0",
                "__a", "1");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUDIO_API_URL))
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FB-Friendly-Name", "PolarisClipsAudioRoute")
                .header("X-CSRFToken", csrfToken)
                .header("X-IG-App-ID", igAppId)
                .header("X-FB-LSD", fbLsd)
                .header("X-ASBD-ID", "129477")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", BROWSER_UA)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/reels/audio/" + audioId + "/")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.debug("Audio API request failed with status {}", response.statusCode());
                return null;
            }

            String bodyStr = response.body();
            if (bodyStr.startsWith("for (;;);"))
                bodyStr = bodyStr.substring("for (;;);".length());

            JsonNode json = objectMapper.readTree(bodyStr);
            if (json == null)
                return null;

            JsonNode payload = json.has("payload") ? json.get("payload") : json;
            if (!payload.has("metadata"))
                return null;

            return parseAudioMetadata(payload.path("metadata"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Map<String, Object> parseAudioMetadata(JsonNode metadata) {
        JsonNode audioInfo = metadata.path("original_sound_info");
        boolean isOriginal = !audioInfo.isMissingNode() && !audioInfo.isNull();

        if (!isOriginal) {
            audioInfo = metadata.path("music_info");
            if (audioInfo.isMissingNode() || audioInfo.isNull())
                return null;
        }

        String audioUrl;
        String artist;
        String title;
        long duration;
        String thumbnail;

        if (isOriginal) {
            audioUrl = audioInfo.path("progressive_download_url").asText(null);
            artist = audioInfo.path("ig_artist").path("username").asText("Unknown");
            title = audioInfo.path("original_audio_title").asText("Instagram Audio");
            duration = audioInfo.path("duration_in_ms").asLong(0);
            thumbnail = audioInfo.path("ig_artist").path("profile_pic_url").asText("");
        } else {
            JsonNode musicAsset = audioInfo.path("music_asset_info");
            JsonNode musicConsumption = audioInfo.path("music_consumption_info");

            audioUrl = musicAsset.path("progressive_download_url").asText(null);

            if (audioUrl == null && musicConsumption.has("dash_manifest")) {
                String manifest = musicConsumption.get("dash_manifest").asText("");
                Matcher urlMatch = BASE_URL_PATTERN.matcher(manifest);
                if (urlMatch.find())
                    audioUrl = urlMatch.group(1).replace("&amp;", "&");
            }

            if (audioUrl == null)
                audioUrl = audioInfo.path("progressive_download_url").asText(null);

            artist = musicAsset.path("artist_name").asText("Unknown");
            title = musicAsset.path("title").asText("Instagram Audio");
            duration = musicAsset.path("duration_in_ms").asLong(0);
            thumbnail = musicAsset.path("cover_artwork_thumbnail_uri").asText("");
        }

        if (audioUrl == null)
            return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoUrl", audioUrl);
        result.put("author", artist);
        result.put("length", duration);
        result.put("thumbnail", thumbnail);
        result.put("title", title);
        return result;
    }

    private JsonNode findVideoNode(JsonNode media) {
        if (media.path("is_video").asBoolean(false))
            return media;

        if ("XDTGraphSidecar".equals(media.path("__typename").asText(""))) {
            JsonNode edges = media.path("edge_sidecar_to_children").path("edges");
            if (edges.isArray()) {
                for (JsonNode edge : edges) {
                    if (edge.path("node").path("is_video").asBoolean(false))
                        return edge.path("node");
                }
            }
        }
        return null;
    }

    private String extractCaption(JsonNode media) {
        String title = media.path("edge_media_to_caption").path("edges")
                .path(0).path("node").path("text").asText("Instagram Video");
        if (title.length() > 100)
            title = title.substring(0, 97) + "...";
        return title;
    }

    private String extractPattern(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String buildFormBody(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (sb.length() > 0)
                sb.append("&");
            sb.append(java.net.URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
                    .append("=")
                    .append(java.net.URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static String getShortcodeFromMediaId(String mediaId) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder shortcode = new StringBuilder();

        if (mediaId.contains("_"))
            mediaId = mediaId.substring(0, mediaId.indexOf('_'));

        try {
            long id = Long.parseLong(mediaId);
            if (id <= 0)
                return null;
            while (id > 0) {
                int remainder = (int) (id % 64);
                id = id / 64;
                shortcode.insert(0, alphabet.charAt(remainder));
            }
            return shortcode.toString();
        } catch (NumberFormatException e) {
            log.debug("Could not convert mediaId '{}' to shortcode", mediaId);
            return null;
        }
    }
}
