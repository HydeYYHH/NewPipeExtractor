package org.schabi.newpipe.extractor.services.youtube.streams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InnertubeAuth {

    private InnertubeAuth() { }

    public static Map<String, List<String>> headers(
        final YoutubeSession session,
        final ClientProfile profile,
        final String origin,
        final long timestampSeconds
    ) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("User-Agent", List.of(profile.userAgent(session)));
        headers.put("Origin", List.of(origin));
        headers.put("Referer", List.of(origin + "/"));
        headers.put("Content-Type", List.of("application/json"));
        headers.put("X-Youtube-Client-Name", List.of(String.valueOf(profile.id)));
        headers.put("X-Youtube-Client-Version", List.of(profile.version(session)));
        if (session.visitorData != null && !session.visitorData.isEmpty()) {
            headers.put("X-Goog-Visitor-Id", List.of(session.visitorData));
        }
        if (!profile.supportsCookies || !origin.equals("https://www.youtube.com")) {
            return headers;
        }
        final String cookie = session.cookies(origin);
        if (cookie == null || cookie.isEmpty()) {
            return headers;
        }
        headers.put("Cookie", List.of(cookie));
        if (session.account == YoutubeSession.Account.ANONYMOUS) {
            return headers;
        }
        headers.put("X-Youtube-Bootstrap-Logged-In", List.of("true"));
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String part : cookie.split(";")) {
            final int equal = part.indexOf('=');
            if (equal > 0) {
                values.put(part.substring(0, equal).trim(), part.substring(equal + 1));
            }
        }
        final String[] schemes = {"SAPISIDHASH", "SAPISID1PHASH", "SAPISID3PHASH"};
        final String[] sids = {
            values.getOrDefault("SAPISID", values.get("__Secure-3PAPISID")),
            values.get("__Secure-1PAPISID"),
            values.get("__Secure-3PAPISID"),
        };
        final StringBuilder authorization = new StringBuilder();
        for (int i = 0; i < schemes.length; i++) {
            if (sids[i] == null) {
                continue;
            }
            final boolean user = session.userSessionId != null && !session.userSessionId.isEmpty();
            final String input =
                (user ? session.userSessionId + " " : "")
                + timestampSeconds
                + " "
                + sids[i]
                + " "
                + origin;
            if (authorization.length() > 0) {
                authorization.append(' ');
            }
            authorization
                .append(schemes[i])
                .append(' ')
                .append(timestampSeconds)
                .append('_')
                .append(sha1(input))
                .append(user ? "_u" : "");
        }
        if (authorization.length() > 0) {
            headers.put("Authorization", List.of(authorization.toString()));
            headers.put("X-Origin", List.of(origin));
            headers.put("X-Goog-AuthUser", List.of(String.valueOf(session.accountIndex)));
            if (session.delegatedId != null && !session.delegatedId.isEmpty()) {
                headers.put("X-Goog-PageId", List.of(session.delegatedId));
            }
        }
        return headers;
    }

    private static String sha1(final String input) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                input.getBytes(StandardCharsets.UTF_8)
            );
            final StringBuilder result = new StringBuilder();
            for (final byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
