package com.slugyzeon.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.slugyzeon.pandora")
@Component
public class PandoraConfig {

    private String tokenApiUrl = "";
    private String csrfToken = "";
    private boolean preferTokenApi = true;
    private int searchLimit = 6;

    public String getTokenApiUrl() {
        return tokenApiUrl;
    }

    public void setTokenApiUrl(String tokenApiUrl) {
        this.tokenApiUrl = tokenApiUrl;
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }

    public boolean isPreferTokenApi() {
        return preferTokenApi;
    }

    public void setPreferTokenApi(boolean preferTokenApi) {
        this.preferTokenApi = preferTokenApi;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }
}
