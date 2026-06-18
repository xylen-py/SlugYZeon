package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class AndroidVrClient extends InnerTubeClient {

    @Override
    public String getClientName() {
        return "ANDROID_VR";
    }

    @Override
    public String getClientVersion() {
        return "1.65.10";
    }

    @Override
    public String getUserAgent() {
        return "Mozilla/5.0 (X11; Linux x86_64; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/39.3.0.11.46.766180192 Chrome/136.0.7103.177 VR Safari/537.36,gzip(gfe);GoogleHypersonic";
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
        clientNode.put("osName", "Android").put("osVersion", "15").put("androidSdkVersion", "35").put("deviceMake", "Google");
    }
}
