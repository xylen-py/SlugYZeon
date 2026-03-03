package com.slugyzeon.plugin.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyTokenTracker {

    private static final Logger log = LoggerFactory.getLogger(SpotifyTokenTracker.class);

    private static final String SPOTIFY_ACCOUNTS_TOKEN = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_TOKEN_URL = "https://open.spotify.com/api/token";
    private static final String SPOTIFY_HOMEPAGE = "https://open.spotify.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";

    private static final Pattern SCRIPT_PATTERN = Pattern.compile("src=\"([^\"]*mobile-web-player[^\"]*\\.js)\"");
    private static final Pattern SECRET_ARRAY_PATTERN = Pattern
            .compile("\\[\\{secret:['\"]([^'\"]+)['\"],version:(\\d+)\\}");
    private static final Pattern SECRET_NUMBER_ARRAY_PATTERN = Pattern.compile("\"secret\":\\[([\\d]+(?:,[\\d]+)+)]");

    private static final int MAX_RETRIES = 2;
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String clientId;
    private final String clientSecret;
    private final String spDc;
    private final String customTokenEndpoint;

    private volatile String accessToken;
    private volatile Instant accessTokenExpires;

    private volatile String anonymousAccessToken;
    private volatile Instant anonymousExpires;

    private volatile String accountAccessToken;
    private volatile Instant accountAccessTokenExpires;

    private volatile String cachedTotpSecret;
    private volatile int cachedTotpVersion;
    private volatile Instant cachedSecretExpires;

    public SpotifyTokenTracker(String clientId, String clientSecret, String spDc, String customTokenEndpoint) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.spDc = spDc;
        this.customTokenEndpoint = customTokenEndpoint;

        if (!hasValidCredentials()) {
            log.debug("Spotify invalid credentials, falling back to public token.");
        }
        if (!hasValidAccountCredentials()) {
            log.debug("Spotify invalid sp_dc account credentials.");
        }
    }

    public boolean hasValidCredentials() {
        return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
    }

    public boolean hasValidAccountCredentials() {
        return spDc != null && !spDc.isEmpty();
    }

    public String getAccessToken(boolean preferAnonymous) throws IOException {
        if (preferAnonymous || !hasValidCredentials()) {
            return getAnonymousAccessToken();
        }
        if (isExpired(this.accessToken, this.accessTokenExpires)) {
            synchronized (this) {
                if (isExpired(this.accessToken, this.accessTokenExpires)) {
                    refreshAccessToken();
                }
            }
        }
        return this.accessToken;
    }

    public String getAnonymousAccessToken() throws IOException {
        if (isExpired(this.anonymousAccessToken, this.anonymousExpires)) {
            synchronized (this) {
                if (isExpired(this.anonymousAccessToken, this.anonymousExpires)) {
                    refreshAnonymousAccessToken();
                }
            }
        }
        return this.anonymousAccessToken;
    }

    public String getAccountAccessToken() throws IOException {
        if (!hasValidAccountCredentials()) {
            throw new IOException("Spotify sp_dc is not configured");
        }
        if (isExpired(this.accountAccessToken, this.accountAccessTokenExpires)) {
            synchronized (this) {
                if (isExpired(this.accountAccessToken, this.accountAccessTokenExpires)) {
                    refreshAccountAccessToken();
                }
            }
        }
        return this.accountAccessToken;
    }

    private boolean isExpired(String token, Instant expires) {
        return token == null || expires == null || expires.isBefore(Instant.now());
    }

    private void refreshAccessToken() throws IOException {
        String auth = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SPOTIFY_ACCOUNTS_TOKEN))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Basic " + auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(500L * (attempt + 1));
                        continue;
                    }
                    throw new IOException("Spotify OAuth returned status " + response.statusCode());
                }

                JsonNode json = mapper.readTree(response.body());
                this.accessToken = json.get("access_token").asText();
                long expiresIn = json.path("expires_in").asLong(3600);
                this.accessTokenExpires = Instant.now()
                        .plusSeconds(Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS, 60));
                log.info("Spotify access token refreshed via client credentials (expires in {}s)", expiresIn);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while refreshing Spotify client credentials token", e);
            }
        }
    }

    private void refreshAnonymousAccessToken() throws IOException {
        IOException lastException = null;

        try {
            fetchTokenViaTOTP();
            return;
        } catch (IOException e) {
            lastException = e;
            log.debug("TOTP token failed: {}", e.getMessage());
        }

        try {
            fetchTokenDirect();
            return;
        } catch (IOException e) {
            lastException = e;
            log.debug("Direct token failed: {}", e.getMessage());
        }

        if (customTokenEndpoint != null && !customTokenEndpoint.isBlank()) {
            try {
                fetchTokenFromEndpoint(customTokenEndpoint);
                return;
            } catch (IOException e) {
                lastException = e;
                log.debug("Custom endpoint token failed: {}", e.getMessage());
            }
        }

        throw new IOException("All Spotify token methods failed", lastException);
    }

    private void fetchTokenViaTOTP() throws IOException {
        String totpSecret = getOrFetchTotpSecret();
        if (totpSecret == null) {
            throw new IOException("Could not extract TOTP secret from Spotify");
        }

        String hexSecret = transformSecretToHex(totpSecret);
        String totp = generateTOTP(hexSecret, 30, 6);
        long ts = System.currentTimeMillis();

        String url = SPOTIFY_TOKEN_URL + "?reason=init&productType=web-player&totp=" + totp
                + "&totpVer=" + cachedTotpVersion + "&ts=" + ts;

        fetchAndSetToken(url, null);
        log.info("Spotify anonymous token refreshed via TOTP (version {})", cachedTotpVersion);
    }

    private void fetchTokenDirect() throws IOException {
        String url = SPOTIFY_TOKEN_URL + "?reason=init&productType=web-player";
        fetchAndSetToken(url, null);
        log.info("Spotify anonymous token refreshed via direct open.spotify.com");
    }

    private void fetchTokenFromEndpoint(String endpoint) throws IOException {
        fetchAndSetToken(endpoint, null);
        log.info("Spotify anonymous token refreshed via custom endpoint");
    }

    private void refreshAccountAccessToken() throws IOException {
        String totpSecret = null;
        String url;

        try {
            totpSecret = getOrFetchTotpSecret();
        } catch (IOException ignored) {
        }

        if (totpSecret != null) {
            String hexSecret = transformSecretToHex(totpSecret);
            String totp = generateTOTP(hexSecret, 30, 6);
            long ts = System.currentTimeMillis();
            url = SPOTIFY_TOKEN_URL + "?reason=init&productType=web-player&totp=" + totp
                    + "&totpVer=" + cachedTotpVersion + "&ts=" + ts;
        } else {
            url = SPOTIFY_TOKEN_URL + "?reason=init&productType=web-player";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("App-Platform", "WebPlayer")
                    .header("Cookie", "sp_dc=" + this.spDc)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Spotify account token returned status " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            String token = extractToken(json);
            if (token == null) {
                throw new IOException("No account access token in response");
            }

            this.accountAccessToken = token;
            this.accountAccessTokenExpires = extractExpiry(json);
            log.info("Spotify account token refreshed via sp_dc");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing Spotify account token", e);
        }
    }

    private void fetchAndSetToken(String url, String cookie) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("App-Platform", "WebPlayer")
                    .GET();

            if (cookie != null) {
                builder.header("Cookie", cookie);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Token endpoint returned " + response.statusCode() + " for " + url);
            }

            JsonNode json = mapper.readTree(response.body());
            String token = extractToken(json);
            if (token == null) {
                throw new IOException("No access token in response from " + url);
            }

            boolean isAnonymous = json.path("isAnonymous").asBoolean(true);
            Instant expiry = extractExpiry(json);

            if (Instant.now().isAfter(expiry)) {
                throw new IOException("Token from " + url + " is already expired");
            }

            this.anonymousAccessToken = token;
            this.anonymousExpires = expiry;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching token", e);
        }
    }

    private String extractToken(JsonNode json) {
        String token = json.path("accessToken").asText(null);
        if (token != null)
            return token;
        return json.path("access_token").asText(null);
    }

    private Instant extractExpiry(JsonNode json) {
        long expiresMs = json.path("accessTokenExpirationTimestampMs").asLong(0);
        if (expiresMs > 0) {
            return Instant.ofEpochMilli(expiresMs).minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS);
        }
        long expiresIn = json.path("expires_in").asLong(3600);
        return Instant.now().plusSeconds(Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS, 60));
    }

    private String getOrFetchTotpSecret() throws IOException {
        if (cachedTotpSecret != null && cachedSecretExpires != null && cachedSecretExpires.isAfter(Instant.now())) {
            return cachedTotpSecret;
        }

        String[] result = scrapeSecretFromSpotify();
        if (result != null) {
            cachedTotpSecret = result[0];
            cachedTotpVersion = Integer.parseInt(result[1]);
            cachedSecretExpires = Instant.now().plusSeconds(600);
            log.debug("Scraped TOTP secret (version {}, length {})", cachedTotpVersion, cachedTotpSecret.length());
            return cachedTotpSecret;
        }

        throw new IOException("Could not scrape TOTP secret from Spotify");
    }

    private String[] scrapeSecretFromSpotify() throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPOTIFY_HOMEPAGE))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            String html = response.body();
            Matcher scriptMatcher = SCRIPT_PATTERN.matcher(html);
            List<String> scriptUrls = new ArrayList<>();
            while (scriptMatcher.find()) {
                String scriptUrl = scriptMatcher.group(1);
                if (!scriptUrl.contains("vendor")) {
                    scriptUrls.add(scriptUrl);
                }
            }

            for (String scriptUrl : scriptUrls) {
                String[] result = extractSecretFromScript(scriptUrl);
                if (result != null) {
                    return result;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while scraping Spotify", e);
        }

        return null;
    }

    private String[] extractSecretFromScript(String scriptUrl) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(scriptUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            String js = response.body();

            Matcher stringMatcher = SECRET_ARRAY_PATTERN.matcher(js);
            if (stringMatcher.find()) {
                String secret = stringMatcher.group(1);
                String version = stringMatcher.group(2);
                log.debug("Found string TOTP secret (version {}) from {}", version, scriptUrl);
                return new String[] { secret, version };
            }

            Matcher numberMatcher = SECRET_NUMBER_ARRAY_PATTERN.matcher(js);
            if (numberMatcher.find()) {
                String numbersStr = numberMatcher.group(1);
                String[] parts = numbersStr.split(",");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    sb.append((char) Integer.parseInt(part.trim()));
                }
                log.debug("Found number array TOTP secret from {}", scriptUrl);
                return new String[] { sb.toString(), "5" };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting secret", e);
        }

        return null;
    }

    private String transformSecretToHex(String secret) {
        int[] transformed = new int[secret.length()];
        for (int i = 0; i < secret.length(); i++) {
            transformed[i] = secret.charAt(i) ^ ((i % 33) + 9);
        }

        StringBuilder joined = new StringBuilder();
        for (int val : transformed) {
            joined.append(val);
        }

        byte[] utf8Bytes = joined.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder();
        for (byte b : utf8Bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String generateTOTP(String hexSecret, int period, int digits) {
        long time = System.currentTimeMillis() / 1000 / period;
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(time);
        byte[] timeBytes = buffer.array();

        try {
            SecretKeySpec keySpec = new SecretKeySpec(hexStringToByteArray(hexSecret), "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(timeBytes);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
