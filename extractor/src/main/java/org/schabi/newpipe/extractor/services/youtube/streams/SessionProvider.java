package org.schabi.newpipe.extractor.services.youtube.streams;

import java.io.IOException;

/** Host owns credentials, account selection and network generations. */
public interface SessionProvider {
    YoutubeSession capture(String videoId, boolean fresh) throws IOException;
    boolean isCurrent(YoutubeSession session);
}
