package com.slugyzeon.plugin.mirror;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultMirroringAudioTrackResolver.class);

    private String[] providers = {
            "ytsearch:\"%ISRC%\"",
            "ytsearch:%QUERY%"
    };

    public DefaultMirroringAudioTrackResolver(String[] providers) {
        if (providers != null && providers.length > 0) {
            this.providers = providers;
        }
    }

    @Override
    public AudioItem apply(MirroringAudioTrack track) {
        for (var provider : providers) {
            if (provider.contains("%ISRC%")) {
                if (track.getInfo().isrc != null && !track.getInfo().isrc.isEmpty()) {
                    provider = provider.replace("%ISRC%", track.getInfo().isrc.replace("-", ""));
                } else {
                    continue;
                }
            }

            provider = provider.replace("%QUERY%", getTrackTitle(track));

            AudioItem item;
            try {
                item = track.loadItem(provider);
            } catch (Exception e) {
                log.error("Failed to load track from provider \"{}\"!", provider, e);
                continue;
            }

            if (item instanceof AudioPlaylist && ((AudioPlaylist) item).getTracks().isEmpty()
                    || item == AudioReference.NO_TRACK) {
                continue;
            }

            return item;
        }

        return AudioReference.NO_TRACK;
    }

    public String getTrackTitle(MirroringAudioTrack track) {
        var query = track.getInfo().title;
        if (!track.getInfo().author.equals("unknown")) {
            query += " " + track.getInfo().author;
        }
        return query;
    }
}
