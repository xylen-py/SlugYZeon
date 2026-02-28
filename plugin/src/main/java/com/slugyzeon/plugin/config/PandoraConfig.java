package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.slugyzeon.pandora")
@Component
public class PandoraConfig {

    private String tokenApiUrl = "https://get.1lucas1apk.fun/pandora/gettoken";
    private int searchLimit = 6;

    public String getTokenApiUrl() {
        return tokenApiUrl;
    }

    public void setTokenApiUrl(String tokenApiUrl) {
        this.tokenApiUrl = tokenApiUrl;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }
}
