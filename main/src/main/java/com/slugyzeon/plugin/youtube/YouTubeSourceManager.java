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
    private final Function<Void, AudioPlayerManager> audioPlayerManager;
    private final String apiUrl;
    private final String masterKey;
    private AudioSourceManager originalYouTubeSource;
    private boolean attached = false;
    private final java.util.concurrent.ExecutorService networkExecutor = java.util.concurrent.Executors.newFixedThreadPool(10);
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager httpSourceManager = new com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager();

    public YouTubeSourceManager(
            String apiUrl,
            String masterKey,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
        this.apiUrl = apiUrl;
        this.masterKey = masterKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public Function<Void, AudioPlayerManager> getAudioPlayerManager() {
        return audioPlayerManager;
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

    public java.util.concurrent.ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }

    public java.net.http.HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (originalYouTubeSource == null)
            return null;

        String videoId = extractVideoId(reference.identifier);
        if (videoId != null) {
            AudioTrack cdnTrack = checkCdnForTrack(videoId);
            if (cdnTrack != null) return cdnTrack;
        }

        AudioItem result = null;
        Exception loadError = null;

        try {
            result = originalYouTubeSource.loadItem(manager, reference);
        } catch (Exception e) {
            loadError = e;
        }

        if (result != null) {
            if (result instanceof AudioTrack) {
                return enrichTrack((AudioTrack) result, reference);
            }
            if (result instanceof AudioPlaylist) {
                AudioPlaylist original = (AudioPlaylist) result;
                java.util.List<AudioTrack> fixedTracks = new java.util.ArrayList<>();
                for (AudioTrack track : original.getTracks()) {
                    fixedTracks.add((AudioTrack) enrichTrack(track, new AudioReference(track.getInfo().identifier, null)));
                }
                AudioTrack selectedTrack = original.getSelectedTrack() != null
                        ? (AudioTrack) enrichTrack(original.getSelectedTrack(), new AudioReference(original.getSelectedTrack().getInfo().identifier, null))
                        : null;
                return new BasicAudioPlaylist(original.getName(), fixedTracks, selectedTrack, original.isSearchResult());
            }
            return result;
        }

        return null;
    }

    private AudioItem enrichTrack(AudioTrack track, AudioReference reference) {
        return wrapTrack(track);
    }

    AudioTrack checkCdnForTrack(String videoId) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiUrl + "/api/v1/metadata/" + videoId))
                .header("User-Agent", "SlugYZeon-Node")
                .timeout(java.time.Duration.ofSeconds(3))
                .GET().build();
            java.net.http.HttpResponse<String> res = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                
            if (res.statusCode() == 200 && res.body() != null) {
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.body());
                String title = json.path("title").asText("Unknown");
                String author = json.path("author").asText("Unknown");
                long length = json.path("length").asLong(Long.MAX_VALUE);
                String thumb = "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg";
                
                AudioTrackInfo info = new AudioTrackInfo(
                    title, author, length, videoId,
                    false, apiUrl + "/api/v1/stream/" + videoId, thumb, null);
                
                com.sedmelluq.discord.lavaplayer.track.AudioTrack httpTrack = new com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack(info, new com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor(null, null), httpSourceManager);
                
                return new YouTubeTrack(info, videoId, httpTrack, this);
            }
        } catch (Exception e) {
            log.error("Failed to fetch metadata from CDN for {}", videoId, e);
        }
        return null;
    }

    static String extractVideoId(String url) {
        if (url == null || (url = url.trim()).isEmpty()) return null;
        if (url.length() == 11 && !url.matches(".*[/.#?&].*")) return url;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:v=|youtu\\.be/|/embed/|/v/)([^&#?/]+)").matcher(url);
        if (m.find()) {
            String id = m.group(1);
            return id.length() >= 11 ? id.substring(0, 11) : id;
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
        if (track instanceof YouTubeTrack) {
            AudioTrack original = ((YouTubeTrack) track).getOriginalTrack();
            if (original != null && originalYouTubeSource != null) {
                return originalYouTubeSource.isTrackEncodable(original);
            }
            return true;
        }
        return originalYouTubeSource != null ? originalYouTubeSource.isTrackEncodable(track) : true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        if (track instanceof YouTubeTrack) {
            AudioTrack original = ((YouTubeTrack) track).getOriginalTrack();
            if (original != null && originalYouTubeSource != null) {
                output.writeBoolean(true);
                originalYouTubeSource.encodeTrack(original, output);
            } else {
                output.writeBoolean(false);
            }
        } else if (originalYouTubeSource != null) {
            output.writeBoolean(true);
            originalYouTubeSource.encodeTrack(track, output);
        } else {
            output.writeBoolean(false);
        }
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, java.io.DataInput input) throws java.io.IOException {
        AudioTrack original = null;
        try {
            boolean hasOriginal = input.readBoolean();
            if (hasOriginal && originalYouTubeSource != null) {
                original = originalYouTubeSource.decodeTrack(trackInfo, input);
            }
        } catch (Exception ignored) {
        }
        
        YouTubeTrack decoded = new YouTubeTrack(trackInfo, trackInfo.identifier, original, this);
        AudioItem fixed = enrichTrack(decoded, new AudioReference(trackInfo.identifier, null));
        if (fixed instanceof AudioTrack) {
            return (AudioTrack) fixed;
        }
        return decoded;
    }

    @Override
    public void shutdown() {
        if (originalYouTubeSource != null)
            originalYouTubeSource.shutdown();
        networkExecutor.shutdownNow();
    }
}