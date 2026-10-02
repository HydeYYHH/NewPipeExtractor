package org.schabi.newpipe.extractor.services.youtube;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;
import org.schabi.newpipe.extractor.utils.Parser;
import org.schabi.newpipe.extractor.utils.jsextractor.JavaScriptExtractor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to get the throttling parameter decryption code and check if a streaming has the
 * throttling parameter.
 */
final class YoutubeThrottlingParameterUtils {

    // NOTE: When changing this you should also change the quick exit/shortcut
    // in getThrottlingParameterFromStreamingUrl
    private static final Pattern THROTTLING_PARAM_PATTERN = Pattern.compile("[&?]n=([^&]+)");

    private static final String SINGLE_CHAR_VARIABLE_REGEX = "[a-zA-Z0-9$_]";

    private static final String MULTIPLE_CHARS_REGEX = SINGLE_CHAR_VARIABLE_REGEX + "+";

    private static final String ARRAY_ACCESS_REGEX = "\\[(\\d+)]";

    // CHECKSTYLE:OFF
    private static final Pattern[] DEOBFUSCATION_FUNCTION_NAME_REGEXES = {
            /*
             * Matches the following text, where we want m85:
             *
             * m85=function( ... return Y[45]
             */
            Pattern.compile("([A-Za-z0-9_\\$]{2,})=function.*return [A-Z]\\[\\d+\\]"),


            /*
             * Matches the following text, where we want SDa and the array index accessed:
             *
             * a.D&&(b="nn"[+a.D],WL(a),c=a.j[b]||null)&&(c=SDa[0](c),a.set(b,c),SDa.length||Wma("")
             */
            Pattern.compile(SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX
                    + "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "\\("
                    + MULTIPLE_CHARS_REGEX + "\\)," + MULTIPLE_CHARS_REGEX + "="
                    + MULTIPLE_CHARS_REGEX + "\\." + MULTIPLE_CHARS_REGEX + "\\["
                    + MULTIPLE_CHARS_REGEX + "]\\|\\|null\\)&&\\(" + MULTIPLE_CHARS_REGEX + "=("
                    + MULTIPLE_CHARS_REGEX + ")" + ARRAY_ACCESS_REGEX),

            /*
             * Matches the following text, where we want Wma:
             *
             * a.D&&(b="nn"[+a.D],WL(a),c=a.j[b]||null)&&(c=SDa[0](c),a.set(b,c),SDa.length||Wma("")
             */
            Pattern.compile(SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX
                    + "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "\\("
                    + MULTIPLE_CHARS_REGEX + "\\)," + MULTIPLE_CHARS_REGEX + "="
                    + MULTIPLE_CHARS_REGEX + "\\." + MULTIPLE_CHARS_REGEX + "\\["
                    + MULTIPLE_CHARS_REGEX + "]\\|\\|null\\).+\\|\\|(" + MULTIPLE_CHARS_REGEX
                    + ")\\(\"\"\\)"),

            /*
             * Matches the following text, where we want cvb and the array index accessed:
             *
             * ,Vb(m),W=m.j[c]||null)&&(W=cvb[0](W),m.set(c,W)
             */
            Pattern.compile("," + MULTIPLE_CHARS_REGEX + "\\("
                    + MULTIPLE_CHARS_REGEX + "\\)," + MULTIPLE_CHARS_REGEX + "="
                    + MULTIPLE_CHARS_REGEX + "\\." + MULTIPLE_CHARS_REGEX + "\\["
                    + MULTIPLE_CHARS_REGEX + "]\\|\\|null\\)&&\\(\\b" + MULTIPLE_CHARS_REGEX + "=("
                    + MULTIPLE_CHARS_REGEX + ")" + ARRAY_ACCESS_REGEX + "\\("
                    + SINGLE_CHAR_VARIABLE_REGEX + "\\)," + MULTIPLE_CHARS_REGEX
                    + "\\.set\\((?:\"n+\"|" + MULTIPLE_CHARS_REGEX + ")," + MULTIPLE_CHARS_REGEX
                    + "\\)"),

            /*
             * Matches the following text, where we want rma:
             *
             * a.D&&(b="nn"[+a.D],c=a.get(b))&&(c=rDa[0](c),a.set(b,c),rDa.length||rma("")
             */
            Pattern.compile(SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX
                    + "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "="
                    + MULTIPLE_CHARS_REGEX + "\\.get\\(" + MULTIPLE_CHARS_REGEX + "\\)\\).+\\|\\|("
                    + MULTIPLE_CHARS_REGEX + ")\\(\"\"\\)"),

            /*
             * Matches the following text, where we want rDa and the array index accessed:
             *
             * a.D&&(b="nn"[+a.D],c=a.get(b))&&(c=rDa[0](c),a.set(b,c),rDa.length||rma("")
             */
            Pattern.compile(SINGLE_CHAR_VARIABLE_REGEX + "=\"nn\"\\[\\+" + MULTIPLE_CHARS_REGEX
                    + "\\." + MULTIPLE_CHARS_REGEX + "]," + MULTIPLE_CHARS_REGEX + "="
                    + MULTIPLE_CHARS_REGEX + "\\.get\\(" + MULTIPLE_CHARS_REGEX + "\\)\\)&&\\("
                    + MULTIPLE_CHARS_REGEX + "=(" + MULTIPLE_CHARS_REGEX + ")\\[(\\d+)]"),

            /*
             * Matches the following text, where we want BDa and the array index accessed:
             *
             * (b=String.fromCharCode(110),c=a.get(b))&&(c=BDa[0](c)
             */
            Pattern.compile("\\(" + SINGLE_CHAR_VARIABLE_REGEX + "=String\\.fromCharCode\\(110\\),"
                    + SINGLE_CHAR_VARIABLE_REGEX + "=" + SINGLE_CHAR_VARIABLE_REGEX + "\\.get\\("
                    + SINGLE_CHAR_VARIABLE_REGEX + "\\)\\)" + "&&\\(" + SINGLE_CHAR_VARIABLE_REGEX
                    + "=(" + MULTIPLE_CHARS_REGEX + ")" + "(?:" + ARRAY_ACCESS_REGEX + ")?\\("
                    + SINGLE_CHAR_VARIABLE_REGEX + "\\)"),

            /*
             * Matches the following text, where we want Yva and the array index accessed:
             *
             * .get("n"))&&(b=Yva[0](b)
             */
            Pattern.compile("\\.get\\(\"n\"\\)\\)&&\\(" + SINGLE_CHAR_VARIABLE_REGEX
                    + "=(" + MULTIPLE_CHARS_REGEX + ")(?:" + ARRAY_ACCESS_REGEX + ")?\\("
                    + SINGLE_CHAR_VARIABLE_REGEX + "\\)")
    };
    // CHECKSTYLE:ON


    // Escape the curly end brace to allow compatibility with Android's regex engine
    // See https://stackoverflow.com/q/45074813
    @SuppressWarnings("RegExpRedundantEscape")
    private static final String DEOBFUSCATION_FUNCTION_BODY_REGEX =
            "=\\s*function([\\S\\s]*?\\}\\s*return [\\w$]+?\\.join\\(\"\"\\)\\s*\\};)";

    private static final String DEOBFUSCATION_FUNCTION_ARRAY_OBJECT_TYPE_DECLARATION_REGEX = "var ";

    private static final String FUNCTION_NAMES_IN_DEOBFUSCATION_ARRAY_REGEX =
            "\\s*=\\s*\\[(.+?)][;,]";

    private static final String FUNCTION_ARGUMENTS_REGEX =
            "=\\s*function\\s*\\(\\s*([^)]*)\\s*\\)";

    private static final String EARLY_RETURN_REGEX =
            ";\\s*if\\s*\\(\\s*typeof\\s+" + MULTIPLE_CHARS_REGEX
                    + "+\\s*===?\\s*([\"'])undefined\\1\\s*\\)\\s*return\\s+";

    private YoutubeThrottlingParameterUtils() {
    }

    /**
     * Get the throttling parameter deobfuscation function name of YouTube's base JavaScript file.
     *
     * <p>
     * Candidates may be a direct function name or an array reference
     * ({@code Yva[0]}). Array entries are resolved to the real function name
     * before {@link #isFunctionNameLikelyDeobfuscation} runs: validating the
     * array object itself always fails, which used to drop every array-shaped
     * n-transform.
     * </p>
     *
     * <p>
     * The first pattern (a generic {@code xxx=function(...) ... return Y[n]}) is
     * the historical one and is very loose: on current player builds it matches
     * unrelated helpers (e.g. {@code String.prototype} polyfills), which used to
     * make deobfuscation silently produce garbage and every WEB/MWEB URL 403.
     * Every resolved candidate is therefore validated with
     * {@link #isFunctionNameLikelyDeobfuscation} before use; a candidate that
     * fails validation is skipped so the tighter patterns can still match.
     * </p>
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @return the name of the throttling parameter deobfuscation function
     * @throws ParsingException if the name of the throttling parameter deobfuscation function
     * could not be extracted
     */
    @Nonnull
    static String getDeobfuscationFunctionName(@Nonnull final String javaScriptPlayerCode)
            throws ParsingException {
        final List<String> seenKeys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> indexes = new ArrayList<>();
        for (final Pattern pattern : DEOBFUSCATION_FUNCTION_NAME_REGEXES) {
            final Matcher matcher = pattern.matcher(javaScriptPlayerCode);
            while (matcher.find()) {
                final String name = matcher.group(1);
                if (name == null) {
                    continue;
                }
                final String index = matcher.groupCount() >= 2 ? matcher.group(2) : null;
                final String key = index == null ? name : name + "[" + index + "]";
                if (seenKeys.contains(key)) {
                    continue;
                }
                seenKeys.add(key);
                names.add(name);
                indexes.add(index);
            }
        }
        if (names.isEmpty()) {
            throw new ParsingException("Could not find deobfuscation function with any of the "
                    + "known patterns in the base JavaScript player code");
        }

        final List<String> resolvedTried = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            final String resolved = resolveFunctionName(
                    javaScriptPlayerCode, names.get(i), indexes.get(i));
            if (resolved == null) {
                continue;
            }
            if (!resolvedTried.contains(resolved)) {
                resolvedTried.add(resolved);
            }
            if (!isFunctionNameLikelyDeobfuscation(javaScriptPlayerCode, resolved)) {
                continue;
            }
            return resolved;
        }
        throw new ParsingException("Deobfuscation function candidates found ("
                + String.join(", ", seenKeys) + ") resolved to ("
                + String.join(", ", resolvedTried) + ") but none looks like an array-transform "
                + "deobfuscation function; the player code likely changed again");
    }

    /**
     * Turns an array reference ({@code Yva} + index {@code 0}) into the
     * function name stored at that slot ({@code Fn}). Direct names are
     * returned unchanged.
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @param name an identifier or the array object that holds function names
     * @param arrayIndex slot in that array, or {@code null} for a direct name
     * @return the resolved function name, or {@code null} if the array
     * declaration cannot be parsed or the index is out of range
     */
    @Nullable
    static String resolveFunctionName(@Nonnull final String javaScriptPlayerCode,
                                      @Nonnull final String name,
                                      @Nullable final String arrayIndex) {
        if (arrayIndex == null) {
            return name;
        }
        final int index;
        try {
            index = Integer.parseInt(arrayIndex);
        } catch (final NumberFormatException e) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        try {
            final Pattern arrayPattern = Pattern.compile(
                    DEOBFUSCATION_FUNCTION_ARRAY_OBJECT_TYPE_DECLARATION_REGEX
                            + Pattern.quote(name)
                            + FUNCTION_NAMES_IN_DEOBFUSCATION_ARRAY_REGEX);
            final String array = Parser.matchGroup1(arrayPattern, javaScriptPlayerCode);
            final String[] functionNames = array.split(",");
            if (index >= functionNames.length) {
                return null;
            }
            final String resolved = functionNames[index].trim();
            if (resolved.isEmpty()) {
                return null;
            }
            return resolved;
        } catch (final Parser.RegexException e) {
            return null;
        }
    }

    /**
     * Heuristic check that {@code functionName} really refers to the n-parameter
     * deobfuscation function: its body must combine string/array operations that
     * every known variant uses ({@code split} + {@code join}/index access and at
     * least one array-mutation helper such as {@code splice}, {@code reverse},
     * {@code unshift} or {@code push}), must be small enough to be a standalone
     * function rather than a big framework helper, and must not touch object
     * properties — the n transform works purely on its string argument, while
     * helpers that also satisfy the string/array shape (e.g. the avatar
     * {@code userDisplayImage} URL resizer on 2026-08 player builds) do not.
     */
    private static boolean isFunctionNameLikelyDeobfuscation(
            @Nonnull final String javaScriptPlayerCode, @Nonnull final String functionName) {
        String function;
        try {
            function = parseFunctionWithLexer(javaScriptPlayerCode, functionName);
        } catch (final Exception ignored) {
            try {
                function = parseFunctionWithRegex(javaScriptPlayerCode, functionName);
            } catch (final Exception alsoIgnored) {
                return false;
            }
        }
        return looksLikeDeobfuscationBody(function);
    }

    private static boolean looksLikeDeobfuscationBody(@Nonnull final String function) {
        if (function.length() > 20_000) {
            return false;
        }
        if (function.contains("userDisplayImage")) {
            return false;
        }
        return function.contains("split")
                && (function.contains("join") || function.contains("[0]")
                        || function.contains("length"))
                && (function.contains("splice") || function.contains("reverse")
                        || function.contains("unshift") || function.contains("push"));
    }

    /**
     * Get the throttling parameter deobfuscation code of YouTube's base JavaScript file.
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @param functionName the deobfuscation function name resolved from the player code
     * @return the throttling parameter deobfuscation function code
     * @throws ParsingException if the throttling parameter deobfuscation code couldn't be
     * extracted
     */
    @Nonnull
    static String getDeobfuscationFunction(@Nonnull final String javaScriptPlayerCode,
                                           @Nonnull final String functionName)
            throws ParsingException {
        String function;
        try {
            function = parseFunctionWithLexer(javaScriptPlayerCode, functionName);
        } catch (final Exception e) {
            function = parseFunctionWithRegex(javaScriptPlayerCode, functionName);
        }
        return fixupFunction(function);
    }

    /**
     * Get the throttling parameter of a streaming URL if it exists.
     *
     * @param streamingUrl a streaming URL
     * @return the throttling parameter of the streaming URL or {@code null} if no parameter has
     * been found
     */
    @Nullable
    static String getThrottlingParameterFromStreamingUrl(@Nonnull final String streamingUrl) {
        // Do a quick check if the n parameter is even present, if not abort
        // This improves performance by 60-900x
        if (!streamingUrl.contains("&n=") && !streamingUrl.contains("?n=")) {
            return null;
        }
        try {
            return Parser.matchGroup1(THROTTLING_PARAM_PATTERN, streamingUrl);
        } catch (final Parser.RegexException e) {
            // If the throttling parameter could not be parsed from the URL, it means that there is
            // no throttling parameter
            // Return null in this case
            return null;
        }
    }

    @Nonnull
    private static String parseFunctionWithLexer(@Nonnull final String javaScriptPlayerCode,
                                                 @Nonnull final String functionName)
            throws ParsingException {
        final String functionBase = functionName + "=function";
        return functionBase + JavaScriptExtractor.matchToClosingBrace(
                javaScriptPlayerCode, functionBase) + ";";
    }

    @Nonnull
    private static String parseFunctionWithRegex(@Nonnull final String javaScriptPlayerCode,
                                                 @Nonnull final String functionName)
            throws Parser.RegexException {
        // Quote the function name, as it may contain special regex characters such as dollar
        final Pattern functionPattern = Pattern.compile(
                Pattern.quote(functionName) + DEOBFUSCATION_FUNCTION_BODY_REGEX,
                Pattern.DOTALL);
        return validateFunction("function " + functionName
                + Parser.matchGroup1(functionPattern, javaScriptPlayerCode));
    }

    @Nonnull
    private static String validateFunction(@Nonnull final String function) {
        JavaScript.compileOrThrow(function);
        return function;
    }

    /**
     * Removes an early return statement from the code of the throttling parameter deobfuscation
     * function.
     *
     * <p>In newer version of the player code the function contains a check for something defined
     * outside of the function. If that was not found it will return early.
     *
     * <p>The check can look like this (JS):<br>
     * if(typeof RUQ==="undefined")return p;
     *
     * <p>In this example RUQ will always be undefined when running the function as standalone.
     * If the check is kept it would just return p which is the input parameter and would be wrong.
     * For that reason this check and return statement needs to be removed.
     *
     * @param function the original throttling parameter deobfuscation function code
     * @return the throttling parameter deobfuscation function code with the early return statement
     * removed
     */
    @Nonnull
    private static String fixupFunction(@Nonnull final String function)
            throws Parser.RegexException {
        final String firstArgName = Parser
                .matchGroup1(FUNCTION_ARGUMENTS_REGEX, function)
                .split(",")[0].trim();
        final Pattern earlyReturnPattern = Pattern.compile(
                EARLY_RETURN_REGEX + firstArgName + ";",
                Pattern.DOTALL);
        final Matcher earlyReturnCodeMatcher = earlyReturnPattern.matcher(function);
        return earlyReturnCodeMatcher.replaceFirst(";");
    }
}
