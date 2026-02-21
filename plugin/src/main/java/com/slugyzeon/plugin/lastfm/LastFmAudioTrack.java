package com.slugyzeon.plugin.lastfm;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LastFmAudioTrack extends DelegatedAudioTrack {

    private final LastFmAudioSourceManager sourceManager;

    public LastFmAudioTrack(AudioTrackInfo trackInfo, LastFmAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        AudioPlayerManager manager = sourceManager.getPlayerManager();
        if (manager == null) {
            throw new RuntimeException("AudioPlayerManager not available for YouTube mirroring");
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
            throw new RuntimeException("No YouTube mirror found for: " + trackInfo.title + " by " + trackInfo.author);
        }

        AudioTrack ytTrack = (AudioTrack) result;
        processDelegate((InternalAudioTrack) ytTrack, executor);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new LastFmAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
