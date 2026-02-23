package com.slugyzeon.plugin.mirror;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
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
        AudioItem track = this.sourceManager.getResolver().apply(this);

        if (track instanceof AudioPlaylist) {
            var tracks = ((AudioPlaylist) track).getTracks();
            if (tracks.isEmpty()) {
                throw new TrackNotFoundException("No tracks found in playlist or search result for track");
            }
            track = tracks.get(0);
        }

        if (track instanceof InternalAudioTrack) {
            InternalAudioTrack internalTrack = (InternalAudioTrack) track;

            internalTrack.setUserData(this.getUserData());

            updateDuration(internalTrack.getDuration());

            log.debug("Loaded mirror from {} {}({})",
                    internalTrack.getSourceManager().getSourceName(),
                    internalTrack.getInfo().title,
                    internalTrack.getInfo().uri);
            processDelegate(internalTrack, executor);
            return;
        }

        throw new TrackNotFoundException("No mirror found for track");
    }

    private void updateDuration(long duration) {
        try {
            Field lengthField = AudioTrackInfo.class.getDeclaredField("length");
            lengthField.setAccessible(true);
            lengthField.setLong(this.trackInfo, duration);
        } catch (Exception e) {
            log.debug("Could not update track duration: {}", e.getMessage());
        }
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
                log.debug("Track loaded: {}", track.getIdentifier());
                cf.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                log.debug("Playlist loaded: {}", playlist.getName());
                cf.complete(playlist);
            }

            @Override
            public void noMatches() {
                log.debug("No matches found for: {}", query);
                cf.complete(AudioReference.NO_TRACK);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                log.debug("Failed to load: {}", query);
                cf.completeExceptionally(exception);
            }
        });
        return cf.join();
    }
}
