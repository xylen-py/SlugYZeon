package com.slugyzeon.plugin.lastfm;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import com.slugyzeon.plugin.mirror.DefaultMirroringAudioTrackResolver;
import com.slugyzeon.plugin.mirror.MirroringAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LastFmAudioSourceManager extends MirroringAudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(LastFmAudioSourceManager.class);

    public static final String SOURCE_NAME = "lastfm";

    private static final Pattern LASTFM_URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?last\\.fm/(?:[a-z]{2}/)?music/.+");

    private final LastFmApiHandler api;
    private final SlugYZeonConfig.LastFmConfig config;
    private volatile AudioPlayerManager playerManager;

    public LastFmAudioSourceManager(SlugYZeonConfig.LastFmConfig config, AudioPlayerManager manager) {
        super(unused -> null, new DefaultMirroringAudioTrackResolver(config.getProviders()));
        this.config = config;
        this.api = new LastFmApiHandler(config.getApiKey());
        this.playerManager = manager;
    }

    @Override
    public AudioPlayerManager getAudioPlayerManager() {
        return playerManager;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        this.playerManager = manager;
        return loadItem(reference.identifier);
    }

    public AudioItem loadItem(String identifier) {
        try {
            if (!LASTFM_URL_PATTERN.matcher(identifier).matches())
                return null;

            return resolveUrl(identifier);
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Last.fm track", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioItem resolveUrl(String url) throws IOException {
        Map<String, String> pathInfo = api.parseUrlPath(url);
        if (pathInfo == null)
            return AudioReference.NO_TRACK;

        String artist = pathInfo.get("artist");
        String title = pathInfo.get("title");
        boolean isTrack = Boolean.parseBoolean(pathInfo.getOrDefault("isTrack", "false"));

        if (isTrack)
            return resolveSingleTrack(url, artist, title);

        return resolveCollection(url, artist, title);
    }

    private AudioItem resolveSingleTrack(String url, String artist, String title) {
        AudioTrackInfo info = new AudioTrackInfo(title, artist, 0, url, false, url, null, null);
        return new LastFmAudioTrack(info, this);
    }

    private AudioItem resolveCollection(String url, String artist, String title) throws IOException {
        String pageBody = api.fetchPageBody(url);
        if (pageBody == null)
            return AudioReference.NO_TRACK;

        List<String> youtubeUrls = api.extractYouTubeUrls(pageBody);
        if (youtubeUrls.isEmpty()) {
            AudioTrackInfo info = new AudioTrackInfo(title, artist, 0, url, false, url, null, null);
            return new LastFmAudioTrack(info, this);
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (String ytUrl : youtubeUrls) {
            AudioTrackInfo info = new AudioTrackInfo(
                    title + " (YouTube)", artist, 0, ytUrl, false, url, null, null);
            tracks.add(new LastFmAudioTrack(info, this));
        }

        return new BasicAudioPlaylist(title + " - " + artist, tracks, null, false);
    }

    public LastFmApiHandler getApiHandler() {
        return api;
    }

    public SlugYZeonConfig.LastFmConfig getConfig() {
        return config;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new LastFmAudioTrack(trackInfo, this);
    }
}
