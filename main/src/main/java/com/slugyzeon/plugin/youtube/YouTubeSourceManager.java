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
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager httpSourceManager = new com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager();

    public com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager getHttpSourceManager() {
        return httpSourceManager;
    }

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
                log.info("Attached SlugYZeon-YTCDN to YouTube source {}", source.getClass().getName());
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
                return wrapTrack((AudioTrack) result);
            }
            if (result instanceof AudioPlaylist) {
                AudioPlaylist original = (AudioPlaylist) result;
                List<AudioTrack> fixedTracks = new ArrayList<>();
                for (AudioTrack track : original.getTracks()) {
                    fixedTracks.add(wrapTrack(track));
                }
                AudioTrack selectedTrack = original.getSelectedTrack() != null
                        ? wrapTrack(original.getSelectedTrack())
                        : null;
                return new BasicAudioPlaylist(original.getName(), fixedTracks, selectedTrack, original.isSearchResult());
            }
            return result;
        }

        return null;
    }

    AudioTrack checkCdnForTrack(String videoId) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiUrl + "/api/v1/metadata/" + videoId))
                .header("User-Agent", "SlugYZeon-Node")
                .timeout(java.time.Duration.ofSeconds(3))
                .GET().build();
            var res = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                
            if (res.statusCode() == 200 && res.body() != null) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.body());
                String title = json.path("title").asText("");
                String author = json.path("author").asText("");
                try {
                    var oReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json"))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .GET().build();
                    var oRes = httpClient.send(oReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (oRes.statusCode() == 200) {
                        var oJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(oRes.body());
                        if (oJson.has("title")) title = oJson.path("title").asText(title);
                        if (oJson.has("author_name")) author = oJson.path("author_name").asText(author);
                    }
                } catch (Exception ignored) {
                }
                
                long length = json.path("length").asLong(Long.MAX_VALUE);
                String maxRes = "https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg";
                String thumb = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                try {
                    var headReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(maxRes))
                        .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .timeout(java.time.Duration.ofSeconds(2))
                        .build();
                    if (httpClient.send(headReq, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                        thumb = maxRes;
                    }
                } catch (Exception ignored) {
                }

                String streamUrl = apiUrl + "/api/v1/stream/" + videoId;
                AudioTrackInfo info = new AudioTrackInfo(title, author, length, videoId, false, streamUrl, thumb, null);                
                return new YouTubeTrack(info, videoId, null, this, streamUrl);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    String checkCdnStreamUrl(String videoId) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiUrl + "/api/v1/metadata/" + videoId))
                .header("User-Agent", "SlugYZeon-Node")
                .timeout(java.time.Duration.ofSeconds(3))
                .GET().build();
            var res = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (res.statusCode() == 200 && res.body() != null) {
                return apiUrl + "/api/v1/stream/" + videoId;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String extractVideoId(String url) {
        if (url == null || (url = url.trim()).isEmpty()) return null;
        if (url.contains("list=")) return null;
        if (url.length() == 11 && url.matches("^[a-zA-Z0-9_-]{11}$")) return url;

        var m = java.util.regex.Pattern.compile(
            "(?i)(?:youtu\\.be/|v/|vi/|embed/|shorts/|live/|e/|\\?v=|&v=|\\?vi=|&vi=)([a-zA-Z0-9_-]{11})"
        ).matcher(url);

        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private AudioTrack wrapTrack(AudioTrack original) {
        return new YouTubeTrack(original.getInfo(), original.getInfo().identifier, original, this);
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
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        AudioTrack original = null;
        try {
            boolean hasOriginal = input.readBoolean();
            if (hasOriginal && originalYouTubeSource != null) {
                original = originalYouTubeSource.decodeTrack(trackInfo, input);
            }
        } catch (Exception ignored) {
        }
        
        return new YouTubeTrack(trackInfo, trackInfo.identifier, original, this);
    }

    @Override
    public void shutdown() {
        if (originalYouTubeSource != null)
            originalYouTubeSource.shutdown();
        networkExecutor.shutdownNow();
    }
}