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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class YouTubeTrack extends DelegatedAudioTrack {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private static final String[] RETRIABLE_KEYWORDS = {
            "sign in", "login", "bot", "confirm", "verify",
            "403", "age", "restricted", "unavailable",
            "country", "blocked", "copyright", "removed",
            "private", "no supported audio", "playback on other",
            "premium", "members only", "requires payment"
    };

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
                if (!isRetriableError(e)) {
                    throw e;
                }
            }
        }

        YouTubeProxyHandler.StreamResult stream = sourceManager.getProxyHandler().getStream(videoId);

        if (stream != null) {
            try {
                playFromProxyStream(stream, executor);
                return;
            } catch (Exception ignored) {
            }
        }

        AudioItem mirrorResult = searchMirror(trackInfo.title + " " + trackInfo.author);
        if (tryPlayMirror(mirrorResult, executor)) {
            return;
        }

        throw new FriendlyException(
                "[SlugYTube] YouTube playback failed — all clients, proxies, and mirrors exhausted for: " + videoId,
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video: " + videoId + " | Title: " + trackInfo.title));
    }

    private boolean isRetriableError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        for (String keyword : RETRIABLE_KEYWORDS) {
            if (msg.contains(keyword))
                return true;
        }

        if (e instanceof FriendlyException) {
            FriendlyException fe = (FriendlyException) e;
            if (fe.severity == FriendlyException.Severity.COMMON)
                return true;

            Throwable cause = fe.getCause();
            if (cause != null) {
                String causeMsg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
                for (String keyword : RETRIABLE_KEYWORDS) {
                    if (causeMsg.contains(keyword))
                        return true;
                }
            }
        }

        return false;
    }

    private void playFromProxyStream(YouTubeProxyHandler.StreamResult stream,
            LocalAudioTrackExecutor executor) throws Exception {

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(stream.url))
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .GET().build();

        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new FriendlyException(
                    "[SlugYTube] Proxy stream returned HTTP " + response.statusCode(),
                    FriendlyException.Severity.SUSPICIOUS, null);
        }

        try (com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream nis = new com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream(
                response.body())) {
            if (stream.mimeType != null && (stream.mimeType.contains("webm")
                    || stream.mimeType.contains("opus"))) {
                processDelegate(new MatroskaAudioTrack(trackInfo, nis), executor);
            } else {
                processDelegate(new MpegAudioTrack(trackInfo, nis), executor);
            }
        }
    }

    private boolean tryPlayMirror(AudioItem mirrorResult, LocalAudioTrackExecutor executor)
            throws Exception {
        if (mirrorResult == null)
            return false;

        if (mirrorResult instanceof AudioPlaylist) {
            AudioPlaylist playlist = (AudioPlaylist) mirrorResult;
            for (AudioTrack track : playlist.getTracks()) {
                if (track instanceof InternalAudioTrack) {
                    processDelegate((InternalAudioTrack) track, executor);
                    return true;
                }
            }
        }

        if (mirrorResult instanceof InternalAudioTrack) {
            processDelegate((InternalAudioTrack) mirrorResult, executor);
            return true;
        }

        return false;
    }

    private AudioItem searchMirror(String query) {
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        if (manager == null)
            return null;

        for (String provider : sourceManager.getMirrorProviders()) {
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
                    public void loadFailed(FriendlyException exception) {
                        future.complete(null);
                    }
                });

                AudioItem result = future.get(10, TimeUnit.SECONDS);
                if (result != null)
                    return result;
            } catch (Exception ignored) {
            }
        }
        return null;
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