package org.schabi.newpipe.extractor.services.youtube;

import static org.schabi.newpipe.extractor.utils.Parser.matchGroup1;
import static org.schabi.newpipe.extractor.utils.Parser.matchMultiplePatterns;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;
import org.schabi.newpipe.extractor.utils.Pair;
import org.schabi.newpipe.extractor.utils.Parser;
import org.schabi.newpipe.extractor.utils.jsextractor.JavaScriptExtractor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to get the signature timestamp of YouTube's base JavaScript player and deobfuscate
 * signature of streaming URLs from HTML5 clients.
 */
final class YoutubeSignatureUtils {

    /**
     * The name of the deobfuscation function which needs to be called inside the deobfuscation
     * code.
     */
    static final String DEOBFUSCATION_FUNCTION_NAME = "deobfuscate";

    private static final Pattern[] FUNCTION_REGEXES = {
            // CHECKSTYLE:OFF
            Pattern.compile("\\b(?:[a-zA-Z0-9_$]+)&&\\((?:[a-zA-Z0-9_$]+)=([a-zA-Z0-9_$]{2,})\\((\\d+,)decodeURIComponent\\((?:[a-zA-Z0-9_$]+)\\)\\)"),
            Pattern.compile("\\b(?:[a-zA-Z0-9_$]+)&&\\((?:[a-zA-Z0-9_$]+)=([a-zA-Z0-9_$]{2,})\\(decodeURIComponent\\((?:[a-zA-Z0-9_$]+)\\)\\)"),
            Pattern.compile("\\bm=([a-zA-Z0-9$]{2,})\\(decodeURIComponent\\(h\\.s\\)\\)"),
            Pattern.compile("\\bc&&\\(c=([a-zA-Z0-9$]{2,})\\(decodeURIComponent\\(c\\)\\)"),
            Pattern.compile("(?:\\b|[^a-zA-Z0-9$])([a-zA-Z0-9$]{2,})\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\s*\"\"\\s*\\)"),
            Pattern.compile("([\\w$]+)\\s*=\\s*function\\((\\w+)\\)\\{\\s*\\2=\\s*\\2\\.split\\(\"\"\\)\\s*;")
            // CHECKSTYLE:ON
    };

    private static final String STS_REGEX = "signatureTimestamp[=:](\\d+)";

    private static final String DEOBF_FUNC_REGEX_START = "(";
    private static final String DEOBF_FUNC_REGEX_END = "=function\\([a-zA-Z0-9_]+\\)\\{.+?\\})";

    // CHECKSTYLE:OFF
    private static final Pattern SIG_DEOBF_GLOBAL_ARRAY_REGEX =
            Pattern.compile("(var [A-z]=['\"].*['\"].split\\(\"[;{]\"\\))");
    private static final Pattern SIG_DEOBF_HELPER_OBJ_NAME_REGEX =
            Pattern.compile("[;,]([A-Za-z0-9_$]{2,})\\[..");
    private static final String SIG_DEOBF_HELPER_OBJ_REGEX_START = "(var ";
    private static final String SIG_DEOBF_HELPER_OBJ_REGEX_END = "=\\{(?>.|\\n)+?\\}\\};)";
    // CHECKSTYLE:ON

    // -- 2026 flattened builds ------------------------------------------------
    // Player builds of this generation hide the whole cipher machinery behind a
    // control-flow-flattened dispatcher whose string operands come from a
    // `"a;b;c".split(";")` table, so none of the classic traits (a.split(""),
    // the decodeURIComponent call shape) appear in the source. The machinery is
    // still fully derivable at runtime:
    // the URL builder carries a raw `set("alr","yes")` anchor and calls the
    // solve chain with numeric constants, e.g. (plasma dac2d7b2)
    //   x$=function(P,U="",c=""){P=new g.te(P,!0);P.set("alr","yes");
    //       c&&(c=DW(3,1720,Tu(68,3912,c)),P[T[8]](U,BF(2,3753,c)));return P}
    // where DW is the flattened dispatcher (split, swap via an ops object,
    // reverse, drop, join — branch selected by the first constant), Tu decodes
    // and BF re-encodes. Everything is extracted per build and re-run in
    // Rhino; nothing is hardcoded.

    /** The raw string-literal anchor inside the player's stream-URL builder. */
    private static final String ALR_ANCHOR = "set(\"alr\",\"yes\")";

    /** `DISP(P,U,DECODE(P,U,arg))` — the flattened solve chain with numeric constants. */
    private static final Pattern FLATTENED_SOLVE_CALL_PATTERN = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\((-?\\d+),(-?\\d+),([A-Za-z_$][\\w$]*)\\((-?\\d+),(-?\\d+),"
                    + "[A-Za-z_$][\\w$]*\\)\\)");

    /** `ENC(P,U,arg)` — the flattened re-encode step of the solve chain. */
    private static final Pattern FLATTENED_ENCODE_CALL_PATTERN = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\((-?\\d+),(-?\\d+),[A-Za-z_$][\\w$]*\\)");

    private YoutubeSignatureUtils() {
    }

    @Nonnull
    static String getSignatureTimestamp(@Nonnull final String javaScriptPlayerCode)
            throws ParsingException {
        try {
            return matchGroup1(STS_REGEX, javaScriptPlayerCode);
        } catch (final ParsingException e) {
            throw new ParsingException(
                    "Could not extract signature timestamp from JavaScript code", e);
        }
    }

    /**
     * Get the signature deobfuscation code of YouTube's base JavaScript file.
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @return the signature deobfuscation code
     * @throws ParsingException if the signature deobfuscation code couldn't be extracted
     */
    @Nonnull
    static String getDeobfuscationCode(@Nonnull final String javaScriptPlayerCode)
            throws ParsingException {
        final ParsingException flattenedEx;
        try {
            return getFlattenedDeobfuscationCode(javaScriptPlayerCode);
        } catch (final ParsingException e) {
            flattenedEx = e;
        }
        try {
            final Pair<String, String> deobfuscationFunctionNameAndParams =
                    getDeobfuscationFunctionNameAndParams(javaScriptPlayerCode);
            final String deobfuscationFunctionName = deobfuscationFunctionNameAndParams.getFirst();
            final String functionAdditionalParams = deobfuscationFunctionNameAndParams.getSecond();

            String deobfuscationFunction;
            try {
                deobfuscationFunction = getDeobfuscateFunctionWithLexer(
                        javaScriptPlayerCode, deobfuscationFunctionName);
            } catch (final Exception e) {
                deobfuscationFunction = getDeobfuscateFunctionWithRegex(
                        javaScriptPlayerCode, deobfuscationFunctionName);
            }

            // Assert the extracted deobfuscation function is valid
            JavaScript.compileOrThrow(deobfuscationFunction);

            final String globalVar =
                    Parser.matchGroup1(SIG_DEOBF_GLOBAL_ARRAY_REGEX, javaScriptPlayerCode);

            final String helperObjectName =
                    Parser.matchGroup1(SIG_DEOBF_HELPER_OBJ_NAME_REGEX, deobfuscationFunction);

            final String helperObject = getHelperObject(javaScriptPlayerCode, helperObjectName);

            final String callerFunction = "function " + DEOBFUSCATION_FUNCTION_NAME
                    + "(a){return "
                    + deobfuscationFunctionName
                    + "(" + functionAdditionalParams + "a);}";

            return globalVar + ";" + helperObject + deobfuscationFunction + ";" + callerFunction;
        } catch (final Exception e) {
            e.addSuppressed(flattenedEx);
            throw new ParsingException("Could not parse deobfuscation function", e);
        }
    }

    @Nonnull
    private static Pair<String, String> getDeobfuscationFunctionNameAndParams(
            @Nonnull final String javaScriptPlayerCode) throws ParsingException {
        try {
            final Matcher m = matchMultiplePatterns(FUNCTION_REGEXES, javaScriptPlayerCode);
            final String functionName = m.group(1);
            final String functionAdditionalParams;
            if (m.groupCount() > 1) {
                functionAdditionalParams = m.group(2);
            } else {
                functionAdditionalParams = "";
            }
            return new Pair<>(functionName, functionAdditionalParams);
        } catch (final Parser.RegexException e) {
            throw new ParsingException(
                    "Could not find deobfuscation function with any of the known patterns", e);
        }
    }

    @Nonnull
    private static String getDeobfuscateFunctionWithLexer(
            @Nonnull final String javaScriptPlayerCode,
            @Nonnull final String deobfuscationFunctionName) throws ParsingException {
        final String functionBase = deobfuscationFunctionName + "=function";
        return functionBase + JavaScriptExtractor.matchToClosingBrace(
                javaScriptPlayerCode, functionBase);
    }

    @Nonnull
    private static String getDeobfuscateFunctionWithRegex(
            @Nonnull final String javaScriptPlayerCode,
            @Nonnull final String deobfuscationFunctionName) throws ParsingException {
        final String functionPattern = DEOBF_FUNC_REGEX_START
                + Pattern.quote(deobfuscationFunctionName)
                + DEOBF_FUNC_REGEX_END;
        return "var " + Parser.matchGroup1(functionPattern, javaScriptPlayerCode);
    }

    @Nonnull
    private static String getHelperObject(@Nonnull final String javaScriptPlayerCode,
                                          @Nonnull final String helperObjectName)
            throws ParsingException {
        final String helperPattern = SIG_DEOBF_HELPER_OBJ_REGEX_START
                + Pattern.quote(helperObjectName)
                + SIG_DEOBF_HELPER_OBJ_REGEX_END;
        return Parser.matchGroup1(helperPattern, javaScriptPlayerCode)
                .replace("\n", "");
    }

    // -- flattened-build extraction -------------------------------------------

    /**
     * The swap op of the cipher ops object — `f:function(a,b){var c=a[0];
     * a[0]=a[b%a[TBL[k]]];a[b%a[TBL[k]]]=c}`. No other player code mutates an
     * array through its own length modulo, so this shape uniquely anchors the
     * ops object without assuming its method names or their order (builds
     * shuffle which op comes first).
     */
    private static final Pattern FLATTENED_OPS_SWAP_PATTERN = Pattern.compile(
            "([A-Za-z_$][\\w$]*):function\\(([A-Za-z_$][\\w$]*),([A-Za-z_$][\\w$]*)\\)\\{var "
                    + "([A-Za-z_$][\\w$]*)=\\2\\[0\\];\\2\\[0\\]=\\2\\[\\3%\\2\\[[^\\]]*\\]\\]\\];"
                    + "\\2\\[\\3%\\2\\[[^\\]]*\\]\\]\\]=\\4\\}");

    /** `NAME={` or `NAME:{` — an object literal, assigned or keyed. */
    private static final Pattern OBJECT_DECL_PATTERN = Pattern.compile(
            "(?<![\\w$.])([A-Za-z_$][\\w$]*)[:=]\\{");

    /**
     * A string-table read: `TBL[mask^lit]` or `TBL[lit]`. The flattened code
     * reads the table only through the branch mask, so locals like `N[T[...]]`
     * (a method call on an array) never match this shape.
     */
    private static final Pattern TABLE_ACCESS_PATTERN = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\[(?:[A-Za-z_$][\\w$]*\\^-?\\d+|-?\\d+)\\]");

    /** `NAME=function` with a name boundary, so suffix-sharing names don't collide. */
    private static final Pattern FUNCTION_DEF_PATTERN = Pattern.compile(
            "(?<![\\w$.])([A-Za-z_$][\\w$]*)=function");

    private static final String CANARY_INPUT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * Extracts the flattened-build solver as a self-contained bundle.
     *
     * <p>Every piece is derived from the URL builder's own numeric solve chain
     * (dispatcher, ops object, string table, decode/encode helpers), so nothing
     * build-specific lives in this code. A canary run validates the bundle:
     * any wrong pick (a sibling closure's def, a stale anchor) resolves to a
     * non-function at runtime and is rejected here.</p>
     */
    @Nonnull
    private static String getFlattenedDeobfuscationCode(
            @Nonnull final String javaScriptPlayerCode) throws ParsingException {
        ParsingException last = null;
        int anchor = 0;
        while ((anchor = javaScriptPlayerCode.indexOf(ALR_ANCHOR, anchor)) >= 0) {
            try {
                return extractFlattenedBundle(javaScriptPlayerCode, anchor);
            } catch (final ParsingException e) {
                last = e;
                anchor += ALR_ANCHOR.length();
            }
        }
        throw new ParsingException("No flattened signature solver found", last);
    }

    @Nonnull
    private static String extractFlattenedBundle(@Nonnull final String code, final int anchorIndex)
            throws ParsingException {
        final String builder = enclosingFunction(code, anchorIndex);
        if (builder.isEmpty()) {
            throw new ParsingException("No enclosing function for the alr anchor");
        }

        final Matcher solve = FLATTENED_SOLVE_CALL_PATTERN.matcher(builder);
        if (!solve.find()) {
            throw new ParsingException("Flattened solve call not found in the URL builder");
        }
        final String dispName = solve.group(1);
        final int dispP = Integer.parseInt(solve.group(2));
        final int dispU = Integer.parseInt(solve.group(3));
        final String decodeName = solve.group(4);
        final int decodeP = Integer.parseInt(solve.group(5));
        final int decodeU = Integer.parseInt(solve.group(6));

        final Matcher encode = FLATTENED_ENCODE_CALL_PATTERN.matcher(
                builder.substring(solve.end()));
        if (!encode.find()) {
            throw new ParsingException("Flattened encode call not found in the URL builder");
        }
        final String encodeName = encode.group(1);
        final int encodeP = Integer.parseInt(encode.group(2));
        final int encodeU = Integer.parseInt(encode.group(3));

        // The ops object is anchored by its swap op, which may sit anywhere in
        // the object (builds shuffle the op order): walk back from the method
        // to the nearest object literal declaration and extract from there.
        // The table is whatever the ops methods read through. Both feed the
        // canary, which rejects wrong picks (sibling closures redeclare the
        // same shapes).
        final Matcher opsSwap = FLATTENED_OPS_SWAP_PATTERN.matcher(code);
        while (opsSwap.find()) {
            final Matcher objDecl = OBJECT_DECL_PATTERN.matcher(code.substring(0, opsSwap.start()));
            String opsName = null;
            int objStart = -1;
            while (objDecl.find()) {
                objStart = objDecl.start();
                opsName = objDecl.group(1);
            }
            if (opsName == null) {
                continue;
            }
            final String opsDef;
            try {
                // The prefix must not include the brace: matchToClosingBrace
                // balances from the object's own opening brace.
                final String prefix = opsName + "=";
                opsDef = prefix + JavaScriptExtractor.matchToClosingBrace(
                        code.substring(objStart), prefix);
            } catch (final ParsingException e) {
                continue;
            }
            if (objStart + opsDef.length() <= opsSwap.start()) {
                // A sibling object literal swallowed the walk-back — not ours.
                continue;
            }
            final String tableName = findTableIdentifier(opsDef);
            if (tableName == null || tableName.equals(opsName)) {
                continue;
            }
            final String table;
            try {
                table = getTableDeclaration(code, tableName);
            } catch (final ParsingException e) {
                continue;
            }
            for (final String dispatcher : extractDefs(code, dispName)) {
                if (!dispatcher.contains(opsName + "[")) {
                    continue;
                }
                for (final String decode : extractDefs(code, decodeName)) {
                    for (final String enc : extractDefs(code, encodeName)) {
                        final String bundle = table + "var " + opsDef + ";" + dispatcher + ";"
                                + decode + ";" + enc + ";function " + DEOBFUSCATION_FUNCTION_NAME
                                + "(a){return " + encodeName + "(" + encodeP + "," + encodeU + ","
                                + dispName + "(" + dispP + "," + dispU + "," + decodeName + "("
                                + decodeP + "," + decodeU + ",a)));}";
                        if (canaryRuns(bundle)) {
                            return bundle;
                        }
                    }
                }
            }
        }
        throw new ParsingException("No working flattened solver bundle for " + dispName);
    }

    /** Compiles and runs the bundle on a synthetic input; false on any failure. */
    private static boolean canaryRuns(@Nonnull final String bundle) {
        try {
            JavaScript.compileOrThrow(bundle);
            final String out = JavaScript.run(bundle, DEOBFUSCATION_FUNCTION_NAME, CANARY_INPUT);
            return !out.isEmpty();
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * The string-table identifier the [opsDef] methods read through: the first
     * masked-table access (`TBL[mask^lit]` / `TBL[lit]`) whose identifier is
     * not one of the method parameters.
     */
    @Nullable
    private static String findTableIdentifier(@Nonnull final String opsDef) {
        final String excluded = functionParams(opsDef);
        final Matcher m = TABLE_ACCESS_PATTERN.matcher(opsDef);
        while (m.find()) {
            final String id = m.group(1);
            if (!isParamName(excluded, id)) {
                return id;
            }
        }
        return null;
    }

    /** Comma-separated parameter list of the first `function(…)` in [source]. */
    @Nonnull
    private static String functionParams(@Nonnull final String source) {
        final Matcher params = Pattern.compile("function\\(([^)]*)\\)").matcher(source);
        return params.find() ? params.group(1) : "";
    }

    private static boolean isParamName(@Nonnull final String paramList,
                                       @Nonnull final String name) {
        for (final String param : paramList.split(",")) {
            if (param.trim().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Source of every `NAME=function(…){…}` definition, in file order. */
    @Nonnull
    private static List<String> extractDefs(@Nonnull final String code,
                                            @Nonnull final String name) {
        final List<String> defs = new ArrayList<>();
        final String prefix = name + "=function";
        final Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(prefix)).matcher(code);
        while (m.find()) {
            try {
                defs.add(prefix + JavaScriptExtractor.matchToClosingBrace(
                        code.substring(m.start()), prefix));
            } catch (final ParsingException ignored) {
                // A pseudo-def inside a string/template literal — skip it.
            }
        }
        return defs;
    }

    /**
     * The innermost `NAME=function(…){…}` span containing [innerIndex], or an
     * empty string. Candidates are walked nearest-first; defs whose braces end
     * before the anchor (sibling closures) are skipped.
     */
    @Nonnull
    private static String enclosingFunction(@Nonnull final String code, final int innerIndex) {
        final Matcher m = FUNCTION_DEF_PATTERN.matcher(code.substring(0, innerIndex));
        final List<int[]> positions = new ArrayList<>(); // {defStart, nameStart}
        while (m.find()) {
            positions.add(new int[]{m.start(), m.start(1)});
        }
        for (int i = positions.size() - 1; i >= 0; i--) {
            final int start = positions.get(i)[0];
            final int nameStart = positions.get(i)[1];
            final String name = code.substring(nameStart, nameEnd(code, nameStart));
            final String prefix = name + "=function";
            try {
                final String def = prefix + JavaScriptExtractor.matchToClosingBrace(
                        code.substring(start), prefix);
                if (start + def.length() > innerIndex) {
                    return def;
                }
            } catch (final ParsingException ignored) {
                // Unbalanced pseudo-def inside a literal — try the next one out.
            }
        }
        return "";
    }

    private static int nameEnd(@Nonnull final String code, final int from) {
        int end = from;
        while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
            end++;
        }
        return end;
    }

    /** `var NAME="…".split(";")` rebuilt as a standalone statement. */
    @Nonnull
    private static String getTableDeclaration(@Nonnull final String code,
                                              @Nonnull final String tableName)
            throws ParsingException {
        final Pattern p = Pattern.compile("(?<![\\w$.])" + Pattern.quote(tableName)
                + "=\"((?:[^\"\\\\]|\\\\.)+)\"\\.split\\(\";\"\\)");
        final Matcher m = p.matcher(code);
        if (!m.find()) {
            throw new ParsingException("String table " + tableName + " not found");
        }
        return "var " + tableName + "=\"" + m.group(1) + "\".split(\";\");";
    }
}
