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
import java.util.Base64;

public class SpotifyTokenTracker {

    private static final Logger log = LoggerFactory.getLogger(SpotifyTokenTracker.class);

    private static final String SPOTIFY_ACCOUNTS_TOKEN = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_TOKEN_URL = "https://open.spotify.com/api/token";
    private static final String SPOTIFY_SERVER_TIME = "https://open.spotify.com/api/server-time";
    private static final String NUANCE_URL = "https://gist.githubusercontent.com/saraansx/a622d4c1a12c36afdcf701201e9482a3/raw/9afe2c9c7d1a5eb3f7a05d0002a94f45b73682d0/nuance.json";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";

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

    private volatile String accessToken;
    private volatile Instant accessTokenExpires;

    private volatile String anonymousAccessToken;
    private volatile Instant anonymousExpires;

    private volatile String accountAccessToken;
    private volatile Instant accountAccessTokenExpires;

    private volatile String cachedNuanceSecret;
    private volatile int cachedNuanceVersion;
    private volatile Instant cachedNuanceExpires;

    public SpotifyTokenTracker(String clientId, String clientSecret, String spDc) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.spDc = spDc;

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
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
    }

    private void refreshAnonymousAccessToken() throws IOException {
        IOException lastException = null;

        try {
            fetchTokenWithTOTP(null);
            return;
        } catch (IOException e) {
            lastException = e;
        }

        throw new IOException("All Spotify token methods failed", lastException);
    }

    private void refreshAccountAccessToken() throws IOException {
        try {
            fetchTokenWithTOTP("sp_dc=" + this.spDc);
        } catch (IOException e) {
            try {
                String url = SPOTIFY_TOKEN_URL + "?reason=transport&productType=web-player";
                fetchAccountTokenFromUrl(url);
            } catch (IOException e2) {
                throw new IOException("Account token refresh failed", e2);
            }
        }
    }

    private void fetchTokenWithTOTP(String cookie) throws IOException {
        String[] nuance = getOrFetchNuance();
        String secret = nuance[0];
        int version = Integer.parseInt(nuance[1]);

        long serverTime = fetchServerTime();
        String totp = generateTOTP(secret, serverTime * 1000L);

        String url = SPOTIFY_TOKEN_URL
                + "?reason=transport"
                + "&productType=web-player"
                + "&totp=" + totp
                + "&totpServer=" + totp
                + "&totpVer=" + version
                + "&ts=" + System.currentTimeMillis();

        if (cookie != null) {
            fetchAccountTokenFromUrlWithCookie(url, cookie);
        } else {
            fetchTokenFromUrl(url, null);
        }
    }

    private void fetchTokenFromUrl(String url, String cookie) throws IOException {
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
                throw new IOException("Token endpoint returned " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            String token = json.path("accessToken").asText(null);
            if (token == null) {
                token = json.path("access_token").asText(null);
            }
            if (token == null) {
                throw new IOException("No access token in response");
            }

            long expiresMs = json.path("accessTokenExpirationTimestampMs").asLong(0);
            Instant expiry;
            if (expiresMs > 0) {
                expiry = Instant.ofEpochMilli(expiresMs).minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS);
                if (Instant.now().isAfter(expiry)) {
                    throw new IOException("Token already expired");
                }
            } else {
                long expiresIn = json.path("expires_in").asLong(3600);
                expiry = Instant.now().plusSeconds(Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SECONDS, 60));
            }

            this.anonymousAccessToken = token;
            this.anonymousExpires = expiry;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private void fetchAccountTokenFromUrl(String url) throws IOException {
        fetchAccountTokenFromUrlWithCookie(url, "sp_dc=" + this.spDc);
    }

    private void fetchAccountTokenFromUrlWithCookie(String url, String cookie) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("App-Platform", "WebPlayer")
                    .header("Cookie", cookie)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Account token returned " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            String token = json.path("accessToken").asText(null);
            if (token == null) {
                throw new IOException("No account token in response");
            }

            long expiresMs = json.path("accessTokenExpirationTimestampMs").asLong(0);
            this.accountAccessToken = token;
            this.accountAccessTokenExpires = expiresMs > 0
                    ? Instant.ofEpochMilli(expiresMs).minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS)
                    : Instant.now().plusSeconds(3600 - TOKEN_EXPIRY_BUFFER_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private long fetchServerTime() throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPOTIFY_SERVER_TIME))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return System.currentTimeMillis() / 1000;
            }

            JsonNode json = mapper.readTree(response.body());
            long serverTime = json.path("serverTime").asLong(0);
            if (serverTime > 0) {
                return serverTime;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        return System.currentTimeMillis() / 1000;
    }

    private String[] getOrFetchNuance() throws IOException {
        if (cachedNuanceSecret != null && cachedNuanceExpires != null && cachedNuanceExpires.isAfter(Instant.now())) {
            return new String[] { cachedNuanceSecret, String.valueOf(cachedNuanceVersion) };
        }

        String url = NUANCE_URL;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Nuance endpoint returned " + response.statusCode());
            }

            JsonNode arr = mapper.readTree(response.body());
            if (!arr.isArray() || arr.size() == 0) {
                throw new IOException("Invalid nuance format");
            }

            String bestSecret = null;
            int bestVersion = -1;
            for (JsonNode entry : arr) {
                int v = entry.path("v").asInt(0);
                if (v > bestVersion) {
                    bestVersion = v;
                    bestSecret = entry.path("s").asText(null);
                }
            }

            if (bestSecret == null) {
                throw new IOException("No valid nuance found");
            }

            cachedNuanceSecret = bestSecret;
            cachedNuanceVersion = bestVersion;
            cachedNuanceExpires = Instant.now().plusSeconds(3600);

            return new String[] { bestSecret, String.valueOf(bestVersion) };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String generateTOTP(String base32Secret, long timestampMs) {
        byte[] key = base32Decode(base32Secret);
        long epoch = timestampMs / 1000;
        long counter = epoch / 30;

        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(counter);
        byte[] counterBytes = buffer.array();

        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % 1000000;
            return String.format("%06d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    private static byte[] base32Decode(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] output = new byte[(base32.length() * 5) / 8];
        int bits = 0;
        int value = 0;
        int index = 0;

        for (int i = 0; i < base32.length(); i++) {
            int val = alphabet.indexOf(base32.charAt(i));
            if (val < 0)
                continue;
            value = (value << 5) | val;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((value >> (bits - 8)) & 255);
                bits -= 8;
            }
        }

        byte[] result = new byte[index];
        System.arraycopy(output, 0, result, 0, index);
        return result;
    }
}
