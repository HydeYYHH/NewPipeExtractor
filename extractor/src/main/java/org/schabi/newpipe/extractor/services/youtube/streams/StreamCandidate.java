package org.schabi.newpipe.extractor.services.youtube.streams;

import com.grack.nanojson.JsonObject;

public final class StreamCandidate {

    public final FormatKey key;
    public final String url;
    public final String playerUrl;
    public final long expiresAtMillis;
    public final String resourceIdentity;
    public final RequestPlan requestPlan;
    public final JsonObject format;
    public final boolean audioOnly;
    public final boolean videoOnly;

    public StreamCandidate(
        final FormatKey key,
        final String url,
        final String playerUrl,
        final long expiresAtMillis,
        final String resourceIdentity,
        final RequestPlan requestPlan,
        final JsonObject format,
        final boolean audioOnly,
        final boolean videoOnly
    ) {
        this.key = key;
        this.url = url;
        this.playerUrl = playerUrl;
        this.expiresAtMillis = expiresAtMillis;
        this.resourceIdentity = resourceIdentity;
        this.requestPlan = requestPlan;
        this.format = format;
        this.audioOnly = audioOnly;
        this.videoOnly = videoOnly;
    }
}
