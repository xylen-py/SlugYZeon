package com.slugyzeon.plugin.deezer;

import com.sedmelluq.discord.lavaplayer.container.flac.FlacAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
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
import java.security.MessageDigest;

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
        String quality = sourceManager.getPreferredQuality();

        DeezerApiHandler.StreamInfo streamInfo = sourceManager.getApiHandler()
                .getStreamInfo(trackInfo.identifier, quality);

        if (streamInfo == null) {
            if (!"128".equals(quality)) {
                streamInfo = sourceManager.getApiHandler().getStreamInfo(trackInfo.identifier, "128");
            }
            if (streamInfo == null) {
                throw new FriendlyException(
                        "Failed to resolve Deezer stream for: " + trackInfo.title,
                        FriendlyException.Severity.COMMON, null);
            }
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            URI streamUri = new URI(streamInfo.streamUrl);
            Long contentLength = streamInfo.contentLength > 0 ? streamInfo.contentLength : null;

            if (streamInfo.requiresDecryption()) {
                byte[] decryptionKey = streamInfo.blowfishKey.getBytes("ISO-8859-1");

                try (DeezerPersistentHttpStream stream = new DeezerPersistentHttpStream(
                        httpInterface, streamUri, contentLength, decryptionKey)) {

                    if ("FLAC".equalsIgnoreCase(streamInfo.quality)) {
                        processDelegate(new FlacAudioTrack(trackInfo, stream), executor);
                    } else {
                        processDelegate(new Mp3AudioTrack(trackInfo, stream), executor);
                    }
                }
            } else {
                try (PersistentHttpStream stream = new PersistentHttpStream(
                        httpInterface, streamUri, contentLength)) {
                    processDelegate(new Mp3AudioTrack(trackInfo, stream), executor);
                }
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
