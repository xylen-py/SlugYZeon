package com.slugyzeon.plugin.amazonmusic;

import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import com.slugyzeon.plugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class AmazonMusicAudioTrack extends MirroringAudioTrack {

    private final String albumName;
    private final String albumUrl;
    private final String artistUrl;

    public AmazonMusicAudioTrack(AudioTrackInfo trackInfo, AmazonMusicAudioSourceManager sourceManager) {
        this(trackInfo, null, null, null, sourceManager);
    }

    public AmazonMusicAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl,
            MirroringAudioSourceManager sourceManager) {
        super(trackInfo, sourceManager);
        this.albumName = albumName;
        this.albumUrl = albumUrl;
        this.artistUrl = artistUrl;
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

    @Override
    protected AudioTrack makeShallowClone() {
        return new AmazonMusicAudioTrack(trackInfo, albumName, albumUrl, artistUrl,
                (AmazonMusicAudioSourceManager) sourceManager);
    }
}