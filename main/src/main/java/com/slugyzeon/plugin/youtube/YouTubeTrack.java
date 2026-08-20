package com.slugyzeon.plugin.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTrack.class);
    private static final String UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip";
    private final YouTubeSourceManager sourceManager;
    private final String videoId;
    private final AudioTrack originalTrack;
    private final String cdnStreamUrl;

    public YouTubeTrack(AudioTrackInfo trackInfo, String videoId, AudioTrack originalTrack,
            YouTubeSourceManager sourceManager) {
        this(trackInfo, videoId, originalTrack, sourceManager, null);
    }

    public YouTubeTrack(AudioTrackInfo trackInfo, String videoId, AudioTrack originalTrack,
            YouTubeSourceManager sourceManager, String cdnStreamUrl) {
        super(trackInfo);
        this.videoId = videoId;
        this.originalTrack = originalTrack;
        this.sourceManager = sourceManager;
        this.cdnStreamUrl = cdnStreamUrl;
    }

    public AudioTrack getOriginalTrack() {
        return originalTrack;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String streamUrl = cdnStreamUrl;
        if (streamUrl == null && originalTrack != null) {
            streamUrl = sourceManager.checkCdnStreamUrl(videoId);
        }

        if (streamUrl != null) {
            try {
                log.info("Playing track {} via SlugYZeon-YTCDN", videoId);
                playCdnStreamFromUrl(streamUrl, executor);
                return;
            } catch (Exception e) {
                log.warn("CDN playback failed for {}, falling back to YouTube", videoId, e);
            }
        }

        InternalAudioTrack fallback = null;
        if (originalTrack instanceof InternalAudioTrack) {
            fallback = (InternalAudioTrack) originalTrack;
        } else {
            fallback = reloadFromYouTube();
        }

        if (fallback != null) {
            if (!trackInfo.isStream && trackInfo.length <= 720000L) {
                triggerBackgroundUpload();
            }
            processDelegate(fallback, executor);
            return;
        }

        throw new FriendlyException(
                "Cannot play YouTube track (no source available)",
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    private InternalAudioTrack reloadFromYouTube() {
        try {
            AudioSourceManager ytSource = sourceManager.getOriginalYouTubeSource();
            if (ytSource == null) return null;

            AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
            AudioItem item = ytSource.loadItem(manager, new AudioReference(videoId, null));
            if (item instanceof AudioTrack && item instanceof InternalAudioTrack) {
                log.info("Reloaded track {} from YouTube for fallback playback", videoId);
                return (InternalAudioTrack) item;
            }
        } catch (Exception e) {
            log.warn("Failed to reload track {} from YouTube", videoId, e);
        }
        return null;
    }

    private void playCdnStreamFromUrl(String streamUrl, LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpSourceManager().getHttpInterface()) {
            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamUrl), Long.MAX_VALUE)) {
                int statusCode = stream.checkStatusCode();
                if (statusCode != 200 && statusCode != 206) {
                    throw new FriendlyException(
                        "CDN returned HTTP " + statusCode + " for " + videoId,
                        FriendlyException.Severity.SUSPICIOUS, null);
                }

                MediaContainerDetectionResult result = new MediaContainerDetection(
                    MediaContainerRegistry.DEFAULT_REGISTRY,
                    new AudioReference(streamUrl, null),
                    stream,
                    MediaContainerHints.from(null, null)
                ).detectContainer();

                if (result == null || !result.isContainerDetected() || result.isReference()) {
                    throw new FriendlyException(
                        "Could not detect audio format from CDN for " + videoId,
                        FriendlyException.Severity.SUSPICIOUS, null);
                }

                InternalAudioTrack internalTrack = (InternalAudioTrack) result.getContainerDescriptor()
                    .createTrack(trackInfo, stream);

                processDelegate(internalTrack, executor);
            }
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new YouTubeTrack(trackInfo, videoId, originalTrack, sourceManager, cdnStreamUrl);
    }

    private void triggerBackgroundUpload() {
        CompletableFuture.runAsync(() -> {
            try {
                String audioUrl = fetchDirectAudioUrl(videoId);
                if (audioUrl == null) {
                    log.warn("Could not fetch direct audio URL for {}, skipping CDN upload", videoId);
                    return;
                }

                HttpClient client = sourceManager.getHttpClient();
                HttpRequest request = HttpRequest.newBuilder().uri(new URI(audioUrl))
                        .header("User-Agent", UA)
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200 || response.statusCode() == 206) {
                    String contentType = response.headers().firstValue("Content-Type").orElse("audio/mp4");
                    String ext = contentType.contains("webm") ? "audio.webm" : "audio.mp4";

                    String boundary = "---boundary" + System.currentTimeMillis();
                    String metadataJson = "{\"title\":\"" + trackInfo.title.replace("\"", "\\\"") + "\",\"author\":\"" + trackInfo.author.replace("\"", "\\\"") + "\",\"length\":" + trackInfo.length + "}";

                    StringBuilder body = new StringBuilder();
                    body.append("--").append(boundary).append("\r\n");
                    body.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n");
                    body.append(metadataJson).append("\r\n");
                    body.append("--").append(boundary).append("\r\n");
                    body.append("Content-Disposition: form-data; name=\"audio\"; filename=\"").append(ext).append("\"\r\n");
                    body.append("Content-Type: ").append(contentType).append("\r\n\r\n");

                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    try (InputStream in = response.body()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    HttpRequest uploadReq = HttpRequest.newBuilder()
                        .uri(new URI(sourceManager.getApiUrl() + "/api/v1/upload/" + videoId))
                        .header("Authorization", "Bearer " + sourceManager.getMasterKey())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                        .build();

                    HttpResponse<String> uploadRes = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
                    if (uploadRes.statusCode() == 200) {
                        log.info("Successfully uploaded clean audio for {} to CDN", videoId);
                    } else {
                        log.warn("CDN upload returned {} for {}", uploadRes.statusCode(), videoId);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to upload {} to CDN", videoId, e);
            }
        }, sourceManager.getNetworkExecutor());
    }

    private String fetchDirectAudioUrl(String videoId) {
        AudioSourceManager sm = sourceManager.getOriginalYouTubeSource();
        if (sm == null) return null;

        String url = tryPluginReflection(sm, videoId);
        if (url != null) return url;

        url = tryInnertubeViaPluginHttp(sm, videoId);
        if (url != null) return url;

        return tryInnertubeStandalone(videoId);
    }

    private String tryPluginReflection(AudioSourceManager sm, String videoId) {
        try {
            Object[] clients = null;
            for (java.lang.reflect.Field f : sm.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(sm);
                    if (val != null && val.getClass().isArray() && val.getClass().getComponentType().isInterface()) {
                        clients = (Object[]) val;
                        break;
                    }
                    if (val instanceof java.util.Collection) {
                        java.util.Collection<?> col = (java.util.Collection<?>) val;
                        if (!col.isEmpty()) {
                            Object first = col.iterator().next();
                            if (first != null && first.getClass().getName().contains("Client")) {
                                clients = col.toArray();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (clients == null) {
                for (java.lang.reflect.Field f : sm.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(sm);
                        if (val != null && val.getClass().isArray()) {
                            Object[] arr = (Object[]) val;
                            if (arr.length > 0 && arr[0] != null && !arr[0].getClass().isPrimitive()) {
                                clients = arr;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (clients == null || clients.length == 0) return null;

            com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface httpInterface = null;
            try {
                java.lang.reflect.Method getIface = sm.getClass().getMethod("getHttpInterface");
                httpInterface = (com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface) getIface.invoke(sm);
            } catch (Exception ignored) {}

            for (Object client : clients) {
                if (client == null) continue;
                String clientName = client.getClass().getSimpleName();
                if (clientName.contains("Streaming")) continue;

                for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    Class<?> returnType = m.getReturnType();
                    if (returnType == void.class || returnType.isPrimitive()) continue;

                    try {
                        Object result = null;
                        Class<?>[] params = m.getParameterTypes();

                        if (params.length == 1 && params[0] == String.class) {
                            result = m.invoke(client, videoId);
                        } else if (params.length == 2 && params[1] == String.class && httpInterface != null) {
                            result = m.invoke(client, httpInterface, videoId);
                        } else {
                            continue;
                        }

                        if (result == null) continue;

                        String audioUrl = scanObjectForAudioUrl(result, new java.util.HashSet<>(), 0);
                        if (audioUrl != null) {
                            log.info("Extracted audio URL for {} via plugin client {}.{}", videoId, clientName, m.getName());
                            if (httpInterface != null) httpInterface.close();
                            return audioUrl;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (httpInterface != null) httpInterface.close();
        } catch (Exception e) {
            log.debug("Plugin reflection failed for {}", videoId, e);
        }
        return null;
    }

    private String scanObjectForAudioUrl(Object obj, java.util.Set<Integer> visited, int depth) {
        if (obj == null || depth > 6) return null;
        if (!visited.add(System.identityHashCode(obj))) return null;

        if (obj instanceof String) {
            String s = (String) obj;
            if (s.contains("googlevideo.com") && s.contains("videoplayback") && !s.contains("sabr=1")) {
                return s;
            }
            return null;
        }

        if (obj instanceof java.util.Collection) {
            String bestUrl = null;
            long bestBitrate = 0;
            for (Object item : (java.util.Collection<?>) obj) {
                if (item == null) continue;
                String url = tryExtractAudioFormatUrl(item);
                if (url != null) return url;
                String scanned = scanObjectForAudioUrl(item, visited, depth + 1);
                if (scanned != null) return scanned;
            }
            return bestUrl;
        }

        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            int len = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(obj, i);
                String url = scanObjectForAudioUrl(item, visited, depth + 1);
                if (url != null) return url;
            }
            return null;
        }

        String pkg = obj.getClass().getName();
        if (pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.")) {
            return null;
        }

        for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterCount() != 0) continue;
            String name = m.getName().toLowerCase();
            if (name.contains("format") || name.contains("stream") || name.contains("url") || name.contains("detail")) {
                try {
                    Object val = m.invoke(obj);
                    String url = scanObjectForAudioUrl(val, visited, depth + 1);
                    if (url != null) return url;
                } catch (Exception ignored) {}
            }
        }

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    String url = scanObjectForAudioUrl(val, visited, depth + 1);
                    if (url != null) return url;
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }

    private String tryExtractAudioFormatUrl(Object format) {
        try {
            String url = null;
            String mimeType = null;

            for (java.lang.reflect.Method m : format.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
                String name = m.getName().toLowerCase();

                if (m.getReturnType() == String.class) {
                    if (name.equals("geturl") || name.equals("url")) {
                        url = (String) m.invoke(format);
                    } else if (name.contains("mime") || name.contains("type")) {
                        mimeType = (String) m.invoke(format);
                    }
                }
            }

            if (url != null && mimeType != null && mimeType.startsWith("audio/")) {
                return url;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String tryInnertubeViaPluginHttp(AudioSourceManager sm, String videoId) {
        try {
            java.lang.reflect.Method getIface = sm.getClass().getMethod("getHttpInterface");
            try (com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface httpInterface =
                    (com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface) getIface.invoke(sm)) {

                String[][] configs = {
                    {"ANDROID_TESTSUITE", "1.9", "30"},
                    {"ANDROID", "19.09.37", "30"},
                    {"WEB", "2.20240101.00.00", "null"},
                };

                for (String[] c : configs) {
                    try {
                        StringBuilder payload = new StringBuilder();
                        payload.append("{\"videoId\":\"").append(videoId).append("\",");
                        payload.append("\"context\":{\"client\":{");
                        payload.append("\"clientName\":\"").append(c[0]).append("\",");
                        payload.append("\"clientVersion\":\"").append(c[1]).append("\"");
                        if (!c[2].equals("null")) {
                            payload.append(",\"androidSdkVersion\":").append(c[2]);
                        }
                        payload.append(",\"hl\":\"en\",\"gl\":\"US\"");
                        payload.append("}}}");

                        org.apache.http.client.methods.HttpPost post = new org.apache.http.client.methods.HttpPost(
                            "https://www.youtube.com/youtubei/api/v1/player?prettyPrint=false&key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w");
                        post.setEntity(new org.apache.http.entity.StringEntity(payload.toString(), "UTF-8"));
                        post.setHeader("Content-Type", "application/json");

                        org.apache.http.HttpResponse response = httpInterface.execute(post);
                        int status = response.getStatusLine().getStatusCode();
                        if (status != 200) continue;

                        String body = org.apache.http.util.EntityUtils.toString(response.getEntity());
                        String audioUrl = parseAudioUrlFromPlayerResponse(body);
                        if (audioUrl != null) {
                            log.info("Got audio URL for {} via plugin HTTP + {} client", videoId, c[0]);
                            return audioUrl;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("Plugin HTTP innertube failed for {}", videoId, e);
        }
        return null;
    }

    private String tryInnertubeStandalone(String videoId) {
        String[][] clients = {
            {"ANDROID_TESTSUITE", "1.9", "30", UA},
            {"ANDROID", "19.09.37", "30", UA},
            {"IOS", "19.09.3", "null", "com.google.ios.youtube/19.09.3 (iPhone; CPU iPhone OS 17_4 like Mac OS X)"},
            {"TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "null", "Mozilla/5.0"},
        };

        HttpClient client = sourceManager.getHttpClient();

        for (String[] c : clients) {
            try {
                StringBuilder payload = new StringBuilder();
                payload.append("{\"videoId\":\"").append(videoId).append("\",");
                payload.append("\"context\":{\"client\":{");
                payload.append("\"clientName\":\"").append(c[0]).append("\",");
                payload.append("\"clientVersion\":\"").append(c[1]).append("\"");
                if (!c[2].equals("null")) {
                    payload.append(",\"androidSdkVersion\":").append(c[2]);
                }
                payload.append(",\"hl\":\"en\",\"gl\":\"US\"");
                payload.append("}}}");

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(new URI("https://www.youtube.com/youtubei/api/v1/player?prettyPrint=false&key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", c[3])
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) continue;

                String audioUrl = parseAudioUrlFromPlayerResponse(res.body());
                if (audioUrl != null) {
                    log.info("Got audio URL for {} via standalone {} client", videoId, c[0]);
                    return audioUrl;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String parseAudioUrlFromPlayerResponse(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode formats = root.path("streamingData").path("adaptiveFormats");

            if (formats.isMissingNode() || !formats.isArray()) return null;

            String bestUrl = null;
            long bestBitrate = 0;

            for (com.fasterxml.jackson.databind.JsonNode format : formats) {
                String mimeType = format.path("mimeType").asText("");
                if (!mimeType.startsWith("audio/")) continue;

                String url = null;
                if (format.has("url")) {
                    url = format.path("url").asText();
                } else if (format.has("signatureCipher")) {
                    url = extractUrlFromCipher(format.path("signatureCipher").asText());
                }

                if (url == null) continue;

                long bitrate = format.path("bitrate").asLong(0);
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate;
                    bestUrl = url;
                }
            }
            return bestUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractUrlFromCipher(String cipher) {
        try {
            String[] parts = cipher.split("&");
            for (String part : parts) {
                if (part.startsWith("url=")) {
                    return java.net.URLDecoder.decode(part.substring(4), "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}