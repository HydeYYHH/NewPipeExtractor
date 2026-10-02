package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.downloader.DownloaderTestImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live end-to-end check of the VISIONOS-first extraction path (real network).
 *
 * <p>As of 2026-08 VISIONOS is the only client whose adaptive formats carry
 * direct googlevideo URLs without a poToken and without the ~64 s read window.
 * This test pins that invariant: a VOD extract must return video-only streams
 * with direct {@code c=VISIONOS} URLs up to the full height ladder.</p>
 */
@Tag("network")
final class YoutubeVisionOsExtractionTest {

    @Test
    void vodExtractionReturnsVisionOsDirectUrls() throws Exception {
        final Downloader downloader = DownloaderTestImpl.getInstance();
        NewPipe.init(downloader);

        final StreamingService youtube = ServiceList.YouTube;
        final StreamExtractor extractor =
                youtube.getStreamExtractor("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
        extractor.fetchPage();

        final StreamInfo info = StreamInfo.getInfo(extractor);

        final List<VideoStream> videoOnly = info.getVideoOnlyStreams();
        assertFalse(videoOnly.isEmpty(), "expected adaptive video-only streams");
        assertTrue(
                videoOnly.stream().allMatch(stream -> stream.getContent().contains("c=VISIONOS")),
                "expected all video-only URLs to come from the VISIONOS client");
        final int maxHeight = videoOnly.stream()
                .mapToInt(VideoStream::getHeight)
                .max()
                .orElse(0);
        assertTrue(maxHeight >= 1080, "expected at least 1080p, got " + maxHeight);
        assertFalse(info.getAudioStreams().isEmpty(), "expected audio streams");
    }
}
