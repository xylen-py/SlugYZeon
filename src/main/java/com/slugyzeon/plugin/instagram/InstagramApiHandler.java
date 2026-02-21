package com.slugyzeon.plugin.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramApiHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramApiHandler.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String AUDIO_API_URL = "https://www.instagram.com/api/v1/clips/music/";

    private static final Pattern CSRF_PATTERN = Pattern.compile("\"csrf_token\":\"(.*?)\"");
    private static final Pattern APP_ID_PATTERN = Pattern.compile("\"appId\":\"(.*?)\"");
    private static final Pattern LSD_PATTERN = Pattern.compile("\"LSD\",\\[],\\{\"token\":\"(.*?)\"\\},");
    private static final Pattern LSD_PATTERN_ALT = Pattern.compile("name=\"lsd\" value=\"(.*?)\"");
    private static final Pattern DOC_ID_PATTERN = Pattern.compile("\"PostPage\",\\[],\"(\\d+)\",");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String csrfToken;
    private volatile String igAppId;
    private volatile String fbLsd;
    private volatile String docIdPost = "10015901848480474";
    private static final String JAZOEST = "2957";

    private volatile boolean initialized = false;

    public InstagramApiHandler() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public synchronized boolean initialize() {
        if (initialized)
            return true;

        try {
            Request request = new Request.Builder()
                    .url("https://www.instagram.com/")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("Failed to fetch Instagram homepage (Status: {})", response.code());
                    return false;
                }

                String body = response.body().string();

                Matcher csrfMatcher = CSRF_PATTERN.matcher(body);
                this.csrfToken = csrfMatcher.find() ? csrfMatcher.group(1) : null;

                Matcher appIdMatcher = APP_ID_PATTERN.matcher(body);
                this.igAppId = appIdMatcher.find() ? appIdMatcher.group(1) : null;

                Matcher lsdMatcher = LSD_PATTERN.matcher(body);
                if (lsdMatcher.find()) {
                    this.fbLsd = lsdMatcher.group(1);
                } else {
                    Matcher lsdAltMatcher = LSD_PATTERN_ALT.matcher(body);
                    this.fbLsd = lsdAltMatcher.find() ? lsdAltMatcher.group(1) : null;
                }

                Matcher docIdMatcher = DOC_ID_PATTERN.matcher(body);
                if (docIdMatcher.find()) {
                    this.docIdPost = docIdMatcher.group(1);
                }

                if (csrfToken == null || igAppId == null || fbLsd == null) {
                    log.error("Could not fetch all required Instagram parameters (CSRF: {}, AppID: {}, LSD: {})",
                            csrfToken != null, igAppId != null, fbLsd != null);
                    return false;
                }

                initialized = true;
                log.info("Instagram API parameters initialized successfully");
                return true;
            }
        } catch (Exception e) {
            log.error("Instagram setup failed: {}", e.getMessage());
            return false;
        }
    }

    public synchronized boolean reinitialize() {
        initialized = false;
        return initialize();
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

        FormBody formBody = new FormBody.Builder()
                .add("av", "0")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "3")
                .add("dpr", "1")
                .add("__ccg", "UNKNOWN")
                .add("lsd", fbLsd)
                .add("jazoest", JAZOEST)
                .add("doc_id", docIdPost)
                .add("variables", variables)
                .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
                .add("fb_api_caller_class", "RelayModern")
                .build();

        String referer = "https://www.instagram.com/" + (pathSegment != null ? pathSegment : "p") + "/" + shortcode
                + "/";

        Request request = new Request.Builder()
                .url(GRAPHQL_URL)
                .post(formBody)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                .header("X-CSRFToken", csrfToken)
                .header("X-IG-App-ID", igAppId)
                .header("X-FB-LSD", fbLsd)
                .header("X-ASBD-ID", "129477")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", referer)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("GraphQL request failed with status {}", response.code());
                return null;
            }

            String bodyStr = response.body().string();
            JsonNode json = objectMapper.readTree(bodyStr);

            if (json == null || !json.has("data"))
                return null;

            JsonNode media = json.path("data").path("xdt_shortcode_media");
            if (media.isNull() || media.isMissingNode())
                return null;

            JsonNode videoNode = null;

            if (media.path("is_video").asBoolean(false)) {
                videoNode = media;
            } else if ("XDTGraphSidecar".equals(media.path("__typename").asText(""))) {
                JsonNode edges = media.path("edge_sidecar_to_children").path("edges");
                if (edges.isArray()) {
                    for (JsonNode edge : edges) {
                        if (edge.path("node").path("is_video").asBoolean(false)) {
                            videoNode = edge.path("node");
                            break;
                        }
                    }
                }
            }

            if (videoNode == null)
                return null;

            String videoUrl = videoNode.path("video_url").asText(null);
            if (videoUrl == null)
                return null;

            String title = media.path("edge_media_to_caption").path("edges")
                    .path(0).path("node").path("text").asText("Instagram Video");
            if (title.length() > 100)
                title = title.substring(0, 97) + "...";

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("videoUrl", videoUrl);
            result.put("author", media.path("owner").path("username").asText("Unknown"));
            result.put("length", (long) (videoNode.path("video_duration").asDouble(0) * 1000));
            result.put("thumbnail", videoNode.path("display_url").asText(media.path("display_url").asText("")));
            result.put("title", title);
            return result;
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

        FormBody formBody = new FormBody.Builder()
                .add("audio_cluster_id", audioId)
                .add("lsd", fbLsd)
                .add("jazoest", JAZOEST)
                .add("__user", "0")
                .add("__a", "1")
                .build();

        Request request = new Request.Builder()
                .url(AUDIO_API_URL)
                .post(formBody)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-FB-Friendly-Name", "PolarisClipsAudioRoute")
                .header("X-CSRFToken", csrfToken)
                .header("X-IG-App-ID", igAppId)
                .header("X-FB-LSD", fbLsd)
                .header("X-ASBD-ID", "129477")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/reels/audio/" + audioId + "/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("Audio API request failed with status {}", response.code());
                return null;
            }

            String bodyStr = response.body().string();
            if (bodyStr.startsWith("for (;;);")) {
                bodyStr = bodyStr.substring("for (;;);".length());
            }

            JsonNode json = objectMapper.readTree(bodyStr);
            if (json == null)
                return null;

            JsonNode payload = json.has("payload") ? json.get("payload") : json;
            if (!payload.has("metadata"))
                return null;

            JsonNode audioInfo = payload.path("metadata").path("original_sound_info");
            String infoSource = "original_sound_info";

            if (audioInfo.isMissingNode() || audioInfo.isNull()) {
                audioInfo = payload.path("metadata").path("music_info");
                infoSource = "music_info";
            }

            if (audioInfo.isMissingNode() || audioInfo.isNull())
                return null;

            String audioUrl;
            String artist;
            String title;
            long duration;
            String thumbnail;

            if ("original_sound_info".equals(infoSource)) {
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
                    Matcher urlMatch = Pattern.compile("<BaseURL>(.*?)</BaseURL>").matcher(manifest);
                    if (urlMatch.find()) {
                        audioUrl = urlMatch.group(1).replace("&amp;", "&");
                    }
                }

                if (audioUrl == null) {
                    audioUrl = audioInfo.path("progressive_download_url").asText(null);
                }

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
    }

    public static String getShortcodeFromMediaId(String mediaId) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder shortcode = new StringBuilder();

        if (mediaId.contains("_")) {
            mediaId = mediaId.substring(0, mediaId.indexOf('_'));
        }

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
