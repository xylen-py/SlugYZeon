package com.slugyzeon.plugin.youtube.clients;

public class WebClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "WEB";
    }

    @Override
    public String getClientVersion() {
        return "2.20260114.01.00";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    }

    @Override
    public String getClientId() {
        return "1";
    }
}
