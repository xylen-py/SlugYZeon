package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.slugyzeon.sources")
@Component
public class SlugYZeonSourcesConfig {

    private boolean gaana = true;
    private boolean amazonmusic = true;
    private boolean instagram = true;
    private boolean pandora = true;
    private boolean spotify = false;
    private boolean youtube = false;

    public boolean isGaana() {
        return gaana;
    }

    public void setGaana(boolean gaana) {
        this.gaana = gaana;
    }

    public boolean isAmazonmusic() {
        return amazonmusic;
    }

    public void setAmazonmusic(boolean amazonmusic) {
        this.amazonmusic = amazonmusic;
    }

    public boolean isInstagram() {
        return instagram;
    }

    public void setInstagram(boolean instagram) {
        this.instagram = instagram;
    }

    public boolean isPandora() {
        return pandora;
    }

    public void setPandora(boolean pandora) {
        this.pandora = pandora;
    }

    public boolean isSpotify() {
        return spotify;
    }

    public void setSpotify(boolean spotify) {
        this.spotify = spotify;
    }

    public boolean isYoutube() {
        return youtube;
    }

    public void setYoutube(boolean youtube) {
        this.youtube = youtube;
    }
}