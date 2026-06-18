package com.slugyzeon.plugin.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YouTubeTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTrack.class);

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private final YouTubeSourceManager sourceManager;
    private final String videoId;
    private final AudioTrack originalTrack;

    public YouTubeTrack(AudioTrackInfo trackInfo, String videoId, AudioTrack originalTrack,
            YouTubeSourceManager sourceManager) {
        super(trackInfo);
        this.videoId = videoId;
        this.originalTrack = originalTrack;
        this.sourceManager = sourceManager;
    }

    public AudioTrack getOriginalTrack() {
        return originalTrack;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (sourceManager.isLocalDiskCache()) {
            java.io.File webmFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ".webm");
            if (webmFile.exists() && webmFile.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(webmFile)) {
                    processDelegate(new MatroskaAudioTrack(trackInfo, new com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream(fis)), executor);
                    return;
                } catch (Exception e) {
                }
            }
            java.io.File m4aFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ".m4a");
            if (m4aFile.exists() && m4aFile.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(m4aFile)) {
                    processDelegate(new MpegAudioTrack(trackInfo, new com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream(fis)), executor);
                    return;
                } catch (Exception e) {
                }
            }
            triggerBackgroundCache();
        }

        if (originalTrack instanceof InternalAudioTrack) {
            try {
                processDelegate((InternalAudioTrack) originalTrack, executor);
                return;
            } catch (Exception e) {
                if (!YouTubeSourceManager.isRetriableError(e)) {
                    throw e;
                }
            }
        }

        if (tryExactOfficialTrackFallback(executor))
            return;
        if (tryProxyStream(executor))
            return;
        if (tryMirrorSearch(executor))
            return;

        throw new FriendlyException(
                "[SlugYZeon] All fallbacks exhausted for " + videoId,
                FriendlyException.Severity.SUSPICIOUS,
                new RuntimeException("Video " + videoId));
    }

    private boolean tryExactOfficialTrackFallback(LocalAudioTrackExecutor executor) {
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        if (manager == null) {
            return false;
        }

        List<String> validQueries = new ArrayList<>();
        if (trackInfo.isrc != null && !trackInfo.isrc.isEmpty()) {
            validQueries.add("ytmsearch:\"" + trackInfo.isrc.replace("-", "") + "\"");
        }

        String counterpartId = sourceManager.getProxyHandler().getCounterpartVideoId(this.videoId);
        if (counterpartId != null && !counterpartId.isEmpty()) {
            validQueries.add("ytsearch:" + counterpartId);
        }

        if (trackInfo.title != null && !trackInfo.title.isEmpty() && trackInfo.author != null
                && !trackInfo.author.isEmpty()) {
            if (!trackInfo.author.equalsIgnoreCase("Unknown")
                    && !trackInfo.author.equalsIgnoreCase("Unknown artist")) {
                validQueries.add("ytmsearch:" + trackInfo.title + " " + trackInfo.author);
            }
        }

        for (String query : validQueries) {
            try {
                CompletableFuture<AudioItem> future = new CompletableFuture<>();
                manager.loadItem(query, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(track);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        future.complete(playlist);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(null);
                    }

                    @Override
                    public void loadFailed(FriendlyException e) {
                        future.complete(null);
                    }
                });

                AudioItem result = future.get(10, TimeUnit.SECONDS);

                if (result instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) result;
                    for (AudioTrack track : playlist.getTracks()) {
                        if (!track.getIdentifier().equals(this.videoId)) {
                            if (track instanceof InternalAudioTrack) {
                                processDelegate((InternalAudioTrack) track, executor);
                                return true;
                            }
                        }
                    }
                } else if (result instanceof InternalAudioTrack) {
                    AudioTrack track = (AudioTrack) result;
                    if (!track.getIdentifier().equals(this.videoId)) {
                        processDelegate((InternalAudioTrack) track, executor);
                        return true;
                    }
                }
            } catch (Exception e) {
            }
        }
        return false;
    }

    private boolean tryProxyStream(LocalAudioTrackExecutor executor) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                YouTubeProxyHandler.StreamResult stream = sourceManager.getProxyHandler().getStream(videoId);
                if (stream == null)
                    return false;

                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(stream.url))
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();

                HttpResponse<InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200)
                    continue;

                try (com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream nis = new com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream(
                        response.body())) {
                    if (stream.mimeType != null && (stream.mimeType.contains("webm")
                            || stream.mimeType.contains("opus"))) {
                        processDelegate(new MatroskaAudioTrack(trackInfo, nis), executor);
                    } else {
                        processDelegate(new MpegAudioTrack(trackInfo, nis), executor);
                    }
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean tryMirrorSearch(LocalAudioTrackExecutor executor) {
        AudioPlayerManager manager = sourceManager.getAudioPlayerManager().apply(null);
        if (manager == null)
            return false;

        String query = trackInfo.title;
        if (trackInfo.author != null && !trackInfo.author.isEmpty()
                && !"Unknown".equalsIgnoreCase(trackInfo.author)
                && !"Unknown artist".equalsIgnoreCase(trackInfo.author)) {
            query = trackInfo.title + " " + trackInfo.author;
        }

        for (String provider : sourceManager.getMirrorProviders()) {
            if (provider.contains("ytsearch") || provider.contains("ytmsearch"))
                continue;

            String searchQuery = provider.replace("%QUERY%", query);

            try {
                CompletableFuture<AudioItem> future = new CompletableFuture<>();
                manager.loadItem(searchQuery, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(track);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        future.complete(playlist);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(null);
                    }

                    @Override
                    public void loadFailed(FriendlyException e) {
                        future.complete(null);
                    }
                });

                AudioItem result = future.get(10, TimeUnit.SECONDS);
                if (result == null)
                    continue;

                if (result instanceof AudioPlaylist) {
                    AudioPlaylist playlist = (AudioPlaylist) result;
                    for (AudioTrack track : playlist.getTracks()) {
                        if (track instanceof InternalAudioTrack) {
                            processDelegate((InternalAudioTrack) track, executor);
                            return true;
                        }
                    }
                }

                if (result instanceof InternalAudioTrack) {
                    processDelegate((InternalAudioTrack) result, executor);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new YouTubeTrack(trackInfo, videoId, originalTrack, sourceManager);
    }

    private String extractUrlFromOriginalPlugin() {
        try {
            AudioSourceManager sm = sourceManager.getOriginalYouTubeSource();
            if (sm == null) return null;
            
            java.lang.reflect.Method getHttpInterfaceManager = null;
            try { getHttpInterfaceManager = sm.getClass().getMethod("getHttpInterfaceManager"); } catch (Exception ignored) {}
            
            Object httpInterfaceManager = null;
            if (getHttpInterfaceManager != null) {
                httpInterfaceManager = getHttpInterfaceManager.invoke(sm);
            }
            
            Object httpInterface = null;
            if (httpInterfaceManager != null) {
                java.lang.reflect.Method getInterface = httpInterfaceManager.getClass().getMethod("getInterface");
                httpInterface = getInterface.invoke(httpInterfaceManager);
            } else {
                try { 
                    java.lang.reflect.Method getHttpInterface = sm.getClass().getMethod("getHttpInterface");
                    httpInterface = getHttpInterface.invoke(sm);
                } catch (Exception ignored) {}
            }
            
            if (httpInterface == null) {
                return null;
            }
            
            java.lang.reflect.Method getTrackDetailsLoader = null;
            try { getTrackDetailsLoader = sm.getClass().getMethod("getTrackDetailsLoader"); } catch (Exception ignored) {}
            
            if (getTrackDetailsLoader == null) {
                return null;
            }
            
            Object loader = getTrackDetailsLoader.invoke(sm);
            
            java.lang.reflect.Method loadDetails = null;
            for (java.lang.reflect.Method m : loader.getClass().getMethods()) {
                if (m.getName().equals("loadDetails")) {
                    loadDetails = m;
                    break;
                }
            }
            if (loadDetails == null) {
                return null;
            }
            
            Object trackDetails;
            if (loadDetails.getParameterCount() == 2 && httpInterface != null) {
                trackDetails = loadDetails.invoke(loader, httpInterface, videoId);
            } else if (loadDetails.getParameterCount() == 1) {
                trackDetails = loadDetails.invoke(loader, videoId);
            } else {
                return null;
            }
            
            java.lang.reflect.Method getFormats = trackDetails.getClass().getMethod("getFormats");
            java.util.List<?> formats = (java.util.List<?>) getFormats.invoke(trackDetails);
            
            String bestUrl = null;
            long bestBitrate = -1;
            
            for (Object format : formats) {
                java.lang.reflect.Method getInfo = format.getClass().getMethod("getInfo");
                Object info = getInfo.invoke(format);
                
                java.lang.reflect.Method getMimeType = info.getClass().getMethod("getMimeType");
                String mimeType = (String) getMimeType.invoke(info);
                
                if (mimeType != null && mimeType.startsWith("audio/")) {
                    java.lang.reflect.Method getBitrate = info.getClass().getMethod("getBitrate");
                    long bitrate = ((Number) getBitrate.invoke(info)).longValue();
                    
                    java.lang.reflect.Method getUrl = format.getClass().getMethod("getUrl");
                    String url = (String) getUrl.invoke(format);
                    
                    if (url != null && bitrate > bestBitrate) {
                        bestBitrate = bitrate;
                        bestUrl = url;
                    }
                }
            }
            return bestUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private void triggerBackgroundCache() {
        java.io.File webmFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ".webm");
        java.io.File m4aFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ".m4a");
        if (webmFile.exists() || m4aFile.exists()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String streamUrl = extractUrlFromOriginalPlugin();
                String finalUa = UA;
                boolean isWebm = true;

                if (streamUrl == null) {
                    YouTubeProxyHandler.StreamResult stream = sourceManager.getProxyHandler().getStream(videoId);
                    if (stream == null) {
                        return;
                    }
                    streamUrl = stream.url;
                    if (stream.userAgent != null) finalUa = stream.userAgent;
                    isWebm = stream.mimeType != null && (stream.mimeType.contains("webm") || stream.mimeType.contains("opus"));
                }

                String ext = isWebm ? ".webm" : ".m4a";
                java.io.File targetFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ext);
                java.io.File partFile = new java.io.File(sourceManager.getDiskCachePath(), videoId + ext + ".part");

                if (targetFile.exists()) return;

                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest request = HttpRequest.newBuilder().uri(new URI(streamUrl))
                        .header("User-Agent", finalUa)
                        .header("Range", "bytes=0-")
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200 || response.statusCode() == 206) {
                    try (InputStream in = response.body(); java.io.FileOutputStream out = new java.io.FileOutputStream(partFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    partFile.renameTo(targetFile);
                }
            } catch (Exception e) {
            }
        });
    }
}