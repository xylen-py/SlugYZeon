package com.slugyzeon.plugin.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(YouTubeSourceManager.class);

    private final YouTubeProxyHandler proxyHandler;
    private final Function<Void, AudioPlayerManager> audioPlayerManager;
    private final String[] mirrorProviders;
    private AudioSourceManager originalYouTubeSource;
    private boolean attached = false;

    public YouTubeSourceManager(
            String[] mirrorProviders,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this.proxyHandler = new YouTubeProxyHandler();
        this.audioPlayerManager = audioPlayerManager;
        this.mirrorProviders = mirrorProviders != null ? mirrorProviders : new String[] { "scsearch:%QUERY%" };
    }

    public YouTubeProxyHandler getProxyHandler() {
        return proxyHandler;
    }

    public Function<Void, AudioPlayerManager> getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public String[] getMirrorProviders() {
        return mirrorProviders;
    }

    public boolean isAttached() {
        return attached;
    }

    public AudioSourceManager getOriginalYouTubeSource() {
        return originalYouTubeSource;
    }

    @SuppressWarnings("unchecked")
    public boolean attachToYouTube(AudioPlayerManager manager) {
        List<AudioSourceManager> sources = findSourceList(manager);
        if (sources == null)
            return false;

        for (int i = 0; i < sources.size(); i++) {
            AudioSourceManager source = sources.get(i);
            if (source instanceof YouTubeSourceManager)
                continue;

            boolean isYouTube = "youtube".equalsIgnoreCase(source.getSourceName());
            if (!isYouTube) {
                String className = source.getClass().getName().toLowerCase();
                isYouTube = className.contains("youtube") || className.contains("youtubeaudiosource");
            }

            if (isYouTube) {
                this.originalYouTubeSource = source;
                sources.set(i, this);
                this.attached = true;
                log.info("Registered Youtube audio Fallback manager...");
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private List<AudioSourceManager> findSourceList(AudioPlayerManager manager) {
        Class<?> clazz = manager.getClass();

        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof List) {
                        List<?> list = (List<?>) value;
                        if (!list.isEmpty() && list.get(0) instanceof AudioSourceManager) {
                            return (List<AudioSourceManager>) value;
                        }
                        if (list.isEmpty() && field.getName().toLowerCase().contains("source")) {
                            return (List<AudioSourceManager>) value;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }

    @Override
    public String getSourceName() {
        return "youtube";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (originalYouTubeSource == null)
            return null;

        AudioItem result = null;
        Exception loadError = null;

        try {
            result = originalYouTubeSource.loadItem(manager, reference);
        } catch (Exception e) {
            loadError = e;
        }

        if (result != null) {
            if (result instanceof AudioTrack) {
                return wrapTrack((AudioTrack) result);
            }
            if (result instanceof AudioPlaylist) {
                return wrapPlaylist((AudioPlaylist) result);
            }
            return result;
        }

        if (loadError != null && isRetriableError(loadError)) {
            log.info("[SlugYZeon] Original YouTube client failed & attempting scraper fallback for {}", reference.identifier);
            AudioItem fallback = fallbackLoadItem(reference);
            if (fallback != null)
                return fallback;
        }

        return null;
    }

    static boolean isRetriableError(Throwable e) {
        if (e == null)
            return false;

        String className = e.getClass().getSimpleName().toLowerCase();
        if (className.contains("allclientsfailed"))
            return true;
        if (className.contains("friendlyexception")) {
            try {
                com.sedmelluq.discord.lavaplayer.tools.FriendlyException fe = (com.sedmelluq.discord.lavaplayer.tools.FriendlyException) e;
                if (fe.severity == com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON
                        || fe.severity == com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS) {
                    String msg = fe.getMessage() != null ? fe.getMessage().toLowerCase() : "";
                    if (msg.contains("something broke") || msg.contains("something went wrong"))
                        return true;
                }
            } catch (Exception ignored) {
            }
        }

        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("sign in") || msg.contains("login") || msg.contains("requires login")
                || msg.contains("bot") || msg.contains("confirm") || msg.contains("verify")
                || msg.contains("403") || msg.contains("400") || msg.contains("401")
                || msg.contains("age") || msg.contains("restricted") || msg.contains("unavailable")
                || msg.contains("country") || msg.contains("blocked") || msg.contains("copyright")
                || msg.contains("removed") || msg.contains("private") || msg.contains("premium")
                || msg.contains("members only") || msg.contains("requires payment")
                || msg.contains("all clients failed") || msg.contains("configuration error")
                || msg.contains("no supported audio") || msg.contains("playback on other")
                || msg.contains("invalid status") || msg.contains("not success status")
                || msg.contains("playability")) {
            return true;
        }

        return isRetriableError(e.getCause());
    }

    private AudioItem fallbackLoadItem(AudioReference reference) {
        String id = reference.identifier;
        if (id == null)
            return null;

        if (id.startsWith("ytsearch:") || id.startsWith("ytmsearch:")) {
            String query = id.substring(id.indexOf(':') + 1);
            List<YouTubeProxyHandler.VideoInfo> results = proxyHandler.search(query, id.startsWith("ytm"));
            if (results != null && !results.isEmpty()) {
                log.info("[SlugYZeon] Scraper search found {} results", results.size());
                List<AudioTrack> tracks = new ArrayList<>();
                for (YouTubeProxyHandler.VideoInfo info : results) {
                    tracks.add(buildProxyTrack(info));
                }
                return new BasicAudioPlaylist("Search results for: " + query, tracks, null, true);
            }
        } else {
            String videoId = extractVideoId(id);
            if (videoId != null) {
                YouTubeProxyHandler.VideoInfo info = proxyHandler.getVideoInfo(videoId);
                if (info != null) {
                    log.info("[SlugYZeon] successfully extracted info for videoId {}", videoId);
                    return buildProxyTrack(info);
                }
            }
        }
        return null;
    }

    private AudioTrack buildProxyTrack(YouTubeProxyHandler.VideoInfo info) {
        AudioTrackInfo trackInfo = new AudioTrackInfo(
                info.title, info.author, info.durationMs, info.videoId,
                false, info.uri, info.thumbnail, null);
        return new YouTubeTrack(trackInfo, info.videoId, null, this);
    }

    static String extractVideoId(String url) {
        if (url == null || url.isEmpty())
            return null;
        url = url.trim();

        if (url.length() == 11 && !url.contains("/") && !url.contains(".") && !url.contains(":")) {
            return url;
        }

        try {
            if (url.contains("youtu.be/")) {
                int start = url.indexOf("youtu.be/") + 9;
                String rest = url.substring(start);
                int end = rest.indexOf('?');
                if (end == -1)
                    end = rest.indexOf('&');
                if (end == -1)
                    end = rest.indexOf('/');
                if (end == -1)
                    end = rest.length();
                String id = rest.substring(0, end);
                return id.length() >= 11 ? id.substring(0, 11) : id;
            }

            if (url.contains("v=")) {
                int start = url.indexOf("v=") + 2;
                String rest = url.substring(start);
                int end = rest.indexOf('&');
                if (end == -1)
                    end = rest.indexOf('#');
                if (end == -1)
                    end = rest.length();
                String id = rest.substring(0, end);
                return id.length() >= 11 ? id.substring(0, 11) : id;
            }

            if (url.contains("/embed/")) {
                int start = url.indexOf("/embed/") + 7;
                String rest = url.substring(start);
                int end = rest.indexOf('?');
                if (end == -1)
                    end = rest.indexOf('/');
                if (end == -1)
                    end = rest.length();
                String id = rest.substring(0, end);
                return id.length() >= 11 ? id.substring(0, 11) : id;
            }

            if (url.contains("/v/")) {
                int start = url.indexOf("/v/") + 3;
                String rest = url.substring(start);
                int end = rest.indexOf('?');
                if (end == -1)
                    end = rest.indexOf('/');
                if (end == -1)
                    end = rest.length();
                String id = rest.substring(0, end);
                return id.length() >= 11 ? id.substring(0, 11) : id;
            }
        } catch (Exception ignored) {
        }

        return url;
    }

    private AudioTrack wrapTrack(AudioTrack original) {
        return new YouTubeTrack(original.getInfo(), original.getInfo().identifier, original, this);
    }

    private AudioPlaylist wrapPlaylist(AudioPlaylist original) {
        List<AudioTrack> wrappedTracks = new ArrayList<>();
        for (AudioTrack track : original.getTracks()) {
            wrappedTracks.add(wrapTrack(track));
        }
        AudioTrack selectedTrack = original.getSelectedTrack() != null
                ? wrapTrack(original.getSelectedTrack())
                : null;
        return new BasicAudioPlaylist(original.getName(), wrappedTracks, selectedTrack, original.isSearchResult());
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return originalYouTubeSource != null ? originalYouTubeSource.isTrackEncodable(track) : true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        if (originalYouTubeSource != null)
            originalYouTubeSource.encodeTrack(track, output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        AudioTrack original = null;
        if (originalYouTubeSource != null) {
            try {
                original = originalYouTubeSource.decodeTrack(trackInfo, input);
            } catch (Exception ignored) {
            }
        }
        return new YouTubeTrack(trackInfo, trackInfo.identifier, original, this);
    }

    @Override
    public void shutdown() {
        if (originalYouTubeSource != null)
            originalYouTubeSource.shutdown();
    }
}