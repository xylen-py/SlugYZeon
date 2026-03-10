package com.slugyzeon.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.slugyzeon.plugin.amazonmusic.AmazonMusicAudioSourceManager;
import com.slugyzeon.plugin.config.*;
import com.slugyzeon.plugin.gaana.GaanaAudioSourceManager;
import com.slugyzeon.plugin.instagram.InstagramAudioSourceManager;
import com.slugyzeon.plugin.pandora.PandoraAudioSourceManager;
import com.slugyzeon.plugin.spotify.SpotifyAudioSourceManager;
import com.slugyzeon.plugin.youtube.YouTubeSourceManager;
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
    private PandoraAudioSourceManager pandora;
    private SpotifyAudioSourceManager spotify;
    private YouTubeSourceManager youtube;

    public SlugYZeonPlugin(
            SlugYZeonSourcesConfig sourcesConfig,
            GaanaConfig gaanaConfig,
            AmazonMusicConfig amazonMusicConfig,
            InstagramConfig instagramConfig,
            PandoraConfig pandoraConfig,
            SlugYZeonSpotifyConfig spotifyConfig,
            SlugYZeonYouTubeConfig youtubeConfig) {
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
                    amazonMusicConfig.getPlaylistLoadLimit(),
                    amazonMusicConfig.getAlbumLoadLimit(),
                    amazonMusicConfig.getArtistLoadLimit(),
                    unused -> manager);
        }
        if (sourcesConfig.isInstagram()) {
            this.instagram = new InstagramAudioSourceManager();
        }
        if (sourcesConfig.isPandora()) {
            this.pandora = new PandoraAudioSourceManager(
                    DEFAULT_PROVIDERS,
                    pandoraConfig.getTokenApiUrl(),
                    pandoraConfig.getCsrfToken(),
                    pandoraConfig.isPreferTokenApi(),
                    pandoraConfig.getSearchLimit(),
                    unused -> manager);
        }
        if (sourcesConfig.isSpotify()) {
            this.spotify = new SpotifyAudioSourceManager(
                    DEFAULT_PROVIDERS,
                    spotifyConfig.getClientId(),
                    spotifyConfig.getClientSecret(),
                    spotifyConfig.getSpDc(),
                    spotifyConfig.getNuanceUrl(),
                    spotifyConfig.getCountryCode(),
                    spotifyConfig.getPlaylistLoadLimit(),
                    spotifyConfig.getAlbumLoadLimit(),
                    spotifyConfig.isResolveArtistsInSearch(),
                    spotifyConfig.isLocalFiles(),
                    unused -> manager);
        }
        if (sourcesConfig.isYoutube()) {
            String[] providers = (youtubeConfig.getMirrorProviders() != null && !youtubeConfig.getMirrorProviders().isEmpty())
                            ? youtubeConfig.getMirrorProviders().toArray(new String[0])
                            : new String[] {
                                    "ytsearch:%QUERY%",
                                    "jssearch:%QUERY%",
                                    "dzsearch:%QUERY%",
                                    "scsearch:%QUERY%"
                            };
            this.youtube = new YouTubeSourceManager(
                    providers,
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
        if (pandora != null) {
            log.info("Registering Pandora audio source manager...");
            manager.registerSourceManager(pandora);
        }
        if (spotify != null) {
            log.info("Registering Spotify audio source manager...");
            manager.registerSourceManager(spotify);
        }

        return manager;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (youtube != null && manager != null) {
            youtube.attachToYouTube(manager);
        }
    }
}