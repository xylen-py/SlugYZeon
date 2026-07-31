package com.slugyzeon.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.slugyzeon.plugin.amazonmusic.AmazonMusicAudioSourceManager;
import com.slugyzeon.plugin.config.*;
import com.slugyzeon.plugin.deezer.DeezerAudioSourceManager;
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
            "dzisrc:%ISRC%",
            "ytsearch:\"%ISRC%\"",
            "ytmsearch:%QUERY%",
            "ytsearch:%QUERY%"
    };

    private AudioPlayerManager manager;

    private GaanaAudioSourceManager gaana;
    private AmazonMusicAudioSourceManager amazonMusic;
    private InstagramAudioSourceManager instagram;
    private PandoraAudioSourceManager pandora;
    private SpotifyAudioSourceManager spotify;
    private YouTubeSourceManager youtube;
    private DeezerAudioSourceManager deezer;

    public SlugYZeonPlugin(
            org.springframework.core.env.Environment env,
            SlugYZeonSourcesConfig sourcesConfig,
            GaanaConfig gaanaConfig,
            AmazonMusicConfig amazonMusicConfig,
            InstagramConfig instagramConfig,
            PandoraConfig pandoraConfig,
            SlugYZeonSpotifyConfig spotifyConfig,
            SlugYZeonYouTubeConfig youtubeConfig,
            SlugYZeonDeezerConfig deezerConfig) {
        log.info("Loading SlugYZeoN plugin...");
        
        String[] lavasrcProviders = env.getProperty("plugins.lavasrc.providers", String[].class);
        String[] slugyzeonProviders = env.getProperty("plugins.slugyzeon.providers", String[].class);
        
        String[] providersToUse = DEFAULT_PROVIDERS;
        if (lavasrcProviders != null && lavasrcProviders.length > 0) {
            providersToUse = lavasrcProviders;
            log.info("SlugYZeoN has smartly synced mirroring providers from LavaSrc's config!");
        } else if (slugyzeonProviders != null && slugyzeonProviders.length > 0) {
            providersToUse = slugyzeonProviders;
        }

        String youtubeRefreshToken = env.getProperty("plugins.youtube.oauth.refresh-token");
        if (youtubeRefreshToken == null) {
            youtubeRefreshToken = env.getProperty("plugins.youtube.oauth.refreshToken");
        }

        if (sourcesConfig.isGaana()) {
            this.gaana = new GaanaAudioSourceManager(
                    gaanaConfig.getApiUrl(),
                    gaanaConfig.getPlaylistLoadLimit(),
                    gaanaConfig.getAlbumLoadLimit(),
                    gaanaConfig.getArtistLoadLimit(),
                    unused -> manager);
        }
        if (sourcesConfig.isAmazonmusic()) {
            this.amazonMusic = new AmazonMusicAudioSourceManager(
                    providersToUse,
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
                    providersToUse,
                    pandoraConfig.getTokenApiUrl(),
                    pandoraConfig.getCsrfToken(),
                    pandoraConfig.isPreferTokenApi(),
                    pandoraConfig.getSearchLimit(),
                    unused -> manager);
        }
        if (sourcesConfig.isSpotify()) {
            this.spotify = new SpotifyAudioSourceManager(
                    providersToUse,
                    spotifyConfig.getCountryCode(),
                    spotifyConfig.getPlaylistLoadLimit(),
                    spotifyConfig.getAlbumLoadLimit(),
                    spotifyConfig.isResolveArtistsInSearch(),
                    spotifyConfig.isLocalFiles(),
                    unused -> manager);
        }
        if (sourcesConfig.isDeezer()) {
            this.deezer = new DeezerAudioSourceManager(
                    deezerConfig.getApiUrl(),
                    deezerConfig.getPlaylistLoadLimit(),
                    deezerConfig.getAlbumLoadLimit(),
                    deezerConfig.getArtistLoadLimit(),
                    deezerConfig.getQuality());
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
                    youtubeConfig.isLocalDiskCache(),
                    youtubeConfig.getDiskCachePath(),
                    youtubeConfig.getCipherUrl(),
                    youtubeRefreshToken,
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
        if (deezer != null) {
            log.info("Registering Deezer audio source manager...");
            manager.registerSourceManager(deezer);
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