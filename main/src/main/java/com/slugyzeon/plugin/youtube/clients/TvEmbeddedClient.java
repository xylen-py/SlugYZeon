package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class TvEmbeddedClient extends InnerTubeClient {

    @Override
    public String getClientName() {
        return "TVHTML5_SIMPLY_EMBEDDED_PLAYER";
    }

    @Override
    public String getClientVersion() {
        return "2.0";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.142 Safari/537.36; SmartTv/10.0";
    }

    @Override
    public String getClientId() {
        return "85";
    }

    @Override
    public void populateClientContext(ObjectNode clientNode) {
        super.populateClientContext(clientNode);
    }
}
