package org.schabi.newpipe.extractor.services.youtube.streams;

import java.util.List;

/** Capabilities, rather than a hierarchy of client extractors. Pinned to Nightly 51bab8a0. */
public enum ClientProfile {
    VISIONOS(
        "VISIONOS",
        "1.02",
        101,
        false,
        false,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    ),
    WEB("WEB", "2.20260708.00.00", 1, true, true, null),
    WEB_EMBEDDED(
        "WEB_EMBEDDED_PLAYER",
        "2.20260708.00.00",
        56,
        true,
        false,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.5 Safari/605.1.15"
    ),
    WEB_SAFARI(
        "WEB",
        "2.20260708.00.00",
        1,
        true,
        true,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.5 Safari/605.1.15"
    ),
    WEB_CREATOR("WEB_CREATOR", "1.20260708.06.00", 62, true, true, null),
    TV_DOWNGRADED(
        "TVHTML5",
        "5.20260707",
        7,
        true,
        false,
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    );

    public final String clientName;
    public final String version;
    public final int id;
    public final boolean supportsCookies;
    public final boolean requiresGvs;
    public final boolean requiresJs;
    private final String fixedUserAgent;

    ClientProfile(
        final String name,
        final String version,
        final int id,
        final boolean cookies,
        final boolean gvs,
        final String userAgent
    ) {
        this.clientName = name;
        this.version = version;
        this.id = id;
        this.supportsCookies = cookies;
        this.requiresGvs = gvs;
        this.requiresJs = !"VISIONOS".equals(name);
        this.fixedUserAgent = userAgent;
    }

    public String userAgent(final YoutubeSession session) {
        return fixedUserAgent == null ? session.userAgent : fixedUserAgent;
    }

    public boolean webTokens() {
        return name().startsWith("WEB");
    }

    /** None of these pinned profiles requires or recommends a Player token. */
    public boolean requiresPlayerToken() {
        return false;
    }

    public String version(final YoutubeSession session) {
        if (this == WEB || this == WEB_SAFARI) {
            if (session.webClientVersion() != null) {
                return session.webClientVersion();
            }
        }
        return version;
    }

    public boolean requiresToken(
        final RequestPlan.Protocol protocol,
        final YoutubeSession session
    ) {
        return (
            requiresGvs
            && protocol != RequestPlan.Protocol.HLS
            && session.account != YoutubeSession.Account.PREMIUM
        );
    }

    public static List<ClientProfile> route(final YoutubeSession session, final boolean live) {
        if (live) {
            return List.of(
                WEB,
                WEB_SAFARI,
                session.account == YoutubeSession.Account.ANONYMOUS ? VISIONOS : TV_DOWNGRADED,
                WEB_EMBEDDED
            );
        }
        switch (session.account) {
            case PREMIUM:
                return List.of(WEB_CREATOR, TV_DOWNGRADED, WEB, WEB_SAFARI);
            case AUTHENTICATED:
                return List.of(WEB_EMBEDDED, TV_DOWNGRADED, WEB, WEB_SAFARI);
            default:
                return List.of(VISIONOS, WEB, WEB_EMBEDDED, WEB_SAFARI);
        }
    }

    /**
     * Playback tries authenticated HLS first, then the exempt direct-URL client. Remaining web
     * profiles retain their recovery role when that client cannot satisfy the playback demand.
     */
    public static List<ClientProfile> route(
        final YoutubeSession session,
        final boolean live,
        final boolean playback
    ) {
        if (playback && session.account == YoutubeSession.Account.AUTHENTICATED) {
            return List.of(WEB_SAFARI, VISIONOS, WEB, WEB_EMBEDDED, TV_DOWNGRADED);
        }
        return route(session, live);
    }
}
