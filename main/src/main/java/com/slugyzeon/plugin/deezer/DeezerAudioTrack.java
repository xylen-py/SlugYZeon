package com.slugyzeon.plugin.deezer;

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

/**
 * Deezer audio track that streams directly from the deezer-plugin-api's
 * /stream/:id endpoint, which returns fully decrypted audio (MP3/FLAC).
 * No mirroring — native Deezer playback.
 */
public class DeezerAudioTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(DeezerAudioTrack.class);

    private final DeezerAudioSourceManager sourceManager;
    private final String albumName;
    private final String albumUrl;
    private final String artistUrl;
    private final String artistArtworkUrl;
    private final String previewUrl;

    public DeezerAudioTrack(AudioTrackInfo trackInfo, DeezerAudioSourceManager sourceManager) {
        this(trackInfo, null, null, null, null, null, sourceManager);
    }

    public DeezerAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl,
            String artistArtworkUrl, String previewUrl, DeezerAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.albumName = albumName;
        this.albumUrl = albumUrl;
        this.artistUrl = artistUrl;
        this.artistArtworkUrl = artistArtworkUrl;
        this.previewUrl = previewUrl;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String streamUrl = sourceManager.getApiHandler().getStreamUrl(trackInfo.identifier);

        if (streamUrl == null || streamUrl.isEmpty()) {
            throw new FriendlyException("Failed to resolve Deezer stream for track: " + trackInfo.title,
                    FriendlyException.Severity.COMMON, null);
        }

        log.debug("Streaming Deezer track {} from: {}", trackInfo.identifier, streamUrl);

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamUrl), null)) {
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
            }
        }
    }

    public String getAlbumName() {
        return albumName;
    }

    public String getAlbumUrl() {
        return albumUrl;
    }

    public String getArtistUrl() {
        return artistUrl;
    }

    public String getArtistArtworkUrl() {
        return artistArtworkUrl;
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new DeezerAudioTrack(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl,
                sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
