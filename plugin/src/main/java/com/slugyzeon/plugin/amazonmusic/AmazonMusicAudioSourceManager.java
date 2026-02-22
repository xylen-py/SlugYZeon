package com.slugyzeon.plugin.amazonmusic;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
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
    private static final String SOURCE_NAME = "amazonmusic";
    private static final String SEARCH_PREFIX = "azsearch:";

    private static final Pattern AMAZON_MUSIC_URL = Pattern.compile(
            "https?://music\\.amazon\\.[a-z.]+/(?:.*/)?(?<type>track|album|playlist|artist)s?/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMAZON_DP_URL = Pattern.compile(
            "https?://(?:www\\.)?amazon\\.[a-z.]+/dp/(?<id>[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final AmazonMusicApiHandler apiHandler;
    private final SlugYZeonConfig.AmazonMusicConfig config;
    private volatile AudioPlayerManager playerManager;

    public AmazonMusicAudioSourceManager(SlugYZeonConfig.AmazonMusicConfig config) {
        super(unused -> null, new DefaultMirroringAudioTrackResolver(null));
        this.config = config;
        this.apiHandler = new AmazonMusicApiHandler(config);
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

        Matcher matcher = AMAZON_MUSIC_URL.matcher(identifier);
        if (matcher.find()) {
            String type = matcher.group("type").toLowerCase();
            String id = matcher.group("id");

            String trackAsin = AmazonMusicApiHandler.extractIdentifier(identifier);
            if (trackAsin != null && identifier.contains("trackAsin=")) {
                return resolveTrack(identifier, trackAsin);
            }

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
        if (dpMatcher.find()) {
            String id = dpMatcher.group("id");
            return resolveTrack(identifier, id);
        }

        return null;
    }

    private AudioItem searchTracks(String query) {
        try {
            List<Map<String, Object>> results = apiHandler.search(query);
            if (results == null || results.isEmpty())
                return AudioReference.NO_TRACK;

            List<AudioTrack> tracks = new ArrayList<>();
            for (Map<String, Object> result : results) {
                AudioTrack track = mapTrackFromResult(result);
                if (track != null)
                    tracks.add(track);
            }

            return tracks.isEmpty() ? AudioReference.NO_TRACK
                    : new BasicAudioPlaylist("Amazon Music Search: " + query, tracks, null, true);
        } catch (Exception e) {
            log.error("[AmazonMusic] Search failed for '{}': {}", query, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem resolveTrack(String url, String id) {
        try {
            Map<String, Object> data = apiHandler.fetchFromPage(url, id);

            if (data != null && "track".equals(data.get("_type"))) {
                return mapTrackFromResult(data);
            }

            Map<String, Object> odesliData = apiHandler.fetchFromOdesli(url);
            if (odesliData != null) {
                return mapTrackFromResult(odesliData);
            }

            return AudioReference.NO_TRACK;
        } catch (Exception e) {
            log.error("[AmazonMusic] Failed to resolve track {}: {}", id, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    @SuppressWarnings("unchecked")
    private AudioItem resolveCollection(String url, String type) {
        try {
            Map<String, Object> data = apiHandler.fetchFromPage(url, null);

            if (data != null && "playlist".equals(data.get("_type"))) {
                String name = (String) data.getOrDefault("name", "Amazon Music " + type);
                List<Map<String, Object>> trackMaps = (List<Map<String, Object>>) data.get("tracks");

                if (trackMaps != null && !trackMaps.isEmpty()) {
                    List<AudioTrack> tracks = new ArrayList<>();
                    for (Map<String, Object> trackMap : trackMaps) {
                        AudioTrack track = mapTrackFromResult(trackMap);
                        if (track != null)
                            tracks.add(track);
                    }
                    if (!tracks.isEmpty()) {
                        return new BasicAudioPlaylist(name, tracks, null, false);
                    }
                }
            }

            return AudioReference.NO_TRACK;
        } catch (Exception e) {
            log.error("[AmazonMusic] Failed to resolve {}: {}", type, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioTrack mapTrackFromResult(Map<String, Object> data) {
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
        return new AmazonMusicAudioTrack(info, this);
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
