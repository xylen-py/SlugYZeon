package com.slugyzeon.plugin.gaana;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggAudioTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GaanaAudioTrack extends DelegatedAudioTrack {

    private final GaanaAudioSourceManager sourceManager;

    public GaanaAudioTrack(AudioTrackInfo trackInfo, GaanaAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String trackId = trackInfo.identifier;
        String quality = sourceManager.getConfig().getStreamQuality();

        JsonNode streamData = sourceManager.getApiHandler().getStream(trackId, quality);

        if (streamData == null) {
            mirrorViaYouTube(executor);
            return;
        }

        String streamUrl = extractDirectUrl(streamData);

        if (streamUrl == null || streamUrl.isEmpty() || streamUrl.contains(".m3u8")) {
            mirrorViaYouTube(executor);
            return;
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamUrl), null)) {
                String urlLower = streamUrl.toLowerCase();

                InternalAudioTrack delegateTrack;
                if (urlLower.contains(".mp4") || urlLower.contains(".m4a") || urlLower.contains(".aac")) {
                    delegateTrack = new MpegAudioTrack(trackInfo, stream);
                } else if (urlLower.contains(".ogg")) {
                    delegateTrack = new OggAudioTrack(trackInfo, stream);
                } else {
                    delegateTrack = new Mp3AudioTrack(trackInfo, stream);
                }

                processDelegate(delegateTrack, executor);
            }
        } catch (Exception e) {
            mirrorViaYouTube(executor);
        }
    }

    private String extractDirectUrl(JsonNode streamData) {
        if (streamData.has("url") && !streamData.get("url").isNull()) {
            String url = streamData.get("url").asText();
            if (!url.isEmpty() && !url.contains(".m3u8"))
                return url;
        }
        if (streamData.has("stream_url") && !streamData.get("stream_url").isNull()) {
            String url = streamData.get("stream_url").asText();
            if (!url.isEmpty() && !url.contains(".m3u8"))
                return url;
        }
        if (streamData.has("mp3_url") && !streamData.get("mp3_url").isNull()) {
            String url = streamData.get("mp3_url").asText();
            if (!url.isEmpty())
                return url;
        }
        if (streamData.has("media_url") && !streamData.get("media_url").isNull()) {
            String url = streamData.get("media_url").asText();
            if (!url.isEmpty() && !url.contains(".m3u8"))
                return url;
        }
        if (streamData.has("hlsUrl") && !streamData.get("hlsUrl").isNull()) {
            return streamData.get("hlsUrl").asText();
        }
        if (streamData.has("hls_url") && !streamData.get("hls_url").isNull()) {
            return streamData.get("hls_url").asText();
        }
        return null;
    }

    private void mirrorViaYouTube(LocalAudioTrackExecutor executor) throws Exception {
        AudioPlayerManager manager = sourceManager.getPlayerManager();
        if (manager == null) {
            throw new RuntimeException("No playable stream found for: " + trackInfo.title);
        }

        String query = "ytmsearch:" + trackInfo.title + " " + trackInfo.author;

        CompletableFuture<AudioItem> future = new CompletableFuture<>();
        manager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks() != null && !playlist.getTracks().isEmpty()) {
                    future.complete(playlist.getTracks().get(0));
                } else {
                    future.complete(null);
                }
            }

            @Override
            public void noMatches() {
                future.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        AudioItem result = future.get(10, TimeUnit.SECONDS);
        if (!(result instanceof AudioTrack)) {
            throw new RuntimeException("No playable stream or YouTube mirror found for: " + trackInfo.title);
        }
        processDelegate((InternalAudioTrack) result, executor);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new GaanaAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
