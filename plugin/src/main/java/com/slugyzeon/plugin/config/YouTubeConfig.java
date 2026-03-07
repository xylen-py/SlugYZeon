package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@ConfigurationProperties(prefix = "plugins.slugyzeon.youtube")
@Component
public class YouTubeConfig {

    private List<String> invidiousInstances;
    private List<String> pipedInstances;
    private List<String> mirrorProviders;

    public List<String> getInvidiousInstances() {
        return invidiousInstances;
    }

    public void setInvidiousInstances(List<String> invidiousInstances) {
        this.invidiousInstances = invidiousInstances;
    }

    public List<String> getPipedInstances() {
        return pipedInstances;
    }

    public void setPipedInstances(List<String> pipedInstances) {
        this.pipedInstances = pipedInstances;
    }

    public List<String> getMirrorProviders() {
        return mirrorProviders;
    }

    public void setMirrorProviders(List<String> mirrorProviders) {
        this.mirrorProviders = mirrorProviders;
    }
}