package org.schabi.newpipe.extractor.services.youtube.streams;

import java.util.List;
import java.io.IOException;

/** Host-owned feedback, scoped to the extraction's session and media demand. */
public interface ClientOrderPolicy {
    List<ClientProfile> order(ExtractionContext context, List<ClientProfile> defaults);

    void succeeded(ExtractionContext context, ClientProfile profile) throws IOException;
}
