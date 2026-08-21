package com.slugyzeon.plugin.youtube;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JioSaavnHelper {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DES_KEY = "38346591";

    public static class JioSaavnTrack {
        public String mediaUrl;
        public long length;
    }

    public static JioSaavnTrack searchAndGetTrack(HttpClient client, String title, String author) {
        try {
            String query = (title + " " + author).replaceAll("(?i)\\b(official|video|audio|lyric|lyrics|music)\\b", "").trim();
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&cc=in&ctx=web6dot0&includeMetaTags=1&q=" + encodedQuery))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;

            JsonNode results = mapper.readTree(res.body()).path("results");
            if (results.isMissingNode() || !results.isArray() || results.isEmpty()) return null;

            JsonNode bestTrack = null;
            int bestScore = -1;

            for (JsonNode trackNode : results) {
                String encryptedUrl = trackNode.path("more_info").path("encrypted_media_url").asText("");
                if (encryptedUrl.isEmpty()) continue;

                String trackTitle = trackNode.path("title").asText("").toLowerCase();
                String trackArtists = trackNode.path("subtitle").asText("").toLowerCase() + " " + 
                                      trackNode.path("more_info").path("music").asText("").toLowerCase();

                String tTitle = title.toLowerCase();
                String tAuthor = author.toLowerCase();

                int score = 0;
                if (trackTitle.contains(tTitle) || tTitle.contains(trackTitle)) score += 10;
                if (trackArtists.contains(tAuthor) || tAuthor.contains(trackArtists)) score += 5;
                if (trackTitle.contains("karaoke") && !tTitle.contains("karaoke")) score -= 10;
                if (trackTitle.contains("cover") && !tTitle.contains("cover")) score -= 5;

                if (score > bestScore) {
                    bestScore = score;
                    bestTrack = trackNode;
                }
            }

            if (bestTrack == null || bestScore == 0) {
                return null;
            }

            String encryptedUrl = bestTrack.path("more_info").path("encrypted_media_url").asText("");
            JioSaavnTrack track = new JioSaavnTrack();
            track.mediaUrl = decryptMediaUrl(encryptedUrl);
            track.length = bestTrack.path("more_info").path("duration").asLong(0) * 1000L;

            if (track.mediaUrl != null) {
                track.mediaUrl = track.mediaUrl.replace("_96.mp4", "_320.mp4");
            }

            return track;
        } catch (Exception e) {
            return null;
        }
    }

    private static String decryptMediaUrl(String encryptedUrl) {
        try {
            SecretKey secretKey = SecretKeyFactory.getInstance("DES")
                .generateSecret(new DESKeySpec(DES_KEY.getBytes(StandardCharsets.UTF_8)));

            Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedUrl)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}