package org.schabi.newpipe.extractor.services.youtube.streams;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.schabi.newpipe.extractor.downloader.Downloader;

/** Synchronous core; the host owns scheduling, cancellation, transport and caches. */
public final class ExtractionContext {

    public interface Diagnostics {
        void event(String stage, String profile, String detail, long elapsedMillis, int httpStatus);
    }

    public final YoutubeSession session;
    public final Downloader downloader;
    public final ChallengeSolver solver;
    public final PoTokenProvider tokens;
    public final boolean live;
    public final boolean catalog;
    public final long deadlineNanos;
    private final BooleanSupplier current;
    public final Diagnostics diagnostics;
    public final StreamDemand demand;
    public final boolean playbackPriority;
    public final ClientOrderPolicy clientOrder;

    public ExtractionContext(
        final YoutubeSession session,
        final Downloader downloader,
        final ChallengeSolver solver,
        final PoTokenProvider tokens,
        final boolean live,
        final boolean catalog,
        final long deadlineNanos,
        final BooleanSupplier current,
        final Diagnostics diagnostics
    ) {
        this(
            session,
            downloader,
            solver,
            tokens,
            live,
            catalog,
            deadlineNanos,
            current,
            diagnostics,
            new StreamDemand(0, null, false, Set.of()),
            !catalog,
            null
        );
    }

    private ExtractionContext(
        final YoutubeSession session,
        final Downloader downloader,
        final ChallengeSolver solver,
        final PoTokenProvider tokens,
        final boolean live,
        final boolean catalog,
        final long deadlineNanos,
        final BooleanSupplier current,
        final Diagnostics diagnostics,
        final StreamDemand demand,
        final boolean playbackPriority,
        final ClientOrderPolicy clientOrder
    ) {
        this.session = session;
        this.downloader = downloader;
        this.solver = solver;
        this.tokens = tokens;
        this.live = live;
        this.catalog = catalog;
        this.deadlineNanos = deadlineNanos;
        this.current = current;
        this.diagnostics = diagnostics;
        this.demand = demand;
        this.playbackPriority = playbackPriority;
        this.clientOrder = clientOrder;
    }

    public ExtractionContext withDemand(final StreamDemand value) {
        return new ExtractionContext(
            session,
            downloader,
            solver,
            tokens,
            live,
            catalog,
            deadlineNanos,
            current,
            diagnostics,
            value,
            playbackPriority,
            clientOrder
        );
    }

    public ExtractionContext withTimeLimit(final long milliseconds) {
        return new ExtractionContext(
            session,
            downloader,
            solver,
            tokens,
            live,
            catalog,
            Math.min(deadlineNanos, System.nanoTime() + milliseconds * 1_000_000),
            current,
            diagnostics,
            demand,
            playbackPriority,
            clientOrder
        );
    }

    public ExtractionContext withPlaybackPriority(final boolean value) {
        return new ExtractionContext(
            session,
            downloader,
            solver,
            tokens,
            live,
            catalog,
            deadlineNanos,
            current,
            diagnostics,
            demand,
            value,
            clientOrder
        );
    }

    public ExtractionContext withClientOrder(final ClientOrderPolicy value) {
        return new ExtractionContext(session, downloader, solver, tokens, live, catalog,
            deadlineNanos, current, diagnostics, demand, playbackPriority, value);
    }

    public long remainingMillis() throws IOException {
        check();
        return Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000);
    }

    public void check() throws IOException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
            throw new IOException("SESSION_CHANGED_OR_CANCELLED");
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw new IOException("EXTRACTION_DEADLINE");
        }
    }

    public org.schabi.newpipe.extractor.downloader.Response get(
        final String url,
        final Map<String, List<String>> headers
    ) throws IOException, org.schabi.newpipe.extractor.exceptions.ReCaptchaException {
        check();
        return downloader.execute(
            org.schabi.newpipe.extractor.downloader.Request.newBuilder()
                .get(url)
                .headers(headers)
                .executionDeadlineNanos(deadlineNanos)
                .build()
        );
    }

    public org.schabi.newpipe.extractor.downloader.Response post(
        final String url,
        final Map<String, List<String>> headers,
        final byte[] body
    ) throws IOException, org.schabi.newpipe.extractor.exceptions.ReCaptchaException {
        check();
        return downloader.execute(
            org.schabi.newpipe.extractor.downloader.Request.newBuilder()
                .post(url, body)
                .headers(headers)
                .executionDeadlineNanos(deadlineNanos)
                .build()
        );
    }
}
