package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class AndroidClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "ANDROID";
    }

    @Override
    public String getClientVersion() {
        return "20.01.35";
    }

    @Override
    public String getUserAgent() {
        return "com.google.android.youtube/20.01.35 (Linux; U; Android 14) identity";
    }

    @Override
    public String getClientId() {
        return "3";
    }

    @Override
    public boolean requiresCipher() {
        return false;
    }

    @Override
    public void populateClientContext(ObjectNode clientNode) {
        super.populateClientContext(clientNode);
        clientNode.put("osName", "Android")
                .put("osVersion", "14")
                .put("androidSdkVersion", "34")
                .put("deviceMake", "Google")
                .put("deviceModel", "Pixel 6");
    }
}
