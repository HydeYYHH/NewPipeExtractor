package org.schabi.newpipe.extractor.services.youtube.streams;

import static org.junit.jupiter.api.Assertions.*;

import com.grack.nanojson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

class YoutubeStreamEngineTest {

    @Test
    void successfulProfileChangesTheNextProductionRouteAndSurvivesContextCopies() throws Exception {
        final List<String> requested = new ArrayList<>();
        final ClientProfile[] preferred = {null};
        final ClientOrderPolicy policy = new ClientOrderPolicy() {
            @Override
            public List<ClientProfile> order(final ExtractionContext ctx, final List<ClientProfile> defaults) {
                if (preferred[0] == null) return defaults;
                final List<ClientProfile> ordered = new ArrayList<>(defaults);
                ordered.remove(preferred[0]);
                ordered.add(0, preferred[0]);
                return ordered;
            }

            @Override
            public void succeeded(final ExtractionContext ctx, final ClientProfile profile) {
                preferred[0] = profile;
            }
        };
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                final boolean vision = new String(request.dataToSend(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("VISIONOS");
                requested.add(vision ? "VISIONOS" : "WEB");
                final String data = vision
                    ? "{\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls/index.m3u8\"}"
                    : "{\"serverAbrStreamingUrl\":\"https://rr.googlevideo.com/sabr\"}";
                return new Response(200, "OK", Map.of(),
                    "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":" + data + "}", request.url());
            }
        };
        final ExtractionContext ctx = playback(transport, null, null).withClientOrder(policy)
            .withTimeLimit(10_000).withPlaybackPriority(true)
            .withDemand(new StreamDemand(0, null, false, Set.of()));
        assertSame(policy, ctx.clientOrder);
        final YoutubeStreamEngine engine = new YoutubeStreamEngine();
        engine.extract("aaaaaaaaaaa", ctx);
        engine.extract("bbbbbbbbbbb", ctx);
        assertEquals(List.of("WEB", "VISIONOS", "VISIONOS"), requested);
        assertEquals(ClientProfile.VISIONOS, preferred[0]);
    }

    private YoutubeSession session(final YoutubeSession.Account account) {
        return new YoutubeSession(
            "session",
            account,
            2,
            "channel",
            "user",
            "channel||user",
            "visitor",
            "browser-UA",
            7,
            null,
            "test",
            new JsonObject(),
            origin -> account == YoutubeSession.Account.ANONYMOUS ? ""
                : "SAPISID=sid; __Secure-1PAPISID=one; __Secure-3PAPISID=three"
        );
    }

    private ExtractionContext context(
        final YoutubeSession session,
        final Downloader downloader,
        final PoTokenProvider tokens
    ) {
        return new ExtractionContext(
            session,
            downloader,
            (url, sigs, ns, ctx) -> new ChallengeSolver.Solutions(Map.of(), Map.of()),
            tokens,
            false,
            true,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(45),
            () -> true,
            (stage, profile, detail, duration, status) -> { }
        );
    }

    private static final String MEDIA =
        "{\"playabilityStatus\":{\"status\":\"OK\"},"
        + "\"streamingData\":{\"adaptiveFormats\":[{\"itag\":999,"
        + "\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"contentLength\":\"100\","
        + "\"url\":\"https://rr.googlevideo.com/videoplayback?id=object&expire=9999999999\"}]}}";

    private ExtractionContext playback(final Downloader transport, final ChallengeSolver solver,
                                      final PoTokenProvider tokens) {
        return new ExtractionContext(session(YoutubeSession.Account.AUTHENTICATED), transport,
            solver, tokens, false, false,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(45), () -> true,
            (stage, profile, detail, duration, status) -> { });
    }

    private static final String HLS_WITH_FILES =
        "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
        + "\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls/index.m3u8\","
        + "\"formats\":[{\"itag\":18,\"height\":360,\"mimeType\":\"video/mp4\","
        + "\"url\":\"https://rr.googlevideo.com/videoplayback?n=file-challenge\"}]}}";

    private static final String FILE_PAIR = MEDIA.replace("\"adaptiveFormats\":[",
        "\"adaptiveFormats\":[{\"itag\":137,\"height\":1080,\"mimeType\":\"video/mp4; "
            + "codecs=\\\"avc1.640028\\\"\",\"url\":\"https://rr.googlevideo.com/videoplayback?id=v\"},");

    @Test
    void catalogContinuesPastManifestsAndRetainsCaptionRequestContexts() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                final boolean vision = new String(request.dataToSend(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("VISIONOS");
                final String lang = requests.size() == 1 ? "en" : "fr";
                final String json = (vision ? FILE_PAIR : HLS_WITH_FILES.replace(
                    ",\"formats\":[{\"itag\":18,\"height\":360,\"mimeType\":\"video/mp4\","
                        + "\"url\":\"https://rr.googlevideo.com/videoplayback?n=file-challenge\"}]", ""));
                final String caption = ",\"captions\":{\"playerCaptionsTracklistRenderer\":{" 
                    + "\"captionTracks\":[{\"languageCode\":\"" + lang + "\","
                    + "\"baseUrl\":\"https://www.youtube.com/api/timedtext?lang=" + lang + "\"}]}}}";
                return new Response(200, "OK", Map.of(), json.substring(0, json.length() - 1)
                    + caption, request.url());
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", context(session(YoutubeSession.Account.AUTHENTICATED), transport, null));
        assertEquals(6, requests.size());
        assertFalse(requests.get(4).headers().containsKey("Authorization"));
        assertTrue(result.candidates.stream().allMatch(c -> c.requestPlan.profile
            == ClientProfile.VISIONOS));
        assertEquals(2, result.captions.size());
        assertEquals(ClientProfile.WEB_EMBEDDED, result.captions.get(0).plan.profile);
        assertEquals(ClientProfile.TV_DOWNGRADED, result.captions.get(1).plan.profile);
    }

    @Test
    void playbackHlsSkipsUnselectedFileChallengesAndOptionalTokens() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(200, "OK", Map.of(), HLS_WITH_FILES, request.url());
            }
        };
        final PoTokenProvider tokens = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                fail("Exempt playback HLS must not initialize BotGuard");
                return null;
            }
            @Override
            public void invalidate(final String scope) { }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", playback(transport, (url, s, n, ctx) -> {
                fail("Unselected file challenges must not compile EJS");
                return null;
            }, tokens));
        assertEquals(1, requests.size());
        assertEquals(ClientProfile.WEB_SAFARI, result.manifests.get(0).requestPlan.profile);
        assertEquals(RequestPlan.Protocol.HLS, result.manifests.get(0).key.protocol);
        assertTrue(result.candidates.isEmpty());
        assertTrue(requests.get(0).headers().containsKey("Authorization"));
    }

    @Test
    void authenticatedPlaybackUsesVisionOsWhenWebClientsAreSabrOnly() throws Exception {
        final String sabr = "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
            + "\"adaptiveFormats\":[{\"itag\":140}],\"serverAbrStreamingUrl\":\"sabr\"}}";
        final List<String> bodies = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                final String body = new String(request.dataToSend(), java.nio.charset.StandardCharsets.UTF_8);
                bodies.add(body);
                final String json = body.contains("\"clientName\":\"VISIONOS\"") ? MEDIA : sabr;
                return new Response(200, "OK", Map.of(), json, request.url());
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            playback(transport, (url, s, n, ctx) -> new ChallengeSolver.Solutions(Map.of(), Map.of()), null)
        );
        assertFalse(bodies.get(0).contains("\"clientName\":\"VISIONOS\""));
        assertTrue(bodies.stream().anyMatch(body -> body.contains("\"clientName\":\"VISIONOS\"")));
        assertEquals(ClientProfile.VISIONOS, result.candidates.get(0).requestPlan.profile);
    }

    @Test
    void playbackHlsDoesNotMaterializeUnselectedAudioFiles() throws Exception {
        final String body = "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
            + "\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls/index.m3u8\","
            + "\"formats\":[{\"itag\":18,\"height\":360,\"mimeType\":\"video/mp4\","
            + "\"url\":\"https://rr.googlevideo.com/videoplayback?n=file-challenge\"}],"
            + "\"adaptiveFormats\":["
            + "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\","
            + "\"audioTrack\":{\"id\":\"en-US.4\",\"displayName\":\"English\"},"
            + "\"url\":\"https://rr.googlevideo.com/videoplayback?id=en&n=audio-challenge\"},"
            + "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\","
            + "\"audioTrack\":{\"id\":\"ja.10\",\"displayName\":\"Japanese\"},"
            + "\"url\":\"https://rr.googlevideo.com/videoplayback?id=ja&expire=9999999999\"}"
            + "]}}";
        final int[] solved = { 0 };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            playback(response(body), (url, s, n, ctx) -> {
                solved[0]++;
                assertFalse(n.contains("file-challenge"), n.toString());
                return new ChallengeSolver.Solutions(Map.of(), Map.of());
            }, new PoTokenProvider() {
                @Override
                public Token get(final TokenRequest request, final ExtractionContext context) {
                    fail("HLS audio renditions must not require file GVS tokens");
                    return null;
                }

                @Override
                public void invalidate(final String scope) { }
            })
        );
        assertEquals(0, solved[0]);
        assertEquals(1, result.manifests.size());
        assertEquals(RequestPlan.Protocol.HLS, result.manifests.get(0).key.protocol);
        assertTrue(result.candidates.isEmpty());
    }

    @Test
    void playbackStillSolvesManifestChallenges() throws Exception {
        final String body = HLS_WITH_FILES.replace("hls/index.m3u8", "hls/n/manifest-input/index.m3u8")
            .replace("\"streamingData\":", "\"assets\":{\"js\":\"/s/player/test/base.js\"},\"streamingData\":");
        final int[] solved = { 0 };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", playback(response(body), (url, s, n, ctx) -> {
                solved[0]++;
                assertTrue(s.isEmpty());
                assertEquals(Set.of("manifest-input"), n);
                return new ChallengeSolver.Solutions(Map.of(), Map.of("manifest-input", "decoded"));
            }, null));
        assertEquals(1, solved[0]);
        assertTrue(result.manifests.get(0).url.contains("/n/decoded/"));
    }

    @Test
    void missingPlaybackHlsFallsBackWithoutAnonymousAccount() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(200, "OK", Map.of(), requests.size() <= 2
                    ? "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}}" : HLS_WITH_FILES,
                    request.url());
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", playback(transport,
                (url, s, n, ctx) -> new ChallengeSolver.Solutions(Map.of(), Map.of()), null));
        assertEquals(3, requests.size());
        assertEquals(ClientProfile.WEB, result.manifests.get(0).requestPlan.profile);
        assertTrue(requests.get(0).headers().containsKey("Authorization"));
        assertFalse(requests.get(1).headers().containsKey("Authorization"));
        assertTrue(requests.get(2).headers().containsKey("Authorization"));
    }

    @Test
    void exemptProfilesDoNotInitializeTokenProvider() throws Exception {
        final PoTokenProvider provider = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                fail("Exempt clients should not initialize the token provider");
                return null;
            }

            @Override
            public void invalidate(final String scope) { }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.AUTHENTICATED), response(FILE_PAIR), provider)
        );
        assertTrue(result.candidates.size() >= 2);
        assertEquals(ClientProfile.WEB_EMBEDDED, result.candidates.get(0).requestPlan.profile);
        assertTrue(result.candidates.stream().anyMatch(c -> c.requestPlan.profile
            == ClientProfile.TV_DOWNGRADED));
    }

    @Test
    void missingUrlsDoNotMintGvsOrOptionalPlayerTokens() throws Exception {
        final PoTokenProvider provider = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                fail("URL-less responses should not mint tokens");
                return null;
            }

            @Override
            public void invalidate(final String scope) { }
        };
        assertThrows(ExtractionException.class, () -> new YoutubeStreamEngine().extract(
            "abcdefghijk", context(session(YoutubeSession.Account.AUTHENTICATED),
                response("{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
                    + "\"adaptiveFormats\":[{\"itag\":140}],\"serverAbrStreamingUrl\":\"https://example.invalid\"}}"),
                provider)));
    }

    @Test
    void legacyFacadeAcceptsManifestWithoutFileStreams() throws Exception {
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                return new Response(200, "OK", Map.of(),
                    "{\"playabilityStatus\":{\"status\":\"OK\"},"
                        + "\"videoDetails\":{\"videoId\":\"abcdefghijk\",\"title\":\"fixture\"},"
                        + "\"streamingData\":{\"hlsManifestUrl\":"
                        + "\"https://manifest.googlevideo.com/api/manifest/hls_variant/expire/9999999999/index.m3u8\"}}",
                    request.url());
            }
        };
        org.schabi.newpipe.extractor.NewPipe.init(transport);
        final org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor facade =
            (org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor)
                org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor(
                    "https://www.youtube.com/watch?v=abcdefghijk");
        facade.setExtractionContext(context(session(YoutubeSession.Account.ANONYMOUS), transport, null));
        final org.schabi.newpipe.extractor.stream.StreamInfo info =
            org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(facade);
        assertFalse(info.getHlsUrl().isEmpty());
        assertTrue(info.getAudioStreams().isEmpty());
        assertTrue(info.getVideoStreams().isEmpty());
    }

    @Test
    void unknownItagAndAggregateBudget() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(200, "OK", Map.of(), MEDIA, request.url());
            }
        };
        final PoTokenProvider tokens = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                return new Token("test-token", Long.MAX_VALUE);
            }

            @Override
            public void invalidate(final String scope) { }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.ANONYMOUS), transport, tokens)
        );
        assertTrue(requests.size() >= 2);
        assertTrue(result.candidates.size() >= 2);
        assertEquals(999, result.candidates.get(0).key.itag);
        assertEquals("opus", result.candidates.get(0).key.codec);
        assertEquals(
            "",
            result.candidates.get(0).requestPlan.cookies(result.candidates.get(0).url)
        );
        assertFalse(result.candidates.get(0).requestPlan.headers.containsKey("Authorization"));
    }

    @Test
    void accountRestrictionDoesNotAbortOtherAccountProfiles() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(
                    200,
                    "OK",
                    Map.of(),
                    requests.size() == 1
                        ? "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}}"
                        : MEDIA,
                    request.url()
                );
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.AUTHENTICATED), transport, null)
        );
        assertFalse(result.candidates.isEmpty());
        assertTrue(requests.size() <= 6);
        assertTrue(
            requests.stream().filter(request -> !new String(request.dataToSend(),
                java.nio.charset.StandardCharsets.UTF_8).contains("VISIONOS"))
                .allMatch(request -> request.headers().containsKey("Authorization"))
        );
        assertEquals(ClientProfile.TV_DOWNGRADED, result.candidates.get(0).requestPlan.profile);
    }

    @Test
    void rateLimitStopsImmediately() {
        final int[] count = { 0 };
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                count[0]++;
                return new Response(
                    429,
                    "Rate limit",
                    Map.of("Retry-After", List.of("60")),
                    "",
                    request.url()
                );
            }
        };
        assertThrows(IOException.class, () ->
            new YoutubeStreamEngine().extract(
                "abcdefghijk",
                context(session(YoutubeSession.Account.ANONYMOUS), transport, null)
            )
        );
        assertEquals(1, count[0]);
    }

    @Test
    void sabrOnlyIsNotRequestableMedia() {
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                return new Response(
                    200,
                    "OK",
                    Map.of(),
                    "{\"playabilityStatus\":{\"status\":\"OK\"},"
                        + "\"streamingData\":{\"adaptiveFormats\":[],\"serverAbrStreamingUrl\":\"sabr\"}}",
                    request.url()
                );
            }
        };
        final ExtractionException error = assertThrows(ExtractionException.class, () ->
            new YoutubeStreamEngine().extract(
                "abcdefghijk",
                context(session(YoutubeSession.Account.ANONYMOUS), transport, null)
            )
        );
        assertTrue(error.getMessage().contains("SABR_ONLY"));
    }

    @Test
    void completeFormatIdentityIncludesTrackAndProtocol() {
        final FormatKey first = new FormatKey(
            140,
            "en.0",
            "mp4a.40.2",
            false,
            false,
            RequestPlan.Protocol.HTTPS
        );
        assertNotEquals(
            first,
            new FormatKey(140, "fr.0", "mp4a.40.2", false, false, RequestPlan.Protocol.HTTPS)
        );
        assertNotEquals(
            first,
            new FormatKey(140, "en.0", "mp4a.40.2", true, false, RequestPlan.Protocol.HTTPS)
        );
        assertNotEquals(
            first,
            new FormatKey(140, "en.0", "mp4a.40.2", false, false, RequestPlan.Protocol.HLS)
        );
    }

    @Test
    void sidHashIncludesOriginAndUserSession() {
        final Map<String, List<String>> headers = InnertubeAuth.headers(
            session(YoutubeSession.Account.AUTHENTICATED),
            ClientProfile.WEB,
            "https://www.youtube.com",
            123
        );
        assertTrue(headers.get("Authorization").get(0).contains("SAPISIDHASH 123_"));
        assertTrue(headers.get("Authorization").get(0).contains("_u"));
        assertEquals(List.of("2"), headers.get("X-Goog-AuthUser"));
        assertFalse(
            InnertubeAuth.headers(
                session(YoutubeSession.Account.AUTHENTICATED),
                ClientProfile.WEB,
                "https://rr.googlevideo.com",
                123
            ).containsKey("Cookie")
        );
    }

    private Downloader response(final String body) {
        return new Downloader() {
            @Override
            public Response execute(final Request request) {
                return new Response(200, "OK", Map.of(), body, request.url());
            }
        };
    }

    @Test
    void purposeAndAccountBindingAreExplicit() throws Exception {
        final List<PoTokenProvider.TokenRequest> seen = new ArrayList<>();
        final PoTokenProvider provider = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                seen.add(request);
                return new Token("token", Long.MAX_VALUE);
            }

            @Override
            public void invalidate(final String scope) { }
        };
        final String captions =
            MEDIA.substring(0, MEDIA.length() - 1)
            + ",\"captions\":{\"playerCaptionsTracklistRenderer\":{\"captionTracks\":["
            + "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?xpe=1\"}]}}}";
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.AUTHENTICATED), response(captions), provider)
        );
        assertTrue(
            seen
                .stream()
                .anyMatch(
                    r ->
                        r.purpose == PoTokenProvider.Purpose.SUBS
                        && r.binding == PoTokenProvider.Binding.VIDEO
                        && r.identifier.equals("abcdefghijk")
                )
        );
        assertTrue(
            result.player
                .getObject("captions")
                .getObject("playerCaptionsTracklistRenderer")
                .getArray("captionTracks")
                .getObject(0)
                .getString("baseUrl")
                .contains("potc=1")
        );
        new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.ANONYMOUS), response(MEDIA), provider)
        );
        assertTrue(
            seen
                .stream()
                .anyMatch(
                    r ->
                        r.purpose == PoTokenProvider.Purpose.GVS
                        && r.binding == PoTokenProvider.Binding.VISITOR
                        && r.identifier.equals("visitor")
                )
        );
    }

    @Test
    void sessionConfigurationCannotBeMutatedThroughNestedObjects() {
        final JsonObject client = new JsonObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", "current-page-version");
        final JsonObject inner = new JsonObject();
        inner.put("client", client);
        final JsonObject config = new JsonObject();
        config.put("INNERTUBE_CONTEXT", inner);
        final YoutubeSession value = new YoutubeSession(
            "key",
            YoutubeSession.Account.ANONYMOUS,
            0,
            null,
            null,
            null,
            "visitor",
            "browser",
            1,
            null,
            "test",
            config,
            origin -> ""
        );
        client.put("clientVersion", "mutated");
        value
            .configuration()
            .getObject("INNERTUBE_CONTEXT")
            .getObject("client")
            .put("clientVersion", "mutated-again");
        assertEquals("current-page-version", ClientProfile.WEB.version(value));
        assertEquals(
            ClientProfile.TV_DOWNGRADED.version,
            ClientProfile.TV_DOWNGRADED.version(value)
        );
    }

    @Test
    void tokenKeysDoNotCrossPurposeNetworkOrProfile() {
        final PoTokenProvider.TokenRequest first = new PoTokenProvider.TokenRequest(
            PoTokenProvider.Purpose.GVS,
            ClientProfile.WEB,
            RequestPlan.Protocol.HTTPS,
            PoTokenProvider.Binding.VISITOR,
            "visitor",
            "session:1"
        );
        assertNotEquals(
            first.cacheKey(),
            new PoTokenProvider.TokenRequest(
                PoTokenProvider.Purpose.SUBS,
                ClientProfile.WEB,
                RequestPlan.Protocol.HTTPS,
                PoTokenProvider.Binding.VIDEO,
                "video",
                "session:1"
            ).cacheKey()
        );
        assertNotEquals(
            first.cacheKey(),
            new PoTokenProvider.TokenRequest(
                PoTokenProvider.Purpose.GVS,
                ClientProfile.WEB,
                RequestPlan.Protocol.HTTPS,
                PoTokenProvider.Binding.VISITOR,
                "visitor",
                "session:2"
            ).cacheKey()
        );
        assertNotEquals(
            first.cacheKey(),
            new PoTokenProvider.TokenRequest(
                PoTokenProvider.Purpose.GVS,
                ClientProfile.WEB_SAFARI,
                RequestPlan.Protocol.HTTPS,
                PoTokenProvider.Binding.VISITOR,
                "visitor",
                "session:1"
            ).cacheKey()
        );
    }

    @Test
    void expiredTokensAreNotEmbeddedAndAv1IsRetained() throws Exception {
        final PoTokenProvider expired = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext context) {
                return new Token("expired-token", 1);
            }

            @Override
            public void invalidate(final String scope) { }
        };
        final String av1 = MEDIA.replace("audio/webm", "video/mp4").replace(
            "opus",
            "av01.0.05M.08"
        );
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            context(session(YoutubeSession.Account.ANONYMOUS), response(av1), expired)
        );
        assertTrue(result.candidates.stream().allMatch(c -> c.key.codec.startsWith("av01")));
        assertTrue(result.candidates.stream().noneMatch(c -> c.url.contains("expired-token")));
        assertTrue(
            result.rejections.stream().anyMatch(r -> r.failure == YoutubeStreamEngine.Failure.TOKEN)
        );
    }

    @Test
    void allProfile403sPreserveStageForBoundedHostRecovery() {
        final Downloader downloader = new Downloader() {
            @Override
            public Response execute(final Request request) {
                return new Response(403, "Forbidden", Map.of(), "", request.url());
            }
        };
        final ExtractionException failure = assertThrows(ExtractionException.class, () ->
            new YoutubeStreamEngine().extract(
                "abcdefghijk",
                context(session(YoutubeSession.Account.AUTHENTICATED), downloader, null)
            )
        );
        assertEquals(403, ((StreamHttpException) failure.getCause()).status);
    }

    @Test
    void changedSessionStopsBeforeRequestAndDeadlineIsCarriedToTransport() throws Exception {
        final boolean[] current = { false };
        final int[] calls = { 0 };
        final Downloader downloader = new Downloader() {
            @Override
            public Response execute(final Request request) {
                calls[0]++;
                assertTrue(request.executionDeadlineNanos() < Long.MAX_VALUE);
                return new Response(200, "OK", Map.of(), MEDIA, request.url());
            }
        };
        final ExtractionContext ctx = new ExtractionContext(
            session(YoutubeSession.Account.ANONYMOUS),
            downloader,
            (url, s, n, c) -> new ChallengeSolver.Solutions(Map.of(), Map.of()),
            null,
            false,
            false,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(45),
            () -> current[0],
            (s, p, d, t, code) -> { }
        );
        assertThrows(IOException.class, () ->
            new YoutubeStreamEngine().extract("abcdefghijk", ctx)
        );
        assertEquals(0, calls[0]);
        current[0] = true;
        ctx.get("https://www.youtube.com/", Map.of());
        assertEquals(1, calls[0]);
    }

    @Test
    void manifestChallengesAndTokensUseThePathAndPreserveSignedQuery() throws Exception {
        final String playerUrl = "https://www.youtube.com/s/player/version/variant/base.js";
        final String body =
            "{\"assets\":{\"js\":\""
            + playerUrl
            + "\"},"
            + "\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
            + "\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls/expire/9999999999/n/input/file/index.m3u8?sig=a%2Fb\","
            + "\"dashManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/dash/s/signature/n/input?keep=%2F\"}}";
        final ChallengeSolver solver = (url, s, n, ctx) -> {
            assertEquals(playerUrl, url);
            assertEquals(Set.of("signature"), s);
            assertEquals(Set.of("input"), n);
            return new ChallengeSolver.Solutions(
                Map.of("signature", "signed"),
                Map.of("input", "decoded")
            );
        };
        final PoTokenProvider provider = new PoTokenProvider() {
            @Override
            public Token get(final TokenRequest request, final ExtractionContext ctx) {
                return new Token("token-value", Long.MAX_VALUE);
            }

            @Override
            public void invalidate(final String scope) { }
        };
        final ExtractionContext ctx = new ExtractionContext(
            session(YoutubeSession.Account.ANONYMOUS),
            response(body),
            solver,
            provider,
            true,
            true,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(45),
            () -> true,
            (s, p, d, t, code) -> { }
        );
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk",
            ctx
        );
        assertTrue(
            result.manifests
                .stream()
                .anyMatch(c ->
                    c.url.endsWith("/n/decoded/pot/token-value/file/index.m3u8?sig=a%2Fb")
                )
        );
        assertTrue(
            result.manifests
                .stream()
                .anyMatch(c ->
                    c.url.endsWith("/signature/signed/n/decoded/pot/token-value?keep=%2F")
                )
        );
        assertTrue(result.manifests.stream().allMatch(c -> !c.url.contains("?pot=")));
    }

    @Test
    void nonEmptyAdaptiveArrayWithoutUrlsIsStillSabrOnly() {
        final String body =
            "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
            + "\"adaptiveFormats\":[{\"itag\":140}],\"serverAbrStreamingUrl\":\"sabr\"}}";
        final ExtractionException error = assertThrows(ExtractionException.class, () ->
            new YoutubeStreamEngine().extract(
                "abcdefghijk",
                context(session(YoutubeSession.Account.ANONYMOUS), response(body), null)
            )
        );
        assertTrue(error.getMessage().contains("SABR_ONLY"));
    }

    @Test
    void allProfileCaptchaResponsesReportCaptchaAfterBoundedFallbacks() {
        final int[] count = { 0 };
        final Downloader downloader = new Downloader() {
            @Override
            public Response execute(final Request request) {
                count[0]++;
                return new Response(
                    200,
                    "OK",
                    Map.of(),
                    "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm you are not a bot\"}}",
                    request.url()
                );
            }
        };
        final StreamHttpException failure = assertThrows(StreamHttpException.class, () ->
            new YoutubeStreamEngine().extract(
                "abcdefghijk",
                context(session(YoutubeSession.Account.ANONYMOUS), downloader, null)
            )
        );
        assertEquals(402, failure.status);
        assertEquals("PLAYABILITY_CAPTCHA", failure.stage);
        assertEquals(4, count[0]);
    }

    @Test
    void anonymousProfileCaptchaFallsBackToPlayableWebHls() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(200, "OK", Map.of(), requests.size() == 1
                    ? "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\","
                        + "\"reason\":\"Sign in to confirm you are not a bot\"}}"
                    : HLS_WITH_FILES, request.url());
            }
        };
        final ExtractionContext ctx = new ExtractionContext(
            session(YoutubeSession.Account.ANONYMOUS), transport,
            (url, sigs, ns, context) -> new ChallengeSolver.Solutions(Map.of(), Map.of()),
            null, false, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(45),
            () -> true, (stage, profile, detail, duration, status) -> { });
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", ctx);
        assertEquals(2, requests.size());
        assertEquals(ClientProfile.WEB, result.manifests.get(0).requestPlan.profile);
        assertEquals(RequestPlan.Protocol.HLS, result.manifests.get(0).key.protocol);
        assertTrue(result.rejections.stream().anyMatch(rejection ->
            rejection.profile == ClientProfile.VISIONOS
                && rejection.failure == YoutubeStreamEngine.Failure.CAPTCHA));
        assertTrue(requests.stream().noneMatch(request ->
            request.headers().containsKey("Authorization") || request.headers().containsKey("Cookie")));
    }

    @Test
    void optionalProfileCaptchaDoesNotDiscardPreviouslyRequestableCatalog() throws Exception {
        final int[] requests = { 0 };
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests[0]++;
                return new Response(200, "OK", Map.of(), requests[0] == 1 ? FILE_PAIR
                    : "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\","
                        + "\"reason\":\"Sign in to confirm you are not a bot\"}}", request.url());
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", context(session(YoutubeSession.Account.ANONYMOUS), transport, null));
        assertEquals(4, requests[0]);
        assertEquals(2, result.candidates.size());
        assertTrue(result.candidates.stream().allMatch(candidate ->
            candidate.requestPlan.profile == ClientProfile.VISIONOS));
        assertEquals(3, result.rejections.stream().filter(rejection ->
            rejection.failure == YoutubeStreamEngine.Failure.CAPTCHA).count());
    }

    @Test
    void authenticatedProfileCaptchaFallsBackWithoutChangingSessionCredentials() throws Exception {
        final List<Request> requests = new ArrayList<>();
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests.add(request);
                return new Response(200, "OK", Map.of(), requests.size() < 3
                    ? "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\","
                        + "\"reason\":\"Sign in to confirm you are not a bot\"}}"
                    : HLS_WITH_FILES, request.url());
            }
        };
        final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
            "abcdefghijk", playback(transport,
                (url, sigs, ns, context) -> new ChallengeSolver.Solutions(Map.of(), Map.of()), null));
        assertEquals(3, requests.size());
        assertEquals(ClientProfile.WEB, result.manifests.get(0).requestPlan.profile);
        assertEquals(2, result.rejections.stream().filter(rejection ->
            rejection.failure == YoutubeStreamEngine.Failure.CAPTCHA).count());
        assertTrue(requests.get(0).headers().containsKey("Authorization"));
        assertFalse(requests.get(1).headers().containsKey("Authorization"));
        assertTrue(requests.get(2).headers().containsKey("Authorization"));
        assertEquals(requests.get(0).headers().get("Cookie"),
            requests.get(2).headers().get("Cookie"));
    }

    @Test
    void signedInCreatorRecoversPlaybackAndCatalogFromSabrAndProfileCaptcha() throws Exception {
        for (final boolean catalog : List.of(false, true)) {
            final List<Request> requests = new ArrayList<>();
            final List<PoTokenProvider.TokenRequest> minted = new ArrayList<>();
            final Downloader transport = new Downloader() {
                @Override
                public Response execute(final Request request) {
                    requests.add(request);
                    final String body = new String(request.dataToSend(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    final String player = body.contains("WEB_CREATOR") ? FILE_PAIR
                        : body.contains("VISIONOS")
                            ? "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\","
                                + "\"reason\":\"Sign in to confirm you are not a bot\"}}"
                            : "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{"
                                + "\"adaptiveFormats\":[{\"itag\":140}],"
                                + "\"serverAbrStreamingUrl\":\"https://example.invalid\"}}";
                    return new Response(200, "OK", Map.of(), player, request.url());
                }
            };
            final PoTokenProvider provider = new PoTokenProvider() {
                @Override
                public Token get(final TokenRequest request, final ExtractionContext context) {
                    minted.add(request);
                    return new Token("creator-gvs", System.currentTimeMillis() + 60_000);
                }
                @Override
                public void invalidate(final String scope) { }
            };
            final ExtractionContext ctx = catalog
                ? context(session(YoutubeSession.Account.AUTHENTICATED), transport, provider)
                : playback(transport,
                    (url, sigs, ns, context) -> new ChallengeSolver.Solutions(Map.of(), Map.of()),
                    provider);
            final YoutubeStreamEngine.Result result = new YoutubeStreamEngine().extract(
                "abcdefghijk", ctx);
            assertEquals(6, requests.size());
            assertEquals(2, result.candidates.size());
            assertTrue(result.candidates.stream().allMatch(candidate ->
                candidate.requestPlan.profile == ClientProfile.WEB_CREATOR));
            assertEquals(requests.get(0).headers().get("Cookie"),
                requests.get(5).headers().get("Cookie"));
            assertTrue(requests.get(5).headers().containsKey("Authorization"));
            assertFalse(minted.isEmpty());
            assertTrue(minted.stream().allMatch(request ->
                request.profile == ClientProfile.WEB_CREATOR
                    && request.purpose == PoTokenProvider.Purpose.GVS
                    && request.binding == PoTokenProvider.Binding.DATA_SYNC
                    && request.identifier.equals(ctx.session.dataSyncId)));
        }
    }

    @Test
    void actualHttpCaptchaStopsWithoutTryingOtherProfiles() {
        final int[] requests = { 0 };
        final Downloader transport = new Downloader() {
            @Override
            public Response execute(final Request request) {
                requests[0]++;
                return new Response(402, "Captcha", Map.of(), "", request.url());
            }
        };
        final StreamHttpException failure = assertThrows(StreamHttpException.class, () ->
            new YoutubeStreamEngine().extract("abcdefghijk",
                context(session(YoutubeSession.Account.ANONYMOUS), transport, null)));
        assertEquals(402, failure.status);
        assertEquals("INNERTUBE", failure.stage);
        assertEquals(1, requests[0]);
    }

    @Test
    void retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(30_000, StreamHttpException.parseRetryAfter("30", 0));
        final long now = java.time.Instant.parse("2026-10-04T00:00:00Z").toEpochMilli();
        assertEquals(
            60_000,
            StreamHttpException.parseRetryAfter("Sun, 04 Oct 2026 00:01:00 GMT", now)
        );
        assertEquals(0, StreamHttpException.parseRetryAfter("invalid", now));
    }
}
