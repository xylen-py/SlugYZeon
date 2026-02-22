package com.slugyzeon.plugin.lastfm;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import com.slugyzeon.plugin.mirror.DefaultMirroringAudioTrackResolver;
import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LastFmAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(LastFmAudioSourceManager.class);
    private static final String SOURCE_NAME = "lastfm";
    private static final String SEARCH_PREFIX = "lfsearch:";
    private static final Pattern LASTFM_URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?last\\.fm/(?:[a-z]{2}/)?music/.+");

    private final LastFmApiHandler apiHandler;
    private final SlugYZeonConfig.LastFmConfig config;
    private volatile AudioPlayerManager playerManager;

    public LastFmAudioSourceManager(SlugYZeonConfig.LastFmConfig config) {
        super(unused -> null, new DefaultMirroringAudioTrackResolver(null));
        this.config = config;
        this.apiHandler = new LastFmApiHandler(config.getApiKey(), config.getMaxSearchResults());
    }

    @Override
    public AudioPlayerManager getAudioPlayerManager() {
        return playerManager;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        this.playerManager = manager;
        String identifier = reference.identifier;

        if (identifier.startsWith(SEARCH_PREFIX)) {
            String query = identifier.substring(SEARCH_PREFIX.length()).trim();
            if (query.isEmpty())
                return AudioReference.NO_TRACK;
            return searchTracks(query);
        }

        if (!LASTFM_URL_PATTERN.matcher(identifier).matches())
            return null;

        return resolveUrl(identifier);
    }

    private AudioItem searchTracks(String query) {
        try {
            List<Map<String, String>> results;

            if (apiHandler.hasApiKey()) {
                results = apiHandler.searchTracksApi(query);
            } else {
                results = apiHandler.searchTracksHtml(query);
            }

            if (results == null || results.isEmpty())
                return AudioReference.NO_TRACK;

            List<AudioTrack> tracks = new ArrayList<>();
            for (Map<String, String> result : results) {
                AudioTrack track = mapTrack(result);
                if (track != null)
                    tracks.add(track);
            }

            return tracks.isEmpty() ? AudioReference.NO_TRACK
                    : new BasicAudioPlaylist("Last.fm Search: " + query, tracks, null, true);
        } catch (Exception e) {
            log.error("Search failed for '{}': {}", query, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem resolveUrl(String url) {
        try {
            Map<String, String> pathInfo = apiHandler.parseUrlPath(url);
            if (pathInfo == null)
                return AudioReference.NO_TRACK;

            String artist = pathInfo.get("artist");
            String title = pathInfo.get("title");
            boolean isTrack = Boolean.parseBoolean(pathInfo.getOrDefault("isTrack", "false"));

            if (isTrack) {
                return resolveSingleTrack(url, artist, title);
            } else {
                return resolveCollection(url, artist, title);
            }
        } catch (Exception e) {
            log.error("Failed to resolve Last.fm URL {}: {}", url, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem resolveSingleTrack(String url, String artist, String title) {
        AudioTrackInfo info = new AudioTrackInfo(
                title, artist, 0, url, false, url, null, null);
        return new LastFmAudioTrack(info, this);
    }

    private AudioItem resolveCollection(String url, String artist, String title) {
        try {
            String pageBody = apiHandler.fetchPageBody(url);
            if (pageBody == null)
                return AudioReference.NO_TRACK;

            List<String> youtubeUrls = apiHandler.extractYouTubeUrls(pageBody);
            if (youtubeUrls.isEmpty()) {
                AudioTrackInfo info = new AudioTrackInfo(
                        title, artist, 0, url, false, url, null, null);
                return new LastFmAudioTrack(info, this);
            }

            List<AudioTrack> tracks = new ArrayList<>();
            for (String ytUrl : youtubeUrls) {
                AudioTrackInfo info = new AudioTrackInfo(
                        title + " (YouTube)", artist, 0, ytUrl, false, url, null, null);
                tracks.add(new LastFmAudioTrack(info, this));
            }

            String playlistName = title + " - " + artist;
            return new BasicAudioPlaylist(playlistName, tracks, null, false);
        } catch (Exception e) {
            log.error("Failed to resolve Last.fm collection: {}", e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioTrack mapTrack(Map<String, String> data) {
        if (data == null)
            return null;

        String title = data.getOrDefault("title", "Unknown Track");
        String author = data.getOrDefault("author", "Unknown Artist");
        String uri = data.get("uri");
        String artworkUrl = data.get("artworkUrl");
        String identifier = uri != null ? uri : author + " - " + title;

        AudioTrackInfo info = new AudioTrackInfo(title, author, 0, identifier, false, uri, artworkUrl, null);
        return new LastFmAudioTrack(info, this);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new LastFmAudioTrack(trackInfo, this);
    }
}
