package org.schabi.newpipe.extractor.services.youtube.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.services.youtube.InitYoutubeTest;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

class YoutubeStreamExtractorSabrRegressionTest implements InitYoutubeTest {
    private static final String BASE_URL = "https://www.youtube.com/watch?v=";

    @ParameterizedTest
    @ValueSource(strings = {"xYWo4P__vl4", "qyNQy7xJvik"})
    void videosRolledOutToSabrStillExposeVideoOnlyStreams(final String videoId) throws Exception {
        final YoutubeStreamExtractor extractor = (YoutubeStreamExtractor)
                YouTube.getStreamExtractor(BASE_URL + videoId);
        extractor.fetchPage();

        final List<VideoStream> videoOnly = extractor.getVideoOnlyStreams();
        final List<AudioStream> audio = extractor.getAudioStreams();

        assertFalse(videoOnly.isEmpty());
        assertFalse(audio.isEmpty());
        assertTrue(videoOnly.stream().anyMatch(stream -> stream.getHeight() > 360));
    }
}
