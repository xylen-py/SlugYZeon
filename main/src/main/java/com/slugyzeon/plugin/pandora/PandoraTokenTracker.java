package com.slugyzeon.plugin.pandora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

public class PandoraTokenTracker {

    private static final Logger log = LoggerFactory.getLogger(PandoraTokenTracker.class);

    private static final String BASE_URL = "https://www.pandora.com";
    private static final String ANONYMOUS_LOGIN_ENDPOINT = "/api/v1/auth/anonymousLogin";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private final PandoraAudioSourceManager sourceManager;
    private final String tokenApiUrl;
    private final String configCsrfToken;
    private final boolean preferTokenApi;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String csrfToken;
    private volatile String authToken;
    private volatile Instant expires;

    public PandoraTokenTracker(PandoraAudioSourceManager sourceManager, String tokenApiUrl,
            String configCsrfToken, boolean preferTokenApi) {
        this.sourceManager = sourceManager;
        this.tokenApiUrl = tokenApiUrl;
        this.configCsrfToken = configCsrfToken;
        this.preferTokenApi = preferTokenApi;
        if (configCsrfToken != null && !configCsrfToken.isEmpty()) {
            this.csrfToken = configCsrfToken;
        }
        try {
            this.refreshTokens();
        } catch (Exception e) {
            log.warn("Failed to pre-fetch Pandora tokens during initialization, will fetch on first request", e);
        }
    }

    public synchronized String getCsrfToken() throws IOException {
        ensureValid();
        return csrfToken;
    }

    public synchronized String getAuthToken() throws IOException {
        ensureValid();
        return authToken;
    }

    private void ensureValid() throws IOException {
        if (this.csrfToken == null || this.authToken == null || this.expires == null
                || this.expires.isBefore(Instant.now())) {
            log.debug("Pandora tokens are invalid or expired, refreshing...");
            this.refreshTokens();
        }
    }

    public synchronized void forceRefresh() {
        this.csrfToken = null;
        this.authToken = null;
        this.expires = null;
    }

    private void refreshTokens() throws IOException {
        if (preferTokenApi) {
            try {
                refreshFromTokenApi();
                return;
            } catch (Exception e) {
                log.warn("Failed to fetch tokens from external API ({}), falling back to anonymous login",
                        e.getMessage());
            }
            refreshFromAnonymousLogin();
        } else {
            try {
                refreshFromAnonymousLogin();
                return;
            } catch (Exception e) {
                log.warn("Failed anonymous login ({}), falling back to external token API", e.getMessage());
            }
            try {
                refreshFromTokenApi();
            } catch (Exception e) {
                throw new IOException("All Pandora token refresh methods failed", e);
            }
        }
    }

    private void refreshFromTokenApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenApiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token API returned status " + response.statusCode());
        }

        JsonNode json = mapper.readTree(response.body());
        if (!json.has("success") || !json.get("success").asBoolean()) {
            throw new IOException("Token API returned success=false");
        }

        String csrf = json.has("csrfToken") ? json.get("csrfToken").asText(null) : null;
        String auth = json.has("authToken") ? json.get("authToken").asText(null) : null;

        if (csrf == null || csrf.isEmpty() || auth == null || auth.isEmpty()) {
            throw new IOException("Token API returned empty tokens");
        }

        this.csrfToken = csrf;
        this.authToken = auth;

        long expiresIn = json.has("expires_in_seconds") ? json.get("expires_in_seconds").asLong(300) : 300;
        this.expires = Instant.now().plusSeconds(Math.max(expiresIn - 30, 30));
        log.debug("Successfully refreshed Pandora tokens from external API (expires in {}s)", expiresIn);
    }

    private void refreshFromAnonymousLogin() throws IOException {
        if (csrfToken == null || csrfToken.isEmpty()) {
            csrfToken = Long.toHexString(System.currentTimeMillis());
        }

        HttpInterface httpInterface = sourceManager.getHttpInterface();
        loadCookies(httpInterface);

        HttpPost post = new HttpPost(BASE_URL + ANONYMOUS_LOGIN_ENDPOINT);
        post.setHeader("Accept", "application/json, text/plain, */*");
        post.setHeader("accept-language", "en-US,en;q=0.9");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("X-CsrfToken", csrfToken);
        post.setHeader("origin", BASE_URL);
        post.setHeader("User-Agent", USER_AGENT);
        post.setEntity(new StringEntity("", StandardCharsets.UTF_8));

        try (var resp = httpInterface.execute(post)) {
            String body = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = mapper.readTree(body);

            if (json.has("errorCode") && !json.get("errorCode").isNull()) {
                long errorCode = json.get("errorCode").asLong(-1);
                String errorString = json.has("errorString") ? json.get("errorString").asText("") : "";
                throw new IOException("Pandora anonymous login error: " + errorCode + " - " + errorString);
            }

            String auth = json.has("authToken") ? json.get("authToken").asText(null) : null;
            if (auth == null || auth.isEmpty()) {
                throw new IOException("No auth token received from Pandora anonymous login");
            }

            this.authToken = auth;
            this.expires = Instant.now().plusSeconds(24 * 60 * 60);
            log.debug("Successfully refreshed Pandora auth token via anonymous login");
        }
    }

    public void loadCookies(HttpInterface httpInterface) throws IOException {
        String csrf;
        try {
            csrf = this.csrfToken;
        } catch (Exception e) {
            throw new IOException("Failed to get csrf token for cookies", e);
        }

        if (csrf == null || csrf.isEmpty()) {
            throw new IOException("CSRF token is required to build cookie header");
        }

        var cookieStore = new BasicCookieStore();
        httpInterface.getContext().setCookieStore(cookieStore);
        httpInterface.getContext().setRequestConfig(
                RequestConfig.copy(
                        httpInterface.getContext().getRequestConfig() != null
                                ? httpInterface.getContext().getRequestConfig()
                                : RequestConfig.DEFAULT)
                        .setCookieSpec(CookieSpecs.STANDARD)
                        .build());

        var cookie = new BasicClientCookie("csrftoken", csrf);
        cookie.setPath("/");
        cookie.setSecure(true);
        cookie.setDomain("pandora.com");
        cookie.setAttribute("domain", ".pandora.com");
        cookieStore.addCookie(cookie);
    }
}
