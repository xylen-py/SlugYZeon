package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "plugins.slugyzeon")
public class SlugYZeonConfig {

    private GaanaConfig gaana = new GaanaConfig();
    private AmazonMusicConfig amazonmusic = new AmazonMusicConfig();
    private InstagramConfig instagram = new InstagramConfig();
    private LastFmConfig lastfm = new LastFmConfig();

    public GaanaConfig getGaana() {
        return gaana;
    }

    public void setGaana(GaanaConfig gaana) {
        this.gaana = gaana;
    }

    public AmazonMusicConfig getAmazonmusic() {
        return amazonmusic;
    }

    public void setAmazonmusic(AmazonMusicConfig amazonmusic) {
        this.amazonmusic = amazonmusic;
    }

    public InstagramConfig getInstagram() {
        return instagram;
    }

    public void setInstagram(InstagramConfig instagram) {
        this.instagram = instagram;
    }

    public LastFmConfig getLastfm() {
        return lastfm;
    }

    public void setLastfm(LastFmConfig lastfm) {
        this.lastfm = lastfm;
    }

    public static class GaanaConfig {
        private boolean enabled = true;
        private String apiUrl = "https://gaana-plugin-api.vercel.app/api";
        private String streamQuality = "high";
        private int playlistLoadLimit = 50;
        private int albumLoadLimit = 50;
        private int artistLoadLimit = 50;
        private String[] providers;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getStreamQuality() {
            return streamQuality;
        }

        public void setStreamQuality(String streamQuality) {
            this.streamQuality = streamQuality;
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

        public int getArtistLoadLimit() {
            return artistLoadLimit;
        }

        public void setArtistLoadLimit(int artistLoadLimit) {
            this.artistLoadLimit = artistLoadLimit;
        }

        public String[] getProviders() {
            return providers;
        }

        public void setProviders(String[] providers) {
            this.providers = providers;
        }
    }

    public static class AmazonMusicConfig {
        private boolean enabled = true;
        private String countryCode = "IN";
        private String[] providers;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public void setCountryCode(String countryCode) {
            this.countryCode = countryCode;
        }

        public String[] getProviders() {
            return providers;
        }

        public void setProviders(String[] providers) {
            this.providers = providers;
        }
    }

    public static class InstagramConfig {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class LastFmConfig {
        private boolean enabled = true;
        private String apiKey = "";
        private String[] providers;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String[] getProviders() {
            return providers;
        }

        public void setProviders(String[] providers) {
            this.providers = providers;
        }
    }
}
