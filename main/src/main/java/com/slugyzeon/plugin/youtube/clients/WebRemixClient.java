package com.slugyzeon.plugin.youtube.clients;

public class WebRemixClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "WEB_REMIX";
    }

    @Override
    public String getClientVersion() {
        return "1.20260302.03.01";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
    }

    @Override
    public String getClientId() {
        return "67";
    }

    @Override
    public String getApiKey() {
        return INNERTUBE_MUSIC_KEY;
    }
}
