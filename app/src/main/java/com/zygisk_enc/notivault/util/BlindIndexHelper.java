package com.zygisk_enc.notivault.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class BlindIndexHelper {

    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[\\s\\p{Punct}]+");
    private static final byte[] SALT = "NotiVault_Blind_Search_Salt_v1".getBytes(StandardCharsets.UTF_8);

    public static long computeTokenHash(String token) {
        if (token == null || token.isEmpty()) return 0L;
        String cleanToken = token.trim().toLowerCase();
        if (cleanToken.isEmpty()) return 0L;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(SALT);
            byte[] hashBytes = digest.digest(cleanToken.getBytes(StandardCharsets.UTF_8));

            // Convert first 8 bytes of SHA-256 to a 64-bit long integer for ultra-fast SQLite B-Tree indexing
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (hashBytes[i] & 0xFFL);
            }
            return hash;
        } catch (Exception e) {
            return cleanToken.hashCode();
        }
    }

    public static Set<Long> extractTokenHashes(String text) {
        Set<Long> hashes = new HashSet<>();
        if (text == null || text.isEmpty()) return hashes;

        String[] words = TOKEN_SPLIT_PATTERN.split(text.toLowerCase());
        for (String word : words) {
            String cleanWord = word.trim();
            if (cleanWord.length() < 2) continue; // skip 1-letter noise

            // 1. Full word hash
            hashes.add(computeTokenHash(cleanWord));

            // 2. Prefix n-grams (3 to 10 chars) for instant prefix search (e.g. "pass", "passw" -> "password")
            int maxLen = Math.min(cleanWord.length(), 10);
            for (int len = 3; len < maxLen; len++) {
                hashes.add(computeTokenHash(cleanWord.substring(0, len)));
            }
        }
        return hashes;
    }

    public static Set<Long> extractTokenHashesForNotification(String appName, String title, String text, String bigText) {
        Set<Long> allHashes = new HashSet<>();
        if (appName != null) allHashes.addAll(extractTokenHashes(appName));
        if (title != null) allHashes.addAll(extractTokenHashes(title));
        if (text != null) allHashes.addAll(extractTokenHashes(text));
        if (bigText != null) allHashes.addAll(extractTokenHashes(bigText));
        return allHashes;
    }

    public static List<Long> extractQueryTokenHashes(String query) {
        List<Long> hashes = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return hashes;

        String[] words = TOKEN_SPLIT_PATTERN.split(query.toLowerCase().trim());
        for (String word : words) {
            String clean = word.trim();
            if (!clean.isEmpty()) {
                long h = computeTokenHash(clean);
                if (h != 0L && !hashes.contains(h)) {
                    hashes.add(h);
                }
            }
        }
        return hashes;
    }
}
