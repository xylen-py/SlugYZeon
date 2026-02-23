package com.slugyzeon.plugin.gaana;

import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import com.slugyzeon.plugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class GaanaAudioTrack extends MirroringAudioTrack {

    private final String albumName;
    private final String albumUrl;
    private final String artistUrl;
    private final String artistArtworkUrl;

    public GaanaAudioTrack(AudioTrackInfo trackInfo, GaanaAudioSourceManager sourceManager) {
        this(trackInfo, null, null, null, null, sourceManager);
    }

    public GaanaAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl,
            String artistArtworkUrl, MirroringAudioSourceManager sourceManager) {
        super(trackInfo, sourceManager);
        this.albumName = albumName;
        this.albumUrl = albumUrl;
        this.artistUrl = artistUrl;
        this.artistArtworkUrl = artistArtworkUrl;
    }

    public String getAlbumName() {
        return albumName;
    }

    public String getAlbumUrl() {
        return albumUrl;
    }

    public String getArtistUrl() {
        return artistUrl;
    }

    public String getArtistArtworkUrl() {
        return artistArtworkUrl;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new GaanaAudioTrack(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl,
                (GaanaAudioSourceManager) sourceManager);
    }
}