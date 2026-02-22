package com.slugyzeon.plugin.lastfm;

import com.slugyzeon.plugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class LastFmAudioTrack extends MirroringAudioTrack {

    public LastFmAudioTrack(AudioTrackInfo trackInfo, LastFmAudioSourceManager sourceManager) {
        super(trackInfo, sourceManager);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new LastFmAudioTrack(trackInfo, (LastFmAudioSourceManager) sourceManager);
    }
}
