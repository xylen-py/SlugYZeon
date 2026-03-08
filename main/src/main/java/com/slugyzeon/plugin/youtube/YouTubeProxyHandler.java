package com.slugyzeon.plugin.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(YouTubeProxyHandler.class);

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private static final Pattern PLAYER_RESPONSE_PATTERN = Pattern.compile(
            "var\\s+ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;\\s*(?:var|</script>)",
            Pattern.DOTALL);
    private static final Pattern PLAYER_RESPONSE_PATTERN2 = Pattern.compile(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;\\s*(?:var|</script>)",
            Pattern.DOTALL);
    private static final Pattern INITIAL_DATA_PATTERN = Pattern.compile(
            "var\\s+ytInitialData\\s*=\\s*(\\{.+?\\})\\s*;\\s*(?:var|</script>)",
            Pattern.DOTALL);
    private static final Pattern TIME_PATTERN_1 = Pattern.compile("(\\d+):(\\d\\d):(\\d\\d)");
    private static final Pattern TIME_PATTERN_2 = Pattern.compile("(\\d+):(\\d\\d)");
    private static final Pattern DURATION_LABEL_PATTERN = Pattern.compile("(\\d+)\\s*(hour|minute|second)s?");

    private static final String[] INNERTUBE_CLIENTS = {
            "WEB_REMIX", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "MEDIA_CONNECT_FRONTEND"
    };

    private static final String INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String INNERTUBE_MUSIC_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public YouTubeProxyHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    public StreamResult getStream(String videoId) {
        StreamResult result = tryWatchPageExtraction(videoId);
        if (result != null) {
            log.info("[YouTube] Successfully extracted audio from watch page for {}", videoId);
            return result;
        }

        result = tryInnertubeClients(videoId);
        if (result != null) {
            log.info("[YouTube] Successfully extracted audio via Innertube API for {}", videoId);
        }
        return result;
    }

    public VideoInfo getVideoInfo(String videoId) {
        try {
            JsonNode playerResponse = fetchPlayerResponseFromPage(videoId);
            if (playerResponse != null) {
                JsonNode videoDetails = playerResponse.get("videoDetails");
                if (videoDetails != null) {
                    return buildVideoInfo(videoDetails, videoId);
                }
            }
        } catch (Exception ignored) {
        }

        VideoInfo innertubeInfo = tryInnertubeVideoInfo(videoId);
        if (innertubeInfo != null)
            return innertubeInfo;

        try {
            String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v="
                    + videoId + "&format=json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oembedUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT)
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                JsonNode json = mapper.readTree(response.body());
                return new VideoInfo(
                        videoId,
                        json.path("title").asText("Unknown"),
                        json.path("author_name").asText("Unknown"),
                        Long.MAX_VALUE,
                        json.path("thumbnail_url").asText(null),
                        "https://www.youtube.com/watch?v=" + videoId);
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public List<VideoInfo> search(String query, boolean musicOnly) {
        List<VideoInfo> results = trySearchPageScrape(query);
        if (results != null && !results.isEmpty()) {
            log.info("[YouTube] Search page scrape returned {} results", results.size());
            return results;
        }

        results = tryInnertubeSearch(query, musicOnly);
        if (results != null && !results.isEmpty()) {
            log.info("[YouTube] Innertube API search returned {} results", results.size());
            return results;
        }

        return Collections.emptyList();
    }

    private StreamResult tryWatchPageExtraction(String videoId) {
        try {
            JsonNode playerResponse = fetchPlayerResponseFromPage(videoId);
            if (playerResponse == null)
                return null;

            JsonNode playability = playerResponse.get("playabilityStatus");
            if (playability != null) {
                String status = playability.path("status").asText("");
                if ("ERROR".equals(status) || "UNPLAYABLE".equals(status)
                        || "LOGIN_REQUIRED".equals(status))
                    return null;
            }

            JsonNode streamingData = playerResponse.get("streamingData");
            if (streamingData == null)
                return null;

            JsonNode formats = streamingData.get("adaptiveFormats");
            if (formats == null || !formats.isArray())
                return null;

            return pickBestAudioFormat(formats);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode fetchPlayerResponseFromPage(String videoId) throws Exception {
        String url = "https://www.youtube.com/watch?v=" + videoId
                + "&bpctr=" + (System.currentTimeMillis() / 1000 + 1800)
                + "&has_verified=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cookie",
                        "CONSENT=PENDING+987; SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjMwODI5LjA3X3AxGgJlbiACGgYIgJnPpwY")
                .timeout(REQUEST_TIMEOUT)
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body() == null)
            return null;

        String html = response.body();

        Matcher m = PLAYER_RESPONSE_PATTERN.matcher(html);
        if (!m.find()) {
            m = PLAYER_RESPONSE_PATTERN2.matcher(html);
            if (!m.find())
                return null;
        }

        return mapper.readTree(m.group(1));
    }

    private StreamResult tryInnertubeClients(String videoId) {
        for (String clientName : INNERTUBE_CLIENTS) {
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, clientName);
                if (json == null)
                    continue;

                JsonNode playability = json.get("playabilityStatus");
                if (playability != null) {
                    String status = playability.path("status").asText("");
                    if (!"OK".equals(status))
                        continue;
                }

                JsonNode streamingData = json.get("streamingData");
                if (streamingData == null)
                    continue;

                JsonNode formats = streamingData.get("adaptiveFormats");
                if (formats == null || !formats.isArray())
                    continue;

                StreamResult result = pickBestAudioFormat(formats);
                if (result != null)
                    return result;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private VideoInfo tryInnertubeVideoInfo(String videoId) {
        for (String clientName : INNERTUBE_CLIENTS) {
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, clientName);
                if (json != null) {
                    JsonNode videoDetails = json.get("videoDetails");
                    if (videoDetails != null) {
                        return buildVideoInfo(videoDetails, videoId);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JsonNode fetchInnertubePlayerResponse(String videoId, String clientName) throws Exception {
        String apiKey;
        JsonNode clientNode;

        switch (clientName) {
            case "WEB_REMIX":
                apiKey = INNERTUBE_MUSIC_KEY;
                clientNode = mapper.createObjectNode()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20240306.01.00")
                        .put("hl", "en")
                        .put("gl", "US");
                break;
            case "TVHTML5_SIMPLY_EMBEDDED_PLAYER":
                apiKey = INNERTUBE_API_KEY;
                clientNode = mapper.createObjectNode()
                        .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        .put("clientVersion", "2.0")
                        .put("hl", "en")
                        .put("gl", "US");
                break;
            case "MEDIA_CONNECT_FRONTEND":
                apiKey = INNERTUBE_API_KEY;
                clientNode = mapper.createObjectNode()
                        .put("clientName", "MEDIA_CONNECT_FRONTEND")
                        .put("clientVersion", "0.1")
                        .put("hl", "en")
                        .put("gl", "US");
                break;
            default:
                return null;
        }

        com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
        context.set("client", clientNode);

        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.set("context", context);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        if ("TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(clientName)) {
            body.put("thirdParty", "https://www.youtube.com");
        }

        String endpoint = "https://www.youtube.com/youtubei/v1/player?key=" + apiKey
                + "&prettyPrint=false";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=" + videoId)
                .header("X-YouTube-Client-Name", getClientId(clientName))
                .header("X-YouTube-Client-Version", getClientVersion(clientName))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body() == null)
            return null;

        return mapper.readTree(response.body());
    }

    private String getClientId(String clientName) {
        switch (clientName) {
            case "WEB_REMIX":
                return "67";
            case "TVHTML5_SIMPLY_EMBEDDED_PLAYER":
                return "85";
            case "MEDIA_CONNECT_FRONTEND":
                return "95";
            default:
                return "1";
        }
    }

    private String getClientVersion(String clientName) {
        switch (clientName) {
            case "WEB_REMIX":
                return "1.20240306.01.00";
            case "TVHTML5_SIMPLY_EMBEDDED_PLAYER":
                return "2.0";
            case "MEDIA_CONNECT_FRONTEND":
                return "0.1";
            default:
                return "2.20240306.01.00";
        }
    }

    private StreamResult pickBestAudioFormat(JsonNode formats) {
        String bestUrl = null;
        int bestBitrate = 0;
        String bestMime = null;

        for (JsonNode fmt : formats) {
            String mimeType = fmt.path("mimeType").asText("");
            if (!mimeType.startsWith("audio/"))
                continue;

            String url = null;
            if (fmt.has("url")) {
                url = fmt.get("url").asText(null);
            } else if (fmt.has("signatureCipher") || fmt.has("cipher")) {
                String cipher = fmt.has("signatureCipher")
                        ? fmt.get("signatureCipher").asText("")
                        : fmt.get("cipher").asText("");
                url = extractUrlFromCipher(cipher);
            }

            if (url == null || url.isEmpty())
                continue;

            int bitrate = fmt.path("bitrate").asInt(0);
            boolean isOpus = mimeType.contains("opus") || mimeType.contains("webm");
            int adjusted = isOpus ? bitrate + 1 : bitrate;

            if (adjusted > bestBitrate) {
                bestBitrate = adjusted;
                bestUrl = url;
                bestMime = mimeType;
            }
        }

        return bestUrl != null ? new StreamResult(bestUrl, bestMime, "youtube-direct", bestBitrate) : null;
    }

    private String extractUrlFromCipher(String cipher) {
        if (cipher == null || cipher.isEmpty())
            return null;

        Map<String, String> params = new HashMap<>();
        for (String param : cipher.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }

        return params.get("url");
    }

    private List<VideoInfo> trySearchPageScrape(String query) {
        try {
            String url = "https://www.youtube.com/results?search_query=" + enc(query)
                    + "&sp=EgIQAQ%253D%253D";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "CONSENT=PENDING+987")
                    .timeout(REQUEST_TIMEOUT)
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;

            Matcher m = INITIAL_DATA_PATTERN.matcher(response.body());
            if (!m.find())
                return null;

            JsonNode data = mapper.readTree(m.group(1));
            JsonNode contents = data.path("contents")
                    .path("twoColumnSearchResultsRenderer")
                    .path("primaryContents")
                    .path("sectionListRenderer")
                    .path("contents");

            if (!contents.isArray())
                return null;

            List<VideoInfo> results = new ArrayList<>();
            for (JsonNode section : contents) {
                JsonNode items = section.path("itemSectionRenderer").path("contents");
                if (!items.isArray())
                    continue;

                for (JsonNode item : items) {
                    JsonNode renderer = item.get("videoRenderer");
                    if (renderer == null)
                        continue;

                    String videoId = renderer.path("videoId").asText(null);
                    if (videoId == null)
                        continue;

                    String title = "";
                    JsonNode titleRuns = renderer.path("title").path("runs");
                    if (titleRuns.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode run : titleRuns)
                            sb.append(run.path("text").asText(""));
                        title = sb.toString();
                    }
                    if (title.isEmpty())
                        title = renderer.path("title").path("simpleText").asText("Unknown");

                    String author = "";
                    JsonNode channelRuns = renderer.path("ownerText").path("runs");
                    if (channelRuns.isArray() && !channelRuns.isEmpty()) {
                        author = channelRuns.get(0).path("text").asText("Unknown");
                    }

                    JsonNode lengthTextNode = renderer.path("lengthText");
                    long durationMs = 0;
                    if (!lengthTextNode.isMissingNode()) {
                        String durationText = lengthTextNode.path("simpleText").asText("");
                        if (durationText.isEmpty())
                            durationText = lengthTextNode.path("accessibility").path("accessibilityData").path("label")
                                    .asText("");
                        durationMs = parseDurationStrict(durationText);
                    }

                    String thumbnail = null;
                    JsonNode thumbs = renderer.path("thumbnail").path("thumbnails");
                    if (thumbs.isArray() && !thumbs.isEmpty()) {
                        thumbnail = thumbs.get(thumbs.size() - 1).path("url").asText(null);
                    }

                    results.add(new VideoInfo(videoId, title, author, durationMs > 0 ? durationMs : Long.MAX_VALUE,
                            thumbnail,
                            "https://www.youtube.com/watch?v=" + videoId));

                    if (results.size() >= 20)
                        break;
                }
                if (results.size() >= 20)
                    break;
            }
            return results.isEmpty() ? null : results;
        } catch (Exception e) {
            return null;
        }
    }

    private List<VideoInfo> tryInnertubeSearch(String query, boolean musicOnly) {
        try {
            String apiKey = musicOnly ? INNERTUBE_MUSIC_KEY : INNERTUBE_API_KEY;
            String clientName = musicOnly ? "WEB_REMIX" : "WEB";
            String clientVersion = musicOnly ? "1.20240306.01.00" : "2.20240306.01.00";

            com.fasterxml.jackson.databind.node.ObjectNode client = mapper.createObjectNode()
                    .put("clientName", clientName)
                    .put("clientVersion", clientVersion)
                    .put("hl", "en")
                    .put("gl", "US");

            com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
            context.set("client", client);

            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.set("context", context);
            body.put("query", query);

            String endpoint = "https://www.youtube.com/youtubei/v1/search?key=" + apiKey
                    + "&prettyPrint=false";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;

            JsonNode json = mapper.readTree(response.body());
            JsonNode contents = json.path("contents")
                    .path("twoColumnSearchResultsRenderer")
                    .path("primaryContents")
                    .path("sectionListRenderer")
                    .path("contents");

            if (musicOnly) {
                contents = json.path("contents")
                        .path("tabbedSearchResultsRenderer")
                        .path("tabs").path(0)
                        .path("tabRenderer")
                        .path("content")
                        .path("sectionListRenderer")
                        .path("contents");
            }

            if (!contents.isArray())
                return null;

            List<VideoInfo> results = new ArrayList<>();
            for (JsonNode section : contents) {
                JsonNode items = section.path("itemSectionRenderer").path("contents");
                if (!items.isArray()) {
                    items = section.path("musicShelfRenderer").path("contents");
                }
                if (!items.isArray())
                    continue;

                for (JsonNode item : items) {
                    JsonNode renderer = item.has("videoRenderer") ? item.get("videoRenderer") : null;
                    if (renderer == null) {
                        renderer = item.path("musicResponsiveListItemRenderer");
                        if (renderer.isMissingNode())
                            continue;

                        String videoId = extractMusicVideoId(renderer);
                        if (videoId == null)
                            continue;

                        String title = extractFlexColumnText(renderer, 0);
                        String author = extractFlexColumnText(renderer, 1);
                        long durationMs = 0;
                        try {
                            String dText = extractFlexColumnText(renderer, renderer.path("flexColumns").size() - 1);
                            if (dText.contains(":")) {
                                durationMs = parseDurationStrict(dText);
                            } else if (renderer.path("flexColumns").size() > 2) {
                                dText = extractFlexColumnText(renderer, renderer.path("flexColumns").size() - 2);
                                if (dText.contains(":"))
                                    durationMs = parseDurationStrict(dText);
                            }
                        } catch (Exception ignored) {
                        }

                        if (title.isEmpty())
                            continue;

                        results.add(new VideoInfo(videoId,
                                title,
                                author.isEmpty() ? "Unknown" : author,
                                durationMs > 0 ? durationMs : Long.MAX_VALUE, null,
                                "https://www.youtube.com/watch?v=" + videoId));
                        continue;
                    }

                    String videoId = renderer.path("videoId").asText(null);
                    if (videoId == null)
                        continue;

                    String title = extractRunsText(renderer.path("title"));
                    String author = "";
                    JsonNode ownerRuns = renderer.path("ownerText").path("runs");
                    if (ownerRuns.isArray() && !ownerRuns.isEmpty()) {
                        author = ownerRuns.get(0).path("text").asText("Unknown");
                    }

                    long durationMs = 0;
                    JsonNode lengthTextNode = renderer.path("lengthText");
                    if (!lengthTextNode.isMissingNode()) {
                        String durationText = lengthTextNode.path("simpleText").asText("");
                        if (durationText.isEmpty())
                            durationText = lengthTextNode.path("accessibility").path("accessibilityData").path("label")
                                    .asText("");
                        durationMs = parseDurationStrict(durationText);
                    }

                    results.add(
                            new VideoInfo(videoId, title, author, durationMs > 0 ? durationMs : Long.MAX_VALUE, null,
                                    "https://www.youtube.com/watch?v=" + videoId));

                    if (results.size() >= 20)
                        break;
                }
                if (results.size() >= 20)
                    break;
            }

            return results.isEmpty() ? null : results;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractMusicVideoId(JsonNode renderer) {
        String videoId = renderer.path("playlistItemData").path("videoId").asText(null);
        if (videoId != null && !videoId.isEmpty())
            return videoId;

        JsonNode playBtn = renderer.path("overlay").path("musicItemThumbnailOverlayRenderer")
                .path("content").path("musicPlayButtonRenderer").path("playNavigationEndpoint");
        if (!playBtn.isMissingNode()) {
            return playBtn.path("watchEndpoint").path("videoId").asText(null);
        }
        return null;
    }

    private String extractFlexColumnText(JsonNode renderer, int columnIndex) {
        try {
            JsonNode runs = renderer.path("flexColumns")
                    .path(columnIndex)
                    .path("musicResponsiveListItemFlexColumnRenderer")
                    .path("text")
                    .path("runs");
            if (runs.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode run : runs) {
                    sb.append(run.path("text").asText(""));
                }
                return sb.toString().trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String extractRunsText(JsonNode node) {
        JsonNode runs = node.path("runs");
        if (runs.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode run : runs)
                sb.append(run.path("text").asText(""));
            return sb.toString().trim();
        }
        return node.path("simpleText").asText("Unknown").trim();
    }

    private long parseDurationStrict(String text) {
        if (text == null || text.trim().isEmpty())
            return 0;
        text = text.trim();

        Matcher m = TIME_PATTERN_1.matcher(text);
        if (m.find()) {
            return (Long.parseLong(m.group(1)) * 3600
                    + Long.parseLong(m.group(2)) * 60
                    + Long.parseLong(m.group(3))) * 1000;
        }

        m = TIME_PATTERN_2.matcher(text);
        if (m.find()) {
            return (Long.parseLong(m.group(1)) * 60
                    + Long.parseLong(m.group(2))) * 1000;
        }

        long totalMs = 0;
        m = DURATION_LABEL_PATTERN.matcher(text.toLowerCase());
        while (m.find()) {
            long val = Long.parseLong(m.group(1));
            String unit = m.group(2);
            if (unit.contains("hour"))
                totalMs += val * 3600 * 1000;
            else if (unit.contains("minute"))
                totalMs += val * 60 * 1000;
            else if (unit.contains("second"))
                totalMs += val * 1000;
        }
        if (totalMs > 0)
            return totalMs;

        return 0;
    }

    private VideoInfo buildVideoInfo(JsonNode videoDetails, String videoId) {
        long durationMs = 0;
        try {
            durationMs = Long.parseLong(videoDetails.path("lengthSeconds").asText("0")) * 1000L;
        } catch (NumberFormatException ignored) {
        }

        return new VideoInfo(
                videoDetails.path("videoId").asText(videoId),
                videoDetails.path("title").asText("Unknown"),
                videoDetails.path("author").asText("Unknown"),
                durationMs > 0 ? durationMs : Long.MAX_VALUE,
                videoDetails.path("thumbnail").path("thumbnails").path(0).path("url").asText(null),
                "https://www.youtube.com/watch?v=" + videoId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static class StreamResult {
        public final String url;
        public final String mimeType;
        public final String source;
        public final int bitrate;

        public StreamResult(String url, String mimeType, String source, int bitrate) {
            this.url = url;
            this.mimeType = mimeType;
            this.source = source;
            this.bitrate = bitrate;
        }
    }

    public static class VideoInfo {
        public final String videoId;
        public final String title;
        public final String author;
        public final long durationMs;
        public final String thumbnail;
        public final String uri;

        public VideoInfo(String videoId, String title, String author,
                long durationMs, String thumbnail, String uri) {
            this.videoId = videoId;
            this.title = title;
            this.author = author;
            this.durationMs = durationMs;
            this.thumbnail = thumbnail;
            this.uri = uri;
        }
    }
}