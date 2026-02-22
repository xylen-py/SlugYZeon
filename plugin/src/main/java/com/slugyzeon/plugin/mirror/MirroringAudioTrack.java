package com.slugyzeon.plugin.mirror;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public abstract class MirroringAudioTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(MirroringAudioTrack.class);

    protected final MirroringAudioSourceManager sourceManager;

    public MirroringAudioTrack(AudioTrackInfo trackInfo, MirroringAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        var track = this.sourceManager.getResolver().apply(this);

        if (track instanceof AudioPlaylist) {
            var tracks = ((AudioPlaylist) track).getTracks();
            if (tracks.isEmpty()) {
                throw new TrackNotFoundException("No tracks found for: " + trackInfo.title);
            }
            track = tracks.get(0);
        }

        if (track instanceof InternalAudioTrack) {
            var internalTrack = (InternalAudioTrack) track;
            log.debug("Loaded mirror from {} {}({})", internalTrack.getSourceManager().getSourceName(),
                    internalTrack.getInfo().title, internalTrack.getInfo().uri);
            processDelegate(internalTrack, executor);
            return;
        }

        throw new TrackNotFoundException("No mirror found for: " + trackInfo.title);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return this.sourceManager;
    }

    public AudioItem loadItem(String query) {
        var cf = new CompletableFuture<AudioItem>();
        this.sourceManager.getAudioPlayerManager().loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                cf.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                cf.complete(playlist);
            }

            @Override
            public void noMatches() {
                cf.complete(AudioReference.NO_TRACK);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                cf.completeExceptionally(exception);
            }
        });
        return cf.join();
    }
}
