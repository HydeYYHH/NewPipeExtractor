package org.schabi.newpipe.extractor.services.youtube.streams;

import java.io.IOException;

public interface PoTokenProvider {
    enum Purpose {
        PLAYER,
        GVS,
        SUBS,
    }

    enum Binding {
        VIDEO,
        VISITOR,
        DATA_SYNC,
    }

    final class TokenRequest {

        public final Purpose purpose;
        public final ClientProfile profile;
        public final RequestPlan.Protocol protocol;
        public final Binding binding;
        public final String identifier;
        public final String scope;

        public TokenRequest(
            final Purpose purpose,
            final ClientProfile profile,
            final RequestPlan.Protocol protocol,
            final Binding binding,
            final String identifier,
            final String scope
        ) {
            this.purpose = purpose;
            this.profile = profile;
            this.protocol = protocol;
            this.binding = binding;
            this.identifier = identifier;
            this.scope = scope;
        }

        public String cacheKey() {
            return (
                scope
                + ":"
                + purpose
                + ":"
                + profile
                + ":"
                + protocol
                + ":"
                + binding
                + ":"
                + identifier
            );
        }
    }

    final class Token {

        public final String value;
        public final long expiresAtMillis;

        public Token(final String value, final long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    Token get(TokenRequest request, ExtractionContext context) throws IOException;
    void invalidate(String scope);

    /** Hosts can release a low-RAM minter after all token purposes in one extraction are done. */
    default void finished(final ExtractionContext context) { }
}
