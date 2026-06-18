package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class IosClient extends InnerTubeClient {
    @Override
    public String getClientName() {
        return "IOS";
    }

    @Override
    public String getClientVersion() {
        return "20.03.02";
    }

    @Override
    public String getUserAgent() {
        return "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)";
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
    public String getPlayerParams() {
        return "CgIQBg%3D%3D";
    }

    @Override
    public void populateClientContext(ObjectNode clientNode) {
        super.populateClientContext(clientNode);
        clientNode.put("osName", "iOS")
                .put("osVersion", "18.2.1")
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2");
    }
}
