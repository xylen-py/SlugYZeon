package com.slugyzeon.plugin.gaana;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

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

        String streamUrl = resolveStreamUrl(streamData);
        if (streamUrl == null || streamUrl.isEmpty()) {
            throw new RuntimeException("No playable stream found for: " + trackInfo.title);
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamUrl), null)) {
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
            }
        }
    }

    private String resolveStreamUrl(JsonNode streamData) {
        String[] fields = { "url", "stream_url", "mp3_url", "media_url" };
        for (String field : fields) {
            if (streamData.has(field) && !streamData.get(field).isNull()) {
                String url = streamData.get(field).asText("");
                if (!url.isEmpty() && !url.contains(".m3u8")) {
                    return url;
                }
            }
        }

        if (streamData.has("segments") && streamData.get("segments").isArray()
                && streamData.get("segments").size() > 0) {
            JsonNode firstSegment = streamData.get("segments").get(0);
            if (firstSegment.has("url")) {
                return firstSegment.get("url").asText();
            }
        }

        if (streamData.has("hlsUrl") && !streamData.get("hlsUrl").isNull()) {
            return streamData.get("hlsUrl").asText();
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
}
