package com.slugyzeon.plugin.spotify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public class SpotifyRequestPayload {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String operationName;
    private final String sha256Hash;
    private final ObjectNode variables;

    private SpotifyRequestPayload(String operationName, String sha256Hash) {
        this.operationName = operationName;
        this.sha256Hash = sha256Hash;
        this.variables = MAPPER.createObjectNode();
    }

    private SpotifyRequestPayload put(String key, Object value) {
        this.variables.set(key, MAPPER.valueToTree(value));
        return this;
    }

    public String getOperationName() {
        return operationName;
    }

    public String serialize() throws IOException {
        ObjectNode persistedQuery = MAPPER.createObjectNode();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", sha256Hash);

        ObjectNode extensions = MAPPER.createObjectNode();
        extensions.set("persistedQuery", persistedQuery);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("variables", variables);
        body.put("operationName", operationName);
        body.set("extensions", extensions);

        return MAPPER.writeValueAsString(body);
    }

    public static SpotifyRequestPayload search(String hash, String query) {
        return new SpotifyRequestPayload("searchDesktop", hash)
                .put("searchTerm", query)
                .put("offset", 0)
                .put("limit", 20)
                .put("numberOfTopResults", 5)
                .put("includeAudiobooks", false)
                .put("includeArtistHasConcertsField", false)
                .put("includePreReleases", false)
                .put("includeLocalConcertsField", false)
                .put("includeAuthors", false);
    }

    public static SpotifyRequestPayload track(String hash, String id) {
        return new SpotifyRequestPayload("getTrack", hash)
                .put("uri", "spotify:track:" + id);
    }

    public static SpotifyRequestPayload recommendations(String hash, String seedTrackId) {
        return new SpotifyRequestPayload("internalLinkRecommenderTrack", hash)
                .put("uri", "spotify:track:" + seedTrackId);
    }

    public static SpotifyRequestPayload album(String hash, String id) {
        return new SpotifyRequestPayload("getAlbum", hash)
                .put("uri", "spotify:album:" + id)
                .put("locale", "en")
                .put("offset", 0)
                .put("limit", 300);
    }

    public static SpotifyRequestPayload playlist(String hash, String id, int offset, int limit) {
        return new SpotifyRequestPayload("fetchPlaylist", hash)
                .put("uri", "spotify:playlist:" + id)
                .put("offset", offset)
                .put("limit", limit)
                .put("enableWatchFeedEntrypoint", false);
    }

    public static SpotifyRequestPayload artist(String hash, String id) {
        return new SpotifyRequestPayload("queryArtistOverview", hash)
                .put("uri", "spotify:artist:" + id)
                .put("locale", "en")
                .put("includePrerelease", false);
    }
}