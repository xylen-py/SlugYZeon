package com.slugyzeon.plugin.youtube.clients;

public class TvHtml5Client extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "TVHTML5";
    }

    @Override
    public String getClientVersion() {
        return "7.20250120.19.00";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36";
    }

    @Override
    public String getClientId() {
        return "7";
    }

    @Override
    public boolean requiresCipher() {
        return true;
    }

    @Override
    public boolean isEmbedded() {
        return false;
    }
}
