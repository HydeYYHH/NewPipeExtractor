package org.schabi.newpipe.extractor.services.youtube.streams;

import java.util.Objects;

public final class FormatKey {

    public final int itag;
    public final String audioTrack;
    public final String codec;
    public final boolean drc;
    public final boolean hdr;
    public final RequestPlan.Protocol protocol;

    public FormatKey(
        final int itag,
        final String audioTrack,
        final String codec,
        final boolean drc,
        final boolean hdr,
        final RequestPlan.Protocol protocol
    ) {
        this.itag = itag;
        this.audioTrack = audioTrack == null ? "" : audioTrack;
        this.codec = codec == null ? "" : codec;
        this.drc = drc;
        this.hdr = hdr;
        this.protocol = protocol;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof FormatKey)) {
            return false;
        }
        final FormatKey key = (FormatKey) other;
        return (
            itag == key.itag
            && drc == key.drc
            && hdr == key.hdr
            && protocol == key.protocol
            && audioTrack.equals(key.audioTrack)
            && codec.equals(key.codec)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(itag, audioTrack, codec, drc, hdr, protocol);
    }
}
