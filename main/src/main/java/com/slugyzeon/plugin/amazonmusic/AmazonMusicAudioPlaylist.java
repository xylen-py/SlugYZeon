package com.slugyzeon.plugin.amazonmusic;

import com.slugyzeon.plugin.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.List;

public class AmazonMusicAudioPlaylist extends ExtendedAudioPlaylist {

    public AmazonMusicAudioPlaylist(String name, List<AudioTrack> tracks, ExtendedAudioPlaylist.Type type, String url,
            String artworkURL, String author, Integer totalTracks) {
        super(name, tracks, type, url, artworkURL, author, totalTracks);
    }
}
