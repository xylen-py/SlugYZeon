package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class IosClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "IOS";
    }

    @Override
    public String getClientVersion() {
        return "21.02.1";
    }

    @Override
    public String getUserAgent() {
        return "com.google.ios.youtube/21.02.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)";
    }

    @Override
    public String getClientId() {
        return "5";
    }

    @Override
    public boolean requiresCipher() {
        return false;
    }

    @Override
    public void populateClientContext(ObjectNode clientNode) {
        super.populateClientContext(clientNode);
        clientNode.put("osName", "iPhone")
                .put("osVersion", "18.2.22C152")
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2");
    }
}
