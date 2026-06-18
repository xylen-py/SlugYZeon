package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class AndroidVrClient extends InnerTubeClient {

    @Override
    public String getClientName() {
        return "ANDROID_VR";
    }

    @Override
    public String getClientVersion() {
        return "1.56.24";
    }

    @Override
    public String getUserAgent() {
        return "com.google.android.apps.youtube.vr/1.56.24 (Linux; U; Android 14) identity";
    }

    @Override
    public String getClientId() {
        return "50";
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
        clientNode.put("osName", "Android").put("osVersion", "14");
    }
}
