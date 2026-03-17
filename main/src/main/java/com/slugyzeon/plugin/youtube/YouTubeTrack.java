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

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (originalTrack instanceof InternalAudioTrack) {
            try {
                processDelegate((InternalAudioTrack) originalTrack, executor);
                return;
            } catch (Exception e) {
                if (!YouTubeSourceManager.isRetriableError(e)) {
                    throw e;
                }
            }
        }

        if (tryExactOfficialTrackFallback(executor))
            return;
        if (tryProxyStream(executor))
            return;
        if (tryMirrorSearch(executor))
            return;

        throw new FriendlyException(
                "[SlugYZeon] All fallbacks exhausted for " + videoId,
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    private boolean tryExactOfficialTrackFallback(LocalAudioTrackExecutor executor) {
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        if (manager == null) {
            return false;
        }

        List<String> validQueries = new ArrayList<>();
        if (trackInfo.isrc != null && !trackInfo.isrc.isEmpty()) {
            validQueries.add("ytmsearch:\"" + trackInfo.isrc.replace("-", "") + "\"");
        }

        String counterpartId = sourceManager.getProxyHandler().getCounterpartVideoId(this.videoId);
        if (counterpartId != null && !counterpartId.isEmpty()) {
            validQueries.add("ytsearch:" + counterpartId);
        }

        if (trackInfo.title != null && !trackInfo.title.isEmpty() && trackInfo.author != null
                && !trackInfo.author.isEmpty()) {
            if (!trackInfo.author.equalsIgnoreCase("Unknown")
                    && !trackInfo.author.equalsIgnoreCase("Unknown artist")) {
                validQueries.add("ytmsearch:" + trackInfo.title + " " + trackInfo.author);
            }
        }

        for (String query : validQueries) {
            try {
                CompletableFuture<AudioItem> future = new CompletableFuture<>();
                manager.loadItem(query, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(track);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        future.complete(playlist);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(null);
                    }

                    @Override
                    public void loadFailed(FriendlyException e) {
                        future.complete(null);
                    }
                });

                AudioItem result = future.get(10, TimeUnit.SECONDS);

                if (result instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) result;
                    for (AudioTrack track : playlist.getTracks()) {
                        if (!track.getIdentifier().equals(this.videoId)) {
                            if (track instanceof InternalAudioTrack) {
                                log.info("[SlugYZeon] Found alternative exact match track for {} using query '{}'",
                                        videoId,
                                        query);
                                processDelegate((InternalAudioTrack) track, executor);
                                return true;
                            }
                        }
                    }
                } else if (result instanceof InternalAudioTrack) {
                    AudioTrack track = (AudioTrack) result;
                    if (!track.getIdentifier().equals(this.videoId)) {
                        log.info("[SlugYZeon] Found alternative exact match track for {} using query '{}'", videoId,
                                query);
                        processDelegate((InternalAudioTrack) track, executor);
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("[SlugYZeon] Exact match fallback query '{}' failed for {}", query, videoId, e);
            }
        }
        return false;
    }

    private boolean tryProxyStream(LocalAudioTrackExecutor executor) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                YouTubeProxyHandler.StreamResult stream = sourceManager.getProxyHandler().getStream(videoId);
                if (stream == null)
                    return false;

                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(stream.url))
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();

                HttpResponse<InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200)
                    continue;

                try (com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream nis = new com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream(
                        response.body())) {
                    if (stream.mimeType != null && (stream.mimeType.contains("webm")
                            || stream.mimeType.contains("opus"))) {
                        processDelegate(new MatroskaAudioTrack(trackInfo, nis), executor);
                    } else {
                        processDelegate(new MpegAudioTrack(trackInfo, nis), executor);
                    }
                    log.info("[SlugYZeon] Successfully opened fallback stream for {}", videoId);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean tryMirrorSearch(LocalAudioTrackExecutor executor) {
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        if (manager == null)
            return false;

        String query = trackInfo.title;
        if (trackInfo.author != null && !trackInfo.author.isEmpty()
                && !"Unknown".equalsIgnoreCase(trackInfo.author)
                && !"Unknown artist".equalsIgnoreCase(trackInfo.author)) {
            query = trackInfo.title + " " + trackInfo.author;
        }

        for (String provider : sourceManager.getMirrorProviders()) {
            if (provider.contains("ytsearch") || provider.contains("ytmsearch"))
                continue;

            String searchQuery = provider.replace("%QUERY%", query);

            try {
                CompletableFuture<AudioItem> future = new CompletableFuture<>();
                manager.loadItem(searchQuery, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(track);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        future.complete(playlist);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(null);
                    }

                    @Override
                    public void loadFailed(FriendlyException e) {
                        future.complete(null);
                    }
                });

                AudioItem result = future.get(10, TimeUnit.SECONDS);
                if (result == null)
                    continue;

                if (result instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) result;
                    for (AudioTrack track : playlist.getTracks()) {
                        if (track instanceof InternalAudioTrack) {
                            processDelegate((InternalAudioTrack) track, executor);
                            return true;
                        }
                    }
                }

                if (result instanceof InternalAudioTrack) {
                    processDelegate((InternalAudioTrack) result, executor);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new YouTubeTrack(trackInfo, videoId, originalTrack, sourceManager);
    }
}