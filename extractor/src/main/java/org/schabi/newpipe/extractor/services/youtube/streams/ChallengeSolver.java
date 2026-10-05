package org.schabi.newpipe.extractor.services.youtube.streams;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface ChallengeSolver {
    default int signatureTimestamp(final String playerUrl, final ExtractionContext context)
        throws IOException {
        return 0;
    }

    /** All distinct challenges for this player are evaluated in one host call. */
    Solutions solve(
        String playerUrl,
        Set<String> signatures,
        Set<String> throttles,
        ExtractionContext context
    ) throws IOException;

    final class Solutions {

        public final Map<String, String> signatures;
        public final Map<String, String> throttles;

        public Solutions(
            final Map<String, String> signatures,
            final Map<String, String> throttles
        ) {
            this.signatures = Map.copyOf(signatures);
            this.throttles = Map.copyOf(throttles);
        }
    }
}
