package org.schabi.newpipe.extractor.services.youtube.streams;

import java.io.IOException;

/** Safe failure metadata: contains no URL, credentials, token or response body. */
public final class StreamHttpException extends IOException {

    public final String stage;
    public final int status;
    public final long retryAfterMillis;

    public StreamHttpException(final String stage, final int status, final long retryAfterMillis) {
        super(stage + "_HTTP_" + status);
        this.stage = stage;
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public boolean terminal() {
        return status == 429 || status == 402;
    }

    public static long parseRetryAfter(final String value, final long nowMillis) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Math.multiplyExact(Math.max(0, Long.parseLong(value.trim())), 1000);
        } catch (final NumberFormatException | ArithmeticException ignored) {
            try {
                return Math.max(
                    0,
                    java.time.ZonedDateTime.parse(
                            value.trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                        )
                            .toInstant()
                            .toEpochMilli()
                        - nowMillis
                );
            } catch (final java.time.format.DateTimeParseException invalid) {
                return 0;
            }
        }
    }
}
