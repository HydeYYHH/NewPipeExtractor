package org.schabi.newpipe.extractor.services.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs the flattened-build signature extraction against real player code
 * captures. The expected outputs were cross-checked against the players'
 * own deciphering behavior for the same inputs.
 */
class YoutubeSignatureUtilsFlattenedTest {

    /** Input chosen from the shape of a real obfuscated signature (64 chars). */
    private static final String TEST_SIGNATURE =
            "AOq0QJ8wRQIgXkLtFPK7BpCTMXhJ8ZjTDGJOHJvOePWlNfCvQVoEYzICIQC2ZqLz";

    private static String playerCode(final String name) throws IOException {
        try (InputStream in = YoutubeSignatureUtilsFlattenedTest.class
                .getResourceAsStream("player/" + name)) {
            assertNotNull(in, "player code resource missing: " + name);
            byte[] bytes = new byte[1 << 22];
            int total = 0;
            int read;
            while ((read = in.read(bytes, total, bytes.length - total)) > 0) {
                total += read;
            }
            return new String(bytes, 0, total, StandardCharsets.UTF_8);
        }
    }

    private static String solve(final String playerCodeResource, final String signature)
            throws IOException, ParsingException {
        final String bundle = YoutubeSignatureUtils.getDeobfuscationCode(
                playerCode(playerCodeResource));
        return JavaScript.run(bundle, YoutubeSignatureUtils.DEOBFUSCATION_FUNCTION_NAME, signature);
    }

    @Test
    void flattenedMobileWebBuild() throws IOException, ParsingException {
        // m.youtube.com plasma player dac2d7b2 — swap(18), reverse, drop(1).
        assertEquals("LqZ2CQICIzYEoVQvCfNlWPeOvJHOJGDTjZ8JhXMTCpB7APFtLkXgIQRw8JQ0qOK",
                solve("dac2d7b2-plasma.js", TEST_SIGNATURE));
    }

    @Test
    void flattenedDesktopWebBuild() throws IOException, ParsingException {
        // www.youtube.com player_ias ecb23058 — different constants, same shape.
        assertEquals("jOq0QJ8wRQIgAkLtFPK7BpCTMXhJ8ZzTDGJOHJvOePWlNfCvQVoEYzICIQC2",
                solve("ecb23058-ias.js", TEST_SIGNATURE));
    }

    @Test
    void flattenedRotatedPlasmaBuild() throws IOException, ParsingException {
        // plasma 7460dd14 — the build the device logcat showed failing: the ops
        // object shuffles its method order (swap is no longer first) and the
        // solve chain runs nine op calls. Output cross-checked by running the
        // build's own machinery on the same input.
        assertEquals("q82CQICIzYEoVQvCfNlWPeOvJHOJGDTjZqJhXMTCpB7KPFtLkXgIQRw",
                solve("7460dd14-plasma.js", TEST_SIGNATURE));
    }
}
