package com.slugyzeon.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.slugyzeon.plugin.amazonmusic.AmazonMusicAudioSourceManager;
import com.slugyzeon.plugin.config.*;
import com.slugyzeon.plugin.gaana.GaanaAudioSourceManager;
import com.slugyzeon.plugin.instagram.InstagramAudioSourceManager;
import com.slugyzeon.plugin.lastfm.LastFmAudioSourceManager;
import com.slugyzeon.plugin.pandora.PandoraAudioSourceManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SlugYZeonPlugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SlugYZeonPlugin.class);
    private static final String[] DEFAULT_PROVIDERS = new String[] {
            "ytsearch:\"%ISRC%\"",
            "ytsearch:%QUERY%"
    };

    private AudioPlayerManager manager;

    private GaanaAudioSourceManager gaana;
    private AmazonMusicAudioSourceManager amazonMusic;
    private InstagramAudioSourceManager instagram;
    private LastFmAudioSourceManager lastFm;
    private PandoraAudioSourceManager pandora;

    public SlugYZeonPlugin(
            SlugYZeonSourcesConfig sourcesConfig,
            GaanaConfig gaanaConfig,
            AmazonMusicConfig amazonMusicConfig,
            InstagramConfig instagramConfig,
            LastFmConfig lastFmConfig,
            PandoraConfig pandoraConfig) {
        log.info("Loading SlugYZeoN plugin...");

        if (sourcesConfig.isGaana()) {
            this.gaana = new GaanaAudioSourceManager(
                    DEFAULT_PROVIDERS,
                    gaanaConfig.getApiUrl(),
                    gaanaConfig.getPlaylistLoadLimit(),
                    gaanaConfig.getAlbumLoadLimit(),
                    gaanaConfig.getArtistLoadLimit(),
                    unused -> manager);
        }
        if (sourcesConfig.isAmazonmusic()) {
            this.amazonMusic = new AmazonMusicAudioSourceManager(
                    DEFAULT_PROVIDERS,
                    amazonMusicConfig.getCountryCode(),
                    unused -> manager);
        }
        if (sourcesConfig.isInstagram()) {
            this.instagram = new InstagramAudioSourceManager();
        }
        if (sourcesConfig.isLastfm()) {
            String apiKey = lastFmConfig.getApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                log.warn("Last.fm source disabled: no API key provided");
            } else {
                this.lastFm = new LastFmAudioSourceManager(
                        apiKey,
                        DEFAULT_PROVIDERS,
                        unused -> manager);
            }
        }
        if (sourcesConfig.isPandora()) {
            this.pandora = new PandoraAudioSourceManager(
                    DEFAULT_PROVIDERS,
                    pandoraConfig.getTokenApiUrl(),
                    pandoraConfig.getSearchLimit(),
                    unused -> manager);
        }
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        this.manager = manager;

        if (gaana != null) {
            log.info("Registering Gaana audio source manager...");
            manager.registerSourceManager(gaana);
        }
        if (amazonMusic != null) {
            log.info("Registering Amazon Music audio source manager...");
            manager.registerSourceManager(amazonMusic);
        }
        if (instagram != null) {
            log.info("Registering Instagram audio source manager...");
            manager.registerSourceManager(instagram);
        }
        if (lastFm != null) {
            log.info("Registering Last.fm audio source manager...");
            manager.registerSourceManager(lastFm);
        }
        if (pandora != null) {
            log.info("Registering Pandora audio source manager...");
            manager.registerSourceManager(pandora);
        }

        return manager;
    }
}
