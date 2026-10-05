package org.schabi.newpipe.extractor.services.youtube.streams;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import java.io.IOException;
import java.util.Locale;
import java.net.URLDecoder;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

/** One synchronous extraction engine. No global visitor, client order or worker threads. */
public final class YoutubeStreamEngine {

    public enum Failure {
        HTTP,
        PERMISSION,
        RATE_LIMIT,
        CAPTCHA,
        MISSING_URL,
        CHALLENGE,
        TOKEN,
        DRM,
        SABR_ONLY,
    }

    public static final class Rejection {

        public final ClientProfile profile;
        public final Failure failure;
        public final int itag;

        Rejection(final ClientProfile profile, final Failure failure, final int itag) {
            this.profile = profile;
            this.failure = failure;
            this.itag = itag;
        }
    }

    public static final class Result {

        public final JsonObject player;
        public final List<StreamCandidate> candidates;
        public final List<StreamCandidate> manifests;
        public final List<Rejection> rejections;
        public final RequestPlan subtitlePlan;
        public final List<Caption> captions;

        Result(
            final JsonObject player,
            final List<StreamCandidate> candidates,
            final List<StreamCandidate> manifests,
            final List<Rejection> rejections,
            final RequestPlan subtitlePlan,
            final List<Caption> captions
        ) {
            this.player = player;
            this.candidates = List.copyOf(candidates);
            this.manifests = List.copyOf(manifests);
            this.rejections = List.copyOf(rejections);
            this.subtitlePlan = subtitlePlan;
            this.captions = List.copyOf(captions);
        }
    }

    public static final class Caption {
        public final JsonObject track;
        public final RequestPlan plan;

        Caption(final JsonObject track, final RequestPlan plan) {
            this.track = new JsonObject(track);
            this.plan = plan;
        }
    }

    public Result extract(final String videoId, final ExtractionContext context)
        throws IOException, ExtractionException {
        try {
            return extractProfiles(videoId, context);
        } finally {
            if (context.tokens != null) {
                context.tokens.finished(context);
            }
        }
    }

    private Result extractProfiles(final String videoId, final ExtractionContext context)
        throws IOException, ExtractionException {
        JsonObject primary = null;
        ClientProfile primaryProfile = null;
        final List<StreamCandidate> candidates = new ArrayList<>();
        final List<StreamCandidate> manifests = new ArrayList<>();
        final List<Rejection> rejected = new ArrayList<>();
        final Map<String, Caption> captionTracks = new LinkedHashMap<>();
        int successes = 0;
        final List<ClientProfile> profiles = profiles(context);
        IOException lastTransport = null;
        StreamHttpException lastPlayabilityCaptcha = null;
        for (final ClientProfile profile : profiles) {
            context.check();
            final long start = System.nanoTime();
            try {
                final JsonObject player = playerWithTransientRetry(videoId, profile, context);
                final String status = player.getObject("playabilityStatus").getString("status", "");
                if (!"OK".equals(status)) {
                    final String reason = player
                        .getObject("playabilityStatus")
                        .getString("reason", "")
                        .toLowerCase(Locale.ROOT);
                    final boolean captcha = reason.contains("not a bot")
                        || reason.contains("unusual traffic");
                    rejected.add(new Rejection(profile,
                        captcha ? Failure.CAPTCHA : Failure.PERMISSION, -1));
                    final String safeStatus = Set.of(
                            "ERROR",
                            "UNPLAYABLE",
                            "LIVE_STREAM_OFFLINE",
                            "LOGIN_REQUIRED",
                            "AGE_CHECK_REQUIRED",
                            "CONTENT_CHECK_REQUIRED"
                        ).contains(status)
                        ? status
                        : "UNKNOWN_PLAYABILITY";
                    context.diagnostics.event("playability", profile.name(),
                        captcha ? "PROFILE_CAPTCHA" : safeStatus, 0, 200);
                    if (captcha) {
                        // An HTTP 200 player refusal applies to this profile. It
                        // must not discard usable media from other session profiles.
                        // Actual HTTP 402/429 responses remain terminal below.
                        lastPlayabilityCaptcha = new StreamHttpException(
                            "PLAYABILITY_CAPTCHA", 402, 0);
                    }
                    continue;
                }
                if (primary == null) {
                    primary = player;
                    primaryProfile = profile;
                }
                final int candidateBefore = candidates.size();
                final int manifestBefore = manifests.size();
                final int before = candidateBefore + (context.catalog ? 0 : manifestBefore);
                final int rejectedBefore = rejected.size();
                parse(videoId, profile, player, context, candidates, manifests, rejected);
                for (final Failure failure : Failure.values()) {
                    final long count = rejected
                        .subList(rejectedBefore, rejected.size())
                        .stream()
                        .filter(r -> r.failure == failure)
                        .count();
                    if (count > 0) {
                        context.diagnostics.event(
                            "formats",
                            profile.name(),
                            failure + ":" + count,
                            0,
                            0
                        );
                    }
                }
                captions(videoId, profile, player, context);
                for (final Object raw : player.getObject("captions")
                    .getObject("playerCaptionsTracklistRenderer").getArray("captionTracks")) {
                    if (raw instanceof JsonObject) {
                        final JsonObject track = (JsonObject) raw;
                        if (!track.getString("baseUrl", "").isEmpty()) {
                            final String key = track.getString("languageCode", "") + ":"
                                + track.getString("kind", "") + ":" + track.getString("vssId", "");
                            captionTracks.putIfAbsent(key, new Caption(track, new RequestPlan(
                                context.session, profile, RequestPlan.Protocol.HTTPS,
                                RequestPlan.Range.NONE, false
                            )));
                        }
                    }
                }
                final int after = candidates.size() + (context.catalog ? 0 : manifests.size());
                if (before != after) {
                    successes++;
                    recordUsefulProfile(context, profile,
                        candidates.subList(candidateBefore, candidates.size()),
                        manifests.subList(manifestBefore, manifests.size()));
                    if (
                        context.catalog
                            ? successes >= 2 && hasFilePair(candidates)
                            : context.demand.satisfied(candidates, manifests)
                    ) {
                        break;
                    }
                }
            } catch (final IOException e) {
                lastTransport = e;
                context.check();
                context.diagnostics.event(
                    "profile",
                    profile.name(),
                    "TRANSPORT_OR_SOLVER_FAILURE",
                    (System.nanoTime() - start) / 1_000_000,
                    0
                );
                rejected.add(new Rejection(profile, Failure.HTTP, -1));
                if (e instanceof StreamHttpException && ((StreamHttpException) e).terminal()) {
                    throw e;
                }
            }
        }
        context.check();
        if (primary == null || (candidates.isEmpty() && manifests.isEmpty())) {
            if (lastPlayabilityCaptcha != null) {
                throw lastPlayabilityCaptcha;
            }
            final Set<Failure> failures = new LinkedHashSet<>();
            for (final Rejection rejection : rejected) {
                failures.add(rejection.failure);
            }
            throw new ExtractionException(
                "No requestable YouTube media: " + failures,
                lastTransport
            );
        }
        return new Result(
            primary,
            candidates,
            manifests,
            rejected,
            new RequestPlan(
                context.session,
                primaryProfile,
                RequestPlan.Protocol.HTTPS,
                RequestPlan.Range.NONE,
                false
            ),
            new ArrayList<>(captionTracks.values())
        );
    }

    private static void recordUsefulProfile(
        final ExtractionContext context,
        final ClientProfile profile,
        final List<StreamCandidate> candidates,
        final List<StreamCandidate> manifests
    ) throws IOException {
        if (context.clientOrder != null && (context.catalog ? hasFilePair(candidates)
                : context.demand.satisfied(candidates, manifests))) {
            context.clientOrder.succeeded(context, profile);
        }
    }

    private static boolean hasFilePair(final List<StreamCandidate> candidates) {
        final boolean audio = candidates.stream().anyMatch(c -> c.audioOnly || !c.videoOnly);
        final boolean video = candidates.stream().anyMatch(c -> !c.audioOnly);
        return audio && video;
    }

    public JsonObject next(final String videoId, final ExtractionContext context)
        throws IOException {
        return api(
            "next",
            request(videoId, ClientProfile.WEB, context),
            ClientProfile.WEB,
            context
        );
    }

    private static List<ClientProfile> profiles(final ExtractionContext context) {
        final List<ClientProfile> profiles = new ArrayList<>(ClientProfile.route(
            context.session, context.live, !context.catalog && context.playbackPriority
        ));
        // A playable manifest is not a downloadable file. Keep the same final
        // direct-URL fallback that playback uses when web responses are SABR-only.
        if (context.catalog && !context.live && !profiles.contains(ClientProfile.VISIONOS)) {
            profiles.add(ClientProfile.VISIONOS);
        }
        // Ordinary signed-in sessions can also use Creator. Keep it as a final
        // authenticated fallback when web clients expose only SABR and the
        // cookie-free profile is challenged; its GVS token policy still applies.
        if (context.session.account == YoutubeSession.Account.AUTHENTICATED) {
            profiles.add(ClientProfile.WEB_CREATOR);
        }
        return context.clientOrder == null ? profiles
            : context.clientOrder.order(context, profiles);
    }

    /**
     * One connection reset or timeout says nothing about the client profile. Retrying the
     * same profile once keeps a flaky first request from moving playback to a profile that
     * needs a token. Cancellation and the total deadline are checked before the retry.
     */
    private JsonObject playerWithTransientRetry(
        final String videoId,
        final ClientProfile profile,
        final ExtractionContext context
    ) throws IOException {
        try {
            return player(videoId, profile, context);
        } catch (final IOException e) {
            if (!(e instanceof SocketTimeoutException
                    || e instanceof SocketException)) {
                throw e;
            }
            context.check();
            context.diagnostics.event(
                "player", profile.name(), "TRANSIENT_RETRY", 0, 0
            );
            return player(videoId, profile, context);
        }
    }

    private JsonObject player(
        final String videoId,
        final ClientProfile profile,
        final ExtractionContext context
    ) throws IOException {
        final JsonObject body = request(videoId, profile, context);
        final PoTokenProvider.Token playerToken = profile.requiresPlayerToken() ? token(
            videoId,
            profile,
            RequestPlan.Protocol.HTTPS,
            PoTokenProvider.Purpose.PLAYER,
            context
        ) : null;
        if (playerToken != null) {
            body.put("serviceIntegrityDimensions", object("poToken", playerToken.value));
        }
        return api("player", body, profile, context);
    }

    private JsonObject request(
        final String videoId,
        final ClientProfile profile,
        final ExtractionContext context
    ) throws IOException {
        final YoutubeSession session = context.session;
        final JsonObject client = object(
            "clientName",
            profile.clientName,
            "clientVersion",
            profile.version(session),
            "hl",
            "en",
            "gl",
            "US",
            "userAgent",
            profile.userAgent(session),
            "timeZone",
            "UTC",
            "utcOffsetMinutes",
            0
        );
        if (session.visitorData != null) {
            client.put("visitorData", session.visitorData);
        }
        if (profile == ClientProfile.VISIONOS) {
            client.put("deviceMake", "Apple");
            client.put("deviceModel", "RealityDevice17,1");
            client.put("osName", "visionOS");
            client.put("osVersion", "26.5.23O471");
        }
        final JsonObject inner = object("client", client);
        if (profile == ClientProfile.WEB_EMBEDDED) {
            inner.put("thirdParty", object("embedUrl", "https://www.youtube.com/"));
        }
        if (profile.supportsCookies && session.account != YoutubeSession.Account.ANONYMOUS) {
            final JsonObject user = object("lockedSafetyMode", false);
            if (session.delegatedId != null) {
                user.put("onBehalfOfUser", session.delegatedId);
            }
            inner.put("user", user);
        }
        final int timestamp = !profile.requiresJs || session.playerUrl == null
            ? 0
            : context.solver.signatureTimestamp(session.playerUrl, context);
        return object(
            "context",
            inner,
            "videoId",
            videoId,
            "contentCheckOk",
            true,
            "racyCheckOk",
            true,
            "playbackContext",
            object(
                "contentPlaybackContext",
                object("html5Preference", "HTML5_PREF_WANTS", "signatureTimestamp", timestamp)
            )
        );
    }

    private JsonObject api(
        final String endpoint,
        final JsonObject body,
        final ClientProfile profile,
        final ExtractionContext context
    ) throws IOException {
        context.check();
        final long start = System.nanoTime();
        final Response response;
        try {
            response = context.post(
                "https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false",
                InnertubeAuth.headers(
                    context.session,
                    profile,
                    "https://www.youtube.com",
                    System.currentTimeMillis() / 1000
                ),
                JsonWriter.string(body).getBytes(StandardCharsets.UTF_8)
            );
        } catch (final org.schabi.newpipe.extractor.exceptions.ReCaptchaException e) {
            throw new StreamHttpException("INNERTUBE", 402, 0);
        }
        context.check();
        context.diagnostics.event(
            endpoint,
            profile.name(),
            "response",
            (System.nanoTime() - start) / 1_000_000,
            response.responseCode()
        );
        if (response.responseCode() != 200) {
            throw new StreamHttpException(
                "INNERTUBE",
                response.responseCode(),
                retryAfter(response)
            );
        }
        try {
            return JsonParser.object().from(response.responseBody());
        } catch (final JsonParserException e) {
            throw new IOException("INNERTUBE_JSON", e);
        }
    }

    private void parse(
        final String videoId,
        final ClientProfile profile,
        final JsonObject player,
        final ExtractionContext context,
        final List<StreamCandidate> candidates,
        final List<StreamCandidate> manifests,
        final List<Rejection> rejected
    ) throws IOException {
        final JsonObject streaming = player.getObject("streamingData");
        final String playerUrl = absolute(
            player.getObject("assets").getString("js", context.session.playerUrl)
        );
        // Playback's exempt HLS supplies its own audio renditions. Catalogs and
        // explicit recovery still materialize the full file response.
        if (!context.catalog && context.playbackPriority
            && !profile.requiresToken(RequestPlan.Protocol.HLS, context.session)
            && !streaming.getString("hlsManifestUrl", "").isEmpty()) {
            final Set<String> manifestS = new LinkedHashSet<>();
            final Set<String> manifestN = new LinkedHashSet<>();
            collectChallenges(streaming.getString("hlsManifestUrl"), manifestS, manifestN);
            ChallengeSolver.Solutions manifestSolved =
                new ChallengeSolver.Solutions(Map.of(), Map.of());
            if ((!manifestS.isEmpty() || !manifestN.isEmpty()) && playerUrl != null) {
                try {
                    manifestSolved = context.solver.solve(
                        playerUrl, manifestS, manifestN, context
                    );
                } catch (final IOException failure) {
                    context.check();
                }
            }
            final int before = manifests.size();
            manifest(videoId, profile, player, context, manifestSolved,
                RequestPlan.Protocol.HLS, manifests, rejected);
            if (manifests.size() > before) {
                context.diagnostics.event("formats", profile.name(), "PLAYBACK_HLS", 0, 0);
                // Named HLS renditions supply the audio menu. Parsing adaptive
                // audio here would put file challenges and GVS minting back on
                // the first-frame path, although none of those URLs is played.
                return;
            }
        }
        final List<JsonObject> formats = new ArrayList<>();
        for (final String key : List.of("formats", "adaptiveFormats")) {
            for (final Object raw : streaming.getArray(key, new JsonArray())) {
                if (raw instanceof JsonObject) {
                    final JsonObject format = new JsonObject((JsonObject) raw);
                    format.put("muxed", key.equals("formats"));
                    formats.add(format);
                }
            }
        }
        final Set<String> sigs = new LinkedHashSet<>();
        final Set<String> ns = new LinkedHashSet<>();
        for (final JsonObject format : formats) {
            final Map<String, String> cipher = query(
                format.getString("signatureCipher", format.getString("cipher", ""))
            );
            if (cipher.containsKey("s")) {
                sigs.add(cipher.get("s"));
            }
            collectChallenges(format.getString("url", cipher.get("url")), sigs, ns);
        }
        for (final String key : List.of("hlsManifestUrl", "dashManifestUrl")) {
            collectChallenges(streaming.getString(key), sigs, ns);
        }
        ChallengeSolver.Solutions solved = new ChallengeSolver.Solutions(Map.of(), Map.of());
        if (!sigs.isEmpty() || !ns.isEmpty()) {
            if (playerUrl != null) {
                try {
                    solved = context.solver.solve(playerUrl, sigs, ns, context);
                } catch (final IOException e) {
                    context.check();
                    rejected.add(new Rejection(profile, Failure.CHALLENGE, -1));
                }
            }
        }
        final String cpn = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        PoTokenProvider.Token gvs = null;
        boolean gvsAttempted = false;
        if (
            formats
                .stream()
                .noneMatch(
                    f ->
                        !f.getString("url", "").isEmpty()
                        || !query(f.getString("signatureCipher", f.getString("cipher", "")))
                            .getOrDefault("url", "")
                            .isEmpty()
                )
            && streaming.has("serverAbrStreamingUrl")
        ) {
            rejected.add(new Rejection(profile, Failure.SABR_ONLY, -1));
        }
        for (final JsonObject format : formats) {
            final int itag = format.getInt("itag", -1);
            if (
                !format.getArray("drmFamilies").isEmpty()
                || !format.getString("drmTrackType", "").isEmpty()
            ) {
                rejected.add(new Rejection(profile, Failure.DRM, itag));
                continue;
            }
            final Map<String, String> cipher = query(
                format.getString("signatureCipher", format.getString("cipher", ""))
            );
            String url = format.getString("url", cipher.get("url"));
            if (url == null || url.isEmpty()) {
                rejected.add(new Rejection(profile, Failure.MISSING_URL, itag));
                continue;
            }
            if (cipher.containsKey("s")) {
                final String signature = solved.signatures.get(cipher.get("s"));
                if (signature == null) {
                    rejected.add(new Rejection(profile, Failure.CHALLENGE, itag));
                    continue;
                }
                url = parameter(url, cipher.getOrDefault("sp", "signature"), signature);
            }
            url = transform(url, solved);
            if (url == null) {
                rejected.add(new Rejection(profile, Failure.CHALLENGE, itag));
                continue;
            }
            if (!gvsAttempted) {
                gvs = token(videoId, profile, RequestPlan.Protocol.HTTPS,
                    PoTokenProvider.Purpose.GVS, context);
                gvsAttempted = true;
            }
            if (profile.requiresToken(RequestPlan.Protocol.HTTPS, context.session) && gvs == null) {
                rejected.add(new Rejection(profile, Failure.TOKEN, itag));
                continue;
            }
            url = parameter(url, "cpn", cpn);
            if (gvs != null) {
                url = parameter(url, "pot", gvs.value);
            }
            final String mime = format.getString("mimeType", "");
            final FormatKey key = new FormatKey(
                itag,
                format.getObject("audioTrack").getString("id", ""),
                codec(mime),
                format.getBoolean("isDrc"),
                format.getString("qualityLabel", "").contains("HDR")
                    || Set.of(
                        "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084",
                        "COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67"
                    ).contains(
                        format.getObject("colorInfo").getString("transferCharacteristics", "")
                    ),
                RequestPlan.Protocol.HTTPS
            );
            candidates.add(candidate(profile, playerUrl, context, format, url, key, gvs));
        }
        if (formats.isEmpty() && !player.getArray("licenseInfos").isEmpty()) {
            rejected.add(new Rejection(profile, Failure.DRM, -1));
        }
        manifests(videoId, profile, player, context, solved, manifests, rejected);
    }

    private void manifests(
        final String videoId,
        final ClientProfile profile,
        final JsonObject player,
        final ExtractionContext context,
        final ChallengeSolver.Solutions solved,
        final List<StreamCandidate> manifests,
        final List<Rejection> rejected
    ) throws IOException {
        for (final RequestPlan.Protocol protocol : List.of(
            RequestPlan.Protocol.DASH,
            RequestPlan.Protocol.HLS
        )) {
            manifest(videoId, profile, player, context, solved, protocol, manifests, rejected);
        }
    }

    private void manifest(
        final String videoId,
        final ClientProfile profile,
        final JsonObject player,
        final ExtractionContext context,
        final ChallengeSolver.Solutions solved,
        final RequestPlan.Protocol protocol,
        final List<StreamCandidate> manifests,
        final List<Rejection> rejected
    ) throws IOException {
        final JsonObject streaming = player.getObject("streamingData");
        final String playerUrl = absolute(
            player.getObject("assets").getString("js", context.session.playerUrl)
        );
        String url = streaming.getString(
            protocol == RequestPlan.Protocol.DASH ? "dashManifestUrl" : "hlsManifestUrl"
        );
        if (url == null || url.isEmpty()) {
            return;
        }
        url = transform(url, solved);
        if (url == null) {
            rejected.add(new Rejection(profile, Failure.CHALLENGE, -1));
            return;
        }
        final boolean optionalPlaybackToken = !context.catalog && context.playbackPriority
            && !profile.requiresToken(protocol, context.session);
        final PoTokenProvider.Token manifestToken = optionalPlaybackToken ? null : token(
            videoId, profile, protocol, PoTokenProvider.Purpose.GVS, context
        );
        if (profile.requiresToken(protocol, context.session) && manifestToken == null) {
            rejected.add(new Rejection(profile, Failure.TOKEN, -1));
            return;
        }
        if (manifestToken != null) {
            url = manifestToken(url, protocol, manifestToken.value);
        }
        manifests.add(
            new StreamCandidate(
                new FormatKey(-1, "", "", false, false, protocol),
                url,
                playerUrl,
                expiry(url, manifestToken),
                "",
                new RequestPlan(
                    context.session,
                    profile,
                    protocol,
                    RequestPlan.Range.NONE,
                    false,
                    -1,
                    expiry(url, manifestToken)
                ),
                new JsonObject(),
                false,
                false
            )
        );
    }

    private StreamCandidate candidate(
        final ClientProfile profile,
        final String playerUrl,
        final ExtractionContext context,
        final JsonObject format,
        final String url,
        final FormatKey key,
        final PoTokenProvider.Token gvs
    ) {
        final Map<String, String> parameters = query(url);
        final String length = format.getString(
            "contentLength",
            parameters.getOrDefault("clen", "-1")
        );
        final String identity = parameters.getOrDefault("id", "").isEmpty()
            ? ""
            : parameters.get("id")
              + ":"
              + key.itag
              + ":"
              + key.audioTrack
              + ":"
              + key.codec
              + ":"
              + key.drc
              + ":"
              + key.hdr
              + ":"
              + format.getString("lastModified", "")
              + ":"
              + length;
        final boolean audio = format.getString("mimeType", "").startsWith("audio/");
        final boolean videoOnly = !audio && !format.getBoolean("muxed");
        return new StreamCandidate(
            key,
            url,
            playerUrl,
            expiry(url, gvs),
            identity,
            new RequestPlan(
                context.session,
                profile,
                RequestPlan.Protocol.HTTPS,
                videoOnly || audio ? RequestPlan.Range.QUERY : RequestPlan.Range.HEADER,
                true,
                number(length),
                expiry(url, gvs)
            ),
            format,
            audio,
            videoOnly
        );
    }

    private void captions(
        final String videoId,
        final ClientProfile profile,
        final JsonObject player,
        final ExtractionContext context
    ) throws IOException {
        final JsonArray tracks = player
            .getObject("captions")
            .getObject("playerCaptionsTracklistRenderer")
            .getArray("captionTracks");
        PoTokenProvider.Token subs = null;
        boolean requested = false;
        for (final Object raw : tracks) {
            if (!(raw instanceof JsonObject)) {
                continue;
            }
            final JsonObject track = (JsonObject) raw;
            final String url = track.getString("baseUrl", "");
            final Map<String, String> params = query(url);
            if (params.containsKey("xpe") || params.containsKey("xpv")) {
                if (!requested) {
                    subs = token(
                        videoId,
                        profile,
                        RequestPlan.Protocol.HTTPS,
                        PoTokenProvider.Purpose.SUBS,
                        context
                    );
                    requested = true;
                }
                if (subs == null) {
                    track.put("baseUrl", "");
                    context.diagnostics.event("subtitles", profile.name(), "TOKEN_MISSING", 0, 0);
                    continue;
                }
                track.put(
                    "baseUrl",
                    parameter(
                        parameter(parameter(url, "pot", subs.value), "potc", "1"),
                        "c",
                        profile.clientName
                    )
                );
            }
        }
    }

    private static long number(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private PoTokenProvider.Token token(
        final String videoId,
        final ClientProfile profile,
        final RequestPlan.Protocol protocol,
        final PoTokenProvider.Purpose purpose,
        final ExtractionContext context
    ) throws IOException {
        if (context.tokens == null || !profile.webTokens()
            || purpose == PoTokenProvider.Purpose.GVS && !profile.requiresGvs) {
            return null;
        }
        final YoutubeSession session = context.session;
        final boolean video = purpose != PoTokenProvider.Purpose.GVS || session.videoBoundGvs();
        final PoTokenProvider.Binding binding = video
            ? PoTokenProvider.Binding.VIDEO
            : session.account == YoutubeSession.Account.ANONYMOUS
                ? PoTokenProvider.Binding.VISITOR
                : PoTokenProvider.Binding.DATA_SYNC;
        final String identifier = video
            ? videoId
            : binding == PoTokenProvider.Binding.VISITOR
                ? session.visitorData
                : session.dataSyncId;
        if (identifier == null || identifier.isEmpty()) {
            context.diagnostics.event("token", profile.name(), "MISSING_BINDING:" + binding, 0, 0);
            return null;
        }
        try {
            final PoTokenProvider.Token result = context.tokens.get(
                new PoTokenProvider.TokenRequest(
                    purpose,
                    profile,
                    protocol,
                    binding,
                    identifier,
                    session.scope()
                ),
                context
            );
            return result != null && result.expiresAtMillis > System.currentTimeMillis() + 1000
                ? result
                : null;
        } catch (final IOException e) {
            context.check();
            if (e instanceof StreamHttpException && ((StreamHttpException) e).terminal()) {
                throw e;
            }
            context.diagnostics.event("token", profile.name(), "MINT_FAILED", 0, 0);
            return null;
        }
    }

    private static JsonObject object(final Object... entries) {
        final JsonObject result = new JsonObject();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    public static String codec(final String mime) {
        final Matcher matcher = Pattern.compile("codecs=\"([^\"]+)\"").matcher(mime);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static Map<String, String> query(final String input) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        final String text = input.contains("?") ? input.substring(input.indexOf('?') + 1) : input;
        for (final String part : text.split("&")) {
            final String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                result.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                );
            }
        }
        return result;
    }

    public static String parameter(final String url, final String name, final String value) {
        final int question = url.indexOf('?');
        final String base = question < 0 ? url : url.substring(0, question);
        final Map<String, String> params = question < 0 ? new LinkedHashMap<>() : query(url);
        params.put(name, value);
        final StringBuilder output = new StringBuilder(base).append('?');
        for (final Map.Entry<String, String> param : params.entrySet()) {
            if (output.charAt(output.length() - 1) != '?') {
                output.append('&');
            }
            output
                .append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        return output.toString();
    }

    /** YouTube manifests carry the GVS token in the path, before the HLS file suffix. */
    private static String manifestToken(
        final String url,
        final RequestPlan.Protocol protocol,
        final String token
    ) {
        final URI parsed = URI.create(url);
        String path = parsed.getRawPath();
        String suffix = "";
        if (protocol == RequestPlan.Protocol.HLS) {
            final Matcher match = Pattern.compile("/(?:file|playlist)/index\\.m3u8$").matcher(path);
            if (match.find()) {
                suffix = path.substring(match.start());
                path = path.substring(0, match.start());
            }
        }
        path =
            path.replaceAll("/+$", "")
            + "/pot/"
            + URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20")
            + suffix;
        return (
            parsed.getScheme()
            + "://"
            + parsed.getRawAuthority()
            + path
            + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery())
            + (parsed.getRawFragment() == null ? "" : "#" + parsed.getRawFragment())
        );
    }

    private static void collectChallenges(
        final String url,
        final Set<String> sigs,
        final Set<String> ns
    ) {
        if (url == null) {
            return;
        }
        final String n = query(url).get("n");
        if (n != null) {
            ns.add(n);
        }
        final Matcher matcher = Pattern.compile("/(s|n)/([^/?#]+)").matcher(url);
        while (matcher.find()) {
            (matcher.group(1).equals("s") ? sigs : ns).add(matcher.group(2));
        }
    }

    private static String transform(final String input, final ChallengeSolver.Solutions solved) {
        String url = input;
        final String n = query(url).get("n");
        if (n != null) {
            if (!solved.throttles.containsKey(n)) {
                return null;
            }
            url = parameter(url, "n", solved.throttles.get(n));
        }
        final Matcher matcher = Pattern.compile("/(s|n)/([^/?#]+)").matcher(url);
        final StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            final String value = (matcher.group(1).equals("s")
                    ? solved.signatures
                    : solved.throttles).get(matcher.group(2));
            if (value == null) {
                return null;
            }
            matcher.appendReplacement(
                output,
                Matcher.quoteReplacement(
                    "/" + (matcher.group(1).equals("s") ? "signature" : "n") + "/" + value
                )
            );
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String absolute(final String player) {
        if (player == null) {
            return null;
        }
        return player.startsWith("/") ? "https://www.youtube.com" + player : player;
    }

    private static long expiry(final String url, final PoTokenProvider.Token token) {
        long expires = Long.MAX_VALUE;
        try {
            final Matcher pathExpiry = Pattern.compile("/expire/(\\d+)(?:/|$)").matcher(url);
            expires =
                Long.parseLong(
                    query(url).getOrDefault("expire", pathExpiry.find() ? pathExpiry.group(1) : "0")
                )
                * 1000;
        } catch (final NumberFormatException ignored) { }
        if (expires == 0) {
            expires = System.currentTimeMillis() + 120_000;
        }
        return token == null ? expires : Math.min(expires, token.expiresAtMillis);
    }

    private static long retryAfter(final Response response) {
        for (final Map.Entry<String, List<String>> header : response.responseHeaders().entrySet()) {
            if ("retry-after".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                return StreamHttpException.parseRetryAfter(
                    header.getValue().get(0),
                    System.currentTimeMillis()
                );
            }
        }
        return 0;
    }
}
