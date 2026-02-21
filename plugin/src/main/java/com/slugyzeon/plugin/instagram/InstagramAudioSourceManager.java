package com.slugyzeon.plugin.instagram;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.slugyzeon.plugin.config.SlugYZeonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(InstagramAudioSourceManager.class);
    private static final String SOURCE_NAME = "instagram";

    private static final String SEARCH_PREFIX = "igsearch:";

    private static final Pattern AUDIO_PATTERN = Pattern
            .compile("^https?://(?:www\\.)?instagram\\.com/reels/audio/(\\d+)");
    private static final Pattern POST_PATTERN = Pattern.compile("^https?://(?:www\\.)?instagram\\.com/p/([\\w-]+)");
    private static final Pattern REEL_PATTERN = Pattern
            .compile("^https?://(?:www\\.)?instagram\\.com/(?:reels?|reel)/([\\w-]+)");

    private final InstagramApiHandler apiHandler;
    private final SlugYZeonConfig.InstagramConfig config;
    private final HttpInterfaceManager httpInterfaceManager;

    public InstagramAudioSourceManager(SlugYZeonConfig.InstagramConfig config) {
        this.config = config;
        this.apiHandler = new InstagramApiHandler();
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    public InstagramApiHandler getApiHandler() {
        return apiHandler;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String url = reference.identifier;

        if (url.startsWith(SEARCH_PREFIX)) {
            String query = url.substring(SEARCH_PREFIX.length()).trim();
            if (query.isEmpty())
                return AudioReference.NO_TRACK;
            return resolveSearch(query);
        }

        Matcher audioMatcher = AUDIO_PATTERN.matcher(url);
        if (audioMatcher.find()) {
            return resolveAudio(url, audioMatcher.group(1));
        }

        Matcher postMatcher = POST_PATTERN.matcher(url);
        if (postMatcher.find()) {
            return resolvePost(url, postMatcher.group(1), "p");
        }

        Matcher reelMatcher = REEL_PATTERN.matcher(url);
        if (reelMatcher.find()) {
            return resolvePost(url, reelMatcher.group(1), "reel");
        }

        if (url.matches("^\\d{15,}(_\\d+)?$")) {
            String shortcode = InstagramApiHandler.getShortcodeFromMediaId(url);
            if (shortcode != null) {
                return resolvePost("https://www.instagram.com/p/" + shortcode + "/", shortcode, "p");
            }
        }

        return null;
    }

    private AudioItem resolveSearch(String query) {
        if (query.startsWith("http")) {
            return loadItem(null, new AudioReference(query, null));
        }

        if (query.matches("^\\d{15,}(_\\d+)?$")) {
            String shortcode = InstagramApiHandler.getShortcodeFromMediaId(query);
            if (shortcode != null) {
                return resolvePost("https://www.instagram.com/p/" + shortcode + "/", shortcode, "p");
            }
        }

        return AudioReference.NO_TRACK;
    }

    private AudioItem resolveAudio(String url, String audioId) {
        try {
            Map<String, Object> data = apiHandler.fetchFromAudioAPI(audioId);
            if (data == null)
                return AudioReference.NO_TRACK;
            return buildTrack(data, url, audioId);
        } catch (Exception e) {
            log.error("Failed to resolve Instagram audio {}: {}", audioId, e.getMessage());
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem resolvePost(String url, String shortcode, String pathSegment) {
        try {
            Map<String, Object> data = apiHandler.fetchFromGraphQL(shortcode, pathSegment);
            if (data == null)
                return AudioReference.NO_TRACK;
            return buildTrack(data, url, shortcode);
        } catch (Exception e) {
            log.error("Failed to resolve Instagram post {}: {}", shortcode, e.getMessage());
            return AudioReference.NO_TRACK;
        }
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
        } catch (Exception ignored) {
        }
    }
}
