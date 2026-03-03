package com.slugyzeon.plugin.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    public static final String SHARE_URL = "https://spotify.link/";
    public static final String GQL_BASE = "https://api-partner.spotify.com/pathfinder/v2/query";
    public static final String API_BASE = "https://api.spotify.com/v1/";

    private static final String SEARCH_HASH = "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49";
    private static final String TRACK_HASH = "612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294";
    private static final String ALBUM_HASH = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10";
    private static final String PLAYLIST_HASH = "7982b11e21535cd2594badc40030b745671b61a1fa66766e569d45e6364f3422";
    private static final String ARTIST_HASH = "dd14c6043d8127b56c5acbe534f6b3c58714f0c26bc6ad41776079ed52833a8f";

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

    private JsonNode gqlQuery(String operationName, String hash, ObjectNode variables) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.set("variables", variables);
        body.put("operationName", operationName);

        ObjectNode extensions = mapper.createObjectNode();
        ObjectNode persistedQuery = mapper.createObjectNode();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", hash);
        extensions.set("persistedQuery", persistedQuery);
        body.set("extensions", extensions);

        String jsonBody = mapper.writeValueAsString(body);

        try {
            String token = getToken();

            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GQL_BASE))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/json")
                        .header("App-Platform", "WebPlayer")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    log.debug("GQL 401, refreshing token ({}/3)", attempt + 1);
                    token = tokenTracker.getAnonymousAccessToken();
                    continue;
                }

                if (response.statusCode() == 429) {
                    long retryAfter = response.headers()
                            .firstValueAsLong("Retry-After")
                            .orElse(2L + attempt);
                    log.debug("GQL rate limited, retrying in {}s ({}/3)", retryAfter, attempt + 1);
                    Thread.sleep(retryAfter * 1000L);
                    continue;
                }

                if (response.statusCode() != 200) {
                    log.warn("GQL returned {} for {}", response.statusCode(), operationName);
                    return null;
                }

                JsonNode json = mapper.readTree(response.body());

                if (json.has("errors") && json.get("errors").size() > 0) {
                    log.warn("GQL error for {}: {}", operationName,
                            json.get("errors").get(0).path("message").asText("unknown"));
                    return null;
                }

                return json.path("data");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during GQL call", e);
        }
    }

    private JsonNode getRestJson(String url) throws IOException {
        try {
            String token = getToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    public AudioItem getTrack(String id, boolean preview) throws IOException {
        ObjectNode vars = mapper.createObjectNode();
        vars.put("uri", "spotify:track:" + id);

        JsonNode data = gqlQuery("getTrack", TRACK_HASH, vars);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode track = data.path("trackUnion");
        if (track.isMissingNode())
            track = data.path("trackV2");
        if (track.isMissingNode())
            track = data.path("track");
        if (track.isMissingNode())
            return AudioReference.NO_TRACK;

        return parseGqlTrack(track, id, preview);
    }

    public AudioItem getSearch(String query, boolean preview) throws IOException {
        ObjectNode vars = mapper.createObjectNode();
        vars.put("searchTerm", query);
        vars.put("offset", 0);
        vars.put("limit", 20);
        vars.put("numberOfTopResults", 5);
        vars.put("includeAudiobooks", false);
        vars.put("includeArtistHasConcertsField", false);
        vars.put("includePreReleases", false);
        vars.put("includeLocalConcertsField", false);
        vars.put("includeAuthors", false);

        JsonNode data = gqlQuery("searchDesktop", SEARCH_HASH, vars);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode items = data.path("searchV2").path("tracksV2").path("items");
        if (!items.isArray() || items.size() == 0)
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode trackData = item.path("item").path("data");
            if (trackData.isMissingNode() || !"Track".equals(trackData.path("__typename").asText("")))
                continue;

            String trackId = extractIdFromUri(trackData.path("uri").asText(""));
            AudioTrack track = parseGqlTrack(trackData, trackId, preview);
            if (track != null)
                tracks.add(track);
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
    }

    public AudioItem getRecommendations(String query, boolean preview) throws IOException {
        Matcher mixMatcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
        if (mixMatcher.find()) {
            String seedType = mixMatcher.group("seedType");
            String seed = mixMatcher.group("seed");

            if ("isrc".equals(seedType)) {
                AudioItem item = getSearch("isrc:" + seed, preview);
                if (item == AudioReference.NO_TRACK)
                    return AudioReference.NO_TRACK;
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

        JsonNode json = getRestJson(API_BASE + "recommendations?" + query);
        if (json == null)
            return AudioReference.NO_TRACK;

        JsonNode tracksArray = json.get("tracks");
        if (tracksArray == null || !tracksArray.isArray() || tracksArray.size() == 0)
            return AudioReference.NO_TRACK;

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonNode trackNode : tracksArray) {
            AudioTrack track = parseRestTrack(trackNode, preview);
            if (track != null)
                tracks.add(track);
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;
        return new SpotifyAudioPlaylist("Spotify Recommendations", tracks,
                ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, tracks.size());
    }

    public AudioItem getAlbum(String id, boolean preview) throws IOException {
        ObjectNode vars = mapper.createObjectNode();
        vars.put("uri", "spotify:album:" + id);
        vars.put("locale", "en");
        vars.put("offset", 0);
        vars.put("limit", 300);

        JsonNode data = gqlQuery("getAlbum", ALBUM_HASH, vars);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode album = data.path("albumUnion");
        if (album.isMissingNode())
            album = data.path("albumV2");
        if (album.isMissingNode())
            album = data.path("album");
        if (album.isMissingNode())
            return AudioReference.NO_TRACK;

        String albumName = album.path("name").asText("Unknown Album");
        String albumId = extractIdFromUri(album.path("uri").asText(""));
        String albumUrl = "https://open.spotify.com/album/" + albumId;
        String albumArtwork = album.path("coverArt").path("sources").path(0).path("url").asText(null);
        String albumArtist = getGqlArtistName(album);
        String artistArtwork = album.path("artists").path("items").path(0)
                .path("visuals").path("avatarImage").path("sources").path(0).path("url").asText(null);

        int totalTracks = album.path("tracks").path("totalCount").asInt(0);

        JsonNode trackItems = album.path("tracks").path("items");
        if (!trackItems.isArray()) {
            trackItems = album.path("tracksV2").path("items");
        }

        List<AudioTrack> tracks = new ArrayList<>();
        if (trackItems.isArray()) {
            for (JsonNode item : trackItems) {
                JsonNode trackData = item.path("track");
                if (trackData.isMissingNode())
                    trackData = item;
                if (trackData.isMissingNode() || trackData.isNull())
                    continue;

                String trackId = extractIdFromUri(trackData.path("uri").asText(""));
                if (trackId.isEmpty())
                    continue;

                String title = trackData.path("name").asText("");
                if (title.isEmpty())
                    continue;

                long duration = trackData.path("duration").path("totalMilliseconds").asLong(0);
                String trackArtist = getGqlArtistName(trackData);
                if ("Unknown".equals(trackArtist))
                    trackArtist = albumArtist;
                String trackUrl = "https://open.spotify.com/track/" + trackId;
                String artistUrl = null;
                String firstArtistUri = trackData.path("artists").path("items").path(0).path("uri").asText(null);
                if (firstArtistUri != null) {
                    artistUrl = "https://open.spotify.com/artist/" + extractIdFromUri(firstArtistUri);
                }

                AudioTrackInfo info = new AudioTrackInfo(
                        title,
                        trackArtist,
                        preview ? PREVIEW_LENGTH : duration,
                        trackId,
                        false,
                        trackUrl,
                        albumArtwork,
                        null);

                tracks.add(new SpotifyAudioTrack(info, albumName, albumUrl, artistUrl, artistArtwork,
                        null, preview, this));
            }
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;
        return new SpotifyAudioPlaylist(albumName, tracks, ExtendedAudioPlaylist.Type.ALBUM,
                albumUrl, albumArtwork, albumArtist, totalTracks);
    }

    public AudioItem getPlaylist(String id, boolean preview) throws IOException {
        ObjectNode vars = mapper.createObjectNode();
        vars.put("uri", "spotify:playlist:" + id);
        vars.put("offset", 0);
        vars.put("limit", 343);
        vars.put("enableWatchFeedEntrypoint", false);

        JsonNode data = gqlQuery("fetchPlaylist", PLAYLIST_HASH, vars);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode playlist = data.path("playlistV2");
        if (playlist.isMissingNode())
            return AudioReference.NO_TRACK;

        String playlistName = playlist.path("name").asText("Unknown Playlist");
        String playlistUrl = "https://open.spotify.com/playlist/" + id;
        String playlistArtwork = playlist.path("images").path("items").path(0)
                .path("sources").path(0).path("url").asText(null);
        String owner = playlist.path("ownerV2").path("data").path("name").asText("Unknown");
        int totalTracks = playlist.path("content").path("totalCount").asInt(0);

        JsonNode contentItems = playlist.path("content").path("items");

        List<AudioTrack> tracks = new ArrayList<>();
        if (contentItems.isArray()) {
            for (JsonNode item : contentItems) {
                JsonNode trackData = item.path("itemV2").path("data");
                if (trackData.isMissingNode() || trackData.isNull())
                    continue;
                if (!"Track".equals(trackData.path("__typename").asText("")))
                    continue;

                String trackId = extractIdFromUri(trackData.path("uri").asText(""));
                if (trackId.isEmpty())
                    continue;

                AudioTrack track = parseGqlTrack(trackData, trackId, preview);
                if (track != null)
                    tracks.add(track);
            }
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;
        return new SpotifyAudioPlaylist(playlistName, tracks, ExtendedAudioPlaylist.Type.PLAYLIST,
                playlistUrl, playlistArtwork, owner, totalTracks);
    }

    public AudioItem getArtist(String id, boolean preview) throws IOException {
        ObjectNode vars = mapper.createObjectNode();
        vars.put("uri", "spotify:artist:" + id);
        vars.put("locale", "en");
        vars.put("includePrerelease", false);

        JsonNode data = gqlQuery("queryArtistOverview", ARTIST_HASH, vars);
        if (data == null)
            return AudioReference.NO_TRACK;

        JsonNode artist = data.path("artistUnion");
        if (artist.isMissingNode())
            artist = data.path("artistV2");
        if (artist.isMissingNode())
            artist = data.path("artist");
        if (artist.isMissingNode())
            return AudioReference.NO_TRACK;

        String artistName = artist.path("profile").path("name").asText("Unknown Artist");
        String artistUrl = "https://open.spotify.com/artist/" + id;
        String artistArtwork = artist.path("visuals").path("avatarImage")
                .path("sources").path(0).path("url").asText(null);

        JsonNode topTracks = artist.path("discography").path("topTracks").path("items");
        if (!topTracks.isArray() || topTracks.size() == 0) {
            topTracks = artist.path("discography").path("popularReleasesAlbums").path("items");
        }

        List<AudioTrack> tracks = new ArrayList<>();
        if (topTracks.isArray()) {
            for (JsonNode item : topTracks) {
                JsonNode trackData = item.path("track");
                if (trackData.isMissingNode())
                    trackData = item;
                if (trackData.isMissingNode() || trackData.isNull())
                    continue;

                String trackId = extractIdFromUri(trackData.path("uri").asText(""));
                if (trackId.isEmpty())
                    continue;

                String title = trackData.path("name").asText("");
                if (title.isEmpty())
                    continue;

                long duration = trackData.path("duration").path("totalMilliseconds").asLong(0);
                String trackUrl = "https://open.spotify.com/track/" + trackId;
                String trackArtwork = trackData.path("albumOfTrack").path("coverArt")
                        .path("sources").path(0).path("url").asText(null);
                String albumName = trackData.path("albumOfTrack").path("name").asText(null);
                String albumId = extractIdFromUri(trackData.path("albumOfTrack").path("uri").asText(""));
                String albumUrl = albumId.isEmpty() ? null : "https://open.spotify.com/album/" + albumId;

                AudioTrackInfo info = new AudioTrackInfo(
                        title,
                        artistName,
                        preview ? PREVIEW_LENGTH : duration,
                        trackId,
                        false,
                        trackUrl,
                        trackArtwork,
                        null);

                tracks.add(new SpotifyAudioTrack(info, albumName, albumUrl, artistUrl, artistArtwork,
                        null, preview, this));
            }
        }

        if (tracks.isEmpty())
            return AudioReference.NO_TRACK;
        return new SpotifyAudioPlaylist(artistName + "'s Top Tracks", tracks,
                ExtendedAudioPlaylist.Type.ARTIST, artistUrl, artistArtwork, artistName, tracks.size());
    }

    private AudioTrack parseGqlTrack(JsonNode trackData, String trackId, boolean preview) {
        if (trackData == null || trackData.isNull() || trackData.isMissingNode())
            return null;

        String title = trackData.path("name").asText("");
        if (title.isEmpty())
            return null;

        long duration = trackData.path("duration").path("totalMilliseconds").asLong(0);

        String artist = getGqlArtistName(trackData);
        String artistUri = trackData.path("artists").path("items").path(0).path("uri").asText(null);
        String artistUrl = artistUri != null
                ? "https://open.spotify.com/artist/" + extractIdFromUri(artistUri)
                : null;
        String artistArtwork = trackData.path("artists").path("items").path(0)
                .path("visuals").path("avatarImage").path("sources").path(0).path("url").asText(null);

        String trackUrl = "https://open.spotify.com/track/" + trackId;

        String artworkUrl = trackData.path("albumOfTrack").path("coverArt")
                .path("sources").path(0).path("url").asText(null);
        String albumName = trackData.path("albumOfTrack").path("name").asText(null);
        String albumUri = trackData.path("albumOfTrack").path("uri").asText(null);
        String albumUrl = albumUri != null
                ? "https://open.spotify.com/album/" + extractIdFromUri(albumUri)
                : null;

        AudioTrackInfo info = new AudioTrackInfo(
                title,
                artist,
                preview ? PREVIEW_LENGTH : duration,
                trackId,
                false,
                trackUrl,
                artworkUrl,
                null);

        return new SpotifyAudioTrack(info, albumName, albumUrl, artistUrl, artistArtwork,
                null, preview, this);
    }

    private AudioTrack parseRestTrack(JsonNode json, boolean preview) {
        if (json == null || json.isNull())
            return null;

        String title = json.path("name").asText("");
        if (title.isEmpty())
            return null;

        String artist = json.path("artists").path(0).path("name").asText("Unknown");
        long duration = preview ? PREVIEW_LENGTH : json.path("duration_ms").asLong(0);
        String trackId = json.path("id").asText("local");
        String trackUrl = json.path("external_urls").path("spotify").asText(null);
        String artworkUrl = json.path("album").path("images").path(0).path("url").asText(null);
        String albumName = json.path("album").path("name").asText(null);
        String albumUrl = json.path("album").path("external_urls").path("spotify").asText(null);
        String artistUrl = json.path("artists").path(0).path("external_urls").path("spotify").asText(null);
        String previewUrl = json.path("preview_url").asText(null);
        String isrc = json.path("external_ids").path("isrc").asText(null);

        AudioTrackInfo trackInfo = new AudioTrackInfo(
                title,
                artist,
                duration,
                trackId,
                false,
                trackUrl,
                artworkUrl,
                isrc);

        return new SpotifyAudioTrack(trackInfo, albumName, albumUrl, artistUrl, null, previewUrl, preview, this);
    }

    private String getGqlArtistName(JsonNode node) {
        if (node == null || node.isMissingNode())
            return "Unknown";

        String name = node.path("artists").path("items").path(0).path("profile").path("name").asText(null);
        if (name != null && !name.isEmpty())
            return name;

        name = node.path("artists").path("items").path(0).path("name").asText(null);
        if (name != null && !name.isEmpty())
            return name;

        name = node.path("firstArtist").path("items").path(0).path("profile").path("name").asText(null);
        if (name != null && !name.isEmpty())
            return name;

        name = node.path("artists").path(0).path("profile").path("name").asText(null);
        if (name != null && !name.isEmpty())
            return name;

        name = node.path("artists").path(0).path("name").asText(null);
        if (name != null && !name.isEmpty())
            return name;

        return "Unknown";
    }

    private String extractIdFromUri(String uri) {
        if (uri == null || uri.isEmpty())
            return "";
        int lastColon = uri.lastIndexOf(':');
        return lastColon >= 0 ? uri.substring(lastColon + 1) : uri;
    }
}