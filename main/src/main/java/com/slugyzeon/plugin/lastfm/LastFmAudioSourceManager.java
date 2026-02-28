package com.slugyzeon.plugin.lastfm;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LastFmAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(LastFmAudioSourceManager.class);

    public static final String SOURCE_NAME = "lastfm";
    public static final String SEARCH_PREFIX = "lfsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "lfrec:";

    public static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?last\\.fm/(?:[a-z]{2}/)?music/(?<artist>[^/]+)(?:/(?<second>[^/]+)(?:/(?<third>[^/]+))?)?(?:[?#].*)?$");

    private final LastFmApiHandler api;
    private final int searchLimit;
    private final int albumLoadLimit;
    private final int artistLoadLimit;

    public LastFmAudioSourceManager(String apiKey, String[] providers, int searchLimit,
            int albumLoadLimit, int artistLoadLimit,
            Function<Void, AudioPlayerManager> manager) {
        super(manager, new DefaultMirroringAudioTrackResolver(providers));
        this.api = new LastFmApiHandler(apiKey);
        this.searchLimit = searchLimit > 0 ? searchLimit : 10;
        this.albumLoadLimit = albumLoadLimit > 0 ? albumLoadLimit : 50;
        this.artistLoadLimit = artistLoadLimit > 0 ? artistLoadLimit : 10;
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
                String query = identifier.substring(SEARCH_PREFIX.length()).trim();
                if (query.isEmpty())
                    return null;
                return getSearch(query);
            }

            if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                String input = identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim();
                if (input.isEmpty())
                    return null;
                return getRecommendations(input);
            }

            Matcher matcher = URL_PATTERN.matcher(identifier);
            if (!matcher.matches())
                return null;

            String artist = decodeUrl(matcher.group("artist"));
            String second = matcher.group("second");
            String third = matcher.group("third");

            if (second == null) {
                return getArtist(artist);
            } else if (second.equals("_") && third != null) {
                return getTrack(artist, decodeUrl(third));
            } else if (second.equals("+tracks")) {
                return getArtist(artist);
            } else if (second.equals("+albums")) {
                return null;
            } else {
                return getAlbum(artist, decodeUrl(second));
            }
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Last.fm item", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioItem getSearch(String query) throws IOException {
        if (api.hasApiKey()) {
            return getApiSearch(query);
        }
        return getHtmlSearch(query);
    }

    private AudioItem getApiSearch(String query) throws IOException {
        JsonNode json = api.searchTracks(query, searchLimit);
        if (json == null)
            return AudioReference.NO_TRACK;

        JsonNode trackMatches = json.path("results").path("trackmatches").path("track");
        if (!trackMatches.isArray() || trackMatches.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode track : trackMatches) {
            AudioTrack parsed = parseApiTrack(track);
            if (parsed != null)
                tracks.add(parsed);
            if (tracks.size() >= searchLimit)
                break;
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new LastFmAudioPlaylist(
                "Last.fm Search: " + query,
                tracks,
                ExtendedAudioPlaylist.Type.PLAYLIST,
                null,
                null,
                null,
                tracks.size());
    }

    private AudioItem getHtmlSearch(String query) throws IOException {
        String searchUrl = "https://www.last.fm/search/tracks?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = api.fetchPageBody(searchUrl);
        if (html == null)
            return AudioReference.NO_TRACK;

        List<Map<String, String>> results = api.parseHtmlSearchResults(html);
        if (results.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (Map<String, String> result : results) {
            String title = result.get("title");
            String artist = result.get("artist");
            String url = result.get("url");
            if (title == null || artist == null)
                continue;

            AudioTrackInfo info = new AudioTrackInfo(title, artist, 0, url, false, url, null, null);
            tracks.add(new LastFmAudioTrack(info, this));
            if (tracks.size() >= searchLimit)
                break;
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new LastFmAudioPlaylist(
                "Last.fm Search: " + query,
                tracks,
                ExtendedAudioPlaylist.Type.PLAYLIST,
                null,
                null,
                null,
                tracks.size());
    }

    public AudioItem getRecommendations(String input) throws IOException {
        if (!api.hasApiKey())
            return AudioReference.NO_TRACK;

        String[] parts = input.split(" - ", 2);
        String artist, track;
        if (parts.length == 2) {
            artist = parts[0].trim();
            track = parts[1].trim();
        } else {
            artist = input.trim();
            track = input.trim();
        }

        JsonNode json = api.getSimilarTracks(artist, track, searchLimit);
        if (json == null)
            return AudioReference.NO_TRACK;

        JsonNode similarTracks = json.path("similartracks").path("track");
        if (!similarTracks.isArray() || similarTracks.isEmpty())
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode t : similarTracks) {
            AudioTrack parsed = parseApiTrack(t);
            if (parsed != null)
                tracks.add(parsed);
            if (tracks.size() >= searchLimit)
                break;
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;

        return new LastFmAudioPlaylist(
                "Last.fm Similar: " + input,
                tracks,
                ExtendedAudioPlaylist.Type.RECOMMENDATIONS,
                null,
                null,
                null,
                tracks.size());
    }

    public AudioItem getTrack(String artist, String title) throws IOException {

        if (api.hasApiKey()) {
            JsonNode json = api.getTrackInfo(artist, title);
            if (json != null) {
                JsonNode trackNode = json.path("track");
                if (!trackNode.isMissingNode()) {
                    AudioTrack track = parseApiDetailTrack(trackNode, artist);
                    if (track != null)
                        return track;
                }
            }
        }

        String url = "https://www.last.fm/music/" + encodeUrl(artist) + "/_/" + encodeUrl(title);
        AudioTrackInfo info = new AudioTrackInfo(title, artist, 0, url, false, url, null, null);
        return new LastFmAudioTrack(info, this);
    }

    public AudioItem getAlbum(String artist, String albumName) throws IOException {
        String albumUrl = "https://www.last.fm/music/" + encodeUrl(artist) + "/" + encodeUrl(albumName);

        if (api.hasApiKey()) {
            JsonNode albumInfo = api.getAlbumInfo(artist, albumName);
            if (albumInfo != null) {
                JsonNode albumNode = albumInfo.path("album");
                JsonNode tracksArray = albumNode.path("tracks").path("track");
                if (tracksArray.isArray() && !tracksArray.isEmpty()) {
                    List<AudioTrack> trackList = new ArrayList<>();
                    String artworkUrl = extractImageUrl(albumNode);

                    for (JsonNode track : tracksArray) {
                        String trackTitle = track.path("name").asText("");
                        String trackArtist = track.path("artist").path("name").asText(artist);
                        String trackUrl = track.path("url").asText(albumUrl);
                        long duration = track.path("duration").asLong(0) * 1000;

                        AudioTrackInfo trackInfo = new AudioTrackInfo(
                                trackTitle, trackArtist, duration, trackUrl, false, trackUrl, artworkUrl, null);
                        trackList.add(
                                new LastFmAudioTrack(trackInfo, albumName, albumUrl, null, null, null, false, this));
                    }

                    if (!trackList.isEmpty()) {
                        return new LastFmAudioPlaylist(
                                albumName + " - " + artist, trackList,
                                ExtendedAudioPlaylist.Type.ALBUM, albumUrl, artworkUrl, artist, trackList.size());
                    }
                }
            }
        }

        return resolveFromHtml(albumUrl, artist, albumName, ExtendedAudioPlaylist.Type.ALBUM);
    }

    public AudioItem getArtist(String artist) throws IOException {
        String artistUrl = "https://www.last.fm/music/" + encodeUrl(artist);

        if (api.hasApiKey()) {
            JsonNode topTracks = api.getArtistTopTracks(artist, searchLimit);
            if (topTracks != null) {
                JsonNode tracks = topTracks.path("toptracks").path("track");
                if (tracks.isArray() && !tracks.isEmpty()) {
                    List<AudioTrack> trackList = new ArrayList<>();

                    for (JsonNode track : tracks) {
                        AudioTrack parsed = parseApiTrack(track);
                        if (parsed != null)
                            trackList.add(parsed);
                        if (trackList.size() >= searchLimit)
                            break;
                    }

                    if (!trackList.isEmpty()) {
                        return new LastFmAudioPlaylist(
                                artist + " - Top Tracks", trackList,
                                ExtendedAudioPlaylist.Type.ARTIST, artistUrl, null, artist, trackList.size());
                    }
                }
            }
        }

        return resolveFromHtml(artistUrl, artist, artist + " Top Tracks", ExtendedAudioPlaylist.Type.ARTIST);
    }

    private AudioItem resolveFromHtml(String url, String artist, String title,
            ExtendedAudioPlaylist.Type playlistType) throws IOException {
        String pageBody = api.fetchPageBody(url);
        if (pageBody == null)
            return AudioReference.NO_TRACK;

        List<String> youtubeUrls = api.extractYouTubeUrls(pageBody);
        if (youtubeUrls.isEmpty()) {
            AudioTrackInfo info = new AudioTrackInfo(title, artist, 0, url, false, url, null, null);
            return new LastFmAudioTrack(info, this);
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (String ytUrl : youtubeUrls) {
            AudioTrackInfo info = new AudioTrackInfo(
                    title, artist, 0, ytUrl, false, url, null, null);
            tracks.add(new LastFmAudioTrack(info, this));
        }

        return new LastFmAudioPlaylist(title + " - " + artist, tracks,
                playlistType, url, null, artist, tracks.size());
    }

    private AudioTrack parseApiTrack(JsonNode node) {
        if (node == null)
            return null;

        String title = node.path("name").asText("");
        if (title.isEmpty())
            return null;

        String artist;
        JsonNode artistNode = node.path("artist");
        if (artistNode.isObject()) {
            artist = artistNode.path("name").asText("Unknown");
        } else {
            artist = artistNode.asText("Unknown");
        }

        String url = node.path("url").asText("");
        long duration = node.path("duration").asLong(0) * 1000;
        String artworkUrl = extractImageUrl(node);
        String mbid = node.path("mbid").asText(null);

        String artistUrl = null;
        if (artistNode.isObject()) {
            artistUrl = artistNode.path("url").asText(null);
        }

        AudioTrackInfo info = new AudioTrackInfo(
                title, artist, duration, url, false, url, artworkUrl, null);
        return new LastFmAudioTrack(info, null, null, artistUrl, null, null, false, this);
    }

    private AudioTrack parseApiDetailTrack(JsonNode trackNode, String fallbackArtist) {
        String title = trackNode.path("name").asText("");
        if (title.isEmpty())
            return null;

        String artist = trackNode.path("artist").path("name").asText(fallbackArtist);
        String url = trackNode.path("url").asText("");
        long duration = trackNode.path("duration").asLong(0);
        String artworkUrl = extractImageUrl(trackNode.path("album"));

        String albumName = trackNode.path("album").path("title").asText(null);
        String albumUrl = trackNode.path("album").path("url").asText(null);
        String artistUrl = trackNode.path("artist").path("url").asText(null);

        AudioTrackInfo info = new AudioTrackInfo(
                title, artist, duration, url, false, url, artworkUrl, null);
        return new LastFmAudioTrack(info, albumName, albumUrl, artistUrl, null, null, false, this);
    }

    private String extractImageUrl(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        JsonNode images = node.path("image");
        if (images.isArray()) {
            for (int i = images.size() - 1; i >= 0; i--) {
                String url = images.get(i).path("#text").asText("");
                if (!url.isEmpty())
                    return url;
            }
        }
        return null;
    }

    private static String decodeUrl(String text) {
        if (text == null)
            return null;
        try {
            return java.net.URLDecoder.decode(text.replace("+", " "), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text.replace("+", " ");
        }
    }

    private static String encodeUrl(String text) {
        if (text == null)
            return "";
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("%20", "+");
    }

    public LastFmApiHandler getApiHandler() {
        return api;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        var extendedAudioTrackInfo = super.decodeTrack(input);
        return new LastFmAudioTrack(trackInfo,
                extendedAudioTrackInfo.albumName,
                extendedAudioTrackInfo.albumUrl,
                extendedAudioTrackInfo.artistUrl,
                extendedAudioTrackInfo.artistArtworkUrl,
                extendedAudioTrackInfo.previewUrl,
                extendedAudioTrackInfo.isPreview,
                this);
    }
}
