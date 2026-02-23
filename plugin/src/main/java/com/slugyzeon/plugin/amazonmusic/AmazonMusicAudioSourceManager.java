package com.slugyzeon.plugin.amazonmusic;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import com.slugyzeon.plugin.mirror.DefaultMirroringAudioTrackResolver;
import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AmazonMusicAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(AmazonMusicAudioSourceManager.class);

    public static final String SOURCE_NAME = "amazonmusic";
    public static final String SEARCH_PREFIX = "azsearch:";

    public static final Pattern AMAZON_MUSIC_URL = Pattern.compile(
            "https?://music\\.amazon\\.[a-z.]+/(?:.*/)?(?<type>track|album|playlist|artist)s?/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern AMAZON_DP_URL = Pattern.compile(
            "https?://(?:www\\.)?amazon\\.[a-z.]+/dp/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final AmazonMusicApiHandler api;
    private final SlugYZeonConfig.AmazonMusicConfig config;
    private volatile AudioPlayerManager playerManager;

    public AmazonMusicAudioSourceManager(SlugYZeonConfig.AmazonMusicConfig config, AudioPlayerManager manager) {
        super(unused -> null, new DefaultMirroringAudioTrackResolver(config.getProviders()));
        this.config = config;
        this.api = new AmazonMusicApiHandler(config);
        this.playerManager = manager;
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

                String trackAsin = AmazonMusicApiHandler.extractIdentifier(identifier);
                if (trackAsin != null && identifier.contains("trackAsin="))
                    return resolveTrack(identifier, trackAsin);

                switch (type) {
                    case "track":
                    case "dp":
                        return resolveTrack(identifier, id);
                    case "album":
                        return resolveCollection(identifier, "album");
                    case "playlist":
                        return resolveCollection(identifier, "playlist");
                    case "artist":
                        return resolveCollection(identifier, "artist");
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
            long duration = api.fetchTrackDuration(first.getInfo().uri);
            if (duration > 0) {
                AudioTrackInfo old = first.getInfo();
                AudioTrackInfo fixed = new AudioTrackInfo(old.title, old.author, duration, old.identifier, false,
                        old.uri, old.artworkUrl, old.isrc);
                tracks.set(0, new AmazonMusicAudioTrack(fixed, null, null, null, this));
            }
        }

        return new AmazonMusicAudioPlaylist(
                "Amazon Music Search: " + query,
                tracks,
                AmazonMusicAudioPlaylist.Type.SEARCH,
                null,
                null,
                null,
                tracks.size());
    }

    public AudioItem resolveTrack(String url, String id) throws IOException {
        Map<String, Object> data = api.fetchFromPage(url, id);

        if (data != null && "track".equals(data.get("_type")))
            return mapTrack(data);

        Map<String, Object> odesliData = api.fetchFromOdesli(url);
        if (odesliData != null)
            return mapTrack(odesliData);

        return AudioReference.NO_TRACK;
    }

    @SuppressWarnings("unchecked")
    public AudioItem resolveCollection(String url, String type) throws IOException {
        Map<String, Object> data = api.fetchFromPage(url, null);

        if (data == null || !"playlist".equals(data.get("_type")))
            return AudioReference.NO_TRACK;

        String name = (String) data.getOrDefault("name", "Amazon Music " + type);
        List<Map<String, Object>> trackMaps = (List<Map<String, Object>>) data.get("tracks");

        if (trackMaps == null || trackMaps.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (Map<String, Object> trackMap : trackMaps) {
            AudioTrack track = mapTrack(trackMap);
            if (track != null)
                tracks.add(track);
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        AmazonMusicAudioPlaylist.Type playlistType;
        switch (type) {
            case "album":
                playlistType = AmazonMusicAudioPlaylist.Type.ALBUM;
                break;
            case "artist":
                playlistType = AmazonMusicAudioPlaylist.Type.ARTIST;
                break;
            default:
                playlistType = AmazonMusicAudioPlaylist.Type.PLAYLIST;
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

        String title = decodeHtml((String) data.getOrDefault("title", "Unknown Track"));
        String author = decodeHtml((String) data.getOrDefault("author", "Unknown Artist"));
        String identifier = (String) data.getOrDefault("identifier", "");
        String uri = (String) data.get("uri");
        String artworkUrl = (String) data.get("artworkUrl");
        String isrc = (String) data.get("isrc");
        long length = data.containsKey("length") ? ((Number) data.get("length")).longValue() : 0;

        AudioTrackInfo info = new AudioTrackInfo(title, author, length, identifier, false, uri, artworkUrl, isrc);
        return new AmazonMusicAudioTrack(info, null, null, null, this);
    }

    private static String decodeHtml(String text) {
        if (text == null)
            return null;
        return text
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ");
    }

    public AmazonMusicApiHandler getApiHandler() {
        return api;
    }

    public SlugYZeonConfig.AmazonMusicConfig getConfig() {
        return config;
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
        return new AmazonMusicAudioTrack(trackInfo, this);
    }
}
