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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(SpotifyAudioSourceManager.class);

    public static final String SOURCE_NAME = "spotify";
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final String PREVIEW_PREFIX = "spprev:";
    public static final long PREVIEW_LENGTH = 30000;

    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://)(www\\.)?open\\.spotify\\.com/(?:(?<region>[a-zA-Z-]+)/)?(?:user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");

    public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern.compile(
            "mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");

    public static final String SHARE_URL = "https://spotify.link/";
    public static final String API_BASE = "https://api.spotify.com/v1/";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";

    private final SpotifyTokenTracker tokenTracker;
    private final String countryCode;
    private final int playlistPageLimit;
    private final int albumPageLimit;
    private boolean resolveArtistsInSearch;
    private boolean localFiles;
    private boolean preferAnonymousToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SpotifyAudioSourceManager(String[] providers, String clientId, String clientSecret,
            String spDc, String customTokenEndpoint, String countryCode, int playlistPageLimit,
            int albumPageLimit, boolean resolveArtistsInSearch, boolean localFiles,
            Function<Void, AudioPlayerManager> manager) {
        super(manager, new DefaultMirroringAudioTrackResolver(providers));
        this.tokenTracker = new SpotifyTokenTracker(clientId, clientSecret, spDc, customTokenEndpoint);
        this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
        this.playlistPageLimit = playlistPageLimit > 0 ? playlistPageLimit : 6;
        this.albumPageLimit = albumPageLimit > 0 ? albumPageLimit : 6;
        this.resolveArtistsInSearch = resolveArtistsInSearch;
        this.localFiles = localFiles;
        this.preferAnonymousToken = !tokenTracker.hasValidCredentials();
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
            String type = matcher.group("type");

            switch (type) {
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
                Matcher matcher = URL_PATTERN.matcher(location);
                if (matcher.find()) {
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
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return AudioReference.NO_TRACK;
    }

    private JsonNode getJson(String url, boolean anonymous) throws IOException {
        try {
            String token = anonymous
                    ? tokenTracker.getAnonymousAccessToken()
                    : tokenTracker.getAccessToken(this.preferAnonymousToken);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 && !anonymous) {
                log.debug("Spotify API returned 401, retrying with anonymous token...");
                String anonToken = tokenTracker.getAnonymousAccessToken();
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + anonToken)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                response = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() != 200) {
                log.warn("Spotify API returned status {} for {}", response.statusCode(), url);
                return null;
            }

            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching from Spotify API", e);
        }
    }

    public AudioItem getTrack(String id, boolean preview) throws IOException {
        JsonNode json = getJson(API_BASE + "tracks/" + id, false);
        if (json == null || (json.has("error") && !json.get("error").isNull())) {
            return AudioReference.NO_TRACK;
        }

        resolveArtistImages(json);
        return parseTrack(json, preview);
    }

    public AudioItem getSearch(String query, boolean preview) throws IOException {
        JsonNode json = getJson(API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&type=track", false);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        JsonNode tracksNode = json.get("tracks");
        if (tracksNode == null || !tracksNode.has("items")) {
            return AudioReference.NO_TRACK;
        }

        JsonNode items = tracksNode.get("items");
        if (!items.isArray() || items.size() == 0) {
            return AudioReference.NO_TRACK;
        }

        if (this.resolveArtistsInSearch) {
            StringBuilder artistIds = new StringBuilder();
            for (JsonNode trackNode : items) {
                if (trackNode.has("artists") && trackNode.get("artists").isArray()
                        && trackNode.get("artists").size() > 0) {
                    String artistId = trackNode.get("artists").get(0).has("id")
                            ? trackNode.get("artists").get(0).get("id").asText()
                            : null;
                    if (artistId != null) {
                        if (artistIds.length() > 0)
                            artistIds.append(",");
                        artistIds.append(artistId);
                    }
                }
            }

            if (artistIds.length() > 0) {
                JsonNode artistJsonResponse = getJson(API_BASE + "artists?ids=" + artistIds, false);
                if (artistJsonResponse != null && artistJsonResponse.has("artists")) {
                    artistImageCache.clear();
                    for (JsonNode artist : artistJsonResponse.get("artists")) {
                        if (artist != null && !artist.isNull() && artist.has("id")) {
                            String img = getFirstImage(artist);
                            if (img != null) {
                                artistImageCache.put(artist.get("id").asText(), img);
                            }
                        }
                    }
                }
            }
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode trackNode : items) {
            AudioTrack track = parseTrack(trackNode, preview);
            if (track != null) {
                tracks.add(track);
            }
        }

        artistImageCache.clear();

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
    }

    private final java.util.Map<String, String> artistImageCache = new java.util.concurrent.ConcurrentHashMap<>();

    public AudioItem getRecommendations(String query, boolean preview) throws IOException {
        Matcher mixMatcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
        if (mixMatcher.find()) {
            String seedType = mixMatcher.group("seedType");
            String seed = mixMatcher.group("seed");

            if (seedType.equals("isrc")) {
                AudioItem item = getSearch("isrc:" + seed, preview);
                if (item == AudioReference.NO_TRACK) {
                    return AudioReference.NO_TRACK;
                }
                if (item instanceof AudioTrack) {
                    seed = ((AudioTrack) item).getIdentifier();
                    seedType = "track";
                } else if (item instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) item;
                    if (!playlist.getTracks().isEmpty()) {
                        seed = playlist.getTracks().get(0).getIdentifier();
                        seedType = "track";
                    } else {
                        return AudioReference.NO_TRACK;
                    }
                }
            }

            String seedParam = "seed_" + seedType + "s=" + seed;
            query = seedParam;
        }

        JsonNode json = getJson(API_BASE + "recommendations?" + query, false);
        if (json == null || !json.has("tracks")) {
            return AudioReference.NO_TRACK;
        }

        JsonNode tracksArray = json.get("tracks");
        if (!tracksArray.isArray() || tracksArray.size() == 0) {
            return AudioReference.NO_TRACK;
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode trackNode : tracksArray) {
            AudioTrack track = parseTrack(trackNode, preview);
            if (track != null) {
                tracks.add(track);
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new SpotifyAudioPlaylist("Spotify Recommendations", tracks,
                ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, tracks.size());
    }

    public AudioItem getAlbum(String id, boolean preview) throws IOException {
        JsonNode albumJson = getJson(API_BASE + "albums/" + id, false);
        if (albumJson == null || (albumJson.has("error") && !albumJson.get("error").isNull())) {
            return AudioReference.NO_TRACK;
        }

        String albumName = safeText(albumJson, "name");
        String albumUrl = getExternalUrl(albumJson);
        String albumArtwork = getFirstImage(albumJson);
        String albumArtist = getFirstArtistName(albumJson);
        int totalTracks = albumJson.has("total_tracks") ? albumJson.get("total_tracks").asInt(0) : 0;

        String artistArtwork = null;
        if (albumJson.has("artists") && albumJson.get("artists").isArray() && albumJson.get("artists").size() > 0) {
            String artistId = albumJson.get("artists").get(0).has("id")
                    ? albumJson.get("artists").get(0).get("id").asText()
                    : null;
            if (artistId != null) {
                JsonNode artistJson = getJson(API_BASE + "artists/" + artistId, false);
                if (artistJson != null) {
                    artistArtwork = getFirstImage(artistJson);
                }
            }
        }

        List<AudioTrack> tracks = new ArrayList<>();
        int offset = 0;
        int pages = 0;

        JsonNode page;
        do {
            page = getJson(API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset,
                    false);
            if (page == null || !page.has("items"))
                break;

            for (JsonNode simpleTrack : page.get("items")) {
                if (simpleTrack == null || !simpleTrack.has("id"))
                    continue;

                JsonNode fullTrack = getJson(API_BASE + "tracks/" + simpleTrack.get("id").asText(), false);
                if (fullTrack != null && !(fullTrack.has("error") && !fullTrack.get("error").isNull())) {
                    AudioTrack track = parseTrackWithArtistArtwork(fullTrack, artistArtwork, preview);
                    if (track != null) {
                        tracks.add(track);
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
        boolean anonymous = id.startsWith("37i9dQZ");

        JsonNode playlistJson = getJson(API_BASE + "playlists/" + id, anonymous);
        if (playlistJson == null || (playlistJson.has("error") && !playlistJson.get("error").isNull())) {
            return AudioReference.NO_TRACK;
        }

        String playlistName = safeText(playlistJson, "name");
        String playlistUrl = getExternalUrl(playlistJson);
        String playlistArtwork = getFirstImage(playlistJson);
        String owner = playlistJson.has("owner") && playlistJson.get("owner").has("display_name")
                ? playlistJson.get("owner").get("display_name").asText("Unknown")
                : "Unknown";

        int totalTracks = 0;
        if (playlistJson.has("tracks") && playlistJson.get("tracks").has("total")) {
            totalTracks = playlistJson.get("tracks").get("total").asInt(0);
        }

        List<AudioTrack> tracks = new ArrayList<>();
        int offset = 0;
        int pages = 0;

        JsonNode page;
        do {
            page = getJson(API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS
                    + "&offset=" + offset, anonymous);
            if (page == null)
                break;

            JsonNode pageItems = page.has("items") ? page.get("items") : null;
            if (pageItems == null || !pageItems.isArray())
                break;

            for (JsonNode value : pageItems) {
                JsonNode trackNode = value.has("track") ? value.get("track") : null;
                if (trackNode == null || trackNode.isNull())
                    continue;
                if (trackNode.has("type") && "episode".equals(trackNode.get("type").asText()))
                    continue;
                if (!this.localFiles && trackNode.has("is_local") && trackNode.get("is_local").asBoolean(false))
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
        JsonNode artistJson = getJson(API_BASE + "artists/" + id, false);
        if (artistJson == null || (artistJson.has("error") && !artistJson.get("error").isNull())) {
            return AudioReference.NO_TRACK;
        }

        String artistName = safeText(artistJson, "name");
        String artistUrl = getExternalUrl(artistJson);
        String artistArtwork = getFirstImage(artistJson);

        JsonNode topTracksJson = getJson(API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode, false);

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

    private void resolveArtistImages(JsonNode trackJson) {
        if (trackJson == null || !trackJson.has("artists"))
            return;
        JsonNode artists = trackJson.get("artists");
        if (!artists.isArray() || artists.size() == 0)
            return;

        JsonNode firstArtist = artists.get(0);
        if (firstArtist.has("images") && firstArtist.get("images").isArray() && firstArtist.get("images").size() > 0) {
            return;
        }

        if (firstArtist.has("id") && !firstArtist.get("id").isNull()) {
            try {
                JsonNode artistJson = getJson(API_BASE + "artists/" + firstArtist.get("id").asText(), false);
                if (artistJson != null && artistJson.has("images")) {
                    String img = getFirstImage(artistJson);
                    if (img != null) {
                        artistImageCache.put(firstArtist.get("id").asText(), img);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to resolve artist images: {}", e.getMessage());
            }
        }
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
        long duration = preview ? PREVIEW_LENGTH : (json.has("duration_ms") ? json.get("duration_ms").asLong(0) : 0);
        String trackId = json.has("id") && !json.get("id").isNull() ? json.get("id").asText() : "unknown";
        String trackUrl = getExternalUrl(json);
        String artworkUrl = null;
        String albumName = null;
        String albumUrl = null;
        String artistUrl = null;
        String artistArtwork = overrideArtistArtwork;
        String previewUrl = json.has("preview_url") && !json.get("preview_url").isNull()
                ? json.get("preview_url").asText()
                : null;
        String isrc = null;

        if (json.has("album") && !json.get("album").isNull()) {
            JsonNode album = json.get("album");
            artworkUrl = getFirstImage(album);
            albumName = safeText(album, "name");
            albumUrl = getExternalUrl(album);
        }

        if (json.has("artists") && json.get("artists").isArray() && json.get("artists").size() > 0) {
            JsonNode firstArtist = json.get("artists").get(0);
            artistUrl = getExternalUrl(firstArtist);
            if (artistArtwork == null && firstArtist.has("images") && firstArtist.get("images").isArray()
                    && firstArtist.get("images").size() > 0) {
                artistArtwork = firstArtist.get("images").get(0).get("url").asText(null);
            }
            if (artistArtwork == null && firstArtist.has("id")) {
                artistArtwork = artistImageCache.get(firstArtist.get("id").asText());
            }
        }

        if (json.has("external_ids") && json.get("external_ids").has("isrc")) {
            isrc = json.get("external_ids").get("isrc").asText(null);
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

    private String safeText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull())
            return "";
        return node.get(field).asText("");
    }

    private String getExternalUrl(JsonNode node) {
        if (node == null || !node.has("external_urls"))
            return null;
        JsonNode urls = node.get("external_urls");
        return urls.has("spotify") ? urls.get("spotify").asText(null) : null;
    }

    private String getFirstImage(JsonNode node) {
        if (node == null || !node.has("images"))
            return null;
        JsonNode images = node.get("images");
        if (!images.isArray() || images.size() == 0)
            return null;
        return images.get(0).has("url") ? images.get(0).get("url").asText(null) : null;
    }

    private String getFirstArtistName(JsonNode node) {
        if (node == null || !node.has("artists"))
            return "Unknown";
        JsonNode artists = node.get("artists");
        if (!artists.isArray() || artists.size() == 0)
            return "Unknown";
        return artists.get(0).has("name") ? artists.get(0).get("name").asText("Unknown") : "Unknown";
    }
}