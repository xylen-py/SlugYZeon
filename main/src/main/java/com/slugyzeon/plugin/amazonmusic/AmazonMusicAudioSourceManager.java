package com.slugyzeon.plugin.amazonmusic;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.ExtendedAudioPlaylist;
import com.slugyzeon.plugin.mirror.DefaultMirroringAudioTrackResolver;
import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AmazonMusicAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(AmazonMusicAudioSourceManager.class);

    public static final String SOURCE_NAME = "amazonmusic";
    public static final String SEARCH_PREFIX = "azsearch:";

    public static final Pattern AMAZON_MUSIC_URL = Pattern.compile(
            "https?://music\\.amazon\\.[a-z.]+/(?:.*/)?(?<type>track|album|playlist|artist|user-playlist)s?/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern AMAZON_DP_URL = Pattern.compile(
            "https?://(?:www\\.)?amazon\\.[a-z.]+/dp/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final AmazonMusicApiHandler api;
    private final String countryCode;
    private final int playlistLoadLimit;
    private final int albumLoadLimit;
    private final int artistLoadLimit;

    public AmazonMusicAudioSourceManager(String[] providers, String countryCode,
            int playlistLoadLimit, int albumLoadLimit, int artistLoadLimit,
            Function<Void, AudioPlayerManager> manager) {
        super(manager, new DefaultMirroringAudioTrackResolver(providers));
        this.countryCode = countryCode;
        this.playlistLoadLimit = playlistLoadLimit;
        this.albumLoadLimit = albumLoadLimit;
        this.artistLoadLimit = artistLoadLimit;
        this.api = new AmazonMusicApiHandler(countryCode);
    }

    @Override
    public AudioPlayerManager getAudioPlayerManager() {
        return this.audioPlayerManager.apply(null);
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
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

            Matcher matcher = AMAZON_MUSIC_URL.matcher(identifier);
            if (matcher.find()) {
                String type = matcher.group("type").toLowerCase();
                String id = matcher.group("id");

                String trackAsin = null;
                if (identifier.contains("trackAsin=")) {
                    trackAsin = identifier.split("trackAsin=")[1].split("&")[0].split("#")[0];
                }
                if (trackAsin != null)
                    return resolveTrack(identifier, trackAsin);

                if (type.equals("user-playlist")) type = "playlist";

                switch (type) {
                    case "track":
                    case "dp":
                        return resolveTrack(identifier, id);
                    case "album":
                        return resolveCollection(identifier, "album", id);
                    case "playlist":
                        return resolveCollection(identifier, "playlist", id);
                    case "artist":
                        return resolveCollection(identifier, "artist", id);
                    default:
                        return null;
                }
            }

            Matcher dpMatcher = AMAZON_DP_URL.matcher(identifier);
            if (dpMatcher.find())
                return resolveTrack(identifier, dpMatcher.group("id"));

            return null;
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Amazon Music track", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioItem getSearch(String query) throws IOException {
        if (query.isEmpty())
            return AudioReference.NO_TRACK;

        List<Map<String, Object>> results = api.search(query);
        if (results == null || results.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (Map<String, Object> result : results) {
            AudioTrack track = mapTrack(result);
            if (track != null)
                tracks.add(track);
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        AudioTrack first = tracks.get(0);
        if (first.getDuration() == 0 && first.getInfo().uri != null) {
            try {
                String trackId = first.getInfo().identifier;
                AudioItem resolved = resolveTrack(first.getInfo().uri, trackId);
                if (resolved instanceof AudioTrack) {
                    tracks.set(0, (AudioTrack) resolved);
                }
            } catch (Exception e) {
            }
        }

        return new BasicAudioPlaylist("Amazon Music Search: " + query, tracks, null, true);
    }

    public AudioItem resolveTrack(String url, String id) throws IOException {
        Map<String, Object> data = api.fetchEntity(url, id, "track");

        if (data != null && "track".equals(data.get("_type")))
            return mapTrack(data);

        return AudioReference.NO_TRACK;
    }

    @SuppressWarnings("unchecked")
    public AudioItem resolveCollection(String url, String type, String id) throws IOException {
        Map<String, Object> data = api.fetchEntity(url, id, type);

        if (data == null || !"playlist".equals(data.get("_type")))
            return AudioReference.NO_TRACK;

        String name = (String) data.getOrDefault("name", "Amazon Music " + type);
        List<Map<String, Object>> trackMaps = (List<Map<String, Object>>) data.get("tracks");

        if (trackMaps == null || trackMaps.isEmpty())
            return AudioReference.NO_TRACK;

        int limit;
        switch (type) {
            case "album":
                limit = albumLoadLimit;
                break;
            case "artist":
                limit = artistLoadLimit;
                break;
            default:
                limit = playlistLoadLimit;
                break;
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (Map<String, Object> trackMap : trackMaps) {
            if (tracks.size() >= limit)
                break;
            AudioTrack track = mapTrack(trackMap);
            if (track != null)
                tracks.add(track);
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        ExtendedAudioPlaylist.Type playlistType;
        switch (type) {
            case "album":
                playlistType = ExtendedAudioPlaylist.Type.ALBUM;
                break;
            case "artist":
                playlistType = ExtendedAudioPlaylist.Type.ARTIST;
                break;
            default:
                playlistType = ExtendedAudioPlaylist.Type.PLAYLIST;
                break;
        }

        String artworkUrl = tracks.get(0).getInfo().artworkUrl;

        return new AmazonMusicAudioPlaylist(
                name,
                tracks,
                playlistType,
                url,
                artworkUrl,
                null,
                tracks.size());
    }

    private AudioTrack mapTrack(Map<String, Object> data) {
        if (data == null)
            return null;

        String title = (String) data.getOrDefault("title", "Unknown Track");
        String author = (String) data.getOrDefault("author", "Unknown Artist");
        String identifier = (String) data.getOrDefault("identifier", "");
        String uri = (String) data.get("uri");
        String artworkUrl = (String) data.get("artworkUrl");
        String isrc = (String) data.get("isrc");
        long length = data.containsKey("length") ? ((Number) data.get("length")).longValue() : 0;

        AudioTrackInfo info = new AudioTrackInfo(title, author, length, identifier, false, uri, artworkUrl, isrc);
        return new AmazonMusicAudioTrack(info, null, null, null, null, null, false, this);
    }

    public AmazonMusicApiHandler getApiHandler() {
        return api;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        var extendedAudioTrackInfo = super.decodeTrack(input);
        return new AmazonMusicAudioTrack(trackInfo,
                extendedAudioTrackInfo.albumName,
                extendedAudioTrackInfo.albumUrl,
                extendedAudioTrackInfo.artistUrl,
                extendedAudioTrackInfo.artistArtworkUrl,
                extendedAudioTrackInfo.previewUrl,
                extendedAudioTrackInfo.isPreview,
                this);
    }
}
