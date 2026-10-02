package org.schabi.newpipe.extractor.services.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

/**
 * Offline regression for n-parameter function-name parsing. Does not download
 * player JS; the existing networked deobfuscation tests stay independently
 * skippable.
 */
class YoutubeThrottlingParameterUtilsTest {

    private static final String ARRAY_SAMPLE =
            "var m85=function(p){p=p.split(\"\");p.reverse();return Y[45]};"
                    + "var Fn=function(a){a=a.split(\"\");a.splice(0,1);a.reverse();"
                    + "return a.join(\"\")};"
                    + "var helper=function(u){return u.userDisplayImage};"
                    + "var Yva=[Fn];"
                    + ".get(\"n\"))&&(b=Yva[0](b)";

    private static final String DIRECT_SAMPLE =
            "var m85=function(p){p=p.split(\"\");p.splice(0,1);p.reverse();"
                    + "p.join(\"\");return Y[45]};";

    private static final String BAD_ARRAY_SAMPLE =
            "var Bad=function(a){return a;};"
                    + "var Qqa=[Bad];"
                    + ".get(\"n\"))&&(b=Qqa[0](b)";

    @Test
    void arrayAccess_resolvesToRealFunctionName() throws ParsingException {
        assertEquals("Fn", YoutubeThrottlingParameterUtils
                .getDeobfuscationFunctionName(ARRAY_SAMPLE));
        assertEquals("Fn", YoutubeThrottlingParameterUtils
                .resolveFunctionName(ARRAY_SAMPLE, "Yva", "0"));
    }

    @Test
    void directFunction_stillSelected() throws ParsingException {
        assertEquals("m85", YoutubeThrottlingParameterUtils
                .getDeobfuscationFunctionName(DIRECT_SAMPLE));
        assertEquals("m85", YoutubeThrottlingParameterUtils
                .resolveFunctionName(DIRECT_SAMPLE, "m85", null));
    }

    @Test
    void unrelatedHelper_isNotSelectedOverArrayFunction() throws ParsingException {
        assertEquals("Fn", YoutubeThrottlingParameterUtils
                .getDeobfuscationFunctionName(ARRAY_SAMPLE));
    }

    @Test
    void invalidArrayCandidate_isRejected() {
        assertThrows(ParsingException.class, () -> YoutubeThrottlingParameterUtils
                .getDeobfuscationFunctionName(BAD_ARRAY_SAMPLE));
    }

    @Test
    void missingPatterns_fail() {
        assertThrows(ParsingException.class, () -> YoutubeThrottlingParameterUtils
                .getDeobfuscationFunctionName("var unused=1;"));
    }
}
