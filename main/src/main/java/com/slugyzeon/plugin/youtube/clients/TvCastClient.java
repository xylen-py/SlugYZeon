package com.slugyzeon.plugin.youtube.clients;

public class TvCastClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "TVHTML5_CAST";
    }

    @Override
    public String getClientVersion() {
        return "1.1";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (CrKey armv7l 1.5.44178) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/43.0.2357.2 Safari/537.36";
    }

    @Override
    public String getClientId() {
        return "37";
    }

    @Override
    public boolean requiresCipher() {
        return false;
    }

    @Override
    public boolean isEmbedded() {
        return true;
    }
}
