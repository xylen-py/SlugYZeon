package com.slugyzeon.plugin.youtube.clients;

import com.fasterxml.jackson.databind.node.ObjectNode;

public abstract class InnerTubeClient {
    public static final String INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    public static final String INNERTUBE_MUSIC_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";

    public abstract String getClientName();

    public abstract String getClientVersion();

    public abstract String getUserAgent();

    public abstract String getClientId();

    public String getEndpointDomain() {
        return "https://www.youtube.com";
    }

    public String getApiKey() {
        return INNERTUBE_API_KEY;
    }

    public void populateClientContext(ObjectNode clientNode) {
        clientNode.put("clientName", getClientName())
                .put("clientVersion", getClientVersion())
                .put("hl", "en")
                .put("gl", "US");
    }
}
