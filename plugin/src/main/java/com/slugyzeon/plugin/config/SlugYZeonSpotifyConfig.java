package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.slugyzeon.spotify")
@Component
public class SlugYZeonSpotifyConfig {

    private String clientId = "";
    private String clientSecret = "";
    private String spDc = "";
    private String countryCode = "US";
    private String nuanceUrl = "";
    private int playlistLoadLimit = 6;
    private int albumLoadLimit = 6;
    private boolean resolveArtistsInSearch = true;
    private boolean localFiles = false;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getSpDc() {
        return spDc;
    }

    public void setSpDc(String spDc) {
        this.spDc = spDc;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public String getNuanceUrl() {
        return nuanceUrl;
    }

    public void setNuanceUrl(String nuanceUrl) {
        this.nuanceUrl = nuanceUrl;
    }

    public int getPlaylistLoadLimit() {
        return playlistLoadLimit;
    }

    public void setPlaylistLoadLimit(int playlistLoadLimit) {
        this.playlistLoadLimit = playlistLoadLimit;
    }

    public int getAlbumLoadLimit() {
        return albumLoadLimit;
    }

    public void setAlbumLoadLimit(int albumLoadLimit) {
        this.albumLoadLimit = albumLoadLimit;
    }

    public boolean isResolveArtistsInSearch() {
        return resolveArtistsInSearch;
    }

    public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
        this.resolveArtistsInSearch = resolveArtistsInSearch;
    }

    public boolean isLocalFiles() {
        return localFiles;
    }

    public void setLocalFiles(boolean localFiles) {
        this.localFiles = localFiles;
    }
}