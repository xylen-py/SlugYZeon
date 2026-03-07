package com.slugyzeon.plugin.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class YouTubeProxyHandler {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private static final String[] DEFAULT_INVIDIOUS = {
            "https://iv.ggtyler.dev",
            "https://invidious.projectsegfau.lt",
            "https://inv.nadeko.net",
            "https://invidious.privacyredirect.com",
            "https://vid.puffyan.us"
    };

    private static final String[] DEFAULT_PIPED = {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.in.projectsegfau.lt",
            "https://pipedapi.lunar.icu"
    };

    private static final long INSTANCE_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final int MAX_RETRIES_PER_INSTANCE = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final List<String> invidiousInstances;
    private final List<String> pipedInstances;
    private final AtomicInteger invidiousIndex = new AtomicInteger(0);
    private final AtomicInteger pipedIndex = new AtomicInteger(0);

    private final ConcurrentHashMap<String, Long> deadInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failCounts = new ConcurrentHashMap<>();

    public YouTubeProxyHandler(List<String> invidiousInstances, List<String> pipedInstances) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
        this.invidiousInstances = (invidiousInstances != null && !invidiousInstances.isEmpty())
                ? new ArrayList<>(invidiousInstances)
                : new ArrayList<>(Arrays.asList(DEFAULT_INVIDIOUS));
        this.pipedInstances = (pipedInstances != null && !pipedInstances.isEmpty())
                ? new ArrayList<>(pipedInstances)
                : new ArrayList<>(Arrays.asList(DEFAULT_PIPED));
    }

    public StreamResult getStream(String videoId) {
        StreamResult result = getStreamFromInvidious(videoId);
        if (result != null)
            return result;

        result = getStreamFromPiped(videoId);
        if (result != null)
            return result;

        return null;
    }

    public VideoInfo getVideoInfo(String videoId) {
        VideoInfo info = getVideoInfoFromInvidious(videoId);
        if (info != null)
            return info;

        info = getVideoInfoFromPiped(videoId);
        if (info != null)
            return info;

        return null;
    }

    public List<VideoInfo> search(String query, boolean musicOnly) {
        List<VideoInfo> results = searchInvidious(query);
        if (results != null && !results.isEmpty())
            return results;

        results = searchPiped(query);
        if (results != null && !results.isEmpty())
            return results;

        return Collections.emptyList();
    }

    public int getAliveInstanceCount() {
        cleanupDeadInstances();
        int alive = 0;
        for (String inst : invidiousInstances) {
            if (!isInstanceDead(inst))
                alive++;
        }
        for (String inst : pipedInstances) {
            if (!isInstanceDead(inst))
                alive++;
        }
        return alive;
    }

    private StreamResult getStreamFromInvidious(String videoId) {
        for (int attempt = 0; attempt < invidiousInstances.size(); attempt++) {
            String instance = getNextAliveInvidious();
            if (instance == null)
                break;

            try {
                JsonNode json = fetchJson(instance + "/api/v1/videos/" + videoId
                        + "?fields=adaptiveFormats,title,author,lengthSeconds,videoThumbnails");
                if (json == null) {
                    markFailure(instance);
                    continue;
                }

                JsonNode formats = json.get("adaptiveFormats");
                if (formats == null || !formats.isArray()) {
                    markFailure(instance);
                    continue;
                }

                StreamResult best = pickBestAudioStream(formats, instance, "url", "type", "bitrate");
                if (best != null) {
                    markSuccess(instance);
                    return best;
                }

                markFailure(instance);
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private StreamResult getStreamFromPiped(String videoId) {
        for (int attempt = 0; attempt < pipedInstances.size(); attempt++) {
            String instance = getNextAlivePiped();
            if (instance == null)
                break;

            try {
                JsonNode json = fetchJson(instance + "/streams/" + videoId);
                if (json == null) {
                    markFailure(instance);
                    continue;
                }

                JsonNode audioStreams = json.get("audioStreams");
                if (audioStreams == null || !audioStreams.isArray()) {
                    markFailure(instance);
                    continue;
                }

                StreamResult best = pickBestAudioStream(audioStreams, instance, "url", "mimeType", "bitrate");
                if (best != null) {
                    markSuccess(instance);
                    return best;
                }

                markFailure(instance);
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private VideoInfo getVideoInfoFromInvidious(String videoId) {
        for (int attempt = 0; attempt < invidiousInstances.size(); attempt++) {
            String instance = getNextAliveInvidious();
            if (instance == null)
                break;

            try {
                JsonNode json = fetchJson(instance + "/api/v1/videos/" + videoId
                        + "?fields=title,author,lengthSeconds,videoThumbnails,videoId");
                if (json == null) {
                    markFailure(instance);
                    continue;
                }

                String title = json.path("title").asText(null);
                if (title == null) {
                    markFailure(instance);
                    continue;
                }

                String thumbnail = extractBestThumbnail(json.get("videoThumbnails"));

                markSuccess(instance);
                return new VideoInfo(
                        json.path("videoId").asText(videoId),
                        title,
                        json.path("author").asText("Unknown"),
                        json.path("lengthSeconds").asLong(0) * 1000L,
                        thumbnail,
                        "https://www.youtube.com/watch?v=" + videoId);
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private VideoInfo getVideoInfoFromPiped(String videoId) {
        for (int attempt = 0; attempt < pipedInstances.size(); attempt++) {
            String instance = getNextAlivePiped();
            if (instance == null)
                break;

            try {
                JsonNode json = fetchJson(instance + "/streams/" + videoId);
                if (json == null) {
                    markFailure(instance);
                    continue;
                }

                String title = json.path("title").asText(null);
                if (title == null) {
                    markFailure(instance);
                    continue;
                }

                markSuccess(instance);
                return new VideoInfo(
                        videoId,
                        title,
                        json.path("uploader").asText("Unknown"),
                        json.path("duration").asLong(0) * 1000L,
                        json.path("thumbnailUrl").asText(null),
                        "https://www.youtube.com/watch?v=" + videoId);
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private List<VideoInfo> searchInvidious(String query) {
        for (int attempt = 0; attempt < invidiousInstances.size(); attempt++) {
            String instance = getNextAliveInvidious();
            if (instance == null)
                break;

            try {
                String url = instance + "/api/v1/search?q=" + enc(query) + "&type=video&sort_by=relevance";
                JsonNode json = fetchJson(url);
                if (json == null || !json.isArray() || json.isEmpty()) {
                    markFailure(instance);
                    continue;
                }

                List<VideoInfo> results = new ArrayList<>();
                for (JsonNode item : json) {
                    if (!"video".equals(item.path("type").asText("")))
                        continue;

                    String videoId = item.path("videoId").asText(null);
                    String title = item.path("title").asText(null);
                    if (videoId == null || title == null)
                        continue;

                    String thumbnail = null;
                    JsonNode thumbs = item.get("videoThumbnails");
                    if (thumbs != null && thumbs.isArray() && !thumbs.isEmpty()) {
                        thumbnail = thumbs.get(0).path("url").asText(null);
                    }

                    results.add(new VideoInfo(videoId, title,
                            item.path("author").asText("Unknown"),
                            item.path("lengthSeconds").asLong(0) * 1000L,
                            thumbnail,
                            "https://www.youtube.com/watch?v=" + videoId));

                    if (results.size() >= 20)
                        break;
                }

                if (!results.isEmpty()) {
                    markSuccess(instance);
                    return results;
                }
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private List<VideoInfo> searchPiped(String query) {
        for (int attempt = 0; attempt < pipedInstances.size(); attempt++) {
            String instance = getNextAlivePiped();
            if (instance == null)
                break;

            try {
                String url = instance + "/search?q=" + enc(query) + "&filter=videos";
                JsonNode json = fetchJson(url);
                if (json == null) {
                    markFailure(instance);
                    continue;
                }

                JsonNode items = json.get("items");
                if (items == null || !items.isArray() || items.isEmpty()) {
                    markFailure(instance);
                    continue;
                }

                List<VideoInfo> results = new ArrayList<>();
                for (JsonNode item : items) {
                    String itemUrl = item.path("url").asText("");
                    if (!itemUrl.startsWith("/watch?v="))
                        continue;

                    String videoId = itemUrl.substring("/watch?v=".length());
                    String title = item.path("title").asText(null);
                    if (title == null)
                        continue;

                    results.add(new VideoInfo(videoId, title,
                            item.path("uploaderName").asText("Unknown"),
                            item.path("duration").asLong(0) * 1000L,
                            item.path("thumbnail").asText(null),
                            "https://www.youtube.com/watch?v=" + videoId));

                    if (results.size() >= 20)
                        break;
                }

                if (!results.isEmpty()) {
                    markSuccess(instance);
                    return results;
                }
            } catch (Exception e) {
                markFailure(instance);
            }
        }
        return null;
    }

    private boolean isInstanceDead(String instance) {
        Long deadSince = deadInstances.get(instance);
        if (deadSince == null)
            return false;

        if (System.currentTimeMillis() - deadSince > INSTANCE_COOLDOWN_MS) {
            deadInstances.remove(instance);
            failCounts.remove(instance);
            return false;
        }
        return true;
    }

    private void markFailure(String instance) {
        AtomicInteger count = failCounts.computeIfAbsent(instance, k -> new AtomicInteger(0));
        if (count.incrementAndGet() >= MAX_RETRIES_PER_INSTANCE) {
            deadInstances.put(instance, System.currentTimeMillis());
        }
    }

    private void markSuccess(String instance) {
        failCounts.remove(instance);
        deadInstances.remove(instance);
    }

    private void cleanupDeadInstances() {
        long now = System.currentTimeMillis();
        deadInstances.entrySet().removeIf(e -> now - e.getValue() > INSTANCE_COOLDOWN_MS);
    }

    private String getNextAliveInvidious() {
        int size = invidiousInstances.size();
        for (int i = 0; i < size; i++) {
            int idx = invidiousIndex.getAndUpdate(v -> (v + 1) % size);
            String instance = invidiousInstances.get(idx);
            if (!isInstanceDead(instance))
                return instance;
        }
        cleanupDeadInstances();
        for (int i = 0; i < size; i++) {
            int idx = invidiousIndex.getAndUpdate(v -> (v + 1) % size);
            String instance = invidiousInstances.get(idx);
            if (!isInstanceDead(instance))
                return instance;
        }
        return size > 0 ? invidiousInstances.get(0) : null;
    }

    private String getNextAlivePiped() {
        int size = pipedInstances.size();
        for (int i = 0; i < size; i++) {
            int idx = pipedIndex.getAndUpdate(v -> (v + 1) % size);
            String instance = pipedInstances.get(idx);
            if (!isInstanceDead(instance))
                return instance;
        }
        cleanupDeadInstances();
        for (int i = 0; i < size; i++) {
            int idx = pipedIndex.getAndUpdate(v -> (v + 1) % size);
            String instance = pipedInstances.get(idx);
            if (!isInstanceDead(instance))
                return instance;
        }
        return size > 0 ? pipedInstances.get(0) : null;
    }

    private StreamResult pickBestAudioStream(JsonNode streams, String source,
            String urlField, String typeField, String bitrateField) {
        String bestUrl = null;
        int bestBitrate = 0;
        String bestMime = null;

        for (JsonNode fmt : streams) {
            String type = fmt.path(typeField).asText("");
            if (!type.startsWith("audio/") && !type.contains("audio"))
                continue;

            String url = fmt.path(urlField).asText(null);
            if (url == null || url.isEmpty())
                continue;

            int bitrate = fmt.path(bitrateField).asInt(0);
            boolean isOpus = type.contains("opus") || type.contains("webm");
            int adjustedBitrate = isOpus ? bitrate + 1 : bitrate;

            if (adjustedBitrate > bestBitrate) {
                bestBitrate = adjustedBitrate;
                bestUrl = url;
                bestMime = type;
            }
        }

        if (bestUrl != null) {
            return new StreamResult(bestUrl, bestMime, source, bestBitrate);
        }
        return null;
    }

    private String extractBestThumbnail(JsonNode thumbs) {
        if (thumbs == null || !thumbs.isArray() || thumbs.isEmpty())
            return null;

        String fallback = thumbs.get(0).path("url").asText(null);
        for (JsonNode t : thumbs) {
            String quality = t.path("quality").asText("");
            if ("maxresdefault".equals(quality) || "sddefault".equals(quality)
                    || "high".equals(quality) || "hqdefault".equals(quality)) {
                return t.path("url").asText(fallback);
            }
        }
        return fallback;
    }

    private JsonNode fetchJson(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null)
                return null;
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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