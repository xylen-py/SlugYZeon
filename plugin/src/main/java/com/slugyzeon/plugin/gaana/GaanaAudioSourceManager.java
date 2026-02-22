package com.slugyzeon.plugin.gaana;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GaanaAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(GaanaAudioSourceManager.class);
    private static final String SOURCE_NAME = "gaana";
    private static final String SEARCH_PREFIX = "gnsearch:";
    private static final Pattern GAANA_URL_PATTERN = Pattern.compile(
            "^@?(?:https?://)?(?:www\\.)?gaana\\.com/(?<type>song|album|playlist|artist)/(?<seokey>[\\w-]+)(?:[?#].*)?$");

    private final GaanaApiHandler apiHandler;
    private final SlugYZeonConfig.GaanaConfig config;
    private final HttpInterfaceManager httpInterfaceManager;
    private volatile AudioPlayerManager playerManager;

    public GaanaAudioSourceManager(SlugYZeonConfig.GaanaConfig config) {
        this.config = config;
        this.apiHandler = new GaanaApiHandler(config);
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
        this.httpInterfaceManager.configureBuilder(
                builder -> builder.addInterceptorFirst((org.apache.http.HttpRequestInterceptor) (request, context) -> {
                    request.setHeader("Referer", "https://gaana.com/");
                    request.setHeader("Origin", "https://gaana.com");
                    request.setHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
                }));
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    public AudioPlayerManager getPlayerManager() {
        return playerManager;
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

        Matcher matcher = GAANA_URL_PATTERN.matcher(identifier);
        if (!matcher.matches())
            return null;

        String type = matcher.group("type");
        String seokey = matcher.group("seokey");
        if (type == null || seokey == null)
            return null;

        try {
            switch (type) {
                case "song":
                    return loadSong(seokey);
                case "album":
                    return loadAlbum(seokey);
                case "playlist":
                    return loadPlaylist(seokey);
                case "artist":
                    return loadArtist(seokey);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.error("[Gaana] Failed to load {}/{}: {}", type, seokey, e.getMessage());
            return null;
        }
    }

    private AudioItem searchTracks(String query) {
        try {
            JsonNode data = apiHandler.searchSongs(query, 10);
            if (data == null || !data.isArray() || data.isEmpty())
                return AudioReference.NO_TRACK;

            List<AudioTrack> tracks = new ArrayList<>();
            for (JsonNode item : data) {
                AudioTrack track = mapTrack(item);
                if (track != null)
                    tracks.add(track);
            }
            return tracks.isEmpty() ? AudioReference.NO_TRACK
                    : new BasicAudioPlaylist("Gaana Search: " + query, tracks, null, true);
        } catch (Exception e) {
            log.error("[Gaana] Search failed for '{}': {}", query, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem loadSong(String seokey) throws IOException {
        JsonNode data = apiHandler.getSong(seokey);
        if (data == null)
            return AudioReference.NO_TRACK;
        AudioTrack track = mapTrack(data);
        return track != null ? track : AudioReference.NO_TRACK;
    }

    private AudioItem loadAlbum(String seokey) throws IOException {
        JsonNode data = apiHandler.getAlbum(seokey);
        if (data == null)
            return AudioReference.NO_TRACK;
        return buildPlaylist(data, "album");
    }

    private AudioItem loadPlaylist(String seokey) throws IOException {
        JsonNode data = apiHandler.getPlaylist(seokey);
        if (data == null)
            return AudioReference.NO_TRACK;
        JsonNode playlist = data.has("playlist") ? data.get("playlist") : data;
        return buildPlaylist(playlist, "playlist");
    }

    private AudioItem loadArtist(String seokey) throws IOException {
        JsonNode data = apiHandler.getArtist(seokey);
        if (data == null)
            return AudioReference.NO_TRACK;
        return buildPlaylist(data, "artist");
    }

    private AudioItem buildPlaylist(JsonNode data, String type) {
        String name = getTextField(data, "title", "name", "playlist_name");
        if (name == null)
            name = "Gaana";
        if ("artist".equals(type))
            name = name + "'s Top Tracks";

        JsonNode tracksArray = data.has("tracks") ? data.get("tracks")
                : data.has("top_tracks") ? data.get("top_tracks")
                        : data.has("songs") ? data.get("songs") : null;

        if (tracksArray == null || !tracksArray.isArray())
            return AudioReference.NO_TRACK;

        int limit = getLoadLimit(type);
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode item : tracksArray) {
            if (tracks.size() >= limit)
                break;
            AudioTrack track = mapTrack(item);
            if (track != null)
                tracks.add(track);
        }
        return tracks.isEmpty() ? AudioReference.NO_TRACK : new BasicAudioPlaylist(name, tracks, null, false);
    }

    AudioTrack mapTrack(JsonNode track) {
        if (track == null)
            return null;
        String title = getTextField(track, "title", "name");
        if (title == null)
            return null;

        long duration = 0;
        if (track.has("duration"))
            duration = track.get("duration").asLong(0) * 1000;

        String author = formatArtists(track.get("artists"));
        if (author == null || author.isEmpty())
            author = "Unknown";

        String identifier;
        if (track.has("track_id"))
            identifier = track.get("track_id").asText();
        else if (track.has("seokey"))
            identifier = track.get("seokey").asText();
        else
            return null;

        String seokey = track.has("seokey") ? track.get("seokey").asText() : null;
        String uri = track.has("song_url") ? track.get("song_url").asText()
                : seokey != null ? "https://gaana.com/song/" + seokey : null;

        String artworkUrl = getTextField(track, "artworkUrl", "artwork");
        String isrc = track.has("isrc") ? track.get("isrc").asText(null) : null;

        AudioTrackInfo info = new AudioTrackInfo(title, author, duration, identifier, false, uri, artworkUrl, isrc);
        return new GaanaAudioTrack(info, this);
    }

    private int getLoadLimit(String type) {
        switch (type) {
            case "album":
                return config.getAlbumLoadLimit();
            case "artist":
                return config.getArtistLoadLimit();
            default:
                return config.getPlaylistLoadLimit();
        }
    }

    private String getTextField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            if (node.has(name) && !node.get(name).isNull()) {
                String v = node.get(name).asText("").trim();
                if (!v.isEmpty())
                    return v;
            }
        }
        return null;
    }

    private String formatArtists(JsonNode artists) {
        if (artists == null || artists.isNull())
            return null;
        if (artists.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode a : artists) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(a.isObject() && a.has("name") ? a.get("name").asText() : a.asText());
            }
            return sb.toString();
        }
        return artists.asText();
    }

    public GaanaApiHandler getApiHandler() {
        return apiHandler;
    }

    public SlugYZeonConfig.GaanaConfig getConfig() {
        return config;
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
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
        return new GaanaAudioTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (Exception ignored) {
        }
    }
}
