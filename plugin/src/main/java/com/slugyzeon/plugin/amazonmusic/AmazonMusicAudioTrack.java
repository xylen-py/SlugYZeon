package com.slugyzeon.plugin.amazonmusic;

import com.slugyzeon.plugin.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

public class AmazonMusicAudioTrack extends MirroringAudioTrack {

    public AmazonMusicAudioTrack(AudioTrackInfo trackInfo, AmazonMusicAudioSourceManager sourceManager) {
        super(trackInfo, sourceManager);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new AmazonMusicAudioTrack(trackInfo, (AmazonMusicAudioSourceManager) sourceManager);
    }
}