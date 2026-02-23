package com.slugyzeon.plugin.gaana;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;

import java.util.List;

public class GaanaAudioPlaylist extends BasicAudioPlaylist {

    private final Type type;
    private final String url;
    private final String artworkURL;
    private final String author;
    private final Integer totalTracks;

    public GaanaAudioPlaylist(String name, List<AudioTrack> tracks, Type type, String url, String artworkURL,
            String author, Integer totalTracks) {
        super(name, tracks, null, type == Type.SEARCH);
        this.type = type;
        this.url = url;
        this.artworkURL = artworkURL;
        this.author = author;
        this.totalTracks = totalTracks;
    }

    public Type getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public String getArtworkURL() {
        return artworkURL;
    }

    public String getAuthor() {
        return author;
    }

    public Integer getTotalTracks() {
        return totalTracks;
    }

    public enum Type {
        ALBUM("album"),
        PLAYLIST("playlist"),
        ARTIST("artist"),
        SEARCH("search"),
        RECOMMENDATIONS("recommendations"),
        TRENDING("trending");

        public final String name;

        Type(String name) {
            this.name = name;
        }
    }
}
