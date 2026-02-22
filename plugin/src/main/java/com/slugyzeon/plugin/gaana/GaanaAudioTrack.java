package com.slugyzeon.plugin.gaana;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.container.adts.AdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegTsElementaryInputStream;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GaanaAudioTrack extends DelegatedAudioTrack {

    private final GaanaAudioSourceManager sourceManager;

    public GaanaAudioTrack(AudioTrackInfo trackInfo, GaanaAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String trackId = trackInfo.identifier;
        String quality = sourceManager.getConfig().getStreamQuality();

        JsonNode streamData = sourceManager.getApiHandler().getStream(trackId, quality);
        if (streamData == null) {
            throw new RuntimeException("No stream data available for: " + trackInfo.title);
        }

        byte[] audioData = downloadAudio(streamData);
        if (audioData == null || audioData.length == 0) {
            throw new RuntimeException("No playable stream found for: " + trackInfo.title);
        }

        GaanaSeekableStream seekableStream = new GaanaSeekableStream(audioData);
        MpegTsElementaryInputStream tsStream = new MpegTsElementaryInputStream(seekableStream,
                MpegTsElementaryInputStream.ADTS_ELEMENTARY_STREAM);
        processDelegate(new AdtsAudioTrack(trackInfo, tsStream), executor);
    }

    private byte[] downloadAudio(JsonNode streamData) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        if (streamData.has("segments") && streamData.get("segments").isArray()
                && streamData.get("segments").size() > 0) {
            return downloadSegments(client, streamData.get("segments"));
        }

        String directUrl = resolveDirectUrl(streamData);
        if (directUrl != null) {
            return downloadUrl(client, directUrl);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private byte[] downloadSegments(HttpClient client, JsonNode segments) throws Exception {
        int count = segments.size();
        CompletableFuture<byte[]>[] futures = new CompletableFuture[count];

        for (int i = 0; i < count; i++) {
            String segUrl = segments.get(i).get("url").asText();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(segUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://gaana.com/")
                    .header("Origin", "https://gaana.com")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            futures[i] = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(r -> r.statusCode() == 200 ? r.body() : new byte[0]);
        }

        CompletableFuture.allOf(futures).join();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (var future : futures) {
            byte[] data = future.get();
            if (data != null && data.length > 0) {
                buffer.write(data);
            }
        }
        return buffer.toByteArray();
    }

    private byte[] downloadUrl(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://gaana.com/")
                .header("Origin", "https://gaana.com")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private String resolveDirectUrl(JsonNode streamData) {
        String[] fields = { "url", "stream_url", "mp3_url", "media_url" };
        for (String field : fields) {
            if (streamData.has(field) && !streamData.get(field).isNull()) {
                String url = streamData.get(field).asText("");
                if (!url.isEmpty() && !url.contains(".m3u8")) {
                    return url;
                }
            }
        }
        return null;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new GaanaAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    private static class GaanaSeekableStream extends SeekableInputStream {
        private final byte[] data;
        private int position = 0;

        public GaanaSeekableStream(byte[] data) {
            super(data.length, 0);
            this.data = data;
        }

        @Override
        public int read() {
            if (position >= data.length)
                return -1;
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length)
                return -1;
            int available = Math.min(len, data.length - position);
            System.arraycopy(data, position, b, off, available);
            position += available;
            return available;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        protected void seekHard(long pos) {
            this.position = (int) Math.min(pos, data.length);
        }

        @Override
        public boolean canSeekHard() {
            return true;
        }

        @Override
        public List<AudioTrackInfoProvider> getTrackInfoProviders() {
            return Collections.emptyList();
        }
    }
}