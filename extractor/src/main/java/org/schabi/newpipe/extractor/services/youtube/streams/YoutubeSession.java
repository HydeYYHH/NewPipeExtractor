package org.schabi.newpipe.extractor.services.youtube.streams;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import java.util.Objects;

/** An immutable identity snapshot. Credentials stay in the host, never in stream DTOs. */
public final class YoutubeSession {

    public enum Account {
        ANONYMOUS,
        AUTHENTICATED,
        PREMIUM,
    }

    public interface Credentials {
        String cookies(String origin);
    }

    public final String key;
    public final Account account;
    public final int accountIndex;
    public final String delegatedId;
    public final String userSessionId;
    public final String dataSyncId;
    public final String visitorData;
    public final String userAgent;
    public final long networkGeneration;
    public final String playerUrl;
    public final String configurationSource;
    private final JsonObject configuration;
    private final Credentials credentials;
    private final String webClientVersion;
    private final boolean videoBoundGvs;

    public YoutubeSession(
        final String key,
        final Account account,
        final int accountIndex,
        final String delegatedId,
        final String userSessionId,
        final String dataSyncId,
        final String visitorData,
        final String userAgent,
        final long networkGeneration,
        final String playerUrl,
        final String configurationSource,
        final JsonObject configuration,
        final Credentials credentials
    ) {
        this.key = Objects.requireNonNull(key);
        this.account = Objects.requireNonNull(account);
        this.accountIndex = accountIndex;
        this.delegatedId = delegatedId;
        this.userSessionId = userSessionId;
        this.dataSyncId = dataSyncId;
        this.visitorData = visitorData;
        this.userAgent = Objects.requireNonNull(userAgent);
        this.networkGeneration = networkGeneration;
        this.playerUrl = playerUrl;
        this.configurationSource = configurationSource;
        this.configuration = copy(configuration);
        final JsonObject client = this.configuration.getObject("INNERTUBE_CONTEXT")
            .getObject("client");
        this.webClientVersion = "WEB".equals(client.getString("clientName"))
            ? client.getString("clientVersion") : null;
        this.videoBoundGvs = this.configuration.getObject("WEB_PLAYER_CONTEXT_CONFIGS")
            .values().stream().filter(JsonObject.class::isInstance).map(JsonObject.class::cast)
            .anyMatch(value -> "true".equals(YoutubeStreamEngine.query(
                value.getString("serializedExperimentFlags", ""))
                .get("html5_generate_content_po_token")));
        this.credentials = Objects.requireNonNull(credentials);
    }

    public JsonObject configuration() {
        return copy(configuration);
    }

    public String webClientVersion() {
        return webClientVersion;
    }

    public boolean videoBoundGvs() {
        return videoBoundGvs;
    }

    private static JsonObject copy(final JsonObject value) {
        try {
            return JsonParser.object().from(JsonWriter.string(value));
        } catch (final JsonParserException e) {
            throw new IllegalArgumentException("Invalid session configuration", e);
        }
    }

    public String cookies(final String origin) {
        return credentials.cookies(origin);
    }

    public String scope() {
        return key + ":" + networkGeneration;
    }
}
