package com.slugyzeon.plugin.instagram;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstagramAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(InstagramAudioSourceManager.class);

    public static final String SOURCE_NAME = "instagram";

    private static final Pattern AUDIO_PATTERN = Pattern
            .compile("^https?://(?:www\\.)?instagram\\.com/reels/audio/(\\d+)");
    private static final Pattern POST_PATTERN = Pattern.compile("^https?://(?:www\\.)?instagram\\.com/p/([\\w-]+)");
    private static final Pattern REEL_PATTERN = Pattern
            .compile("^https?://(?:www\\.)?instagram\\.com/(?:reels?|reel)/([\\w-]+)");

    private final InstagramApiHandler api;
    private final HttpInterfaceManager httpInterfaceManager;

    public InstagramAudioSourceManager() {
        this.api = new InstagramApiHandler();
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    public InstagramApiHandler getApiHandler() {
        return api;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        return loadItem(reference.identifier);
    }

    public AudioItem loadItem(String identifier) {
        try {
            Matcher audioMatcher = AUDIO_PATTERN.matcher(identifier);
            if (audioMatcher.find())
                return resolveAudio(identifier, audioMatcher.group(1));

            Matcher postMatcher = POST_PATTERN.matcher(identifier);
            if (postMatcher.find())
                return resolvePost(identifier, postMatcher.group(1), "p");

            Matcher reelMatcher = REEL_PATTERN.matcher(identifier);
            if (reelMatcher.find())
                return resolvePost(identifier, reelMatcher.group(1), "reel");

            return null;
        } catch (IOException e) {
            throw new FriendlyException("Failed to load Instagram content", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioItem resolveAudio(String url, String audioId) throws IOException {
        Map<String, Object> data = api.fetchFromAudioAPI(audioId);
        if (data == null)
            return AudioReference.NO_TRACK;
        return buildTrack(data, url, audioId);
    }

    public AudioItem resolvePost(String url, String shortcode, String pathSegment) throws IOException {
        Map<String, Object> data = api.fetchFromGraphQL(shortcode, pathSegment);
        if (data == null)
            return AudioReference.NO_TRACK;
        return buildTrack(data, url, shortcode);
    }

    private AudioItem buildTrack(Map<String, Object> data, String url, String identifier) {
        String videoUrl = (String) data.get("videoUrl");
        if (videoUrl == null)
            return AudioReference.NO_TRACK;

        String title = (String) data.getOrDefault("title", "Instagram Content");
        String author = (String) data.getOrDefault("author", "Unknown");
        long length = data.containsKey("length") ? ((Number) data.get("length")).longValue() : 0;
        String thumbnail = (String) data.get("thumbnail");

        AudioTrackInfo info = new AudioTrackInfo(title, author, length, identifier, false, url, thumbnail, null);
        return new InstagramAudioTrack(info, videoUrl, this);
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
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
        return new InstagramAudioTrack(trackInfo, null, this);
    }

    @Override
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }
}