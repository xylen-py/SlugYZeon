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
                playCdnStreamFromUrl(streamUrl, executor, false);
                return;
            } catch (Exception ignored) {
            }
        }

        var directUrl = tryY2mateApi(videoId);
        if (directUrl != null) {
            try {
                playCdnStreamFromUrl(directUrl, executor, true);
                return;
            } catch (Exception ignored) {
            }
        }

        InternalAudioTrack fallback = null;
        if (originalTrack instanceof InternalAudioTrack) {
            fallback = (InternalAudioTrack) originalTrack;
        } else {
            try {
                AudioSourceManager ytSource = sourceManager.getOriginalYouTubeSource();
                if (ytSource != null) {
                    AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
                    AudioItem item = ytSource.loadItem(manager, new AudioReference(videoId, null));
                    if (item instanceof AudioTrack && item instanceof InternalAudioTrack) {
                        fallback = (InternalAudioTrack) item;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (fallback != null) {
            if (!trackInfo.isStream && trackInfo.length <= 720000L && directUrl != null) {
                triggerBackgroundUpload(directUrl);
            }
            processDelegate(fallback, executor);
            return;
        }

        throw new FriendlyException(
                "Cannot play YouTube track (no source available)",
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    private void playCdnStreamFromUrl(String streamUrl, LocalAudioTrackExecutor executor, boolean isBypass) throws Exception {
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

                if (isBypass) {
                    log.info("Playing track {} via SlugYZeon-YTCDN [Bypass Mode]", videoId);
                } else {
                    log.info("Playing track {} via SlugYZeon-YTCDN", videoId);
                }
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

    private void triggerBackgroundUpload(String audioUrl) {
        CompletableFuture.runAsync(() -> {
            try {

                var client = sourceManager.getHttpClient();
                var request = HttpRequest.newBuilder().uri(new URI(audioUrl))
                        .header("User-Agent", UA)
                        .timeout(java.time.Duration.ofMinutes(5))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200 || response.statusCode() == 206) {
                    var contentType = response.headers().firstValue("Content-Type").orElse("audio/mp4");
                    var ext = contentType.contains("webm") ? "audio.webm" : "audio.mp4";

                    var boundary = "---boundary" + System.currentTimeMillis();
                    
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var metadataNode = mapper.createObjectNode();
                    metadataNode.put("title", trackInfo.title);
                    metadataNode.put("author", trackInfo.author);
                    metadataNode.put("length", trackInfo.length);
                    var metadataJson = mapper.writeValueAsString(metadataNode);

                    var bodyStart = new StringBuilder()
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
                        .append(metadataJson).append("\r\n")
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"audio\"; filename=\"").append(ext).append("\"\r\n")
                        .append("Content-Type: ").append(contentType).append("\r\n\r\n")
                        .toString();

                    var bodyEnd = "\r\n--" + boundary + "--\r\n";
                    var startStream = new java.io.ByteArrayInputStream(bodyStart.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var endStream = new java.io.ByteArrayInputStream(bodyEnd.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    try (var in = response.body()) {
                        var streamSupplier = (java.util.function.Supplier<java.io.InputStream>) () -> new java.io.SequenceInputStream(
                            new java.io.SequenceInputStream(startStream, in), endStream
                        );

                        var uploadReq = HttpRequest.newBuilder()
                            .uri(new URI(sourceManager.getApiUrl() + "/api/v1/upload/" + videoId))
                            .header("Authorization", "Bearer " + sourceManager.getMasterKey())
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofInputStream(streamSupplier))
                            .build();

                        var uploadRes = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
                        if (uploadRes.statusCode() == 200) {
                            log.info("Successfully uploaded {} to CDN", videoId);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, sourceManager.getNetworkExecutor());
    }

    private String tryY2mateApi(String videoId) {
        try {
            java.net.http.HttpClient client = sourceManager.getHttpClient();
            
            java.net.http.HttpRequest keyReq = java.net.http.HttpRequest.newBuilder()
                .uri(new java.net.URI("https://cnv.cx/v2/sanity/key?id=" + videoId))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://frame.y2meta-uk.com/")
                .header("Origin", "https://frame.y2meta-uk.com")
                .header("Accept", "application/json")
                .GET().build();
                
            java.net.http.HttpResponse<String> keyRes = client.send(keyReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (keyRes.statusCode() != 200) return null;
            
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String key = null;
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(keyRes.body());
            if (json.has("key")) {
                key = json.path("key").asText();
            }
            if (key == null) return null;
            
            String payload = "link=https://youtu.be/" + videoId + "&format=mp3&audioBitrate=128&videoQuality=720&filenameStyle=pretty&vCodec=h264";
            
            java.net.http.HttpRequest convertReq = java.net.http.HttpRequest.newBuilder()
                .uri(new java.net.URI("https://cnv.cx/v2/converter"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://frame.y2meta-uk.com/")
                .header("Origin", "https://frame.y2meta-uk.com")
                .header("Accept", "application/json")
                .header("key", key)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build();
                
            java.net.http.HttpResponse<String> convertRes = client.send(convertReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (convertRes.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode convJson = mapper.readTree(convertRes.body());
                if (convJson.has("url")) {
                    return convJson.path("url").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}