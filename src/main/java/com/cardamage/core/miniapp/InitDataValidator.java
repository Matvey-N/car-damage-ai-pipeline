package com.cardamage.core.miniapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a Mini App request really comes from Telegram (Bot API,
 * "Validating data received via the Mini App"):
 *
 *   data_check_string = all fields except "hash", as key=value, sorted by key, joined by '\n'
 *   secret_key        = HMAC_SHA256(key = "WebAppData", data = bot token)
 *   valid             = hex(HMAC_SHA256(key = secret_key, data = data_check_string)) == hash
 *
 * plus a maximum age of auth_date, so an intercepted initData cannot be reused forever.
 * Without this check anyone who knows the public tunnel address could spend the API key.
 */
public class InitDataValidator {

    private static final Pattern USER_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final byte[] secretKey;
    private final long maxAgeSeconds;

    public InitDataValidator(String botToken, long maxAgeSeconds) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("bot token is required");
        }
        this.secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.trim());
        this.maxAgeSeconds = maxAgeSeconds;
    }

    /** @return the Telegram user id from the validated data */
    public long validate(String initData, long nowEpochSeconds) {
        if (initData == null || initData.isBlank()) {
            throw new InvalidInitDataException("open the app from the Telegram bot (initData is missing)");
        }
        Map<String, String> fields = new TreeMap<>();
        String hash = null;
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (key.equals("hash")) {
                hash = value;
            } else {
                fields.put(key, value);
            }
        }
        if (hash == null) {
            throw new InvalidInitDataException("initData has no hash");
        }
        StringBuilder check = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (check.length() > 0) {
                check.append('\n');
            }
            check.append(e.getKey()).append('=').append(e.getValue());
        }
        byte[] expected = hmac(secretKey, check.toString());
        byte[] given;
        try {
            given = HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException e) {
            throw new InvalidInitDataException("initData hash is not hex");
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw new InvalidInitDataException("initData signature is not valid");
        }
        long authDate;
        try {
            authDate = Long.parseLong(fields.getOrDefault("auth_date", ""));
        } catch (NumberFormatException e) {
            throw new InvalidInitDataException("initData has no auth_date");
        }
        if (nowEpochSeconds - authDate > maxAgeSeconds) {
            throw new InvalidInitDataException("initData is too old, reopen the app");
        }
        Matcher m = USER_ID.matcher(fields.getOrDefault("user", ""));
        if (!m.find()) {
            throw new InvalidInitDataException("initData has no user");
        }
        return Long.parseLong(m.group(1));
    }

    /** Signs fields the way Telegram does; used by tests and for local development. */
    public String sign(Map<String, String> fields) {
        StringBuilder check = new StringBuilder();
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
            if (check.length() > 0) {
                check.append('\n');
                query.append('&');
            }
            check.append(e.getKey()).append('=').append(e.getValue());
            query.append(e.getKey()).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return query + "&hash=" + HexFormat.of().formatHex(hmac(secretKey, check.toString()));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static class InvalidInitDataException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidInitDataException(String message) {
            super(message);
        }
    }
}
