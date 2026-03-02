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

    private static final String DEFAULT_TOKEN_API = "https://spotify-gettoken.vercel.app/api/token";
    private static final String SPOTIFY_ACCOUNTS_TOKEN = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_HOMEPAGE = "https://open.spotify.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";

    private static final Pattern SECRET_PATTERN = Pattern.compile("\"secret\":\\[([\\d]+(?:,[\\d]+)+)]");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("src=\"([^\"]*mobile-web-player[^\"]*\\.js)\"");

    private static final int MAX_RETRIES = 2;
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String clientId;
    private String clientSecret;
    private String spDc;
    private String customTokenEndpoint;

    private volatile String accessToken;
    private volatile Instant accessTokenExpires;

    private volatile String anonymousAccessToken;
    private volatile Instant anonymousExpires;

    private volatile String accountAccessToken;
    private volatile Instant accountAccessTokenExpires;

    private volatile byte[] cachedSecret;
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
                    log.debug("Spotify access token expired, refreshing via client credentials");
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
                    log.debug("Spotify anonymous token expired, refreshing");
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
                    log.debug("Spotify account token expired, refreshing via sp_dc");
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
                        log.debug("Spotify OAuth returned {}, retrying ({}/{})", response.statusCode(), attempt + 1,
                                MAX_RETRIES);
                        Thread.sleep(500L * (attempt + 1));
                        continue;
                    }
                    throw new IOException("Spotify OAuth returned status " + response.statusCode());
                }

                JsonNode json = mapper.readTree(response.body());
                if (json.has("error") && !json.get("error").isNull()) {
                    throw new IOException("Spotify OAuth error: " + json.get("error").asText());
                }

                this.accessToken = json.get("access_token").asText();
                long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong(3600) : 3600;
                this.accessTokenExpires = Instant.now()
                        .plusSeconds(Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS, 60));
                log.debug("Spotify access token refreshed via client credentials (expires in {}s)", expiresIn);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while refreshing Spotify client credentials token", e);
            }
        }
    }

    private void refreshAnonymousAccessToken() throws IOException {
        IOException lastException = null;

        String tokenUrl = generateGetAccessTokenURL();
        try {
            fetchAndSetAnonymousToken(tokenUrl);
            return;
        } catch (IOException e) {
            lastException = e;
            log.debug("Primary token URL failed ({}): {}", tokenUrl.contains("totp") ? "TOTP" : "custom",
                    e.getMessage());
        }

        if (customTokenEndpoint == null || customTokenEndpoint.isBlank()) {
            try {
                fetchAndSetAnonymousToken(DEFAULT_TOKEN_API);
                return;
            } catch (IOException e) {
                lastException = e;
                log.debug("Default token API failed: {}", e.getMessage());
            }
        }

        String totpUrl = tryGenerateTotpUrl();
        if (totpUrl != null && !totpUrl.equals(tokenUrl)) {
            try {
                fetchAndSetAnonymousToken(totpUrl);
                return;
            } catch (IOException e) {
                lastException = e;
                log.debug("TOTP fallback failed: {}", e.getMessage());
            }
        }

        try {
            fetchAndSetAnonymousToken(DEFAULT_TOKEN_API);
            return;
        } catch (IOException e) {
            lastException = e;
            log.debug("Final token API fallback failed: {}", e.getMessage());
        }

        throw new IOException("All Spotify anonymous token methods failed", lastException);
    }

    private void fetchAndSetAnonymousToken(String tokenUrl) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("App-Platform", "WebPlayer")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Token endpoint returned status " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());

            if (json.has("error") && !json.get("error").isNull()) {
                throw new IOException("Token error: " + json.get("error").asText());
            }

            String token = extractTokenFromJson(json);
            if (token == null || token.isEmpty()) {
                throw new IOException("No access token in response from " + tokenUrl);
            }

            this.anonymousAccessToken = token;
            this.anonymousExpires = extractExpiry(json);

            log.debug("Spotify anonymous token refreshed via {}", describeUrl(tokenUrl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching anonymous token", e);
        }
    }

    private void refreshAccountAccessToken() throws IOException {
        String tokenUrl = generateGetAccessTokenURL();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
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
            if (json.has("error") && !json.get("error").isNull()) {
                throw new IOException("Spotify account token error: " + json.get("error").asText());
            }

            String token = extractTokenFromJson(json);
            if (token == null || token.isEmpty()) {
                throw new IOException("No account access token from Spotify");
            }

            this.accountAccessToken = token;
            this.accountAccessTokenExpires = extractExpiry(json);
            log.debug("Spotify account token refreshed via sp_dc");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing Spotify account token", e);
        }
    }

    private String extractTokenFromJson(JsonNode json) {
        if (json.has("accessToken") && !json.get("accessToken").isNull()) {
            return json.get("accessToken").asText();
        }
        if (json.has("access_token") && !json.get("access_token").isNull()) {
            return json.get("access_token").asText();
        }
        return null;
    }

    private Instant extractExpiry(JsonNode json) {
        if (json.has("accessTokenExpirationTimestampMs")) {
            long expiresMs = json.get("accessTokenExpirationTimestampMs").asLong(0);
            if (expiresMs > 0) {
                return Instant.ofEpochMilli(expiresMs).minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS);
            }
        }
        if (json.has("expires_in")) {
            long expiresIn = json.get("expires_in").asLong(3600);
            return Instant.now().plusSeconds(Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS, 60));
        }
        return Instant.now().plusSeconds(3600 - TOKEN_EXPIRY_BUFFER_SECONDS);
    }

    private String describeUrl(String url) {
        if (url.contains("totp"))
            return "TOTP";
        if (url.contains("spotify-gettoken"))
            return "free token API";
        if (url.contains("open.spotify.com"))
            return "open.spotify.com";
        return "custom endpoint";
    }

    private String generateGetAccessTokenURL() throws IOException {
        if (this.customTokenEndpoint != null && !this.customTokenEndpoint.isBlank()) {
            return this.customTokenEndpoint;
        }

        String totpUrl = tryGenerateTotpUrl();
        if (totpUrl != null) {
            return totpUrl;
        }

        return DEFAULT_TOKEN_API;
    }

    private String tryGenerateTotpUrl() {
        try {
            byte[] secret = getOrFetchSecret();
            if (secret != null) {
                byte[] transformedSecret = convertArrayToTransformedByteArray(secret);
                String hexSecret = toHexString(transformedSecret);
                String totp = generateTOTP(hexSecret, 30, 6);
                long ts = System.currentTimeMillis();
                return "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=" + totp
                        + "&totpVer=7&ts=" + ts;
            }
        } catch (Exception e) {
            log.debug("TOTP URL generation failed: {}", e.getMessage());
        }
        return null;
    }

    private byte[] getOrFetchSecret() throws IOException {
        if (this.cachedSecret != null && this.cachedSecretExpires != null
                && this.cachedSecretExpires.isAfter(Instant.now())) {
            return this.cachedSecret;
        }

        byte[] secret = requestSecret();
        if (secret != null) {
            this.cachedSecret = secret;
            this.cachedSecretExpires = Instant.now().plusSeconds(600);
        }
        return secret;
    }

    private byte[] requestSecret() throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPOTIFY_HOMEPAGE))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Spotify homepage returned status {}", response.statusCode());
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

            if (scriptUrls.isEmpty()) {
                return null;
            }

            for (String scriptUrl : scriptUrls) {
                byte[] secret = extractSecret(scriptUrl);
                if (secret != null) {
                    return secret;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting secret", e);
        }

        return null;
    }

    private byte[] extractSecret(String scriptUrl) throws IOException {
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

            Matcher matcher = SECRET_PATTERN.matcher(response.body());
            if (matcher.find()) {
                String[] parts = matcher.group(1).split(",");
                byte[] secretBytes = new byte[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    secretBytes[i] = (byte) Integer.parseInt(parts[i].trim());
                }
                return secretBytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting secret", e);
        }

        return null;
    }

    private static String generateTOTP(String secret, int period, int digits) {
        long time = System.currentTimeMillis() / 1000 / period;
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(time);
        byte[] timeBytes = buffer.array();

        try {
            SecretKeySpec keySpec = new SecretKeySpec(hexStringToByteArray(secret), "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(timeBytes);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Error generating TOTP", e);
        }
    }

    private static byte[] convertArrayToTransformedByteArray(byte[] array) {
        byte[] transformed = new byte[array.length];
        for (int i = 0; i < array.length; i++) {
            transformed[i] = (byte) (array[i] ^ ((i % 33) + 9));
        }
        return transformed;
    }

    private static String toHexString(byte[] transformed) {
        StringBuilder joinedString = new StringBuilder();
        for (byte b : transformed) {
            joinedString.append(b);
        }
        byte[] utf8Bytes = joinedString.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder hexString = new StringBuilder();
        for (byte b : utf8Bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
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
