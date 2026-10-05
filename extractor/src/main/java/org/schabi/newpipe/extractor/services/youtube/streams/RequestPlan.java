package org.schabi.newpipe.extractor.services.youtube.streams;

import java.net.URI;
import java.util.Map;

/** Non-serializable host request context. API authorization is never media authorization. */
public final class RequestPlan {

    public enum Protocol {
        HTTPS,
        DASH,
        HLS,
    }

    public enum Range {
        HEADER,
        QUERY,
        NONE,
    }

    public final String method;
    public final Map<String, String> headers;
    public final String userAgent;
    public final Range range;
    public final long chunkLimit;
    public final boolean allowPostFallback;
    public final YoutubeSession session;
    public final ClientProfile profile;
    public final Protocol protocol;
    public final long resourceLength;
    public final long expiresAtMillis;

    public RequestPlan(
        final YoutubeSession session,
        final ClientProfile profile,
        final Protocol protocol,
        final Range range,
        final boolean allowPostFallback
    ) {
        this(session, profile, protocol, range, allowPostFallback, -1);
    }

    public RequestPlan(
        final YoutubeSession session,
        final ClientProfile profile,
        final Protocol protocol,
        final Range range,
        final boolean allowPostFallback,
        final long resourceLength
    ) {
        this(session, profile, protocol, range, allowPostFallback, resourceLength, Long.MAX_VALUE);
    }

    public RequestPlan(
        final YoutubeSession session,
        final ClientProfile profile,
        final Protocol protocol,
        final Range range,
        final boolean allowPostFallback,
        final long resourceLength,
        final long expiresAtMillis
    ) {
        this.method = "GET";
        this.session = session;
        this.profile = profile;
        this.protocol = protocol;
        this.userAgent = profile.userAgent(session);
        this.headers = Map.of(
            "Origin",
            "https://www.youtube.com",
            "Referer",
            "https://www.youtube.com/"
        );
        this.range = range;
        this.chunkLimit = 10L * 1024 * 1024;
        this.allowPostFallback = allowPostFallback;
        this.resourceLength = resourceLength;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String cookies(final String url) {
        final URI uri = URI.create(url);
        final String host = uri.getHost();
        // Never disclose account cookies to arbitrary manifests or googlevideo.
        if (
            profile.supportsCookies
            && "https".equals(uri.getScheme())
            && host != null
            && (host.equals("youtube.com") || host.endsWith(".youtube.com"))
        ) {
            return session.cookies("https://" + host);
        }
        return "";
    }
}
