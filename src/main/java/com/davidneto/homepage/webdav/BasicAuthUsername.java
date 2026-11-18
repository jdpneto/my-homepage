package com.davidneto.homepage.webdav;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Extracts the username from an HTTP Basic Authorization header (no password check). */
public final class BasicAuthUsername {

    private BasicAuthUsername() {
    }

    /** The username, or {@code null} if the header is missing, not Basic, or malformed. */
    public static String parse(String header) {
        if (header == null) return null;
        String prefix = "Basic ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String b64 = header.substring(prefix.length()).trim();
        if (b64.isEmpty()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String creds = new String(decoded, StandardCharsets.UTF_8);
        int colon = creds.indexOf(':');
        if (colon <= 0) return null;
        return creds.substring(0, colon);
    }
}
