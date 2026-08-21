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

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTrack.class);
    private static final Set<String> uploadsInProgress = ConcurrentHashMap.newKeySet();
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
        boolean skipCdn = trackInfo.isStream || (trackInfo.length > 0 && trackInfo.length <= 60000L);

        String streamUrl = cdnStreamUrl;
        if (!skipCdn && streamUrl == null && originalTrack != null) {
            streamUrl = sourceManager.checkCdnStreamUrl(videoId);
        }

        if (!skipCdn && streamUrl != null) {
            try {
                log.info("Playing track {} via SlugYZeon-YTCDN", videoId);
                playCdnStreamFromUrl(streamUrl, executor);
                return;
            } catch (Exception e) {
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
            if (!skipCdn) {
                triggerBackgroundMirror();
            }
            processDelegate(fallback, executor);
            return;
        }

        throw new FriendlyException(
                "Cannot play YouTube track (no source available)",
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
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

    private void triggerBackgroundMirror() {
        if (!uploadsInProgress.add(videoId)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {

                JioSaavnHelper.JioSaavnTrack jioTrack = JioSaavnHelper.searchAndGetTrack(
                    sourceManager.getHttpClient(), trackInfo.title, trackInfo.author);

                if (jioTrack == null || jioTrack.mediaUrl == null) {
                    return;
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
                payload.put("title", trackInfo.title);
                payload.put("author", trackInfo.author);
                payload.put("length", jioTrack.length > 0 ? jioTrack.length : trackInfo.length);
                payload.put("mediaUrl", jioTrack.mediaUrl);
                if (trackInfo.isrc != null && !trackInfo.isrc.isEmpty()) {
                    payload.put("isrc", trackInfo.isrc);
                }
                String metadataJson = mapper.writeValueAsString(payload);

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(new URI(sourceManager.getApiUrl() + "/api/v1/upload/" + videoId))
                    .header("Authorization", "Bearer " + sourceManager.getMasterKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(metadataJson, StandardCharsets.UTF_8))
                    .build();

                HttpResponse<String> res = sourceManager.getHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());

                if (res.statusCode() == 200) {
                    log.info("Successfully uploaded {} to cdn", videoId);
                }
            } catch (Exception ignored) {
            } finally {
                uploadsInProgress.remove(videoId);
            }
        }, sourceManager.getNetworkExecutor());
    }
}