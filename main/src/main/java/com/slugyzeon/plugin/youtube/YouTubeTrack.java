package com.slugyzeon.plugin.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTrack.class);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private final YouTubeSourceManager sourceManager;
    private final String videoId;
    private final AudioTrack originalTrack;

    public YouTubeTrack(AudioTrackInfo trackInfo, String videoId, AudioTrack originalTrack,
            YouTubeSourceManager sourceManager) {
        super(trackInfo);
        this.videoId = videoId;
        this.originalTrack = originalTrack;
        this.sourceManager = sourceManager;
    }

    public AudioTrack getOriginalTrack() {
        return originalTrack;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (originalTrack instanceof InternalAudioTrack) {
            if (!(originalTrack instanceof com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack)) {
                AudioTrack cdnTrack = sourceManager.checkCdnForTrack(videoId);
                if (cdnTrack instanceof YouTubeTrack) {
                    AudioTrack internalHttp = ((YouTubeTrack) cdnTrack).getOriginalTrack();
                    if (internalHttp instanceof InternalAudioTrack) {
                        log.info("Playlist track {} found in CDN! Swapping seamlessly.", videoId);
                        processDelegate((InternalAudioTrack) internalHttp, executor);
                        return;
                    }
                }
                if (!trackInfo.isStream && trackInfo.length <= 720000L) {
                    triggerBackgroundUpload();
                } else {
                    log.info("Track {} exceeds 12-minute CDN limit or is a live stream. Skipping upload.", videoId);
                }
            } else {
                log.info("Successfully playing track {} via SlugYZeon-YTCDN", videoId);
            }
            processDelegate((InternalAudioTrack) originalTrack, executor);
            return;
        }

        throw new FriendlyException(
                "Cannot play YouTube track (no original track available)",
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new YouTubeTrack(trackInfo, videoId, originalTrack, sourceManager);
    }

    private String extractUrlFromOriginalPlugin() {
        try {
            AudioSourceManager sm = sourceManager.getOriginalYouTubeSource();
            if (sm == null) return null;
            
            java.lang.reflect.Method getHttpInterfaceManager = null;
            try { getHttpInterfaceManager = sm.getClass().getMethod("getHttpInterfaceManager"); } catch (Exception ignored) {}
            
            Object httpInterfaceManager = null;
            if (getHttpInterfaceManager != null) {
                httpInterfaceManager = getHttpInterfaceManager.invoke(sm);
            }
            
            Object httpInterface = null;
            if (httpInterfaceManager != null) {
                java.lang.reflect.Method getInterface = httpInterfaceManager.getClass().getMethod("getInterface");
                httpInterface = getInterface.invoke(httpInterfaceManager);
            } else {
                try { 
                    java.lang.reflect.Method getHttpInterface = sm.getClass().getMethod("getHttpInterface");
                    httpInterface = getHttpInterface.invoke(sm);
                } catch (Exception ignored) {}
            }
            
            if (httpInterface == null) {
                return null;
            }
            
            java.lang.reflect.Method getTrackDetailsLoader = null;
            try { getTrackDetailsLoader = sm.getClass().getMethod("getTrackDetailsLoader"); } catch (Exception ignored) {}
            
            if (getTrackDetailsLoader == null) {
                return null;
            }
            
            Object loader = getTrackDetailsLoader.invoke(sm);
            
            java.lang.reflect.Method loadDetails = null;
            for (java.lang.reflect.Method m : loader.getClass().getMethods()) {
                if (m.getName().equals("loadDetails")) {
                    loadDetails = m;
                    break;
                }
            }
            if (loadDetails == null) {
                return null;
            }
            
            Object trackDetails;
            if (loadDetails.getParameterCount() == 2 && httpInterface != null) {
                trackDetails = loadDetails.invoke(loader, httpInterface, videoId);
            } else if (loadDetails.getParameterCount() == 1) {
                trackDetails = loadDetails.invoke(loader, videoId);
            } else {
                return null;
            }
            
            java.lang.reflect.Method getFormats = trackDetails.getClass().getMethod("getFormats");
            java.util.List<?> formats = (java.util.List<?>) getFormats.invoke(trackDetails);
            
            String bestUrl = null;
            long bestBitrate = -1;
            
            for (Object format : formats) {
                java.lang.reflect.Method getInfo = format.getClass().getMethod("getInfo");
                Object info = getInfo.invoke(format);
                
                java.lang.reflect.Method getMimeType = info.getClass().getMethod("getMimeType");
                String mimeType = (String) getMimeType.invoke(info);
                
                if (mimeType != null && mimeType.startsWith("audio/")) {
                    java.lang.reflect.Method getBitrate = info.getClass().getMethod("getBitrate");
                    long bitrate = ((Number) getBitrate.invoke(info)).longValue();
                    
                    java.lang.reflect.Method getUrl = format.getClass().getMethod("getUrl");
                    String url = (String) getUrl.invoke(format);
                    
                    if (url != null && bitrate > bestBitrate) {
                        bestBitrate = bitrate;
                        bestUrl = url;
                    }
                }
            }
            return bestUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private void triggerBackgroundUpload() {
        CompletableFuture.runAsync(() -> {
            try {
                String streamUrl = extractUrlFromOriginalPlugin();
                if (streamUrl == null) return;

                HttpClient client = sourceManager.getHttpClient();
                HttpRequest request = HttpRequest.newBuilder().uri(new URI(streamUrl))
                        .header("User-Agent", UA)
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200 || response.statusCode() == 206) {
                    String boundary = "---boundary" + System.currentTimeMillis();
                    String metadataJson = "{\"title\":\"" + trackInfo.title.replace("\"", "\\\"") + "\",\"author\":\"" + trackInfo.author.replace("\"", "\\\"") + "\",\"length\":" + trackInfo.length + "}";
                    
                    StringBuilder body = new StringBuilder();
                    body.append("--").append(boundary).append("\r\n");
                    body.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n");
                    body.append(metadataJson).append("\r\n");
                    body.append("--").append(boundary).append("\r\n");
                    body.append("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.webm\"\r\n");
                    body.append("Content-Type: audio/webm\r\n\r\n");

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
                        log.info("Successfully uploaded {} to CDN", videoId);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to upload {} to CDN", videoId, e);
            }
        }, sourceManager.getNetworkExecutor());
    }
}