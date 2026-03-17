package com.slugyzeon.plugin.youtube.clients;

public class TvHtml5Client extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "TVHTML5";
    }

    @Override
    public String getClientVersion() {
        return "7.20260113.16.00";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 CrKey/1.54.248666";
    }

    @Override
    public String getClientId() {
        return "85";
    }
}
