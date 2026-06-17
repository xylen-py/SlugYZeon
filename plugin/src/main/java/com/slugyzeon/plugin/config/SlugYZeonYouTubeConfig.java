package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@ConfigurationProperties(prefix = "plugins.slugyzeon.youtube")
@Component
public class SlugYZeonYouTubeConfig {

    private List<String> mirrorProviders;
    private boolean localDiskCache = false;
    private String diskCachePath = "youtube-cache";

    public boolean isLocalDiskCache() {
        return localDiskCache;
    }

    public void setLocalDiskCache(boolean localDiskCache) {
        this.localDiskCache = localDiskCache;
    }

    public String getDiskCachePath() {
        return diskCachePath;
    }

    public void setDiskCachePath(String diskCachePath) {
        this.diskCachePath = diskCachePath;
    }

    public List<String> getMirrorProviders() {
        return mirrorProviders;
    }

    public void setMirrorProviders(List<String> mirrorProviders) {
        this.mirrorProviders = mirrorProviders;
    }
}