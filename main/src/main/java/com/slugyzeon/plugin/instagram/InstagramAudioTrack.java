package com.slugyzeon.plugin.instagram;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramAudioTrack extends DelegatedAudioTrack {

    private static final Pattern POST_PATTERN = Pattern.compile("instagram\\.com/p/([\\w-]+)");
    private static final Pattern REEL_PATTERN = Pattern.compile("instagram\\.com/(?:reels?|reel)/([\\w-]+)");
    private static final Pattern AUDIO_PATTERN = Pattern.compile("instagram\\.com/reels/audio/(\\d+)");

    private final InstagramAudioSourceManager sourceManager;
    private final String streamUrl;

    public InstagramAudioTrack(AudioTrackInfo trackInfo, String streamUrl, InstagramAudioSourceManager sourceManager) {
        super(trackInfo);
        this.streamUrl = streamUrl;
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String videoUrl = streamUrl;

        if (videoUrl == null || videoUrl.isEmpty()) {
            videoUrl = refetchVideoUrl();
        }

        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new FriendlyException("No stream URL available for Instagram track: " + trackInfo.title,
                    FriendlyException.Severity.COMMON, null);
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(videoUrl), null)) {
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
            }
        }
    }

    private String refetchVideoUrl() {
        try {
            String uri = trackInfo.uri;
            if (uri == null)
                return null;

            InstagramApiHandler api = sourceManager.getApiHandler();

            Matcher audioMatcher = AUDIO_PATTERN.matcher(uri);
            if (audioMatcher.find()) {
                Map<String, Object> data = api.fetchFromAudioAPI(audioMatcher.group(1));
                return data != null ? (String) data.get("videoUrl") : null;
            }

            String shortcode = null;
            String pathSegment = "p";

            Matcher reelMatcher = REEL_PATTERN.matcher(uri);
            if (reelMatcher.find()) {
                shortcode = reelMatcher.group(1);
                pathSegment = "reel";
            } else {
                Matcher postMatcher = POST_PATTERN.matcher(uri);
                if (postMatcher.find()) {
                    shortcode = postMatcher.group(1);
                }
            }

            if (shortcode != null) {
                Map<String, Object> data = api.fetchFromGraphQL(shortcode, pathSegment);
                return data != null ? (String) data.get("videoUrl") : null;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new InstagramAudioTrack(trackInfo, streamUrl, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}