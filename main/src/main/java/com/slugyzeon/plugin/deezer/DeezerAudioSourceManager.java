package com.slugyzeon.plugin.deezer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.ExtendedAudioPlaylist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeezerAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DeezerAudioSourceManager.class);

    public static final String SOURCE_NAME = "deezer";
    public static final String SEARCH_PREFIX = "dzsearch:";
    public static final String RECOMMEND_PREFIX = "dzrec:";
    public static final int MAX_SEARCH_RESULTS = 25;

    public static final Pattern URL_PATTERN = Pattern.compile(
            "^@?(?:https?://)?(?:www\\.)?deezer\\.com/(?:[a-z]{2}/)?(?<type>track|album|playlist|artist)/(?<id>\\d+)(?:[?#].*)?$");

    private final DeezerApiHandler api;
    private final HttpInterfaceManager httpInterfaceManager;
    private final int playlistLoadLimit;
    private final int albumLoadLimit;
    private final int artistLoadLimit;
    private final String preferredQuality;

    public DeezerAudioSourceManager(String apiUrl, int playlistLoadLimit, int albumLoadLimit,
            int artistLoadLimit) {
        this(apiUrl, playlistLoadLimit, albumLoadLimit, artistLoadLimit, "128");
    }

    public DeezerAudioSourceManager(String apiUrl, int playlistLoadLimit, int albumLoadLimit,
            int artistLoadLimit, String preferredQuality) {
        this.playlistLoadLimit = playlistLoadLimit;
        this.albumLoadLimit = albumLoadLimit;
        this.artistLoadLimit = artistLoadLimit;
        this.preferredQuality = preferredQuality != null ? preferredQuality : "128";
        this.api = new DeezerApiHandler(apiUrl);
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    public DeezerApiHandler getApiHandler() {
        return api;
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    public String getPreferredQuality() {
        return preferredQuality;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        return loadItem(reference.identifier);
    }

    public AudioItem loadItem(String identifier) {
        try {
            if (identifier.startsWith(SEARCH_PREFIX)) {
                return getSearch(identifier.substring(SEARCH_PREFIX.length()).trim());
            }

            if (identifier.startsWith(RECOMMEND_PREFIX)) {
                return getRecommendations(identifier.substring(RECOMMEND_PREFIX.length()).trim());
            }

            Matcher matcher = URL_PATTERN.matcher(identifier);
            if (!matcher.matches())
                return null;

            String type = matcher.group("type");
            String id = matcher.group("id");

            switch (type) {
                case "track":
                    return getTrack(id);
                case "album":
                    return getAlbum(id);
                case "playlist":
                    return getPlaylist(id);
                case "artist":
                    return getArtist(id);
                default:
                    return null;
            }
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Deezer track", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioItem getSearch(String query) throws IOException {
        if (query.isEmpty())
            return AudioReference.NO_TRACK;

        JsonNode data = api.searchTracks(query, MAX_SEARCH_RESULTS);
        if (data == null || !data.isArray() || data.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = parseTracks(data);
        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new DeezerAudioPlaylist(
                "Deezer Search: " + query,
                tracks,
                ExtendedAudioPlaylist.Type.PLAYLIST,
                null,
                null,
                null,
                tracks.size());
    }

    public AudioItem getRecommendations(String query) throws IOException {
        if (query.isEmpty())
            return AudioReference.NO_TRACK;

        JsonNode charts = api.getCharts(MAX_SEARCH_RESULTS);
        if (charts == null)
            return AudioReference.NO_TRACK;

        JsonNode tracksNode = charts.has("tracks") ? charts.get("tracks") : charts;
        if (tracksNode == null || !tracksNode.isArray() || tracksNode.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = parseTracks(tracksNode);
        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new DeezerAudioPlaylist(
                "Deezer Recommendations: " + query,
                tracks,
                ExtendedAudioPlaylist.Type.RECOMMENDATIONS,
                null,
                tracks.get(0).getInfo().artworkUrl != null ? tracks.get(0).getInfo().artworkUrl : null,
                null,
                tracks.size());
    }

    public AudioItem getTrack(String id) throws IOException {
        JsonNode data = api.getTrack(id);
        if (data == null)
            return AudioReference.NO_TRACK;

        AudioTrack track = parseTrack(data);
        return track != null ? track : AudioReference.NO_TRACK;
    }

    public AudioItem getAlbum(String id) throws IOException {
        JsonNode data = api.getAlbum(id);
        if (data == null)
            return AudioReference.NO_TRACK;

        String name = getField(data, "title");
        String artworkUrl = getField(data, "artworkUrl");
        if (artworkUrl == null)
            artworkUrl = getArtworkFromNode(data);
        String author = getField(data, "artists");
        String albumUrl = data.has("album_url") ? data.get("album_url").asText()
                : "https://www.deezer.com/album/" + id;
        int totalTracks = data.has("tracks_count") ? data.get("tracks_count").asInt(0) : 0;

        JsonNode tracksNode = getTracksArray(data);
        if (tracksNode == null)
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = parseTracksWithLimit(tracksNode, albumLoadLimit);
        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new DeezerAudioPlaylist(
                name != null ? name : "Deezer Album",
                tracks,
                ExtendedAudioPlaylist.Type.ALBUM,
                albumUrl,
                artworkUrl,
                author,
                totalTracks > 0 ? totalTracks : tracks.size());
    }

    public AudioItem getPlaylist(String id) throws IOException {
        JsonNode data = api.getPlaylist(id);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode playlist = data.has("playlist") ? data.get("playlist") : data;

        String name = getField(playlist, "title");
        String artworkUrl = getField(playlist, "artworkUrl");
        if (artworkUrl == null)
            artworkUrl = getArtworkFromNode(playlist);
        String author = null;
        if (playlist.has("creator") && playlist.get("creator").has("name"))
            author = playlist.get("creator").get("name").asText();
        String playlistUrl = playlist.has("playlist_url") ? playlist.get("playlist_url").asText()
                : "https://www.deezer.com/playlist/" + id;
        int totalTracks = playlist.has("tracks_count") ? playlist.get("tracks_count").asInt(0) : 0;

        JsonNode tracksNode = getTracksArray(playlist);
        if (tracksNode == null)
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = parseTracksWithLimit(tracksNode, playlistLoadLimit);
        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new DeezerAudioPlaylist(
                name != null ? name : "Deezer Playlist",
                tracks,
                ExtendedAudioPlaylist.Type.PLAYLIST,
                playlistUrl,
                artworkUrl,
                author,
                totalTracks > 0 ? totalTracks : tracks.size());
    }

    public AudioItem getArtist(String id) throws IOException {
        JsonNode data = api.getArtist(id);
        if (data == null)
            return AudioReference.NO_TRACK;

        String name = getField(data, "name");
        String artworkUrl = getField(data, "artworkUrl");
        if (artworkUrl == null)
            artworkUrl = getArtworkFromNode(data);
        String artistUrl = data.has("artist_url") ? data.get("artist_url").asText()
                : "https://www.deezer.com/artist/" + id;

        JsonNode tracksNode = data.has("top_tracks") ? data.get("top_tracks") : getTracksArray(data);
        if (tracksNode == null) {
            JsonNode radioData = api.getArtistRadio(id, artistLoadLimit);
            if (radioData != null && radioData.isArray())
                tracksNode = radioData;
        }
        if (tracksNode == null)
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = parseTracksWithLimit(tracksNode, artistLoadLimit);
        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new DeezerAudioPlaylist(
                (name != null ? name : "Artist") + "'s Top Tracks",
                tracks,
                ExtendedAudioPlaylist.Type.ARTIST,
                artistUrl,
                artworkUrl,
                name,
                tracks.size());
    }

    private JsonNode getTracksArray(JsonNode data) {
        for (String key : new String[] { "tracks", "top_tracks", "songs" }) {
            if (data.has(key) && data.get(key).isArray())
                return data.get(key);
        }
        return null;
    }

    private List<AudioTrack> parseTracks(JsonNode array) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode item : array) {
            AudioTrack track = parseTrack(item);
            if (track != null)
                tracks.add(track);
        }
        return tracks;
    }

    private List<AudioTrack> parseTracksWithLimit(JsonNode array, int limit) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode item : array) {
            if (tracks.size() >= limit)
                break;
            AudioTrack track = parseTrack(item);
            if (track != null)
                tracks.add(track);
        }
        return tracks;
    }

    private AudioTrack parseTrack(JsonNode node) {
        if (node == null)
            return null;

        String title = getField(node, "title", "title_short");
        if (title == null)
            return null;

        long duration = 0;
        if (node.has("duration"))
            duration = node.get("duration").asLong(0) * 1000;

        String author = getField(node, "artists");
        if (author == null || author.isEmpty()) {
            if (node.has("artist") && node.get("artist").isObject() && node.get("artist").has("name"))
                author = node.get("artist").get("name").asText("Unknown");
            else
                author = "Unknown";
        }

        String identifier = null;
        if (node.has("id"))
            identifier = String.valueOf(node.get("id").asLong());
        if (identifier == null)
            return null;

        String uri = node.has("track_url") ? node.get("track_url").asText()
                : "https://www.deezer.com/track/" + identifier;

        String artworkUrl = getField(node, "artworkUrl");
        if (artworkUrl == null)
            artworkUrl = getArtworkFromNode(node);

        String isrc = node.has("isrc") ? node.get("isrc").asText(null) : null;

        String albumName = node.has("album_title") ? node.get("album_title").asText(null) : null;
        String albumUrl = node.has("album_url") ? node.get("album_url").asText(null) : null;
        if (albumUrl == null && node.has("album_id") && node.get("album_id").asLong(0) > 0)
            albumUrl = "https://www.deezer.com/album/" + node.get("album_id").asLong();

        String artistUrl = null;
        if (node.has("artist") && node.get("artist").isObject() && node.get("artist").has("artist_url"))
            artistUrl = node.get("artist").get("artist_url").asText(null);

        String artistArtworkUrl = null;
        if (node.has("artist") && node.get("artist").isObject() && node.get("artist").has("artwork"))
            artistArtworkUrl = node.get("artist").get("artwork").asText(null);

        String previewUrl = node.has("preview_url") ? node.get("preview_url").asText(null) : null;

        AudioTrackInfo info = new AudioTrackInfo(title, author, duration, identifier, false, uri, artworkUrl, isrc);
        return new DeezerAudioTrack(info, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, this);
    }

    private String getArtworkFromNode(JsonNode node) {
        if (node.has("artwork") && node.get("artwork").isObject()) {
            JsonNode artwork = node.get("artwork");
            for (String key : new String[] { "xl", "large", "medium", "small" }) {
                if (artwork.has(key) && !artwork.get(key).asText("").isEmpty())
                    return artwork.get(key).asText();
            }
        }
        return null;
    }

    private String getField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            if (node.has(name) && !node.get(name).isNull() && !node.get(name).isObject()
                    && !node.get(name).isArray()) {
                String v = node.get(name).asText("").trim();
                if (!v.isEmpty())
                    return v;
            }
        }
        return null;
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
        return new DeezerAudioTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }
}
