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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(YouTubeProxyHandler.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    
    private String cachedVisitorData = null;
    private Integer cachedSts = null;
    private String cachedPlayerScriptUrl = null;
    private String cipherUrl = "https://cipher.kikkia.dev";
    private long visitorDataExpires = 0;
    
    private String refreshToken = null;
    private String accessToken = null;
    private long tokenExpiry = 0;

    private static class ClientHealth {
        final InnerTubeClient client;
        int score = 100;
        long lastFailure = 0;

        ClientHealth(InnerTubeClient client) {
            this.client = client;
        }

        void markSuccess() {
            if (score < 100) {
                score = Math.min(100, score + 10);
            }
        }

        void markFailure(int penalty) {
            score = Math.max(0, score - penalty);
            lastFailure = System.currentTimeMillis();
        }
    }

    private final List<ClientHealth> clientPool = new ArrayList<>();

    public YouTubeProxyHandler(String cipherUrl, String refreshToken) {
        if (cipherUrl != null && !cipherUrl.isEmpty()) {
            this.cipherUrl = cipherUrl;
        }
        if (refreshToken != null && !refreshToken.isEmpty() && !refreshToken.equalsIgnoreCase("null")) {
            this.refreshToken = refreshToken;
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.mapper = new ObjectMapper();
        
        clientPool.add(new ClientHealth(new AndroidClient()));
        clientPool.add(new ClientHealth(new IosClient()));
        clientPool.add(new ClientHealth(new TvCastClient()));
        clientPool.add(new ClientHealth(new AndroidVrClient()));
        clientPool.add(new ClientHealth(new WebRemixClient()));
        clientPool.add(new ClientHealth(new TvHtml5Client()));
        clientPool.add(new ClientHealth(new TvEmbeddedClient()));
        clientPool.add(new ClientHealth(new WebClient()));
        clientPool.add(new ClientHealth(new WebEmbeddedClient()));
    }

    public StreamResult getStream(String videoId) {
        StreamResult res = tryInnertubeClients(videoId);
        if (res == null) {
            String counterpart = getCounterpartVideoId(videoId);
            if (counterpart != null && !counterpart.equals(videoId)) {
                res = tryInnertubeClients(counterpart);
            }
        }
        return res;
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
            if (response.statusCode() != 200 || response.body() == null) {
                return null;
            }

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
        } catch (Exception ignored) {
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

    private StreamResult tryInnertubeClients(String videoId) {
        List<ClientHealth> candidates = new ArrayList<>(clientPool);
        candidates.sort((a, b) -> {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            return Long.compare(a.lastFailure, b.lastFailure);
        });

        for (ClientHealth candidate : candidates) {
            InnerTubeClient client = candidate.client;
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, client);
                if (json == null) {
                    candidate.markFailure(10);
                    continue;
                }

                JsonNode playability = json.get("playabilityStatus");
                if (playability != null) {
                    String status = playability.path("status").asText("");
                    if (!"OK".equals(status)) {
                        candidate.markFailure(status.contains("LOGIN_REQUIRED") ? 30 : 20);
                        continue;
                    }
                }

                JsonNode streamingData = json.get("streamingData");
                if (streamingData == null) {
                    candidate.markFailure(15);
                    continue;
                }

                JsonNode formats = streamingData.get("adaptiveFormats");
                if (formats == null || !formats.isArray()) {
                    candidate.markFailure(15);
                    continue;
                }

                StreamResult result = pickBestAudioFormat(formats);
                if (result != null) {
                    candidate.markSuccess();
                    return new StreamResult(result.url, result.mimeType, result.source, result.bitrate, client.getUserAgent());
                } else {
                    candidate.markFailure(5);
                }
            } catch (Exception ignored) {
                candidate.markFailure(25);
            }
        }
        return null;
    }

    private VideoInfo tryInnertubeVideoInfo(String videoId) {
        List<ClientHealth> candidates = new ArrayList<>(clientPool);
        candidates.sort((a, b) -> {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            return Long.compare(a.lastFailure, b.lastFailure);
        });

        for (ClientHealth candidate : candidates) {
            InnerTubeClient client = candidate.client;
            try {
                JsonNode json = fetchInnertubePlayerResponse(videoId, client);
                if (json != null) {
                    JsonNode videoDetails = json.get("videoDetails");
                    if (videoDetails != null) {
                        candidate.markSuccess();
                        return buildVideoInfo(videoDetails, videoId);
                    }
                    candidate.markFailure(10);
                } else {
                    candidate.markFailure(10);
                }
            } catch (Exception ignored) {
                candidate.markFailure(25);
            }
        }
        return null;
    }

    private synchronized void refreshTokens(String videoId) {
        if (cachedVisitorData != null && cachedSts != null && System.currentTimeMillis() < visitorDataExpires) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/watch?v=" + (videoId != null ? videoId : "dQw4w9WgXcQ")))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Cookie", "YSC=cz5kYp3ZuIE; VISITOR_INFO1_LIVE=U-0T5oUyzf8;")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Matcher m = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(response.body());
                if (m.find()) {
                    cachedVisitorData = m.group(1);
                    visitorDataExpires = System.currentTimeMillis() + 3600000;
                }
                
                Matcher scriptMatch = Pattern.compile("\"jsUrl\":\"([^\"]+)\"").matcher(response.body());
                if (scriptMatch.find()) {
                    cachedPlayerScriptUrl = "https://www.youtube.com" + scriptMatch.group(1);
                    
                    try {
                        HttpRequest scriptReq = HttpRequest.newBuilder()
                                .uri(URI.create(cachedPlayerScriptUrl))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .GET().build();
                        HttpResponse<String> scriptRes = httpClient.send(scriptReq, HttpResponse.BodyHandlers.ofString());
                        if (scriptRes.statusCode() == 200) {
                            Matcher stsMatch = Pattern.compile("(?:signatureTimestamp|sts):(\\d+)").matcher(scriptRes.body());
                            if (stsMatch.find()) {
                                cachedSts = Integer.parseInt(stsMatch.group(1));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch player script for STS", e);
                    }
                }
                
                if (cachedVisitorData != null && cachedSts != null && cachedPlayerScriptUrl != null) {
                    return;
                }
            } else {
                log.warn("Failed to fetch watch page for tokens, HTTP {}", response.statusCode());
            }
            
            if (cachedSts == null) {
                log.warn("Could not parse STS from player script, defaulting to 19889");
                cachedSts = 19889;
            }
            
            com.fasterxml.jackson.databind.node.ObjectNode clientNode = mapper.createObjectNode();
            clientNode.put("clientName", "WEB");
            clientNode.put("clientVersion", "2.20240105.01.00");
            com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
            context.set("client", clientNode);
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.set("context", context);
            
            HttpRequest guideReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/youtubei/v1/guide?key=" + new WebClient().getApiKey()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> guideRes = httpClient.send(guideReq, HttpResponse.BodyHandlers.ofString());
            if (guideRes.statusCode() == 200) {
                JsonNode guideJson = mapper.readTree(guideRes.body());
                JsonNode vd = guideJson.path("responseContext").path("visitorData");
                if (!vd.isMissingNode()) {
                    cachedVisitorData = vd.asText();
                    visitorDataExpires = System.currentTimeMillis() + 3600000;
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void refreshOAuthToken() {
        if (refreshToken == null || refreshToken.isEmpty()) return;
        if (accessToken != null && System.currentTimeMillis() < tokenExpiry) return;

        try {
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("client_id", "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com");
            body.put("client_secret", "SboVhoG9s0rNafixCSGGKXAT");
            body.put("refresh_token", refreshToken);
            body.put("grant_type", "refresh_token");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/o/oauth2/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode json = mapper.readTree(res.body());
                if (json.has("access_token") && json.has("expires_in")) {
                    accessToken = json.get("access_token").asText();
                    long expiresIn = json.get("expires_in").asLong();
                    tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000) - 30000;
                }
            } else {
                log.warn("Failed to refresh OAuth token, status: {}", res.statusCode());
            }
        } catch (Exception e) {
            log.error("Error refreshing OAuth token: ", e);
        }
    }

    private JsonNode fetchInnertubePlayerResponse(String videoId, InnerTubeClient client) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode clientNode = mapper.createObjectNode();
        client.populateClientContext(clientNode);

        refreshTokens(videoId);
        refreshOAuthToken();
        if (cachedVisitorData != null) {
            clientNode.put("visitorData", cachedVisitorData);
        }

        com.fasterxml.jackson.databind.node.ObjectNode context = mapper.createObjectNode();
        context.set("client", clientNode);

        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.set("context", context);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        if (client.getPlayerParams() != null) {
            body.put("params", client.getPlayerParams());
        }

        if (cachedSts != null) {
            com.fasterxml.jackson.databind.node.ObjectNode playbackContext = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode contentPlaybackContext = mapper.createObjectNode();
            contentPlaybackContext.put("signatureTimestamp", cachedSts);
            playbackContext.set("contentPlaybackContext", contentPlaybackContext);
            body.set("playbackContext", playbackContext);
        }

        if (client.isEmbedded()) {
        }

        if ("TVHTML5".equals(client.getClientName()) || "TVHTML5_CAST".equals(client.getClientName())) {
            body.put("thirdParty", "https://www.youtube.com");
        }

        String endpoint = client.getEndpointDomain() + "/youtubei/v1/player?key=" + client.getApiKey()
                + "&prettyPrint=false";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(endpoint))
                .header("User-Agent", client.getUserAgent()).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-YouTube-Client-Name", client.getClientId())
                .header("X-YouTube-Client-Version", client.getClientVersion()).timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        if (cachedVisitorData != null) {
            reqBuilder.header("X-Goog-Visitor-Id", cachedVisitorData);
        }
        
        if (accessToken != null && ("TVHTML5".equals(client.getClientName()) || "TVHTML5_CAST".equals(client.getClientName()))) {
            reqBuilder.header("Authorization", "Bearer " + accessToken);
        }
        
        if (client.getClientName().equals("ANDROID")) {
            reqBuilder.header("X-Goog-Api-Format-Version", "2");
        }

        if (client.isEmbedded()) {
            reqBuilder.header("Referer", "https://www.youtube.com");
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        if (response.body() == null) {
            return null;
        }

        return mapper.readTree(response.body());
    }

    private String resolveUrlParams(String url, String s, String sp) {
        if (url == null) return url;
        try {
            String nParam = null;
            Matcher nMatch = Pattern.compile("[?&]n=([^&]+)").matcher(url);
            if (nMatch.find()) {
                nParam = nMatch.group(1);
            }

            if (s == null && nParam == null) {
                return url;
            }

            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("stream_url", url);
            if (cachedPlayerScriptUrl != null) {
                body.put("player_url", cachedPlayerScriptUrl);
            }
            if (s != null) {
                body.put("encrypted_signature", s);
                body.put("signature_key", sp != null ? sp : "sig");
            }
            if (nParam != null) {
                body.put("n_param", nParam);
            }

            String endpoint = this.cipherUrl.endsWith("/") ? this.cipherUrl + "api/resolve_url" : this.cipherUrl + "/api/resolve_url";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "SlugYZeon/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode resJson = mapper.readTree(response.body());
                if (resJson.has("resolved_url")) {
                    return resJson.get("resolved_url").asText();
                }
            }
        } catch (Exception ignored) {}
        return url;
    }

    private String resolveCipher(String cipherStr) {
        try {
            String url = null;
            String s = null;
            String sp = "sig";
            for (String part : cipherStr.split("&")) {
                if (part.startsWith("url=")) url = java.net.URLDecoder.decode(part.substring(4), "UTF-8");
                else if (part.startsWith("s=")) s = java.net.URLDecoder.decode(part.substring(2), "UTF-8");
                else if (part.startsWith("sp=")) sp = java.net.URLDecoder.decode(part.substring(3), "UTF-8");
            }
            if (url != null) {
                return resolveUrlParams(url, s, sp);
            }
        } catch (Exception ignored) {}
        return null;
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
            if (fmt.has("signatureCipher") || fmt.has("cipher")) {
                String cipherStr = fmt.has("signatureCipher") ? fmt.path("signatureCipher").asText() : fmt.path("cipher").asText();
                url = resolveCipher(cipherStr);
            } else if (fmt.has("url")) {
                url = resolveUrlParams(fmt.get("url").asText(null), null, null);
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