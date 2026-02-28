package com.slugyzeon.plugin.gaana;

import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import com.slugyzeon.plugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class GaanaAudioTrack extends MirroringAudioTrack {

    public GaanaAudioTrack(AudioTrackInfo trackInfo, GaanaAudioSourceManager sourceManager) {
        this(trackInfo, null, null, null, null, null, false, sourceManager);
    }

    public GaanaAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl,
            String artistArtworkUrl, String previewUrl, boolean isPreview, MirroringAudioSourceManager sourceManager) {
        super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview, sourceManager);
    }

    @Override
    protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
        return new MpegAudioTrack(trackInfo, stream);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new GaanaAudioTrack(trackInfo, (GaanaAudioSourceManager) sourceManager);
    }
}