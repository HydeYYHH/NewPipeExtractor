package org.schabi.newpipe.extractor.services.youtube;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manage the extraction and the usage of YouTube's player JavaScript needed data in the YouTube
 * service.
 *
 * <p>
 * YouTube restrict streaming their media in multiple ways by requiring their HTML5 clients to use
 * a signature timestamp, and on streaming URLs a signature deobfuscation function for some
 * contents and a throttling parameter deobfuscation one for all contents.
 * </p>
 *
 * <p>
 * This class provides access to methods which allows to get base JavaScript player's signature
 * timestamp and to deobfuscate streaming URLs' signature and/or throttling parameter of HTML5
 * clients.
 * </p>
 */
public final class YoutubeJavaScriptPlayerManager {

    /**
     * Per-player-code extraction state. Player builds rotate frequently and a
     * build's constants only decipher signatures minted for it, so caches are
     * keyed by the base.js source the code came from: the embed watch page
     * discovery by default, or an explicit player JS URL (e.g. the one the
     * WebView session reports) for mirrored bodies.
     */
    private static final class PlayerCodeCache {
        private String javaScriptPlayerCode;

        @Nullable
        private Integer signatureTimestamp;
        @Nullable
        private String signatureDeobfuscationFunction;
        @Nullable
        private String throttlingDeobfuscationFunctionName;
        @Nullable
        private String throttlingDeobfuscationFunction;

        @Nullable
        private ParsingException throttlingDeobfFuncExtractionEx;
        @Nullable
        private ParsingException sigDeobFuncExtractionEx;
        @Nullable
        private ParsingException sigTimestampExtractionEx;
    }

    @Nonnull
    private static final Map<String, PlayerCodeCache> CACHES = new HashMap<>();

    @Nonnull
    private static final Map<String, String> CACHED_THROTTLING_PARAMETERS = new HashMap<>();

    @Nonnull
    private static PlayerCodeCache cacheFor(@Nullable final String playerJsUrl) {
        final String key = playerJsUrl == null ? "" : playerJsUrl;
        synchronized (CACHES) {
            return CACHES.computeIfAbsent(key, k -> new PlayerCodeCache());
        }
    }

    private YoutubeJavaScriptPlayerManager() {
    }

    /**
     * Get the signature timestamp of the base JavaScript player file.
     *
     * <p>
     * A valid signature timestamp sent in the payload of player InnerTube requests is required to
     * get valid stream URLs on HTML5 clients for videos which have obfuscated signatures.
     * </p>
     *
     * <p>
     * The base JavaScript player file will fetched if it is not already done.
     * </p>
     *
     * <p>
     * The result of the extraction is cached until {@link #clearAllCaches()} is called, making
     * subsequent calls faster.
     * </p>
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @return the signature timestamp of the base JavaScript player file
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * signature timestamp failed
     */
    @Nonnull
    public static Integer getSignatureTimestamp(@Nonnull final String videoId)
            throws ParsingException {
        return getSignatureTimestamp(videoId, null);
    }

    @Nonnull
    public static Integer getSignatureTimestamp(@Nonnull final String videoId,
                                                @Nullable final String playerJsUrl)
            throws ParsingException {
        final PlayerCodeCache cache = cacheFor(playerJsUrl);
        synchronized (cache) {
            // Return the cached result if it is present
            if (cache.signatureTimestamp != null) {
                return cache.signatureTimestamp;
            }

            // If the signature timestamp has been not extracted on a previous call, this mean that
            // we will fail to extract it on next calls too if the player code has been not changed
            // Throw again the corresponding stored exception in this case to improve performance
            if (cache.sigTimestampExtractionEx != null) {
                throw cache.sigTimestampExtractionEx;
            }

            extractJavaScriptCodeIfNeeded(videoId, cache, playerJsUrl);

            try {
                cache.signatureTimestamp = Integer.valueOf(YoutubeSignatureUtils
                        .getSignatureTimestamp(cache.javaScriptPlayerCode));
            } catch (final ParsingException e) {
                // Store the exception for future calls, to improve performance
                cache.sigTimestampExtractionEx = e;
                throw e;
            } catch (final NumberFormatException e) {
                cache.sigTimestampExtractionEx = new ParsingException(
                        "Could not convert signature timestamp to a number", e);
            } catch (final Exception e) {
                cache.sigTimestampExtractionEx =
                        new ParsingException("Could not get signature timestamp", e);
                throw e;
            }

            return cache.signatureTimestamp;
        }
    }

    /**
     * Deobfuscate a signature of a streaming URL using its corresponding JavaScript base player's
     * function.
     *
     * <p>
     * Obfuscated signatures are only present on streaming URLs of some videos with HTML5 clients.
     * </p>
     *
     * @param videoId             the video ID used to get the JavaScript base player file (an
     *                            empty one can be passed, even it is not recommend in order to
     *                            spoof better official YouTube clients)
     * @param obfuscatedSignature the obfuscated signature of a streaming URL
     * @return the deobfuscated signature
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * signature deobfuscation function failed
     */
    @Nonnull
    public static String deobfuscateSignature(@Nonnull final String videoId,
                                              @Nonnull final String obfuscatedSignature)
            throws ParsingException {
        return deobfuscateSignature(videoId, obfuscatedSignature, null);
    }

    /**
     * @param playerJsUrl the base.js URL the body being deciphered was minted
     *                    with (e.g. the WebView session's {@code PLAYER_JS_URL});
     *                    {@code null} uses the embed watch page discovery
     */
    @Nonnull
    public static String deobfuscateSignature(@Nonnull final String videoId,
                                              @Nonnull final String obfuscatedSignature,
                                              @Nullable final String playerJsUrl)
            throws ParsingException {
        final PlayerCodeCache cache = cacheFor(playerJsUrl);
        synchronized (cache) {
            // If the signature deobfuscation function has been not extracted on a previous call,
            // this mean that we will fail to extract it on next calls too if the player code has
            // been not changed
            // Throw again the corresponding stored exception in this case to improve performance
            if (cache.sigDeobFuncExtractionEx != null) {
                throw cache.sigDeobFuncExtractionEx;
            }

            extractJavaScriptCodeIfNeeded(videoId, cache, playerJsUrl);

            if (cache.signatureDeobfuscationFunction == null) {
                try {
                    cache.signatureDeobfuscationFunction = YoutubeSignatureUtils
                            .getDeobfuscationCode(cache.javaScriptPlayerCode);
                } catch (final ParsingException e) {
                    // Store the exception for future calls, to improve performance
                    cache.sigDeobFuncExtractionEx = e;
                    throw e;
                } catch (final Exception e) {
                    cache.sigDeobFuncExtractionEx = new ParsingException(
                            "Could not get signature parameter deobfuscation JavaScript function",
                            e);
                    throw e;
                }
            }

            try {
                // Return an empty parameter in the case the function returns null
                return Objects.requireNonNullElse(
                        JavaScript.run(cache.signatureDeobfuscationFunction,
                                YoutubeSignatureUtils.DEOBFUSCATION_FUNCTION_NAME,
                                obfuscatedSignature), "");
            } catch (final Exception e) {
                // This shouldn't happen as the function validity is checked when it is extracted
                throw new ParsingException(
                        "Could not run signature parameter deobfuscation JavaScript function", e);
            }
        }
    }

    /**
     * Return a streaming URL with the throttling parameter of a given one deobfuscated, if it is
     * present, using its corresponding JavaScript base player's function.
     *
     * <p>
     * The throttling parameter is present on all streaming URLs of HTML5 clients.
     * </p>
     *
     * <p>
     * If it is not given or deobfuscated, speeds will be throttled to a very slow speed (around 50
     * KB/s) and some streaming URLs could even lead to invalid HTTP responses such a 403 one.
     * </p>
     *
     * <p>
     * As throttling parameters can be common between multiple streaming URLs of the same player
     * response, deobfuscated parameters are cached with their obfuscated variant, in order to
     * improve performance with multiple calls of this method having the same obfuscated throttling
     * parameter.
     * </p>
     *
     * <p>
     * The cache's size can be get using {@link #getThrottlingParametersCacheSize()} and the cache
     * can be cleared using {@link #clearThrottlingParametersCache()} or {@link #clearAllCaches()}.
     * </p>
     *
     * @param videoId      the video ID used to get the JavaScript base player file (an empty one
     *                     can be passed, even it is not recommend in order to spoof better
     *                     official YouTube clients)
     * @param streamingUrl a streaming URL
     * @return the original streaming URL if it has no throttling parameter or a URL with a
     * deobfuscated throttling parameter
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * throttling parameter deobfuscation function failed
     */
    @Nonnull
    public static String getUrlWithThrottlingParameterDeobfuscated(
            @Nonnull final String videoId,
            @Nonnull final String streamingUrl) throws ParsingException {
        final String obfuscatedThrottlingParameter =
                YoutubeThrottlingParameterUtils.getThrottlingParameterFromStreamingUrl(
                        streamingUrl);
        // If the throttling parameter is not present, return the original streaming URL
        if (obfuscatedThrottlingParameter == null) {
            return streamingUrl;
        }

        // Do not use the containsKey method of the Map interface in order to avoid a double
        // element search, and so to improve performance
        return getUrlWithThrottlingParameterDeobfuscated(videoId, streamingUrl, null);
    }

    /**
     * @param playerJsUrl the base.js URL the body being deciphered was minted
     *                    with; {@code null} uses the embed watch page discovery
     */
    @Nonnull
    public static String getUrlWithThrottlingParameterDeobfuscated(
            @Nonnull final String videoId,
            @Nonnull final String streamingUrl,
            @Nullable final String playerJsUrl) throws ParsingException {
        final String obfuscatedThrottlingParameter =
                YoutubeThrottlingParameterUtils.getThrottlingParameterFromStreamingUrl(
                        streamingUrl);
        // If the throttling parameter is not present, return the original streaming URL
        if (obfuscatedThrottlingParameter == null) {
            return streamingUrl;
        }

        final PlayerCodeCache cache = cacheFor(playerJsUrl);
        synchronized (cache) {
            final String cacheResult = CACHED_THROTTLING_PARAMETERS.get(
                    obfuscatedThrottlingParameter);
            if (cacheResult != null) {
                // If the throttling parameter function has been already ran on the throttling
                // parameter of the current streaming URL, replace directly the obfuscated
                // throttling parameter with the cached result in the streaming URL
                return streamingUrl.replace(obfuscatedThrottlingParameter, cacheResult);
            }

            extractJavaScriptCodeIfNeeded(videoId, cache, playerJsUrl);

            // If the throttling parameter deobfuscation function has been not extracted on a
            // previous call, this mean that we will fail to extract it on next calls too if the
            // player code has been not changed
            // Throw again the corresponding stored exception in this case to improve performance
            if (cache.throttlingDeobfFuncExtractionEx != null) {
                throw cache.throttlingDeobfFuncExtractionEx;
            }

            if (cache.throttlingDeobfuscationFunction == null) {
                try {
                    cache.throttlingDeobfuscationFunctionName =
                            YoutubeThrottlingParameterUtils.getDeobfuscationFunctionName(
                                    cache.javaScriptPlayerCode);

                    cache.throttlingDeobfuscationFunction =
                            YoutubeThrottlingParameterUtils.getDeobfuscationFunction(
                                    cache.javaScriptPlayerCode,
                                    cache.throttlingDeobfuscationFunctionName);
                } catch (final ParsingException e) {
                    // Store the exception for future calls, to improve performance
                    cache.throttlingDeobfFuncExtractionEx = e;
                    throw e;
                } catch (final Exception e) {
                    cache.throttlingDeobfFuncExtractionEx = new ParsingException(
                            "Could not get throttling parameter deobfuscation JavaScript function",
                            e);
                    throw e;
                }
            }

            try {
                final String deobfuscatedThrottlingParameter = JavaScript.run(
                        cache.throttlingDeobfuscationFunction,
                        cache.throttlingDeobfuscationFunctionName,
                        obfuscatedThrottlingParameter);

                if (isNullOrEmpty(deobfuscatedThrottlingParameter)) {
                    throw new IllegalStateException("Extracted n-parameter is empty");
                }

                CACHED_THROTTLING_PARAMETERS.put(
                        obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter);

                return streamingUrl.replace(
                        obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter);
            } catch (final Exception e) {
                // This shouldn't happen as the function validity is checked when extracted
                throw new ParsingException(
                        "Could not run throttling parameter deobfuscation JavaScript function", e);
            }
        }
    }

    /**
     * Get the current cache size of throttling parameters.
     *
     * @return the current cache size of throttling parameters
     */
    public static int getThrottlingParametersCacheSize() {
        return CACHED_THROTTLING_PARAMETERS.size();
    }

    /**
     * Clear all caches.
     *
     * <p>
     * This method will clear all cached JavaScript code and throttling parameters.
     * </p>
     *
     * <p>
     * The next time {@link #getSignatureTimestamp(String)},
     * {@link #deobfuscateSignature(String, String)} or
     * {@link #getUrlWithThrottlingParameterDeobfuscated(String, String)} is called, the JavaScript
     * code will be fetched again and the corresponding extraction methods will be ran.
     * </p>
     */
    public static void clearAllCaches() {
        synchronized (CACHES) {
            CACHES.clear();
        }
        clearThrottlingParametersCache();
    }

    /**
     * Clear all cached throttling parameters.
     *
     * <p>
     * The throttling parameter deobfuscation function will be ran again on these parameters if
     * streaming URLs containing them are passed in the future.
     * </p>
     *
     * <p>
     * This method doesn't clear the cached throttling parameter deobfuscation function, this can
     * be done using {@link #clearAllCaches()}.
     * </p>
     */
    public static void clearThrottlingParametersCache() {
        CACHED_THROTTLING_PARAMETERS.clear();
    }

    /**
     * Extract the JavaScript code if it isn't already cached.
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @throws ParsingException if the extraction of the base JavaScript player file failed
     */
    private static void extractJavaScriptCodeIfNeeded(@Nonnull final String videoId,
                                                      @Nonnull final PlayerCodeCache cache,
                                                      @Nullable final String playerJsUrl)
            throws ParsingException {
        if (cache.javaScriptPlayerCode == null) {
            cache.javaScriptPlayerCode = YoutubeJavaScriptExtractor.extractJavaScriptPlayerCode(
                    videoId, playerJsUrl);
        }
    }
}
