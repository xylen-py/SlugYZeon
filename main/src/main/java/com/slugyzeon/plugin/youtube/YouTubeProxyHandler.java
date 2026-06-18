package com.slugyzeon.plugin.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slugyzeon.plugin.youtube.clients.*;

import java.net.URI;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeProxyHandler {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public YouTubeProxyHandler() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.mapper = new ObjectMapper();
    }

    public StreamResult getStream(String videoId) {
        return tryInnertubeClients(videoId);
    }

    public VideoInfo getVideoInfo(String videoId) {
        VideoInfo innertubeInfo = tryInnertubeVideoInfo(videoId);
        if (innertubeInfo != null) {
            return innertubeInfo;
        }

        return null;
    }

    public String getCounterpartVideoId(String videoId) {
        try {
            InnerTubeClient tubeClient = new WebRemixClient();
            com.fasterxml.jackson.databind.node.ObjectNode client = mapper.createObjectNode();
            tubeClient.populateClientContext(client);

            com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
            context.set("client", client);

            com.fasterxml.jackson.databind.node.ObjectNode config = mapper.createObjectNode();
            config.put("hasPersistentPlaylistPanel", true);
            config.put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV");

            com.fasterxml.jackson.databind.node.ObjectNode supportedConfigs = mapper.createObjectNode();
            supportedConfigs.set("watchEndpointMusicConfig", config);

            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.set("context", context);
            body.put("videoId", videoId);
            body.put("enablePersistentPlaylistPanel", true);
            body.put("isAudioOnly", true);
            body.put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
            body.set("watchEndpointMusicSupportedConfigs", supportedConfigs);

            String endpoint = tubeClient.getEndpointDomain() + "/youtubei/v1/next?key=" + tubeClient.getApiKey()
                    + "&prettyPrint=false";

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .header("User-Agent", tubeClient.getUserAgent())
                    .header("X-YouTube-Client-Name", tubeClient.getClientId())
                    .header("X-YouTube-Client-Version", tubeClient.getClientVersion())
                    .header("Content-Type", "application/json").timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;

            JsonNode json = mapper.readTree(response.body());
            JsonNode contents = json.path("contents").path("singleColumnMusicWatchNextResultsRenderer")
                    .path("tabbedRenderer").path("watchNextTabbedResultsRenderer").path("tabs").path(0)
                    .path("tabRenderer").path("content").path("musicQueueRenderer").path("content")
                    .path("playlistPanelRenderer").path("contents");

            if (contents.isArray() && !contents.isEmpty()) {
                JsonNode firstItem = contents.get(0);
                JsonNode wrapper = firstItem.path("playlistPanelVideoWrapperRenderer");
                if (!wrapper.isMissingNode()) {
                    JsonNode counterparts = wrapper.path("counterpart");
                    if (counterparts.isArray() && !counterparts.isEmpty()) {
                        String counterpartId = counterparts.get(0).path("counterpartRenderer")
                                .path("playlistPanelVideoRenderer").path("videoId").asText(null);

                        if (counterpartId != null && !counterpartId.equals(videoId)) {
                            return counterpartId;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    public List<VideoInfo> search(String query, boolean musicOnly) {
        List<VideoInfo> results = tryInnertubeSearch(query, musicOnly);
        if (results != null && !results.isEmpty()) {
            return results;
        }

        return Collections.emptyList();
    }

    private List<InnerTubeClient> getPlaybackClients() {
        return Arrays.asList(
                new WebRemixClient(),
                new IosClient(),
                new AndroidClient(),
                new AndroidVrClient(),
                new TvHtml5Client(),
                new TvEmbeddedClient(),
                new WebClient(),
                new WebEmbeddedClient());
    }

    private StreamResult tryInnertubeClients(String videoId) {
        for (InnerTubeClient client : getPlaybackClients()) {
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, client);
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
                if (result != null) {
                    return new StreamResult(result.url, result.mimeType, result.source, result.bitrate, client.getUserAgent());
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private VideoInfo tryInnertubeVideoInfo(String videoId) {
        for (InnerTubeClient client : getPlaybackClients()) {
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, client);
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

    private JsonNode fetchInnertubePlayerResponse(String videoId, InnerTubeClient client) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode clientNode = mapper.createObjectNode();
        client.populateClientContext(clientNode);

        com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
        context.set("client", clientNode);

        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.set("context", context);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        if ("TVHTML5".equals(client.getClientName())) {
            body.put("thirdParty", "https://www.youtube.com");
        }

        String endpoint = client.getEndpointDomain() + "/youtubei/v1/player?key=" + client.getApiKey()
                + "&prettyPrint=false";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                .header("User-Agent", client.getUserAgent()).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=" + videoId)
                .header("X-YouTube-Client-Name", client.getClientId())
                .header("X-YouTube-Client-Version", client.getClientVersion()).timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body() == null)
            return null;

        return mapper.readTree(response.body());
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
                continue;
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

        return bestUrl != null ? new StreamResult(bestUrl, bestMime, "youtube-direct", bestBitrate, null) : null;
    }

    private List<VideoInfo> tryInnertubeSearch(String query, boolean musicOnly) {
        try {
            InnerTubeClient tubeClient = musicOnly ? new WebRemixClient() : new WebClient();

            com.fasterxml.jackson.databind.node.ObjectNode clientNode = mapper.createObjectNode();
            tubeClient.populateClientContext(clientNode);

            com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
            context.set("client", clientNode);

            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.set("context", context);
            body.put("query", query);

            String endpoint = tubeClient.getEndpointDomain() + "/youtubei/v1/search?key=" + tubeClient.getApiKey()
                    + "&prettyPrint=false";

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .header("User-Agent", tubeClient.getUserAgent())
                    .header("X-YouTube-Client-Name", tubeClient.getClientId())
                    .header("X-YouTube-Client-Version", tubeClient.getClientVersion())
                    .header("Content-Type", "application/json").timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;

            JsonNode json = mapper.readTree(response.body());
            JsonNode contents = json.path("contents").path("twoColumnSearchResultsRenderer").path("primaryContents")
                    .path("sectionListRenderer").path("contents");

            if (musicOnly) {
                contents = json.path("contents").path("tabbedSearchResultsRenderer").path("tabs").path(0)
                        .path("tabRenderer").path("content").path("sectionListRenderer").path("contents");
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
                        JsonNode flexColumns = renderer.path("flexColumns");
                        if (flexColumns.isArray()) {
                            for (JsonNode col : flexColumns) {
                                StringBuilder colText = new StringBuilder();
                                JsonNode runs = col.path("musicResponsiveListItemFlexColumnRenderer").path("text")
                                        .path("runs");
                                if (runs.isArray()) {
                                    for (JsonNode run : runs) {
                                        colText.append(run.path("text").asText(""));
                                    }
                                }
                                long parsed = parseDurationStrict(colText.toString());
                                if (parsed > 0) {
                                    durationMs = parsed;
                                    break;
                                }
                            }
                        }

                        if (title.isEmpty())
                            continue;

                        results.add(new VideoInfo(videoId, title, author.isEmpty() ? "Unknown" : author,
                                durationMs > 0 ? durationMs : Long.MAX_VALUE, extractThumbnail(renderer, videoId),
                                "https://www.youtube.com/watch?v=" + videoId, durationMs == 0, null));
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

                    results.add(new VideoInfo(videoId, title, author, durationMs > 0 ? durationMs : Long.MAX_VALUE,
                            extractThumbnail(renderer, videoId), "https://www.youtube.com/watch?v=" + videoId,
                            durationMs == 0, null));

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

        JsonNode playBtn = renderer.path("overlay").path("musicItemThumbnailOverlayRenderer").path("content")
                .path("musicPlayButtonRenderer").path("playNavigationEndpoint");
        if (!playBtn.isMissingNode()) {
            return playBtn.path("watchEndpoint").path("videoId").asText(null);
        }
        return null;
    }

    private String extractFlexColumnText(JsonNode renderer, int columnIndex) {
        try {
            JsonNode runs = renderer.path("flexColumns").path(columnIndex)
                    .path("musicResponsiveListItemFlexColumnRenderer").path("text").path("runs");
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

    private String extractThumbnail(JsonNode renderer, String videoId) {
        String url = renderer.path("thumbnail").path("thumbnails").path(0).path("url").asText(null);
        if (url == null) {
            url = renderer.path("thumbnail").path("musicThumbnailRenderer").path("thumbnail").path("thumbnails").path(0)
                    .path("url").asText(null);
        }
        if (url == null && videoId != null) {
            url = "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg";
        }
        return url;
    }

    private long parseDurationStrict(String text) {
        if (text == null || text.trim().isEmpty())
            return 0;

        Matcher m = Pattern.compile("([^0-9]|^)(?:(\\d+):)?(\\d{1,2}):(\\d{2})([^0-9]|$)").matcher(text);
        if (m.find()) {
            long hours = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
            long mins = Long.parseLong(m.group(3));
            long secs = Long.parseLong(m.group(4));
            return (hours * 3600 + mins * 60 + secs) * 1000;
        }

        long totalMs = 0;
        m = Pattern.compile("(\\d+)\\s*(hour|minute|second)s?").matcher(text.toLowerCase());
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
        return totalMs;
    }

    private VideoInfo buildVideoInfo(JsonNode videoDetails, String videoId) {
        long durationMs = 0;
        try {
            durationMs = Long.parseLong(videoDetails.path("lengthSeconds").asText("0")) * 1000L;
        } catch (NumberFormatException ignored) {
        }

        return new VideoInfo(videoDetails.path("videoId").asText(videoId), videoDetails.path("title").asText("Unknown"),
                videoDetails.path("author").asText("Unknown"), durationMs > 0 ? durationMs : Long.MAX_VALUE,
                extractThumbnail(videoDetails, videoId),
                "https://www.youtube.com/watch?v=" + videoId,
                videoDetails.path("isLiveContent").asBoolean(durationMs == 0), null);
    }

    public static class StreamResult {
        public final String url;
        public final String mimeType;
        public final String source;
        public final int bitrate;
        public final String userAgent;

        public StreamResult(String url, String mimeType, String source, int bitrate, String userAgent) {
            this.url = url;
            this.mimeType = mimeType;
            this.source = source;
            this.bitrate = bitrate;
            this.userAgent = userAgent;
        }
    }

    public static class VideoInfo {
        public final String videoId;
        public final String title;
        public final String author;
        public final long durationMs;
        public final String thumbnail;
        public final String uri;
        public final boolean isStream;
        public final String isrc;

        public VideoInfo(String videoId, String title, String author, long durationMs, String thumbnail, String uri,
                boolean isStream, String isrc) {
            this.videoId = videoId;
            this.title = title;
            this.author = author;
            this.durationMs = durationMs;
            this.thumbnail = thumbnail;
            this.uri = uri;
            this.isStream = isStream;
            this.isrc = isrc;
        }
    }
}