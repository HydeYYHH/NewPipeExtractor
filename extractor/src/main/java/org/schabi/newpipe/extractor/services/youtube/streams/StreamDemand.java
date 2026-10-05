package org.schabi.newpipe.extractor.services.youtube.streams;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Selection needs affect when to stop, never which formats the parser retains. */
public final class StreamDemand {

    public final int height;
    public final String audioTrack;
    public final boolean audioOnly;
    public final Set<String> videoCodecPrefixes;

    public StreamDemand(
        final int height,
        final String audioTrack,
        final boolean audioOnly,
        final Set<String> videoCodecPrefixes
    ) {
        this.height = height;
        this.audioTrack = audioTrack;
        this.audioOnly = audioOnly;
        this.videoCodecPrefixes = Set.copyOf(videoCodecPrefixes);
    }

    public boolean satisfied(
        final List<StreamCandidate> formats,
        final List<StreamCandidate> manifests
    ) {
        if (!manifests.isEmpty()) {
            return true;
        }
        boolean audio = false;
        boolean video = false;
        for (final StreamCandidate candidate : formats) {
            if (
                (candidate.audioOnly || !candidate.videoOnly)
                && (audioTrack == null || audioTrack.equals(candidate.key.audioTrack))
            ) {
                audio = true;
            }
            if (
                !candidate.audioOnly
                && candidate.format.getInt("height") >= height
                && (videoCodecPrefixes.isEmpty()
                    || videoCodecPrefixes.stream().anyMatch(candidate.key.codec::startsWith))
            ) {
                video = true;
            }
        }
        return audio && (audioOnly || video);
    }

    public String cacheKey() {
        return (
            height
            + ":"
            + audioTrack
            + ":"
            + audioOnly
            + ":"
            + String.join(",", new TreeSet<>(videoCodecPrefixes))
        );
    }
}
