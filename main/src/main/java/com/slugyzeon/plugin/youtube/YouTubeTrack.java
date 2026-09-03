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
        if (streamUrl == null) {
            streamUrl = sourceManager.checkCdnStreamUrl(videoId);
        }

        if (streamUrl != null) {
            log.info("Playing track {} via SlugYZeon-YTCDN", videoId);
            try {
                playCdnStreamFromUrl(streamUrl, executor);
                return;
            } catch (Exception ignored) {
            }
        }

        var directUrl = tryY2mateApi(videoId);
        if (directUrl != null) {
            log.info("Playing track {} via SlugYZeon-YTCDN [Bypass Mode]", videoId);
            try {
                playFromTempFile(directUrl, executor);
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
            processDelegate(fallback, executor);
            return;
        }

        throw new FriendlyException(
                "Cannot play YouTube track (no source available)",
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    private void playCdnStreamFromUrl(String streamUrl, LocalAudioTrackExecutor executor) throws Exception {
        com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager httpSm = sourceManager.getHttpSourceManager();
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        AudioItem item = httpSm.loadItem(manager, new AudioReference(streamUrl, null));
        
        if (item instanceof InternalAudioTrack) {
            processDelegate((InternalAudioTrack) item, executor);
        } else {
            throw new FriendlyException("Could not load CDN stream", FriendlyException.Severity.SUSPICIOUS, null);
        }
    }

    private void playFromTempFile(String streamUrl, LocalAudioTrackExecutor executor) throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("ytcdn-", ".tmp");
        try {
            java.net.http.HttpClient client = sourceManager.getHttpClient();
            String[] dlHeaders = {
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer", "https://frame.y2meta-uk.com/",
                "Origin", "https://frame.y2meta-uk.com"
            };
            
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(new URI(streamUrl))
                    .headers(dlHeaders)
                    .GET()
                    .build();
            
            var response = client.send(req, HttpResponse.BodyHandlers.ofFile(tempFile));
            
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String loc = response.headers().firstValue("Location").orElse(null);
                if (loc != null) {
                    req = java.net.http.HttpRequest.newBuilder()
                        .uri(new URI(loc))
                        .headers(dlHeaders)
                        .GET()
                        .build();
                    response = client.send(req, HttpResponse.BodyHandlers.ofFile(tempFile));
                }
            }
            
            if (!trackInfo.isStream) {
                java.nio.file.Path uploadFile = java.nio.file.Files.createTempFile("ytcdn-up-", ".tmp");
                java.nio.file.Files.copy(tempFile, uploadFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                triggerBackgroundUploadFromDisk(uploadFile, response.headers().firstValue("Content-Type").orElse("audio/mpeg"));
            }

            com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager localSm = new com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager();
            AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
            AudioItem item = localSm.loadItem(manager, new AudioReference(tempFile.toAbsolutePath().toString(), null));

            if (item instanceof InternalAudioTrack) {
                processDelegate((InternalAudioTrack) item, executor);
            } else {
                throw new FriendlyException("Could not load local temp file", FriendlyException.Severity.SUSPICIOUS, null);
            }
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
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

    private void triggerBackgroundUploadFromDisk(java.nio.file.Path tempFile, String contentType) {
        CompletableFuture.runAsync(() -> {
            try {
                String finalContentType = contentType != null ? contentType : "audio/mpeg";
                String ext = "audio.mp3";
                
                if (finalContentType.contains("webm")) {
                    ext = "audio.webm";
                } else if (finalContentType.contains("mp4")) {
                    ext = "audio.mp4";
                }
                
                String boundary = "---boundary" + System.currentTimeMillis();
                
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var metadataNode = mapper.createObjectNode();
                metadataNode.put("title", trackInfo.title);
                metadataNode.put("author", trackInfo.author);
                metadataNode.put("length", trackInfo.length);
                
                String metadataJson = mapper.writeValueAsString(metadataNode);
                
                byte[] bodyStart = new StringBuilder()
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
                        .append(metadataJson).append("\r\n")
                        .append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"audio\"; filename=\"").append(ext).append("\"\r\n")
                        .append("Content-Type: ").append(finalContentType).append("\r\n\r\n")
                        .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    
                byte[] bodyEnd = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                
                long fileSize = java.nio.file.Files.size(tempFile);
                long totalSize = bodyStart.length + fileSize + bodyEnd.length;

                java.net.URL uploadUrl = new URI(sourceManager.getApiUrl() + "/api/v1/upload/" + videoId).toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uploadUrl.openConnection();
                
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + sourceManager.getMasterKey());
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setFixedLengthStreamingMode(totalSize);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(bodyStart);
                    java.nio.file.Files.copy(tempFile, os);
                    os.write(bodyEnd);
                }

                if (conn.getResponseCode() == 200) {
                    log.info("Successfully uploaded {} to CDN", videoId);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }, sourceManager.getNetworkExecutor());
    }

    private String tryY2mateApi(String videoId) {
        try {
            java.net.http.HttpClient client = sourceManager.getHttpClient();
            
            String[] keyHeaders = {
                "User-Agent", UA,
                "Referer", "https://frame.y2meta-uk.com/",
                "Origin", "https://frame.y2meta-uk.com",
                "Accept", "application/json"
            };

            java.net.http.HttpRequest keyReq = java.net.http.HttpRequest.newBuilder()
                    .uri(new java.net.URI("https://cnv.cx/v2/sanity/key?id=" + videoId))
                    .headers(keyHeaders)
                    .GET()
                    .build();
                
            java.net.http.HttpResponse<String> keyRes = client.send(keyReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (keyRes.statusCode() != 200) {
                return null;
            }
            
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(keyRes.body());
            
            String key = null;
            if (json.has("key")) {
                key = json.path("key").asText();
            }
            if (key == null) {
                return null;
            }
            
            String payload = "link=https://youtu.be/" + videoId 
                    + "&format=mp3&audioBitrate=128&videoQuality=720&filenameStyle=pretty&vCodec=h264";
                    
            String[] convertHeaders = {
                "User-Agent", UA,
                "Referer", "https://frame.y2meta-uk.com/",
                "Origin", "https://frame.y2meta-uk.com",
                "Accept", "application/json",
                "key", key,
                "Content-Type", "application/x-www-form-urlencoded"
            };

            java.net.http.HttpRequest convertReq = java.net.http.HttpRequest.newBuilder()
                    .uri(new java.net.URI("https://cnv.cx/v2/converter"))
                    .headers(convertHeaders)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .build();
                
            java.net.http.HttpResponse<String> convertRes = client.send(convertReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (convertRes.statusCode() != 200) {
                return null;
            }
            
            com.fasterxml.jackson.databind.JsonNode convJson = mapper.readTree(convertRes.body());
            if (convJson.has("url")) {
                return convJson.path("url").asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}