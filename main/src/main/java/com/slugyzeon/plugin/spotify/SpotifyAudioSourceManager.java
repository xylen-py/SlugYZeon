package com.slugyzeon.plugin.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class SpotifyAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(SpotifyAudioSourceManager.class);

    public static final String SOURCE_NAME = "spotify";
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final String PREVIEW_PREFIX = "spprev:";
    public static final long PREVIEW_LENGTH = 30000;
    public static final String SHARE_URL = "https://spotify.link/";
    public static final String API_BASE = "https://api.spotify.com/v1/";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;
    private static final int MAX_API_RETRIES = 5;
    private static final long MIN_RETRY_DELAY_MS = 5000;

    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://)(www\\.)?open\\.spotify\\.com/(?:(?<region>[a-zA-Z-]+)/)?(?:user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");

    public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern.compile(
            "mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";

    private final SpotifyTokenTracker tokenTracker;
    private final String countryCode;
    private final int playlistPageLimit;
    private final int albumPageLimit;
    private final boolean resolveArtistsInSearch;
    private final boolean localFiles;
    private final Map<String, String> artistImageCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SpotifyAudioSourceManager(String[] providers, String clientId, String clientSecret,
            String spDc, String nuanceUrl, String countryCode, int playlistPageLimit,
            int albumPageLimit, boolean resolveArtistsInSearch, boolean localFiles,
            Function<Void, AudioPlayerManager> manager) {
        super(manager, new DefaultMirroringAudioTrackResolver(providers));
        this.tokenTracker = new SpotifyTokenTracker(clientId, clientSecret, spDc, nuanceUrl);
        this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
        this.playlistPageLimit = playlistPageLimit > 0 ? playlistPageLimit : 6;
        this.albumPageLimit = albumPageLimit > 0 ? albumPageLimit : 6;
        this.resolveArtistsInSearch = resolveArtistsInSearch;
        this.localFiles = localFiles;
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
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        var extendedInfo = super.decodeTrack(input);
        return new SpotifyAudioTrack(trackInfo,
                extendedInfo.albumName,
                extendedInfo.albumUrl,
                extendedInfo.artistUrl,
                extendedInfo.artistArtworkUrl,
                extendedInfo.previewUrl,
                extendedInfo.isPreview,
                this);
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        try {
            String identifier = reference.identifier;
            boolean preview = identifier.startsWith(PREVIEW_PREFIX);
            if (preview) {
                identifier = identifier.substring(PREVIEW_PREFIX.length());
            }

            if (identifier.startsWith(SEARCH_PREFIX)) {
                return getSearch(identifier.substring(SEARCH_PREFIX.length()).trim(), preview);
            }

            if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim(), preview);
            }

            if (identifier.startsWith(SHARE_URL)) {
                return resolveShareUrl(identifier, preview);
            }

            Matcher matcher = URL_PATTERN.matcher(identifier);
            if (!matcher.find()) {
                return null;
            }

            String id = matcher.group("identifier");
            switch (matcher.group("type")) {
                case "track":
                    return getTrack(id, preview);
                case "album":
                    return getAlbum(id, preview);
                case "playlist":
                    return getPlaylist(id, preview);
                case "artist":
                    return getArtist(id, preview);
            }
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Spotify item", FriendlyException.Severity.SUSPICIOUS, e);
        }
        return null;
    }

    private AudioItem resolveShareUrl(String url, boolean preview) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                URI finalUri = response.uri();
                if (finalUri != null) {
                    location = finalUri.toString();
                }
            }

            if (location != null && location.startsWith("https://open.spotify.com/")) {
                return loadItem(null, new AudioReference(location, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return AudioReference.NO_TRACK;
    }

    private String getToken() throws IOException {
        return tokenTracker.getAccessToken(false);
    }

    private JsonNode getJson(String url) throws IOException {
        try {
            String token = getToken();

            for (int attempt = 0; attempt <= MAX_API_RETRIES; attempt++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    long retryAfterSec = response.headers()
                            .firstValueAsLong("Retry-After")
                            .orElse(5L);
                    long waitMs = Math.max(retryAfterSec * 1000L, MIN_RETRY_DELAY_MS);
                    log.warn("Spotify rate limited (429) for {} — waiting {}ms (attempt {}/{})",
                            url, waitMs, attempt + 1, MAX_API_RETRIES);
                    if (attempt < MAX_API_RETRIES) {
                        Thread.sleep(waitMs);
                        tokenTracker.invalidateAnonymousToken();
                        token = tokenTracker.getAnonymousAccessToken();
                        continue;
                    }
                    log.warn("Spotify rate limited after {} retries for {}", MAX_API_RETRIES, url);
                    return null;
                }

                if (response.statusCode() == 401) {
                    log.debug("Spotify 401 for {} — refreshing token (attempt {}/{})", url, attempt + 1,
                            MAX_API_RETRIES);
                    if (attempt < MAX_API_RETRIES) {
                        tokenTracker.invalidateAnonymousToken();
                        token = tokenTracker.getAnonymousAccessToken();
                        continue;
                    }
                    return null;
                }

                if (response.statusCode() != 200) {
                    log.warn("Spotify API status {} for {}", response.statusCode(), url);
                    return null;
                }

                return mapper.readTree(response.body());
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during Spotify API call", e);
        }
    }

    public AudioItem getTrack(String id, boolean preview) throws IOException {
        JsonNode json = getJson(API_BASE + "tracks/" + id);
        if (json == null || hasError(json)) {
            return AudioReference.NO_TRACK;
        }

        String artistArtwork = resolveFirstArtistArtwork(json);
        return parseTrackWithArtistArtwork(json, artistArtwork, preview);
    }

    public AudioItem getSearch(String query, boolean preview) throws IOException {
        JsonNode json = getJson(API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&type=track");
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        JsonNode items = json.path("tracks").path("items");
        if (!items.isArray() || items.size() == 0) {
            return AudioReference.NO_TRACK;
        }

        if (this.resolveArtistsInSearch) {
            batchResolveArtistImages(items);
        }

        List<AudioTrack> tracks = parseTracks(items, preview);
        artistImageCache.clear();

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
    }

    public AudioItem getRecommendations(String query, boolean preview) throws IOException {
        Matcher mixMatcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
        if (mixMatcher.find()) {
            String seedType = mixMatcher.group("seedType");
            String seed = mixMatcher.group("seed");

            if ("isrc".equals(seedType)) {
                AudioItem item = getSearch("isrc:" + seed, preview);
                if (item == AudioReference.NO_TRACK) {
                    return AudioReference.NO_TRACK;
                }
                if (item instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) item;
                    if (!playlist.getTracks().isEmpty()) {
                        seed = playlist.getTracks().get(0).getIdentifier();
                        seedType = "track";
                    } else {
                        return AudioReference.NO_TRACK;
                    }
                }
            }

            query = "seed_" + seedType + "s=" + seed;
        }

        JsonNode json = getJson(API_BASE + "recommendations?" + query);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        JsonNode tracksArray = json.get("tracks");
        if (tracksArray == null || !tracksArray.isArray() || tracksArray.size() == 0) {
            return AudioReference.NO_TRACK;
        }

        List<AudioTrack> tracks = parseTracks(tracksArray, preview);
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist("Spotify Recommendations", tracks,
                ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, tracks.size());
    }

    public AudioItem getAlbum(String id, boolean preview) throws IOException {
        JsonNode albumJson = getJson(API_BASE + "albums/" + id);
        if (albumJson == null || hasError(albumJson)) {
            return AudioReference.NO_TRACK;
        }

        String albumName = safeText(albumJson, "name");
        String albumUrl = getExternalUrl(albumJson);
        String albumArtwork = getFirstImage(albumJson);
        String albumArtist = getFirstArtistName(albumJson);
        int totalTracks = albumJson.path("total_tracks").asInt(0);

        String artistArtwork = resolveFirstArtistArtwork(albumJson);

        List<AudioTrack> tracks = new ArrayList<>();
        int offset = 0;
        int pages = 0;

        JsonNode page;
        do {
            page = getJson(API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset);
            if (page == null || !page.has("items"))
                break;

            String trackIds = StreamSupport.stream(page.get("items").spliterator(), false)
                    .filter(t -> t != null && t.has("id"))
                    .map(t -> t.get("id").asText())
                    .collect(Collectors.joining(","));

            if (!trackIds.isEmpty()) {
                JsonNode batchTracks = getJson(API_BASE + "tracks?ids=" + trackIds);
                if (batchTracks != null && batchTracks.has("tracks")) {
                    for (JsonNode fullTrack : batchTracks.get("tracks")) {
                        if (fullTrack == null || fullTrack.isNull())
                            continue;
                        AudioTrack track = parseTrackWithArtistArtwork(fullTrack, artistArtwork, preview);
                        if (track != null) {
                            tracks.add(track);
                        }
                    }
                }
            }

            offset += ALBUM_MAX_PAGE_ITEMS;
        } while (page.has("next") && !page.get("next").isNull() && ++pages < this.albumPageLimit);

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(albumName, tracks, ExtendedAudioPlaylist.Type.ALBUM,
                albumUrl, albumArtwork, albumArtist, totalTracks);
    }

    public AudioItem getPlaylist(String id, boolean preview) throws IOException {
        JsonNode playlistJson = getJson(API_BASE + "playlists/" + id);
        if (playlistJson == null || hasError(playlistJson)) {
            return AudioReference.NO_TRACK;
        }

        String playlistName = safeText(playlistJson, "name");
        String playlistUrl = getExternalUrl(playlistJson);
        String playlistArtwork = getFirstImage(playlistJson);
        String owner = playlistJson.path("owner").path("display_name").asText("Unknown");
        int totalTracks = playlistJson.path("tracks").path("total").asInt(0);

        List<AudioTrack> tracks = new ArrayList<>();
        int offset = 0;
        int pages = 0;

        JsonNode page;
        do {
            page = getJson(API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS
                    + "&offset=" + offset);
            if (page == null)
                break;

            JsonNode pageItems = page.get("items");
            if (pageItems == null || !pageItems.isArray())
                break;

            for (JsonNode value : pageItems) {
                JsonNode trackNode = value.path("track");
                if (trackNode.isMissingNode() || trackNode.isNull())
                    continue;
                if ("episode".equals(trackNode.path("type").asText()))
                    continue;
                if (!this.localFiles && trackNode.path("is_local").asBoolean(false))
                    continue;

                AudioTrack track = parseTrack(trackNode, preview);
                if (track != null) {
                    tracks.add(track);
                }
            }

            offset += PLAYLIST_MAX_PAGE_ITEMS;
        } while (page.has("next") && !page.get("next").isNull() && ++pages < this.playlistPageLimit);

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(playlistName, tracks, ExtendedAudioPlaylist.Type.PLAYLIST,
                playlistUrl, playlistArtwork, owner, totalTracks);
    }

    public AudioItem getArtist(String id, boolean preview) throws IOException {
        JsonNode artistJson = getJson(API_BASE + "artists/" + id);
        if (artistJson == null || hasError(artistJson)) {
            return AudioReference.NO_TRACK;
        }

        String artistName = safeText(artistJson, "name");
        String artistUrl = getExternalUrl(artistJson);
        String artistArtwork = getFirstImage(artistJson);

        JsonNode topTracksJson = getJson(API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode);

        List<AudioTrack> tracks = new ArrayList<>();
        if (topTracksJson != null && topTracksJson.has("tracks")) {
            for (JsonNode trackNode : topTracksJson.get("tracks")) {
                AudioTrack track = parseTrackWithArtistArtwork(trackNode, artistArtwork, preview);
                if (track != null) {
                    tracks.add(track);
                }
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist(artistName + "'s Top Tracks", tracks,
                ExtendedAudioPlaylist.Type.ARTIST, artistUrl, artistArtwork, artistName, tracks.size());
    }

    private void batchResolveArtistImages(JsonNode items) {
        StringBuilder artistIds = new StringBuilder();
        for (JsonNode trackNode : items) {
            String artistId = trackNode.path("artists").path(0).path("id").asText(null);
            if (artistId != null) {
                if (artistIds.length() > 0)
                    artistIds.append(",");
                artistIds.append(artistId);
            }
        }

        if (artistIds.length() == 0)
            return;

        try {
            JsonNode artistResponse = getJson(API_BASE + "artists?ids=" + artistIds);
            if (artistResponse != null && artistResponse.has("artists")) {
                for (JsonNode artist : artistResponse.get("artists")) {
                    if (artist != null && !artist.isNull() && artist.has("id")) {
                        String img = getFirstImage(artist);
                        if (img != null) {
                            artistImageCache.put(artist.get("id").asText(), img);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to batch resolve artist images: {}", e.getMessage());
        }
    }

    private String resolveFirstArtistArtwork(JsonNode json) {
        String artistId = json.path("artists").path(0).path("id").asText(null);
        if (artistId == null)
            return null;

        String cached = artistImageCache.get(artistId);
        if (cached != null)
            return cached;

        try {
            JsonNode artistJson = getJson(API_BASE + "artists/" + artistId);
            if (artistJson != null) {
                String img = getFirstImage(artistJson);
                if (img != null) {
                    artistImageCache.put(artistId, img);
                    return img;
                }
            }
        } catch (IOException e) {
            log.debug("Failed to resolve artist artwork: {}", e.getMessage());
        }
        return null;
    }

    private List<AudioTrack> parseTracks(JsonNode tracksArray, boolean preview) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode trackNode : tracksArray) {
            AudioTrack track = parseTrack(trackNode, preview);
            if (track != null) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    private AudioTrack parseTrack(JsonNode json, boolean preview) {
        return parseTrackWithArtistArtwork(json, null, preview);
    }

    private AudioTrack parseTrackWithArtistArtwork(JsonNode json, String overrideArtistArtwork, boolean preview) {
        if (json == null || json.isNull())
            return null;

        String title = safeText(json, "name");
        if (title.isEmpty())
            return null;

        String artist = getFirstArtistName(json);
        long duration = preview ? PREVIEW_LENGTH : json.path("duration_ms").asLong(0);
        String trackId = json.path("id").asText("local");
        String trackUrl = getExternalUrl(json);
        String artworkUrl = getFirstImage(json.path("album"));
        String albumName = safeText(json.path("album"), "name");
        String albumUrl = getExternalUrl(json.path("album"));
        String artistUrl = getExternalUrl(json.path("artists").path(0));
        String previewUrl = json.path("preview_url").asText(null);
        String isrc = json.path("external_ids").path("isrc").asText(null);

        String artistArtwork = overrideArtistArtwork;
        if (artistArtwork == null) {
            String firstArtistImg = json.path("artists").path(0).path("images").path(0).path("url").asText(null);
            if (firstArtistImg != null) {
                artistArtwork = firstArtistImg;
            } else {
                String aid = json.path("artists").path(0).path("id").asText(null);
                if (aid != null) {
                    artistArtwork = artistImageCache.get(aid);
                }
            }
        }

        AudioTrackInfo trackInfo = new AudioTrackInfo(
                title,
                artist.isEmpty() ? "Unknown" : artist,
                duration,
                trackId,
                false,
                trackUrl,
                artworkUrl,
                isrc);

        return new SpotifyAudioTrack(trackInfo, albumName, albumUrl, artistUrl, artistArtwork, previewUrl, preview,
                this);
    }

    private boolean hasError(JsonNode json) {
        return json.has("error") && !json.get("error").isNull();
    }

    private String safeText(JsonNode node, String field) {
        if (node == null || node.isMissingNode())
            return "";
        return node.path(field).asText("");
    }

    private String getExternalUrl(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        return node.path("external_urls").path("spotify").asText(null);
    }

    private String getFirstImage(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        return node.path("images").path(0).path("url").asText(null);
    }

    private String getFirstArtistName(JsonNode node) {
        if (node == null || node.isMissingNode())
            return "Unknown";
        return node.path("artists").path(0).path("name").asText("Unknown");
    }
}