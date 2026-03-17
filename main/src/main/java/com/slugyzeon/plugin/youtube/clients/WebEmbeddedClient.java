package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class WebEmbeddedClient extends InnerTubeClient {

    @Override
    public String getClientName() {
        return "WEB_EMBEDDED_PLAYER";
    }

    @Override
    public String getClientVersion() {
        return "1.20240306.01.00";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    }

    @Override
    public String getClientId() {
        return "56";
    }

    @Override
    public void populateClientContext(ObjectNode clientNode) {
        super.populateClientContext(clientNode);
    }
}
