package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@ConfigurationProperties(prefix = "plugins.slugyzeon.youtube")
@Component
public class SlugYZeonYouTubeConfig {

    private List<String> mirrorProviders;

    public List<String> getMirrorProviders() {
        return mirrorProviders;
    }

    public void setMirrorProviders(List<String> mirrorProviders) {
        this.mirrorProviders = mirrorProviders;
    }
}