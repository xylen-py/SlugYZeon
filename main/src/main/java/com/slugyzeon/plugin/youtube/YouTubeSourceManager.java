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

public class YouTubeSourceManager implements AudioSourceManager {

    private static final String[] SOURCE_LIST_FIELDS = {
            "sourceManagers", "sources", "audioSourceManagers"
    };

    private final YouTubeProxyHandler proxyHandler;
    private final Function<Void, AudioPlayerManager> audioPlayerManager;
    private final String[] mirrorProviders;
    private AudioSourceManager originalYouTubeSource;
    private boolean attached = false;

    public YouTubeSourceManager(
            List<String> invidiousInstances,
            List<String> pipedInstances,
            String[] mirrorProviders,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this.proxyHandler = new YouTubeProxyHandler(invidiousInstances, pipedInstances);
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

    @SuppressWarnings("unchecked")
    public boolean attachToYouTube(AudioPlayerManager manager) {
        List<AudioSourceManager> sources = findSourceList(manager);
        if (sources == null)
            return false;

        for (int i = 0; i < sources.size(); i++) {
            AudioSourceManager source = sources.get(i);
            if ("youtube".equals(source.getSourceName())) {
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

        while (clazz != null) {
            for (String fieldName : SOURCE_LIST_FIELDS) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof List) {
                        return (List<AudioSourceManager>) value;
                    }
                } catch (NoSuchFieldException ignored) {
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }

    public AudioSourceManager getOriginalYouTubeSource() {
        return originalYouTubeSource;
    }

    @Override
    public String getSourceName() {
        return "youtube";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (originalYouTubeSource == null)
            return null;

        AudioItem result;
        try {
            result = originalYouTubeSource.loadItem(manager, reference);
        } catch (Exception e) {
            return null;
        }

        if (result == null)
            return null;

        if (result instanceof AudioTrack) {
            return wrapTrack((AudioTrack) result);
        }

        if (result instanceof AudioPlaylist) {
            return wrapPlaylist((AudioPlaylist) result);
        }

        return result;
    }

    private AudioTrack wrapTrack(AudioTrack original) {
        String videoId = original.getInfo().identifier;
        return new YouTubeTrack(original.getInfo(), videoId, original, this);
    }

    private AudioPlaylist wrapPlaylist(AudioPlaylist original) {
        List<AudioTrack> wrappedTracks = new ArrayList<>();
        for (AudioTrack track : original.getTracks()) {
            wrappedTracks.add(wrapTrack(track));
        }

        AudioTrack selectedTrack = null;
        if (original.getSelectedTrack() != null) {
            selectedTrack = wrapTrack(original.getSelectedTrack());
        }

        return new BasicAudioPlaylist(
                original.getName(),
                wrappedTracks,
                selectedTrack,
                original.isSearchResult());
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        if (originalYouTubeSource != null) {
            return originalYouTubeSource.isTrackEncodable(track);
        }
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        if (originalYouTubeSource != null) {
            originalYouTubeSource.encodeTrack(track, output);
        }
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
        if (originalYouTubeSource != null) {
            originalYouTubeSource.shutdown();
        }
    }
}
