package com.slugyzeon.plugin.config;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.slugyzeon.plugin.gaana.GaanaAudioSourceManager;
import com.slugyzeon.plugin.amazonmusic.AmazonMusicAudioSourceManager;
import com.slugyzeon.plugin.instagram.InstagramAudioSourceManager;
import com.slugyzeon.plugin.lastfm.LastFmAudioSourceManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SlugYZeonPluginLoader implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SlugYZeonPluginLoader.class);

    private final SlugYZeonConfig config;

    public SlugYZeonPluginLoader(SlugYZeonConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (config.getGaana().isEnabled()) {
            log.info("[SlugYZeoN] Registering Gaana audio source manager...");
            manager.registerSourceManager(new GaanaAudioSourceManager(config.getGaana()));
        }

        if (config.getAmazonmusic().isEnabled()) {
            log.info("[SlugYZeoN] Registering Amazon Music audio source manager...");
            manager.registerSourceManager(new AmazonMusicAudioSourceManager(config.getAmazonmusic()));
        }

        if (config.getInstagram().isEnabled()) {
            log.info("[SlugYZeoN] Registering Instagram audio source manager...");
            manager.registerSourceManager(new InstagramAudioSourceManager(config.getInstagram()));
        }

        if (config.getLastfm().isEnabled()) {
            String apiKey = config.getLastfm().getApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                log.warn("[SlugYZeoN] Last.fm source disabled: no API key provided");
            } else {
                log.info("[SlugYZeoN] Registering Last.fm audio source manager...");
                manager.registerSourceManager(new LastFmAudioSourceManager(config.getLastfm()));
            }
        }

        return manager;
    }
}
